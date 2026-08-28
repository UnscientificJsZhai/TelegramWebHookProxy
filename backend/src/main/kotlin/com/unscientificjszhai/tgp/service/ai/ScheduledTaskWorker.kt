package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.service.ActiveTelegramBotUnavailableException
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.TelegramApiResponse
import com.unscientificjszhai.tgp.service.TelegramBotLease
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.agent.AgentConfigurationNotReadyException
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.TelegramTextChunks
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Clock
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * 扫描并执行 [ScheduledTaskService] 中已到期的任务。
 *
 * worker 独占扫描、执行去重和协程生命周期；任务状态的加载、CRUD 与耐久事务全部委托给
 * [ScheduledTaskService]。依赖方向固定为 worker 到 Agent、Telegram 和任务服务，Agent 工具只反向使用不含
 * worker 生命周期的 [ScheduledTaskService]，因此无需 Provider 打断循环。
 *
 * @param parentScope worker 根任务继承的应用协程作用域。
 * @param scheduledTaskService 提供任务查询与耐久预消费事务的服务。
 * @param telegramService 投递任务执行结果的 Telegram 服务。
 * @param agentService 执行定时任务指令的 Agent 服务。
 * @param settingsChangeCoordinator 捕获当前 Agent 会话与 Telegram Bot 租约的设置协调器。
 * @param startImmediately 构造完成后是否立即启动扫描。
 * @param clock 提供服务器当前时间的时钟。
 * @param deadlines 限制已准入定时任务完整执行链路的时限。
 */
@Singleton
class ScheduledTaskWorker private constructor(
    parentScope: CoroutineScope,
    private val scheduledTaskService: ScheduledTaskService,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val settingsChangeCoordinator: SettingsChangeCoordinator,
    startImmediately: Boolean,
    private val clock: Clock,
    private val deadlines: AgentExecutionDeadlines,
) : AutoCloseable {
    /**
     * 创建应用级 worker；应用模块在完成停止编排注册后显式调用 [start]。
     *
     * @param parentScope worker 根任务继承的应用协程作用域。
     * @param scheduledTaskService 提供任务查询与耐久预消费事务的服务。
     * @param telegramService 投递任务执行结果的 Telegram 服务。
     * @param agentService 执行定时任务指令的 Agent 服务。
     * @param settingsChangeCoordinator 捕获当前 Agent 会话与 Telegram Bot 租约的设置协调器。
     * @param deadlines 限制已准入定时任务完整执行链路的时限。
     */
    @Inject
    internal constructor(
        parentScope: CoroutineScope,
        scheduledTaskService: ScheduledTaskService,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsChangeCoordinator: SettingsChangeCoordinator,
        deadlines: AgentExecutionDeadlines,
    ) : this(
        parentScope,
        scheduledTaskService,
        telegramService,
        agentService,
        settingsChangeCoordinator,
        false,
        Clock.systemDefaultZone(),
        deadlines,
    )

    /**
     * 为确定性测试创建默认不自动启动的 worker。
     *
     * @param parentScope worker 根任务继承的测试协程作用域。
     * @param scheduledTaskService 提供任务查询与耐久预消费事务的服务。
     * @param telegramService 投递任务执行结果的 Telegram 服务。
     * @param agentService 执行定时任务指令的 Agent 服务。
     * @param settingsChangeCoordinator 捕获当前 Agent 会话与 Telegram Bot 租约的设置协调器。
     * @param clock 提供确定性当前时间的时钟。
     * @param startImmediately 构造完成后是否立即启动扫描。
     * @param deadlines 限制已准入定时任务完整执行链路的时限。
     */
    internal constructor(
        parentScope: CoroutineScope,
        scheduledTaskService: ScheduledTaskService,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsChangeCoordinator: SettingsChangeCoordinator,
        clock: Clock = Clock.systemDefaultZone(),
        startImmediately: Boolean = false,
        deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    ) : this(
        parentScope,
        scheduledTaskService,
        telegramService,
        agentService,
        settingsChangeCoordinator,
        startImmediately,
        clock,
        deadlines,
    )

    private val logger = LoggerFactory.getLogger(ScheduledTaskWorker::class.java)
    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + scopeJob)
    private val lifecycleLock = ReentrantLock()
    private val executingTaskIds = mutableSetOf<String>()
    private var scanJob: Job? = null
    private var closed = false

    init {
        if (startImmediately) {
            start()
        }
    }

    /** 启动每分钟一次的扫描；重复调用安全，关闭后不可重启。 */
    fun start() {
        val jobToStart = lifecycleLock.withLock {
            if (closed || scanJob != null) {
                return
            }
            scope.launch(start = CoroutineStart.LAZY) {
                while (isActive) {
                    try {
                        scanAndExecute()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error("Task scan failed; category={}", SafeLogging.failureCategory(e).wireName)
                    }
                    delay(1.minutes)
                }
            }.also { scanJob = it }
        }
        jobToStart.start()
        logger.info("Scheduled task worker started.")
    }

    /**
     * 扫描并依次执行到期任务。
     *
     * 并发扫描通过 worker 本地执行权集合去重；取消、替换和到期状态仍由任务服务在耐久预消费时重新确认。
     */
    internal suspend fun scanAndExecute() {
        val currentTime = try {
            clock.millis()
        } catch (e: ArithmeticException) {
            logger.warn(
                "Scheduler clock cannot be represented as epoch milliseconds; skipping this scan; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            return
        }
        val snapshots = scheduledTaskService.dueTaskSnapshots(currentTime)
        val tasksToExecute = lifecycleLock.withLock {
            if (closed) {
                return
            }
            snapshots.filter { task -> executingTaskIds.add(task.id) }
        }

        try {
            for (task in tasksToExecute) {
                if (!lifecycleLock.withLock { !closed }) {
                    return
                }
                executeTask(task)
            }
        } finally {
            lifecycleLock.withLock {
                tasksToExecute.forEach { task -> executingTaskIds.remove(task.id) }
            }
        }
    }

    /**
     * 在 Agent 就绪作用域中再次授权、耐久预消费并执行任务。
     *
     * @param task 当前扫描已声明本地执行权的任务快照。
     */
    private suspend fun executeTask(task: ScheduledTask) {
        when (taskAuthorization(task)) {
            TaskAuthorization.Retain -> return
            TaskAuthorization.Revoked -> {
                revokeTask(task)
                return
            }

            is TaskAuthorization.Authorized -> Unit
        }

        try {
            agentService.withReadyService { readyAgent ->
                try {
                    withTimeout(deadlines.scheduledTurn) {
                        val authorization = taskAuthorization(task)
                        when (authorization) {
                            TaskAuthorization.Retain -> return@withTimeout
                            TaskAuthorization.Revoked -> {
                                revokeTask(task)
                                return@withTimeout
                            }

                            is TaskAuthorization.Authorized -> Unit
                        }
                        val preparedTask = prepareTaskForExecution(task, authorization.telegramLease)
                            ?: return@withTimeout
                        logger.info("Executing precommitted task {}", preparedTask.task.id)
                        val result = readyAgent.sendMessage(
                            "以下是一个定时任务指令：\n${preparedTask.task.instruction}\n\n请直接执行并返回结果。",
                        )
                        if (result.isNotBlank()) {
                            deliverTaskResult(preparedTask.task, preparedTask.telegramLease.token, result)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.warn("Precommitted task {} timed out; it will not be retried or delivered", task.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AgentTurnFailedException) {
                    logger.warn(
                        "Precommitted task {} agent turn did not complete; it will not be retried; category={}",
                        task.id,
                        SafeLogging.failureCategory(e).wireName,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Precommitted task {} agent execution failed; it will not be retried; category={}",
                        task.id,
                        SafeLogging.failureCategory(e).wireName,
                    )
                }
            }
        } catch (_: AgentConfigurationNotReadyException) {
            logger.info("Skipping task {} because the current AI configuration is not ready", task.id)
        }
    }

    /**
     * 仅在任务服务确认预消费已耐久后返回执行快照与 Telegram token。
     *
     * @param task 当前扫描取得的任务快照。
     * @param telegramLease 第二次授权复核捕获的 Telegram Bot 租约。
     * @return 已耐久预消费的任务与租约；任务已变化或不再到期时返回 `null`。
     */
    private fun prepareTaskForExecution(
        task: ScheduledTask,
        telegramLease: TelegramBotLease,
    ): PreparedTask? = try {
        scheduledTaskService.precommitExecution(task, clock::millis)
            ?.let { PreparedTask(it, telegramLease) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error(
            "Failed to precommit task {}; it will remain eligible for a later scan; category={}",
            task.id,
            SafeLogging.failureCategory(e).wireName,
        )
        null
    }

    /**
     * 短暂确认当前 AI 会话及 Telegram token 授权。
     *
     * @param task 需要复核授权的任务快照。
     * @return 保留、撤销或携带当前 Telegram 租约的授权结果。
     */
    private fun taskAuthorization(task: ScheduledTask): TaskAuthorization = try {
        settingsChangeCoordinator.withActiveTelegramBotSettingsLease { telegramLease, settings ->
            val currentAgentChatId = settings.ai
                ?.takeIf { it.agentEnabled }
                ?.agentChatId
            when {
                currentAgentChatId.isNullOrBlank() -> TaskAuthorization.Revoked
                currentAgentChatId != task.agentChatId -> TaskAuthorization.Revoked
                else -> TaskAuthorization.Authorized(telegramLease)
            }
        }
    } catch (_: ActiveTelegramBotUnavailableException) {
        TaskAuthorization.Retain
    }

    /**
     * 删除已撤销且仍与扫描快照相同的任务。
     *
     * @param task 已被授权复核判定撤销的任务快照。
     */
    private fun revokeTask(task: ScheduledTask) {
        try {
            if (scheduledTaskService.removeIfUnchanged(task)) {
                logger.info("Removed revoked task {}", task.id)
            } else {
                logger.info("Skipping revoked task {} because it was cancelled or replaced before removal", task.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to remove revoked task {}; it will remain eligible for a later scan; category={}",
                task.id,
                SafeLogging.failureCategory(e).wireName,
            )
        }
    }

    /**
     * 尽力分块投递已完成的任务结果，并在每块前重新确认 token。
     *
     * @param task 已耐久预消费并完成 Agent 执行的任务。
     * @param token 任务准入时捕获的 Telegram Bot token。
     * @param result Agent 返回的任务执行结果。
     */
    private suspend fun deliverTaskResult(task: ScheduledTask, token: String, result: String) {
        try {
            val fullText = "⏰ 定时任务执行结果：\n\n$result"
            for (chunk in TelegramTextChunks.split(fullText)) {
                if (!isTaskResultTokenCurrent(token)) {
                    logger.info("Stopping task result delivery for {} because its bot token changed", task.id)
                    return
                }
                val response = telegramService.sendMessageForToken(token, task.agentChatId, chunk)
                if (!response.isTelegramOk()) {
                    logger.warn("Telegram did not accept task result chunk for {}", task.id)
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to send task result for {}; category={}",
                task.id,
                SafeLogging.failureCategory(e).wireName,
            )
        }
    }

    private fun isTaskResultTokenCurrent(token: String): Boolean = try {
        settingsChangeCoordinator.withActiveTelegramBotLease { lease -> lease.token == token }
    } catch (_: ActiveTelegramBotUnavailableException) {
        false
    }

    private fun TelegramApiResponse.isTelegramOk(): Boolean =
        status.isSuccess() && try {
            JsonStructureLimits.parseToJsonElement(Json, body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }

    /** 关闭扫描准入并取消 worker 拥有的全部协程。 */
    internal fun requestStop() {
        val jobToCancel = lifecycleLock.withLock {
            if (closed) {
                return
            }
            // 与扫描准入共享 lifecycle lock，并在发布 closed 前关闭创建准入：一旦后续创建观察到停止，
            // 后续扫描也不可能再越过准入门。
            scheduledTaskService.stopAcceptingNewTasks()
            closed = true
            scanJob.also { scanJob = null }
        }
        jobToCancel?.cancel(CancellationException("Scheduled task worker stopped."))
        scopeJob.cancel(CancellationException("Scheduled task worker stopped."))
        logger.info("Scheduled task worker stopped.")
    }

    /** 等待扫描根任务及全部子协程终态。 */
    internal suspend fun awaitStopped() {
        scopeJob.join()
    }

    /** [AutoCloseable] 兼容入口；关闭扫描准入并请求停止，不等待协程终态。 */
    override fun close() = requestStop()

    /**
     * 已耐久预消费且绑定 Telegram Bot 租约的执行快照。
     *
     * @property task 可以安全执行外部副作用的任务。
     * @property telegramLease 执行准入时捕获的 Telegram Bot 租约。
     */
    private data class PreparedTask(
        val task: ScheduledTask,
        val telegramLease: TelegramBotLease,
    )

    private sealed interface TaskAuthorization {
        /**
         * 当前设置仍授权任务执行。
         *
         * @property telegramLease 授权复核时捕获的 Telegram Bot 租约。
         */
        data class Authorized(val telegramLease: TelegramBotLease) : TaskAuthorization
        data object Revoked : TaskAuthorization
        data object Retain : TaskAuthorization
    }
}
