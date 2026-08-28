package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val INITIAL_COMMAND_RETRY_DELAY = 1.seconds
private val MAX_COMMAND_RETRY_DELAY = 1.minutes

/**
 * 将最新有效设置串行收敛为 Telegram Bot 命令。
 *
 * 本服务是应用内唯一可写入 Telegram Bot 命令的路径。设置订阅器只发布不可变目标并唤醒单一 worker，
 * 不会取消已在途的远程写入；因此旧目标完成后仍会按版本继续收敛最新目标。应用在
 * `ApplicationStopPreparing` 中调用 [requestStop] 关闭发布与重试准入，并在关闭 Telegram 前等待
 * [awaitStopped]。空 token 不执行远程请求，缺少有效 AI 提供方时会删除该 Bot 的命令。
 *
 * @constructor 创建绑定应用协程作用域的命令协调器。
 * @param parentScope 持有订阅与唯一 worker 的应用级作用域；取消该作用域会停止协调。
 * @param settingsChangeCoordinator 提供带单调版本的不可变设置快照。
 * @param telegramService 唯一执行 Telegram 命令写入的服务。
 */
@Singleton
class BotCommandReconciler private constructor(
    parentScope: CoroutineScope,
    private val settingsChangeCoordinator: SettingsChangeCoordinator,
    private val telegramService: TelegramService,
    private val retryDelay: suspend (Duration) -> Unit,
    @Suppress("UNUSED_PARAMETER") testConstructorMarker: Unit,
) : AutoCloseable {
    /**
     * 创建使用生产退避策略的应用级命令协调器。
     *
     * @param parentScope 持有订阅与唯一 worker 的应用级作用域；取消该作用域会停止协调。
     * @param settingsChangeCoordinator 提供带单调版本的不可变设置快照。
     * @param telegramService 唯一执行 Telegram 命令写入的服务。
     */
    @Inject
    constructor(
        parentScope: CoroutineScope,
        settingsChangeCoordinator: SettingsChangeCoordinator,
        telegramService: TelegramService,
    ) : this(parentScope, settingsChangeCoordinator, telegramService, { duration -> delay(duration) }, Unit)

    /**
     * 一个设置版本期望写入 Telegram 的命令目标。
     *
     * @property token 目标 Telegram Bot token。
     * @property provider 应暴露命令的 AI 提供商；`null` 表示清空命令。
     */
    private data class CommandTarget(
        val token: String,
        val provider: AIProvider?,
    )

    /**
     * 带设置版本的命令收敛目标。
     *
     * @property version 生成目标的单调设置版本。
     * @property command 要写入的命令目标；`null` 表示当前 token 不可用。
     */
    private data class ReconciliationTarget(
        val version: Long,
        val command: CommandTarget?,
    )

    private val logger = LoggerFactory.getLogger(BotCommandReconciler::class.java)
    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + scopeJob)
    private val lifecycleLock = Any()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    private var started = false
    private var closed = false
    private var settingsJob: Job? = null
    private var latestTarget: ReconciliationTarget? = null
    private var convergedCommand: CommandTarget? = null

    /**
     * 启动设置订阅与串行收敛 worker。
     *
     * 首次调用会立即以当前持久化设置追赶目标；重复调用和停止后的调用均不产生额外订阅或远程请求。
     */
    internal fun start() {
        synchronized(lifecycleLock) {
            if (started || closed) {
                return
            }
            started = true
            publishTargetLocked(settingsChangeCoordinator.settingsUpdateFlow.value.toReconciliationTarget())
            scope.launch { reconcileLoop() }
            settingsJob = scope.launch {
                settingsChangeCoordinator.settingsUpdateFlow.collect { update ->
                    publishTarget(update.toReconciliationTarget())
                }
            }
        }
    }

    /**
     * 关闭设置发布与重试准入，并取消协调器拥有的工作。
     *
     * 此方法可重复调用且不等待在途 worker；调用方应随后使用 [awaitStopped]，以便在关闭 Telegram 前建立
     * 完整静止边界。
     */
    internal fun requestStop() {
        val subscription = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            settingsJob.also { settingsJob = null }
        }
        subscription?.cancel(CancellationException("Bot command reconciler stopped."))
        wakeups.close()
        scopeJob.cancel(CancellationException("Bot command reconciler stopped."))
    }

    /**
     * 等待此前停止请求完全结束。
     *
     * 等待范围包括设置订阅、串行 worker 与其被取消的退避任务。调用本方法不会自行停止活跃协调器；应先调用
     * [requestStop] 或 [close]。
     */
    internal suspend fun awaitStopped() {
        scopeJob.join()
    }

    /**
     * 请求停止并等待订阅、worker 与退避任务全部结束。
     */
    internal suspend fun closeAndJoin() {
        requestStop()
        awaitStopped()
    }

    /**
     * 请求停止命令协调。
     *
     * 这是 [AutoCloseable] 兼容入口，只负责关闭准入并取消工作，不等待在途协程；需要静止边界时使用
     * [closeAndJoin]。
     */
    override fun close() = requestStop()

    private fun publishTarget(target: ReconciliationTarget) {
        synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            publishTargetLocked(target)
        }
    }

    private fun publishTargetLocked(target: ReconciliationTarget) {
        latestTarget = target
        if (target.command == null) {
            // 空 token 不能向旧 Bot 写入；之后重新配置 token 时必须重新确认其命令状态。
            convergedCommand = null
        }
        wakeups.trySend(Unit)
    }

    private suspend fun reconcileLoop() {
        var retryAttempt = 0
        while (currentCoroutineContext().isActive) {
            val target = synchronized(lifecycleLock) {
                latestTarget?.takeIf { candidate ->
                    candidate.command != null && candidate.command != convergedCommand && !closed
                }
            }
            if (target == null) {
                if (wakeups.receiveCatching().isClosed) {
                    return
                }
                retryAttempt = 0
                continue
            }

            // 设置 collector 使用合并通知；请求开始前丢弃旧通知，防止旧通知缩短本次失败的退避。
            drainWakeups()
            try {
                val command = checkNotNull(target.command)
                telegramService.updateBotCommands(command.token, command.provider)
                val current = synchronized(lifecycleLock) {
                    !closed && latestTarget?.version == target.version
                }
                if (current) {
                    synchronized(lifecycleLock) {
                        if (!closed && latestTarget?.version == target.version) {
                            convergedCommand = command
                        }
                    }
                }
                retryAttempt = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(target)) {
                    retryAttempt = 0
                    continue
                }
                retryAttempt++
                val backoff = retryBackoff(retryAttempt)
                logger.warn(
                    "Bot command reconciliation failed; settingsVersion={}, category={}; retrying after {} ms.",
                    target.version,
                    SafeLogging.failureCategory(e).wireName,
                    backoff.inWholeMilliseconds,
                )
                waitForRetryOrTargetChange(target, backoff)
            }
        }
    }

    private fun isCurrent(target: ReconciliationTarget): Boolean = synchronized(lifecycleLock) {
        !closed && latestTarget?.version == target.version
    }

    private fun drainWakeups() {
        while (wakeups.tryReceive().isSuccess) {
            // 只保留由 latestTarget 表示的最终状态。
        }
    }

    private suspend fun waitForRetryOrTargetChange(
        failedTarget: ReconciliationTarget,
        backoff: Duration,
    ) {
        while (currentCoroutineContext().isActive && isCurrent(failedTarget)) {
            val targetChanged = coroutineScope {
                val retry = async { retryDelay(backoff) }
                try {
                    select {
                        retry.onAwait { false }
                        wakeups.onReceiveCatching { true }
                    }
                } finally {
                    retry.cancel()
                }
            }
            if (!targetChanged || !isCurrent(failedTarget)) {
                return
            }
        }
    }

    private fun SettingsUpdate.toReconciliationTarget(): ReconciliationTarget = ReconciliationTarget(
        version = version,
        command = settings.telegramToken.takeIf(String::isNotBlank)?.let { token ->
            CommandTarget(token, settings.ai.effectiveCommandProvider())
        },
    )

    private fun AISettings?.effectiveCommandProvider(): AIProvider? =
        this?.takeIf { it.agentEnabled }?.provider

    private fun retryBackoff(attempt: Int): Duration {
        var delay = INITIAL_COMMAND_RETRY_DELAY
        repeat((attempt - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(MAX_COMMAND_RETRY_DELAY)
        }
        return delay
    }
}
