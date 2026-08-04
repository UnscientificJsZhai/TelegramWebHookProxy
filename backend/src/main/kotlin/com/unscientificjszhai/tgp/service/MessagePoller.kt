package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.TelegramReplyDeliveryStage
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.repository.AgentTurnClaim
import com.unscientificjszhai.tgp.repository.AgentTurnJournalEntry
import com.unscientificjszhai.tgp.repository.AgentTurnJournalStatus
import com.unscientificjszhai.tgp.repository.MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS
import com.unscientificjszhai.tgp.repository.botIdFromTelegramToken
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.MAX_AGENT_TEXT_BYTES
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.SafeLogging
import io.ktor.http.isSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.random.Random

private const val TELEGRAM_REPLY_FALLBACK_MESSAGE = "抱歉，上一条回复未能发送。"
private const val AGENT_TURN_FAILURE_REPLY = "抱歉，该消息未能处理。"

/**
 * 后台轮询 Telegram 更新，并将授权用户私聊的消息依次交给 AI 代理处理。
 *
 * 每个有效 token 生命周期拥有唯一的轮询会话。会话捕获 token、bot 标识、token 代次、队列、
 * 子作用域和上下文清理计时；token 更换、清空或代次变化时会先取消旧会话的在途及排队任务，
 * 再创建新会话。旧会话永远不会确认排队完成或推进偏移量。
 *
 * @constructor 创建消息轮询服务。
 * @param parentScope 持有轮询服务的父协程作用域；取消该作用域会停止内部轮询任务。
 * @param telegramService 与 Telegram Bot API 通信的服务。
 * @param agentService 处理文本和媒体消息的 AI 代理服务。
 * @param settingsRepository 提供机器人与 AI 设置的仓储。
 * @param updatesRepository 持久化按机器人隔离的聊天信息和已完成更新标识的仓储。
 * @param modelSwitchBarrier 与设置仓储及代理服务共享的启动和模型切换屏障。
 */
@Singleton
class MessagePoller @Inject constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
) : AutoCloseable {
    /**
     * 为未接入依赖注入的既有调用方创建轮询服务。
     *
     * 此兼容构造器复用 [settingsRepository] 持有的屏障，不会创建独立屏障。新代码应显式传入应用共享的
     * [ModelSwitchBarrier]。
     *
     * @param parentScope 持有轮询服务的父协程作用域；取消该作用域会停止内部轮询任务。
     * @param telegramService 与 Telegram Bot API 通信的服务。
     * @param agentService 处理文本和媒体消息的 AI 代理服务。
     * @param settingsRepository 提供机器人与 AI 设置的仓储，且必须持有应用共享的屏障。
     * @param updatesRepository 持久化按机器人隔离的聊天信息和已完成更新标识的仓储。
     */
    @Deprecated("请显式传入与 SettingsRepository 共享的 ModelSwitchBarrier。")
    constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsRepository,
        updatesRepository,
        settingsRepository.modelSwitchBarrier,
    )

    private val logger = LoggerFactory.getLogger(MessagePoller::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private val lifecycleLock = Any()
    private val sessionLock = ReentrantLock()
    private val agentTurnOwners = mutableMapOf<AgentTurnKey, CompletableDeferred<Unit>>()

    @Volatile
    private var closed = false

    private var settingsJob: Job? = null
    private var currentSession: PollingSession? = null
    private var pendingAgentReset: PendingAgentReset? = null
    private var processingTimeout: Duration = 10.minutes
    private var retryDelay: suspend (Duration) -> Unit = { delay(it) }
    private var retryJitter: (Duration) -> Duration = { backoff ->
        if (backoff <= Duration.ZERO) {
            Duration.ZERO
        } else {
            Random.nextLong((backoff.inWholeMilliseconds / 5) + 1).milliseconds
        }
    }

    /**
     * 使用指定单条消息处理时限创建仅供测试使用的轮询服务。
     *
     * @param parentScope 持有轮询服务的父协程作用域。
     * @param telegramService 与 Telegram Bot API 通信的服务。
     * @param agentService 处理文本和媒体消息的 AI 代理服务。
     * @param settingsRepository 提供机器人与 AI 设置的仓储。
     * @param updatesRepository 持久化按机器人隔离的状态的仓储。
     * @param modelSwitchBarrier 与设置仓储及代理服务共享的启动和模型切换屏障。
     * @param processingTimeout 单条排队消息允许的最长处理时长；必须大于零。
     * @param retryDelay 执行一次失败退避的挂起函数；只接收非负时长，测试可注入无等待实现。
     * @param retryJitter 基于本地退避上限生成额外抖动的函数；返回负值会被忽略，测试可返回零以获得确定性时长。
     */
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        modelSwitchBarrier: ModelSwitchBarrier,
        processingTimeout: Duration,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
    ) : this(parentScope, telegramService, agentService, settingsRepository, updatesRepository, modelSwitchBarrier) {
        require(processingTimeout.isPositive()) { "processingTimeout must be positive." }
        this.processingTimeout = processingTimeout
        this.retryDelay = retryDelay
        this.retryJitter = retryJitter
    }

    /**
     * 使用指定单条消息处理时限创建兼容的测试轮询服务。
     *
     * 此兼容构造器复用 [settingsRepository] 持有的屏障，不会创建独立屏障；新的测试应显式传入共享屏障。
     *
     * @param parentScope 持有轮询服务的父协程作用域。
     * @param telegramService 与 Telegram Bot API 通信的服务。
     * @param agentService 处理文本和媒体消息的 AI 代理服务。
     * @param settingsRepository 提供机器人与 AI 设置的仓储，且必须持有共享屏障。
     * @param updatesRepository 持久化按机器人隔离的状态的仓储。
     * @param processingTimeout 单条排队消息允许的最长处理时长；必须大于零。
     * @param retryDelay 执行一次失败退避的挂起函数；只接收非负时长，测试可注入无等待实现。
     * @param retryJitter 基于本地退避上限生成额外抖动的函数；返回负值会被忽略，测试可返回零以获得确定性时长。
     */
    @Deprecated("新的测试应显式传入与 SettingsRepository 共享的 ModelSwitchBarrier。")
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        processingTimeout: Duration,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsRepository,
        updatesRepository,
        settingsRepository.modelSwitchBarrier,
        processingTimeout,
        retryDelay,
        retryJitter,
    )

    private class PollingSession(
        val token: String,
        val botId: String,
        val generation: Long,
        val scope: CoroutineScope,
        val updateChannel: Channel<QueuedUpdate>,
        val consumerResume: Channel<Unit>,
        val outboxSignal: Channel<Unit>,
        var pollJob: Job? = null,
        var consumerJob: Job? = null,
        var outboxJob: Job? = null,
        var lastAiReplyAtMillis: Long? = null,
        var consecutivePollingFailures: Int = 0,
        var initialOffsetResolved: Boolean = false,
    )

    /**
     * 尚未完成的全局 Agent 上下文清除状态。
     *
     * 实例仅在 [sessionLock] 保护下读写。普通 token 轮换会保留该状态直至当前代次的新会话已原子安装，
     * 防止屏障在新 bot 可轮询前提前放行；认证失败会在成功清除后终止当前会话并放行。初次重置失败时保留
     * 外部屏障代次，下一次实际 token 生命周期事件会复用该实例并串行重试。
     */
    private class PendingAgentReset(
        val barrierGeneration: Long,
        val source: AgentResetSource,
        val initialResetCompletion: CompletableDeferred<Boolean> = CompletableDeferred(),
        var retryCompletion: CompletableDeferred<Boolean>? = null,
    )

    /** 待处理 Agent 重置的起因；两种起因共享失败重试和关闭释放语义。 */
    private enum class AgentResetSource {
        TOKEN_ROTATION,
        AUTHENTICATION_FAILURE,
    }

    /** token 生命周期事件对当前会话和待处理 Agent 重置状态的原子判定。 */
    private sealed interface SessionReplacement {
        /** 当前 token 代次已经拥有对应会话，或事件已经过期。 */
        data object NoOp : SessionReplacement

        /** 没有旧会话或待处理重置，可直接尝试安装当前 token 的会话。 */
        data object Install : SessionReplacement

        /** 已摘除旧会话，必须先完成新建的全局 Agent 重置。 */
        data class ResetPrevious(
            val previous: PollingSession,
            val pendingReset: PendingAgentReset,
        ) : SessionReplacement

        /** 另一条路径已经摘除了旧会话；当前事件只能等待或重试其 Agent 重置。 */
        data class AwaitPending(val pendingReset: PendingAgentReset) : SessionReplacement
    }

    /** 尝试安装当前 token 会话后的结果。 */
    private enum class SessionInstallation {
        INSTALLED,
        INVALID_TOKEN,
        NOT_CURRENT,
    }

    private data class QueuedUpdate(
        val update: Update,
        val entryTime: Long,
        val completion: CompletableDeferred<UpdateCompletion>,
    )

    /** 同一进程内唯一标识一个已经由消费者占有的 Agent 回合。 */
    private data class AgentTurnKey(
        val botId: String,
        val updateId: Long,
    )

    /** 消费者完成一项排队更新后对轮询偏移量的处理要求。 */
    private sealed interface UpdateCompletion {
        /** Agent 回合及其 outbox（若有）已经原子持久化，偏移量已同步推进。 */
        data object Persisted : UpdateCompletion

        /** 更新不需要 Agent 事务，轮询器仍需按正常路径确认偏移量。 */
        data object Confirmed : UpdateCompletion

        /** 未能安全持久化或处理该更新，必须保留偏移量以重试。 */
        data object Retry : UpdateCompletion
    }

    /** 一条更新在当前轮询批次中的接纳结果。 */
    private sealed interface UpdateAdmission {
        /** 更新无需排队或队满提示已被 Telegram 接受，可以推进偏移量。 */
        data object Confirmed : UpdateAdmission

        /** 更新已加入消费者队列，必须等待 [completion] 后才能推进偏移量。 */
        data class Enqueued(val completion: CompletableDeferred<UpdateCompletion>) : UpdateAdmission

        /** 队满提示未被 Telegram 接受，必须保留当前及后续更新以便重试。 */
        data object Retry : UpdateAdmission
    }

    /** 屏障放行后的本地接纳判定；队满通知必须在屏障外发送。 */
    private sealed interface BarrierAdmission {
        /** 更新无需排队或会话已不再当前。 */
        data object Confirmed : BarrierAdmission

        /** 稳定配置与当前代理不一致，必须保留偏移量等待后续轮询。 */
        data object Retry : BarrierAdmission

        /** 更新已提交给当前会话的消费者队列。 */
        data class Enqueued(val completion: CompletableDeferred<UpdateCompletion>) : BarrierAdmission

        /** 当前会话的消费者队列已满，等待在屏障外发送 Telegram 提示。 */
        data class QueueFull(val chatId: String, val messageId: Long) : BarrierAdmission
    }

    /** 在 token 生命周期与会话锁内提交队列的结果。 */
    private enum class QueueOfferResult {
        ENQUEUED,
        FULL,
        NOT_CURRENT,
    }

    /** 单次初始化或长轮询请求的结果；失败结果绝不包含可推进偏移量的更新。 */
    private sealed interface PollingAttempt {
        data class Succeeded(val retryOffsetResolved: Boolean) : PollingAttempt
        data object Stopped : PollingAttempt
        data class ApiFailure(val response: GetUpdatesResponse) : PollingAttempt
        data class LocalRetry(val retryOffset: Long) : PollingAttempt
    }

    /**
     * 启动 token 生命周期监听，并按需创建唯一轮询会话。
     *
     * 此方法不会等待代理初始化或阻塞调用线程。屏障放行后才订阅 token 流并创建会话；关闭或调用协程
     * 已取消时不创建会话。重复调用不会创建额外监听器。token 为空或格式无有效 bot 前缀时不创建会话；
     * 每次 token 实际改变后的代次都会替换会话，即使最终 token 文本恢复为原值。
     */
    fun start() {
        val started = synchronized(lifecycleLock) {
            if (closed || settingsJob != null) {
                false
            } else {
                settingsJob = scope.launch {
                    modelSwitchBarrier.awaitReady()
                    currentCoroutineContext().ensureActive()
                    if (closed) {
                        return@launch
                    }
                    settingsRepository.telegramTokenUpdateFlow.collect { tokenUpdate ->
                        currentCoroutineContext().ensureActive()
                        if (!closed) {
                            replaceSession(tokenUpdate.token, tokenUpdate.generation)
                        }
                    }
                }
                true
            }
        }
        if (started) {
            logger.info("Agent poller observer started.")
        }
    }

    private suspend fun replaceSession(token: String, generation: Long) {
        if (closed) {
            return
        }
        val replacement = settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                when {
                    closed || !isTokenGenerationCurrent(token, generation) -> SessionReplacement.NoOp
                    currentSession?.let { it.token == token && it.generation == generation } == true -> SessionReplacement.NoOp
                    pendingAgentReset != null -> SessionReplacement.AwaitPending(checkNotNull(pendingAgentReset))
                    currentSession == null -> SessionReplacement.Install
                    else -> {
                        // 必须在摘除旧会话之前创建屏障：否则新 Bot 的 Agent 请求可能在旧上下文被清除前准入。
                        val pendingReset = PendingAgentReset(
                            barrierGeneration = modelSwitchBarrier.beginExternalSwitch(),
                            source = AgentResetSource.TOKEN_ROTATION,
                        )
                        val previous = checkNotNull(currentSession)
                        currentSession = null
                        previous.updateChannel.close()
                        pendingAgentReset = pendingReset
                        SessionReplacement.ResetPrevious(previous, pendingReset)
                    }
                }
            }
        }
        val pendingReset = when (replacement) {
            SessionReplacement.NoOp -> return
            SessionReplacement.Install -> null
            is SessionReplacement.ResetPrevious -> {
                replacement.previous.scope.cancel(CancellationException("Telegram token changed"))
                withContext(NonCancellable) {
                    replacement.previous.scope.coroutineContext[Job]?.join()
                }
                if (!completeInitialAgentReset(replacement.pendingReset)) {
                    logger.warn(
                        "Refusing to start polling session at token generation {} until the token-rotation Agent reset succeeds.",
                        generation,
                    )
                    return
                }
                logger.info(
                    "Cancelled polling session for bot {} at generation {}",
                    replacement.previous.botId,
                    replacement.previous.generation,
                )
                replacement.pendingReset
            }

            is SessionReplacement.AwaitPending -> {
                if (!awaitPendingAgentResetBeforeSession(replacement.pendingReset)) {
                    logger.warn(
                        "Refusing to start polling session at token generation {} until the pending Agent reset succeeds.",
                        generation,
                    )
                    return
                }
                replacement.pendingReset.takeIf { it.source == AgentResetSource.TOKEN_ROTATION }
            }
        }

        when (installCurrentTokenSession(token, generation, pendingReset)) {
            SessionInstallation.INSTALLED -> Unit
            SessionInstallation.INVALID_TOKEN -> {
                if (pendingReset != null) {
                    completePendingResetForInvalidToken(pendingReset, token, generation)
                }
                logger.info("Agent poller paused due to empty or invalid token.")
            }

            SessionInstallation.NOT_CURRENT -> Unit
        }
    }

    /**
     * 在 token 生命周期锁和会话锁内安装尚未启动的轮询会话。
     *
     * 对普通 token 轮换，只有安装与清除 [PendingAgentReset] 同处一个临界区时才返回
     * [SessionInstallation.INSTALLED]；调用方随后才可释放外部屏障。若 token 已再次变化则保留待处理重置，
     * 交由最新 token 代次收敛，绝不安装过期会话。
     */
    private fun installCurrentTokenSession(
        token: String,
        generation: Long,
        pendingReset: PendingAgentReset?,
    ): SessionInstallation {
        val botId = token.botIdFromTelegramToken() ?: return SessionInstallation.INVALID_TOKEN
        val sessionScope = scope + SupervisorJob(scope.coroutineContext[Job])
        val session = PollingSession(
            token = token,
            botId = botId,
            generation = generation,
            scope = sessionScope,
            updateChannel = Channel(capacity = 10),
            consumerResume = Channel(capacity = Channel.CONFLATED),
            outboxSignal = Channel(capacity = Channel.CONFLATED),
        )
        val barrierGenerationToRelease = settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (
                    closed ||
                    !isTokenGenerationCurrent(token, generation) ||
                    (pendingReset != null && pendingAgentReset !== pendingReset)
                ) {
                    null
                } else {
                    currentSession = session
                    pendingReset?.let { pendingAgentReset = null }
                    pendingReset?.barrierGeneration ?: Long.MIN_VALUE
                }
            }
        }
        if (barrierGenerationToRelease == null) {
            sessionScope.cancel()
            return SessionInstallation.NOT_CURRENT
        }
        if (barrierGenerationToRelease != Long.MIN_VALUE) {
            // 会话身份已在两个生命周期锁内提交；现在才允许新 Agent 请求越过本次外部屏障。
            modelSwitchBarrier.complete(barrierGenerationToRelease)
        }
        try {
            // `completeAgentUpdate` 已经提交而账本删除失败时，下一次同 bot 会话会在任何 Agent claim 前回收
            // FINAL 残留。失败不影响已确认偏移量；后续 claim 仍会 fail-closed。
            updatesRepository.cleanupConfirmedAgentTurns(botId)
        } catch (e: Exception) {
            logger.warn(
                "Deferred confirmed Agent journal cleanup for bot {}; category={}",
                botId,
                SafeLogging.failureCategory(e).wireName,
            )
        }
        session.consumerJob = session.scope.launch { consumeQueue(session) }
        session.outboxJob = session.scope.launch { consumeOutbox(session) }
        session.pollJob = session.scope.launch { runPolling(session) }
        logger.info("Started polling session for bot {} at generation {}", botId, generation)
        return SessionInstallation.INSTALLED
    }

    private suspend fun runPolling(session: PollingSession) {
        var retryOffset: Long? = null
        while (currentCoroutineContext().isActive) {
            try {
                if (retryOffset != null) {
                    // 前一项回合未能安全提交时，消费者已把当批后续更新标记为 Retry 并暂停；只有重新进入
                    // 轮询后才允许它消费新批次，避免较高 update 越过未提交的较低 update。
                    session.consumerResume.trySend(Unit)
                }
                when (val attempt = pollOnce(session, retryOffset)) {
                    is PollingAttempt.Succeeded -> {
                        if (attempt.retryOffsetResolved) {
                            retryOffset = null
                        }
                        session.consecutivePollingFailures = 0
                        // 成功轮询沿用既有短暂让步；失败路径绝不会再叠加这段延迟。
                        delay(1000.milliseconds)
                    }

                    PollingAttempt.Stopped -> return
                    is PollingAttempt.LocalRetry -> {
                        retryOffset = attempt.retryOffset
                        if (!delayAfterFailure(session)) {
                            return
                        }
                    }

                    is PollingAttempt.ApiFailure -> {
                        if (!handleApiFailure(session, attempt.response)) {
                            return
                        }
                    }
                }
            } catch (_: CancellationException) {
                return
            } catch (e: Exception) {
                logger.warn(
                    "Polling request failed for bot {} at generation {}; category={}",
                    session.botId,
                    session.generation,
                    SafeLogging.failureCategory(e).wireName,
                )
                if (!delayAfterFailure(session)) {
                    return
                }
            }
        }
    }

    /**
     * 执行一次初始化或正常长轮询；只有成功响应才会保存初始化偏移量或确认队列更新。
     *
     * @param retryOffset 队满通知未被接受时要重拉的首条更新标识；为 `null` 时从已提交偏移量后的首条更新开始。
     */
    private suspend fun pollOnce(session: PollingSession, retryOffset: Long?): PollingAttempt {
        if (!isCurrent(session)) {
            return PollingAttempt.Stopped
        }
        var lastStoredId = readForCurrent(session) {
            updatesRepository.getData(session.botId).lastUpdateId
        } ?: return PollingAttempt.Stopped
        if (lastStoredId == 0L && retryOffset == null && !session.initialOffsetResolved) {
            val initialResponse = telegramService.getUpdatesForToken(session.token, offset = -1, timeout = 0)
            if (!isCurrent(session)) {
                return PollingAttempt.Stopped
            }
            if (!initialResponse.ok) {
                return PollingAttempt.ApiFailure(initialResponse)
            }
            if (initialResponse.result.isNotEmpty()) {
                lastStoredId = initialResponse.result.last().updateId
                if (!saveForCurrent(session) {
                        updatesRepository.saveLastUpdateId(session.botId, lastStoredId)
                    }
                ) {
                    return PollingAttempt.Stopped
                }
                logger.info("Initialized lastUpdateId for bot {} to {}", session.botId, lastStoredId)
            }
            session.initialOffsetResolved = true
            return PollingAttempt.Succeeded(retryOffsetResolved = true)
        }

        val response = telegramService.getUpdatesForToken(
            session.token,
            offset = retryOffset ?: (lastStoredId + 1),
            timeout = 30,
        )
        if (!isCurrent(session)) {
            return PollingAttempt.Stopped
        }
        if (!response.ok) {
            return PollingAttempt.ApiFailure(response)
        }
        val completions = mutableListOf<Pair<Long, CompletableDeferred<UpdateCompletion>>>()
        val discoveredChats = LinkedHashMap<String, ChatInfo>()
        var mustRetry = false
        var retryUpdateId: Long? = null
        for (update in response.result) {
            try {
                when (val admission = enqueueUpdate(session, update)) {
                    UpdateAdmission.Confirmed -> {
                        update.chatInfo()?.let { discoveredChats[it.id] = it }
                        completions += update.updateId to confirmedSignal()
                    }

                    is UpdateAdmission.Enqueued -> {
                        update.chatInfo()?.let { discoveredChats[it.id] = it }
                        completions += update.updateId to admission.completion
                    }

                    UpdateAdmission.Retry -> {
                        mustRetry = true
                        retryUpdateId = update.updateId
                        break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Failed to admit update {}; preserving its offset for retry; category={}",
                    update.updateId,
                    SafeLogging.failureCategory(e).wireName,
                )
                mustRetry = true
                retryUpdateId = update.updateId
                break
            }
        }
        if (discoveredChats.isNotEmpty() && !saveForCurrent(session) {
                updatesRepository.mergeChats(session.botId, discoveredChats.values)
            }
        ) {
            return PollingAttempt.Stopped
        }
        for ((updateId, completion) in completions.sortedBy { it.first }) {
            when (completion.await()) {
                UpdateCompletion.Persisted -> {
                    // Agent 回合及其可能的 outbox 已在同一次提交中确认偏移量。
                    lastStoredId = maxOf(lastStoredId, updateId)
                }

                UpdateCompletion.Confirmed -> {
                    if (updateId > lastStoredId) {
                        if (!saveForCurrent(session) {
                                updatesRepository.saveLastUpdateId(session.botId, updateId)
                            }
                        ) {
                            return PollingAttempt.Stopped
                        }
                        lastStoredId = updateId
                    }
                }

                UpdateCompletion.Retry -> {
                    mustRetry = true
                    retryUpdateId = updateId
                    break
                }
            }
        }
        return when {
            !isCurrent(session) -> PollingAttempt.Stopped
            mustRetry -> PollingAttempt.LocalRetry(checkNotNull(retryUpdateId))
            else -> PollingAttempt.Succeeded(
                retryOffsetResolved = retryOffset == null || completions.any { (updateId, _) -> updateId == retryOffset },
            )
        }
    }

    /** 分类 Telegram API 的失败；认证失败会仅在本会话仍当前时终止整套轮询资源。 */
    private suspend fun handleApiFailure(session: PollingSession, response: GetUpdatesResponse): Boolean =
        when (response.errorCode) {
            401,
            403,
                -> {
                logger.error(
                    "Telegram authentication failed for bot {} at generation {} with HTTP {}. Polling session will stop.",
                    session.botId,
                    session.generation,
                    response.errorCode,
                )
                terminateAuthenticationFailedSession(session)
                false
            }

            409 -> {
                logger.error(
                    "Telegram getUpdates conflict for bot {} at generation {}; another getUpdates consumer exists.",
                    session.botId,
                    session.generation,
                )
                delayAfterFailure(session)
            }

            429 -> {
                val retryAfter = response.parameters?.retryAfter?.takeIf { it > 0 }?.seconds
                logger.warn(
                    "Telegram rate limited bot {} at generation {} (retry_after={}).",
                    session.botId,
                    session.generation,
                    retryAfter?.inWholeSeconds ?: "ignored",
                )
                delayAfterFailure(session, retryAfter)
            }

            else -> {
                logger.warn(
                    "Telegram getUpdates failed for bot {} at generation {} with API error {}.",
                    session.botId,
                    session.generation,
                    response.errorCode ?: "unknown",
                )
                delayAfterFailure(session)
            }
        }

    /**
     * 增加会话失败计数并执行唯一、可取消的退避。
     *
     * 不会持有会话锁或 token 生命周期锁；token 切换和关闭会取消会话 scope，从而中断该等待。
     */
    private suspend fun delayAfterFailure(session: PollingSession, retryAfter: Duration? = null): Boolean {
        if (!isCurrent(session)) {
            return false
        }
        session.consecutivePollingFailures = (session.consecutivePollingFailures + 1).coerceAtMost(7)
        val localBackoff = localBackoff(session.consecutivePollingFailures)
        val requiredDelay = maxOf(localBackoff, retryAfter ?: Duration.ZERO)
        val jitter = retryJitter(localBackoff).coerceAtLeast(Duration.ZERO)
        val delayDuration = requiredDelay + jitter
        logger.info(
            "Polling retry for bot {} at generation {} after {} ms (failure #{}, local={} ms).",
            session.botId,
            session.generation,
            delayDuration.inWholeMilliseconds,
            session.consecutivePollingFailures,
            localBackoff.inWholeMilliseconds,
        )
        retryDelay(delayDuration)
        return isCurrent(session)
    }

    /** 返回 `1, 2, 4, …, 60` 秒的本地指数退避上限。 */
    private fun localBackoff(failureCount: Int): Duration =
        (1L shl (failureCount - 1).coerceIn(0, 6)).seconds.coerceAtMost(60.seconds)

    /**
     * 原子摘除仍为当前代次的认证失败会话，并在外部屏障代次内清除 Agent 上下文。
     *
     * 认证失败不能由当前已经持久化的设置快照覆盖，因此登记为外部代次。初次重置失败时保留
     * [PendingAgentReset]；后续 token 会话只能在重试成功后安装。会话 scope 的取消会取消调用本方法的
     * 轮询协程，故清理必须置于 [NonCancellable] 中；不会等待该 scope，以免等待自身造成死锁。
     */
    private suspend fun terminateAuthenticationFailedSession(session: PollingSession) {
        val pendingReset = settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (!closed && currentSession === session && isTokenGenerationCurrent(session)) {
                    // 认证失败也必须在摘除会话前关闭 Agent 准入，避免并发请求使用即将清除的上下文。
                    val pending = PendingAgentReset(
                        barrierGeneration = modelSwitchBarrier.beginExternalSwitch(),
                        source = AgentResetSource.AUTHENTICATION_FAILURE,
                    )
                    currentSession = null
                    session.updateChannel.close()
                    pendingAgentReset = pending
                    pending
                } else {
                    null
                }
            }
        }
        pendingReset ?: return

        withContext(NonCancellable) {
            session.scope.cancel(CancellationException("Telegram authentication failed"))
            if (!completeInitialAgentReset(pendingReset)) {
                logger.warn(
                    "Agent session reset failed after Telegram authentication failure; blocking new polling sessions until a retry succeeds.",
                )
            }
        }
    }

    /**
     * 等待待处理 Agent 重置的初次执行完成，并在失败时由即将安装的新 token 会话执行一次串行重试。
     *
     * 返回 `false` 时保留外部代次；调用方不得安装会话。每次失败重试都会清除其等待器，以便后续实际 token
     * 生命周期事件可以再次尝试恢复。普通 token 轮换成功后仍保留待处理状态，直到调用方在 token 生命周期锁内
     * 安装当前会话；认证失败成功后则立即结束已失效会话对应的屏障。服务关闭时会结束所有等待器并释放代次。
     */
    private suspend fun awaitPendingAgentResetBeforeSession(pendingReset: PendingAgentReset): Boolean {
        if (pendingReset.initialResetCompletion.await()) {
            return pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE || isPendingAgentResetCurrent(
                pendingReset
            )
        }

        val retry = sessionLock.withLock {
            if (closed || pendingAgentReset !== pendingReset) {
                return !closed
            }
            pendingReset.retryCompletion?.let { existing -> AuthenticationRetry(existing, isOwner = false) }
                ?: CompletableDeferred<Boolean>().let { completion ->
                    pendingReset.retryCompletion = completion
                    AuthenticationRetry(completion, isOwner = true)
                }
        }
        if (!retry.isOwner) {
            return retry.completion.await()
        }

        val resetSucceeded = performAgentReset()
        val shouldReleaseBarrier = sessionLock.withLock {
            when {
                pendingAgentReset !== pendingReset -> false
                !resetSucceeded -> {
                    pendingReset.retryCompletion = null
                    false
                }

                pendingReset.source != AgentResetSource.AUTHENTICATION_FAILURE -> false
                else -> {
                    pendingAgentReset = null
                    true
                }
            }
        }
        val retrySucceeded = resetSucceeded && (
                pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE || isPendingAgentResetCurrent(
                    pendingReset
                )
                )
        retry.completion.complete(retrySucceeded)
        if (shouldReleaseBarrier) {
            modelSwitchBarrier.complete(pendingReset.barrierGeneration)
        } else if (!retrySucceeded) {
            logger.warn("Pending Agent reset retry failed; polling session remains blocked.")
        }
        return retry.completion.await()
    }

    /**
     * 完成已登记的首次 Agent 重置，并按其来源决定屏障释放时机。
     *
     * 普通 token 轮换成功后仍必须等待新会话原子安装；认证失败没有可安装的当前会话，成功清除上下文后即可
     * 释放其外部代次。失败、取消和同步异常均完成等待器为 `false` 并保留状态。
     */
    private suspend fun completeInitialAgentReset(pendingReset: PendingAgentReset): Boolean {
        val resetSucceeded = performAgentReset()
        val shouldReleaseBarrier = sessionLock.withLock {
            if (pendingAgentReset !== pendingReset) {
                false
            } else {
                pendingReset.initialResetCompletion.complete(resetSucceeded)
                if (resetSucceeded && pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE) {
                    pendingAgentReset = null
                    true
                } else {
                    false
                }
            }
        }
        if (shouldReleaseBarrier) {
            modelSwitchBarrier.complete(pendingReset.barrierGeneration)
        }
        return resetSucceeded && (
                pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE || isPendingAgentResetCurrent(
                    pendingReset
                )
                )
    }

    /** 在外部屏障已关闭时执行一次 Agent 重置，并把所有失败语义转换为可重试的 `false`。 */
    private suspend fun performAgentReset(): Boolean = withContext(NonCancellable) {
        try {
            modelSwitchBarrier.awaitInFlightRequests()
            awaitSuccessfulAgentReset()
        } catch (e: CancellationException) {
            logger.warn(
                "Agent session reset was cancelled; keeping polling fail-closed; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            false
        } catch (e: Exception) {
            logger.warn(
                "Failed to reset agent session; keeping polling fail-closed; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            false
        }
    }

    /** 判断待处理 Agent 重置仍归当前未关闭服务所有。 */
    private fun isPendingAgentResetCurrent(pendingReset: PendingAgentReset): Boolean = sessionLock.withLock {
        !closed && pendingAgentReset === pendingReset
    }

    /**
     * 当前 token 不可建立轮询会话时结束已成功的普通轮换重置。
     *
     * 空或非法 token 本身不会让新 Bot 接收工作；此处只在它仍是当前代次时释放已经完成的上下文清除屏障，
     * 避免永久阻塞应用中与 Telegram 无关的 Agent 请求。
     */
    private fun completePendingResetForInvalidToken(
        pendingReset: PendingAgentReset,
        token: String,
        generation: Long,
    ) {
        val shouldReleaseBarrier = settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (
                    !closed &&
                    currentSession == null &&
                    pendingAgentReset === pendingReset &&
                    isTokenGenerationCurrent(token, generation)
                ) {
                    pendingAgentReset = null
                    true
                } else {
                    false
                }
            }
        }
        if (shouldReleaseBarrier) {
            modelSwitchBarrier.complete(pendingReset.barrierGeneration)
        }
    }

    /** 待处理 Agent 重置的串行重试等待器。 */
    private data class AuthenticationRetry(
        val completion: CompletableDeferred<Boolean>,
        val isOwner: Boolean,
    )

    private suspend fun consumeQueue(session: PollingSession) {
        while (currentCoroutineContext().isActive) {
            val queuedUpdate = session.updateChannel.receiveCatching().getOrNull() ?: return
            val deadline = queuedUpdate.entryTime + processingTimeout.inWholeMilliseconds
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            val completion = try {
                withTimeout(remaining.milliseconds) {
                    processUpdate(session, queuedUpdate.update)
                }
            } catch (_: TimeoutCancellationException) {
                handleProcessingTimeout(session, queuedUpdate.update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Error processing update {}; category={}",
                    queuedUpdate.update.updateId,
                    SafeLogging.failureCategory(e).wireName,
                )
                UpdateCompletion.Retry
            }

            currentCoroutineContext().ensureActive()
            if (isCurrent(session)) {
                queuedUpdate.completion.complete(completion)
                if (completion == UpdateCompletion.Retry) {
                    // 本批更高 update 不得越过失败回合写入 offset；全部退回下次从失败 update 开始的轮询。
                    drainQueuedUpdatesAsRetry(session)
                    session.consumerResume.receiveCatching().getOrNull() ?: return
                }
            }
        }
    }

    /** 把当前消费者队列中尚未执行的更新标为 Retry，保留它们的 offset 供下一次轮询重新接纳。 */
    private fun drainQueuedUpdatesAsRetry(session: PollingSession) {
        while (true) {
            val queued = session.updateChannel.tryReceive().getOrNull() ?: return
            queued.completion.complete(UpdateCompletion.Retry)
        }
    }

    /** 单次 outbox 投递结果；失败只会暂停该 worker，不会阻塞 Agent 消费者。 */
    private enum class OutboxDelivery {
        DELIVERED,
        EMPTY,
        RETRY,
    }

    /**
     * 按更新标识顺序投递当前 bot 已持久化确认的 outbox。
     *
     * 发送网络请求时不持有 token 生命周期锁或会话锁；只有 Telegram 同时返回 HTTP `2xx` 与 `ok: true`
     * 后，才在两个锁的短临界区内确认会话仍是当前代次并删除对应记录。旧 token 的迟到成功因此不会
     * 删除记录，替换后的同 bot 会话会重新投递。失败采用可取消的一秒等待，避免热循环。
     */
    private suspend fun consumeOutbox(session: PollingSession) {
        while (currentCoroutineContext().isActive) {
            when (deliverNextPendingReply(session)) {
                OutboxDelivery.DELIVERED -> Unit
                OutboxDelivery.EMPTY -> {
                    if (session.outboxSignal.receiveCatching().getOrNull() == null) {
                        return
                    }
                }

                OutboxDelivery.RETRY -> {
                    withTimeoutOrNull(1.seconds) {
                        session.outboxSignal.receiveCatching().getOrNull()
                    }
                }
            }
        }
    }

    /**
     * 读取并尝试投递一项当前会话可见的 outbox 记录。
     *
     * 每次网络请求前先在当前 token 代次保护下持久化投递次数。原文收到第二次明确的永久 `4xx` 拒绝后，
     * 原子改为不带回复参数的固定回退消息；回退消息最多登记三次投递，耗尽后删除并继续下一项。网络异常、
     * `429`、其他 HTTP 状态和无效响应正文均保留记录以便重试。
     */
    private suspend fun deliverNextPendingReply(session: PollingSession): OutboxDelivery {
        val pendingReply = try {
            readForCurrent(session) { updatesRepository.getPendingTelegramReplies(session.botId).firstOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to read Telegram outbox for bot {}; retrying later; category={}",
                session.botId,
                SafeLogging.failureCategory(e).wireName,
            )
            return OutboxDelivery.RETRY
        } ?: return if (isCurrent(session)) OutboxDelivery.EMPTY else OutboxDelivery.RETRY

        var replyToSend: PendingTelegramReply? = null
        val deliveryPrepared = try {
            saveForCurrent(session) {
                replyToSend =
                    updatesRepository.preparePendingTelegramReplyDelivery(session.botId, pendingReply.updateId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to persist Telegram outbox delivery attempt for update {}; not sending it; category={}",
                pendingReply.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return OutboxDelivery.RETRY
        }
        if (!deliveryPrepared) {
            return OutboxDelivery.RETRY
        }
        val reply = replyToSend ?: return OutboxDelivery.DELIVERED

        val response = try {
            ensureCurrent(session)
            telegramService.sendMessageForToken(session.token, reply.chatId, reply.text, reply.replyParameters)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Telegram outbox send failed for update {} of bot {}; retrying later; category={}",
                reply.updateId,
                session.botId,
                SafeLogging.failureCategory(e).wireName,
            )
            null
        }
        if (response?.isTelegramAccepted() != true) {
            if (reply.deliveryStage == TelegramReplyDeliveryStage.FALLBACK &&
                reply.deliveryAttempts >= MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS
            ) {
                val exhaustedRemoved = try {
                    saveForCurrent(session) {
                        updatesRepository.deletePendingTelegramReply(session.botId, reply.updateId)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Failed to discard exhausted Telegram fallback reply {}; retaining it; category={}",
                        reply.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    false
                }
                if (exhaustedRemoved) {
                    logger.warn(
                        "Telegram fallback reply {} of bot {} was rejected three times; skipping it.",
                        reply.updateId,
                        session.botId,
                    )
                    return OutboxDelivery.DELIVERED
                }
                return OutboxDelivery.RETRY
            }
            if (reply.deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
                val replacement = if (response?.isPermanentTelegramRejection() == true) {
                    reply.afterPermanentTelegramRejection()
                } else {
                    reply.afterRetryableTelegramFailure()
                }
                if (replacement == reply) {
                    logger.warn(
                        "Telegram did not accept outbox reply for update {} of bot {}; retrying later.",
                        reply.updateId,
                        session.botId,
                    )
                    return OutboxDelivery.RETRY
                }
                var replaced = false
                val replacementPersisted = try {
                    saveForCurrent(session) {
                        replaced = updatesRepository.replacePendingTelegramReply(session.botId, reply, replacement)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Failed to persist Telegram outbox delivery state for reply {}; retrying later; category={}",
                        reply.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    false
                }
                if (!replacementPersisted || !replaced) {
                    return OutboxDelivery.RETRY
                }
                if (replacement.deliveryStage == TelegramReplyDeliveryStage.FALLBACK) {
                    logger.warn(
                        "Telegram permanently rejected outbox reply {} of bot {} twice; sending fallback next.",
                        reply.updateId,
                        session.botId,
                    )
                } else if (replacement.permanentRejectionCount == 0) {
                    logger.warn(
                        "Telegram retryable failure reset permanent rejection count for outbox reply {} of bot {}.",
                        reply.updateId,
                        session.botId,
                    )
                } else {
                    logger.warn(
                        "Telegram permanently rejected outbox reply {} of bot {}; retaining original for one final retry.",
                        reply.updateId,
                        session.botId,
                    )
                }
            } else {
                logger.warn(
                    "Telegram did not accept fallback outbox reply for update {} of bot {}; retrying later.",
                    reply.updateId,
                    session.botId,
                )
            }
            return OutboxDelivery.RETRY
        }

        val removed = try {
            saveForCurrent(session) {
                updatesRepository.deletePendingTelegramReply(session.botId, reply.updateId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to acknowledge delivered Telegram outbox reply {}; retaining it; category={}",
                reply.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            false
        }
        return if (removed) OutboxDelivery.DELIVERED else OutboxDelivery.RETRY
    }

    private suspend fun processUpdate(
        session: PollingSession,
        update: Update,
    ): UpdateCompletion {
        val message = update.message ?: return UpdateCompletion.Confirmed
        val chatId = message.chat.id.toString()
        return when {
            message.text?.startsWith("/") == true -> {
                handleCommand(session, chatId, message.text, message.messageId)
                UpdateCompletion.Confirmed
            }

            message.voice != null -> completeVoiceAgentUpdate(session, update.updateId, chatId, message)
            message.text != null -> completeTextAgentUpdate(session, update.updateId, chatId, message)
            else -> UpdateCompletion.Confirmed
        }
    }

    /**
     * 处理消费者超时。
     *
     * 已进入生产 Agent 状态机的文本或语音更新只会把已有 IN_PROGRESS 原子降级为固定 FINAL 并提交 outbox；
     * 绝不绕过账本即时发送第二条超时消息。未完成 claim 的更新保留偏移量重试，非 Agent 或测试专用更新则
     * 保留既有的即时超时提示。
     */
    private suspend fun handleProcessingTimeout(
        session: PollingSession,
        update: Update,
    ): UpdateCompletion {
        val message = update.message ?: return UpdateCompletion.Retry
        if (
            !message.text.orEmpty().startsWith("/") &&
            (message.text != null || message.voice != null)
        ) {
            return finalizeTimedOutDurableAgentTurn(session, update.updateId)
        }
        logger.warn("Non-durable update {} processing timed out.", update.updateId)
        try {
            sendMessageForSession(
                session,
                message.chat.id.toString(),
                "抱歉，该消息处理超时（超过10分钟）。",
                ReplyParameters(messageId = message.messageId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to send timeout notification; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
        }
        return UpdateCompletion.Retry
    }

    /** 将已安全 claim 但超时的生产回合改为固定失败 FINAL；未 claim 时仅保留偏移量。 */
    private suspend fun finalizeTimedOutDurableAgentTurn(session: PollingSession, updateId: Long): UpdateCompletion =
        try {
            val entry = withContext(NonCancellable) {
                updatesRepository.getData(session.botId).agentTurnJournal.singleOrNull { it.updateId == updateId }
            } ?: return UpdateCompletion.Retry
            val final = when (entry.status) {
                AgentTurnJournalStatus.FINAL -> entry
                AgentTurnJournalStatus.IN_PROGRESS ->
                    withContext(NonCancellable) {
                        updatesRepository.failInProgressAgentTurn(session.botId, updateId, AGENT_TURN_FAILURE_REPLY)
                    } ?: return UpdateCompletion.Retry
            }
            completeFinalAgentTurn(session, final)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Timed out Agent turn {} could not finalize safely; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }

    /**
     * 将一条更新加入当前活跃会话的处理队列。
     *
     * 仅当 AI 功能可用、消息来自 `private` 聊天，且聊天标识和 Telegram `from` 发送者标识均与
     * 当前 AI 设置的 `agentChatId` 一致时，更新才会入队。AI 接纳判定会等待共享模型切换屏障放行，
     * 并在放行后重新读取设置；这样设置已发布但代理尚未替换时不会确认偏移量。未授权、未启用或缺少
     * 当前提供商密钥的更新会被确认，但不会触发命令、语音下载、AI 调用或 Telegram 回复。代理在
     * 稳定设置下仍不可用或检查失败时会保留更新供下一次轮询重试。当前没有有效会话时同样不会产生副作用。
     * 队列满时会在屏障外使用该会话捕获的 token 回复失败提示；只有 Telegram 返回 HTTP `2xx` 且 API
     * `ok` 为 `true` 时才确认该更新，否则由下一次轮询重试。
     *
     * @param update 要检查的 Telegram 更新；不含可处理消息时不会入队。
     */
    suspend fun handleUpdate(update: Update) {
        activeSession()?.let { enqueueUpdate(it, update) }
    }

    /**
     * 仅供测试把更新放入当前队列。
     *
     * 此方法与生产入口使用完全相同的 durable Agent 协议；不能用它绕过 journal 或 outbox。当前没有有效
     * 会话时不产生副作用。
     *
     * @param update 要加入测试队列的 Telegram 更新；不含可处理消息时不会入队。
     */
    internal suspend fun enqueueUpdateForTesting(update: Update) {
        activeSession()?.let { enqueueUpdate(it, update) }
    }

    private suspend fun enqueueUpdate(
        session: PollingSession,
        update: Update,
    ): UpdateAdmission {
        if (!isCurrent(session)) {
            return UpdateAdmission.Confirmed
        }
        val message = update.message ?: return UpdateAdmission.Confirmed
        if (message.text == null && message.voice == null) {
            return UpdateAdmission.Confirmed
        }
        val chatId = message.chat.id.toString()
        val existingTurn = try {
            updatesRepository.findAgentTurn(session.botId, update.updateId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Unable to read durable Agent journal for update {}; preserving its offset; category={}",
                update.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateAdmission.Retry
        }
        if (existingTurn != null) {
            val completion = CompletableDeferred<UpdateCompletion>()
            return when (
                offerUpdateForCurrent(
                    session,
                    QueuedUpdate(update, System.currentTimeMillis(), completion),
                )
            ) {
                QueueOfferResult.ENQUEUED -> UpdateAdmission.Enqueued(completion)
                // 账本回放不能以普通“队满”提示替代；必须保留 offset 等待其可安全提交。
                QueueOfferResult.FULL -> UpdateAdmission.Retry
                QueueOfferResult.NOT_CURRENT -> UpdateAdmission.Confirmed
            }
        }
        val admission = modelSwitchBarrier.runWhenReady {
            if (!isCurrent(session)) {
                return@runWhenReady BarrierAdmission.Confirmed
            }

            val aiSettings = settingsRepository.currentSettingsSnapshot().settings.ai
                ?: return@runWhenReady BarrierAdmission.Confirmed
            if (
                !aiSettings.agentEnabled ||
                aiSettings.requiredApiKey().isBlank() ||
                message.chat.type != "private" ||
                chatId != aiSettings.agentChatId ||
                message.from?.id?.toString() != aiSettings.agentChatId
            ) {
                return@runWhenReady BarrierAdmission.Confirmed
            }

            val aiAvailable = try {
                agentService.isAiFeatureEnabled(aiSettings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "AI availability check failed for update {}; preserving its offset for retry; category={}",
                    update.updateId,
                    SafeLogging.failureCategory(e).wireName,
                )
                return@runWhenReady BarrierAdmission.Retry
            }
            if (!aiAvailable) {
                logger.warn(
                    "AI remains unavailable after the model switch barrier for update {}; preserving its offset for retry.",
                    update.updateId,
                )
                return@runWhenReady BarrierAdmission.Retry
            }

            val completion = CompletableDeferred<UpdateCompletion>()
            when (
                offerUpdateForCurrent(
                    session,
                    QueuedUpdate(update, System.currentTimeMillis(), completion),
                )
            ) {
                QueueOfferResult.ENQUEUED -> BarrierAdmission.Enqueued(completion)
                QueueOfferResult.FULL -> BarrierAdmission.QueueFull(chatId, message.messageId)
                QueueOfferResult.NOT_CURRENT -> BarrierAdmission.Confirmed
            }
        }
        return when (admission) {
            BarrierAdmission.Confirmed -> UpdateAdmission.Confirmed
            BarrierAdmission.Retry -> UpdateAdmission.Retry
            is BarrierAdmission.Enqueued -> UpdateAdmission.Enqueued(admission.completion)
            is BarrierAdmission.QueueFull -> notifyQueueFull(
                session,
                update.updateId,
                admission.chatId,
                admission.messageId
            )
        }
    }

    /**
     * 完成一条文本 Agent 回合，并在偏移量推进前持久化回复或空回复完成事实。
     *
     * 生成的正常回复不会在消费者内直接发送，而是先写入按 bot 隔离的 outbox。这样 Telegram 投递失败
     * 或进程重启后只会重投该回复，不会再次调用 Agent。
     */
    private suspend fun completeTextAgentUpdate(
        session: PollingSession,
        updateId: Long,
        chatId: String,
        message: Message,
    ): UpdateCompletion {
        val text = checkNotNull(message.text)
        if (updatesRepository.findAgentTurn(session.botId, updateId) != null) {
            return reconcileExistingDurableAgentTurn(
                session,
                updateId,
                chatId,
                ReplyParameters(messageId = message.messageId)
            )
        }
        if (!isWithinAgentTextLimit(text)) {
            logger.warn("Text input for update {} exceeds the local pre-claim limit.", updateId)
            return UpdateCompletion.Retry
        }
        return try {
            cleanContextIfNeeded(session, chatId)
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    runDurableAgentTurn(
                        session = session,
                        updateId = updateId,
                        chatId = chatId,
                        replyParameters = ReplyParameters(messageId = message.messageId),
                    ) {
                        agentService.sendMessage(text)
                    }
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Text update {} could not reach durable Agent claim; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    /** 完成一条语音 Agent 回合，并把成功生成的回复原子写入 outbox 与偏移量。 */
    private suspend fun completeVoiceAgentUpdate(
        session: PollingSession,
        updateId: Long,
        chatId: String,
        message: Message,
    ): UpdateCompletion {
        val voice = checkNotNull(message.voice)
        if (updatesRepository.findAgentTurn(session.botId, updateId) != null) {
            // 已有 FINAL 或失联 IN_PROGRESS 不依赖可能已过期的 Telegram 文件；直接按账本完成或降级。
            return reconcileExistingDurableAgentTurn(
                session,
                updateId,
                chatId,
                ReplyParameters(messageId = message.messageId)
            )
        }
        if (!isWithinAgentTextLimit(message.caption)) {
            logger.warn("Voice caption for update {} exceeds the local pre-claim limit.", updateId)
            return UpdateCompletion.Retry
        }
        val audioData = try {
            // 下载和本地输入校验必须在 claim 前完成：文件不可用时既不会进入 Agent，也不会留下一个
            // 会阻止用户重传的进行中账本记录。
            val filePath = telegramService.getFileForToken(session.token, voice.fileId).result?.filePath
                ?: throw IllegalStateException("Failed to get file path for voice message")
            ensureCurrent(session)
            telegramService.downloadFileForToken(session.token, filePath)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Voice input for update {} was unavailable before Agent claim; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateCompletion.Retry
        }
        if (audioData.isEmpty()) {
            logger.warn("Voice input for update {} was empty before Agent claim.", updateId)
            return UpdateCompletion.Retry
        }
        return try {
            cleanContextIfNeeded(session, chatId)
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    runDurableAgentTurn(
                        session = session,
                        updateId = updateId,
                        chatId = chatId,
                        replyParameters = ReplyParameters(messageId = message.messageId),
                    ) {
                        agentService.sendMessage(
                            message.caption,
                            listOf(MediaData(audioData, voice.mimeType ?: "audio/ogg")),
                        )
                    }
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Voice update {} could not reach durable Agent claim; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    /**
     * 执行生产 Agent 回合的不可重放持久状态机。
     *
     * [send] 仅在成功落盘的新 IN_PROGRESS claim 后调用一次。任何没有本地 owner 的进行中记录均被降级为
     * 固定失败 FINAL；因此重启、会话轮换、模型异常和 FINAL 写入失败都不会自动重放模型或工具副作用。
     */
    private suspend fun runDurableAgentTurn(
        session: PollingSession,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters,
        send: suspend () -> String,
    ): UpdateCompletion {
        val key = AgentTurnKey(session.botId, updateId)
        val owner = acquireAgentTurnOwner(key) ?: return UpdateCompletion.Retry
        try {
            val claim = withContext(NonCancellable) {
                updatesRepository.claimAgentTurn(session.botId, updateId, chatId, replyParameters)
            }
            return when (claim) {
                AgentTurnClaim.CLAIMED -> {
                    val finalized = try {
                        val reply = send().takeIf { it.isNotBlank() }
                        withContext(NonCancellable) {
                            updatesRepository.finalizeAgentTurn(session.botId, updateId, reply)
                        }
                    } catch (e: CancellationException) {
                        // 取消时结果不确定；留下 IN_PROGRESS 使下一次无 owner 重投安全降级，而不是猜测重放。
                        throw e
                    } catch (e: Exception) {
                        logger.warn(
                            "Agent turn {} failed after durable claim; category={}",
                            updateId,
                            SafeLogging.failureCategory(e).wireName,
                        )
                        withContext(NonCancellable) {
                            updatesRepository.failInProgressAgentTurn(session.botId, updateId, AGENT_TURN_FAILURE_REPLY)
                        }
                    }
                    finalized?.let { completeFinalAgentTurn(session, it) } ?: UpdateCompletion.Retry
                }

                is AgentTurnClaim.FINAL -> completeFinalAgentTurn(session, claim.entry)
                is AgentTurnClaim.InProgress -> {
                    val failed = withContext(NonCancellable) {
                        updatesRepository.failInProgressAgentTurn(session.botId, updateId, AGENT_TURN_FAILURE_REPLY)
                    }
                    failed?.let { completeFinalAgentTurn(session, it) } ?: UpdateCompletion.Retry
                }

                AgentTurnClaim.AlreadyConfirmed -> UpdateCompletion.Confirmed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Durable Agent journal operation failed for update {}; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateCompletion.Retry
        } finally {
            releaseAgentTurnOwner(key, owner)
        }
    }

    /** 调和已有 durable 回合；传入的 lambda 是防御断言，既有账本绝不能再次进入 Agent。 */
    private suspend fun reconcileExistingDurableAgentTurn(
        session: PollingSession,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters,
    ): UpdateCompletion = runDurableAgentTurn(session, updateId, chatId, replyParameters) {
        error("Existing durable Agent turn must not invoke Agent.")
    }

    /** 将已持久化 FINAL 结果写入 outbox 和更新偏移量，成功后尽力清理账本残留。 */
    private suspend fun completeFinalAgentTurn(
        session: PollingSession,
        entry: AgentTurnJournalEntry,
    ): UpdateCompletion {
        val reply = entry.reply?.let {
            PendingTelegramReply(entry.updateId, entry.chatId, it, entry.replyParameters)
        }
        return try {
            // FINAL 已经持久化。此提交失败时保留 FINAL，下次只会重试此处，绝不会返回 Agent。
            withContext(NonCancellable) {
                updatesRepository.completeAgentUpdate(session.botId, entry.updateId, reply)
            }
            if (reply != null) {
                signalOutboxForBot(session.botId)
                if (isCurrent(session)) {
                    session.lastAiReplyAtMillis = System.currentTimeMillis()
                }
            }
            try {
                withContext(NonCancellable) {
                    updatesRepository.cleanupConfirmedAgentTurns(session.botId)
                }
            } catch (e: Exception) {
                logger.warn(
                    "Confirmed Agent journal cleanup deferred for update {}; category={}",
                    entry.updateId,
                    SafeLogging.failureCategory(e).wireName,
                )
            }
            UpdateCompletion.Persisted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "FINAL Agent turn {} could not commit offset/outbox; category={}",
                entry.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    /** 原子登记本进程 owner；已有未完成 owner 时消费者必须等待下一次重投，不能抢占为 stale。 */
    private fun acquireAgentTurnOwner(key: AgentTurnKey): CompletableDeferred<Unit>? = sessionLock.withLock {
        agentTurnOwners[key]?.takeIf { !it.isCompleted }?.let { return@withLock null }
        CompletableDeferred<Unit>().also { agentTurnOwners[key] = it }
    }

    /** 结束本地 owner，并仅移除仍属于当前 owner 的映射，避免迟到 finally 清除新 owner。 */
    private fun releaseAgentTurnOwner(key: AgentTurnKey, owner: CompletableDeferred<Unit>) {
        sessionLock.withLock {
            owner.complete(Unit)
            if (agentTurnOwners[key] === owner) {
                agentTurnOwners.remove(key)
            }
        }
    }

    /** 检查所有提供商共用的文本输入字节上限；`null` 代表没有文本片段。 */
    private fun isWithinAgentTextLimit(text: String?): Boolean =
        (text ?: "").toByteArray(StandardCharsets.UTF_8).size <= MAX_AGENT_TEXT_BYTES

    /** 唤醒当前持有同一 bot 标识的 outbox worker；不同 bot 的轮换绝不共享投递信号。 */
    private fun signalOutboxForBot(botId: String) {
        sessionLock.withLock {
            currentSession
                ?.takeIf { !closed && it.botId == botId }
                ?.outboxSignal
                ?.trySend(Unit)
        }
    }

    /** 在屏障外发送队满提示；未被 Telegram 接受时保留该更新的偏移量。 */
    private suspend fun notifyQueueFull(
        session: PollingSession,
        updateId: Long,
        chatId: String,
        messageId: Long,
    ): UpdateAdmission {
        logger.warn("Update {} rejected: queue is full.", updateId)
        val notificationAccepted = try {
            sendMessageForSession(
                session,
                chatId,
                "抱歉，当前处理队列已满（最多同时排队10条消息），请稍后再试。",
                ReplyParameters(messageId = messageId),
            ).isTelegramAccepted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Queue full notification request failed for update {}; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            false
        }
        if (notificationAccepted) {
            logger.info("Queue full notification accepted for update {}; confirming update.", updateId)
            return UpdateAdmission.Confirmed
        }
        logger.warn("Queue full notification was not accepted for update {}; preserving offset for retry.", updateId)
        return UpdateAdmission.Retry
    }

    /**
     * 将更新提交给仍属于当前 token 生命周期的队列。
     *
     * 此短临界区把 token 代次、会话身份和非阻塞 [Channel.trySend] 线性化；切换后的已关闭队列不能被
     * 误判为队满，从而不会对旧 bot 发送队满提示。
     */
    private fun offerUpdateForCurrent(session: PollingSession, queuedUpdate: QueuedUpdate): QueueOfferResult =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (closed || currentSession !== session || !isTokenGenerationCurrent(session)) {
                    QueueOfferResult.NOT_CURRENT
                } else {
                    val result = session.updateChannel.trySend(queuedUpdate)
                    when {
                        result.isSuccess -> QueueOfferResult.ENQUEUED
                        result.isClosed -> QueueOfferResult.NOT_CURRENT
                        else -> QueueOfferResult.FULL
                    }
                }
            }
        }

    /** 返回 [AISettings] 当前提供商启用 AI 所必需的 API 密钥。 */
    private fun AISettings.requiredApiKey(): String = when (provider) {
        AIProvider.GEMINI -> geminiApiKey
        AIProvider.OPENAI -> openAiApiKey
    }

    private fun confirmedSignal(): CompletableDeferred<UpdateCompletion> =
        CompletableDeferred<UpdateCompletion>().also { it.complete(UpdateCompletion.Confirmed) }

    /** 判断 Telegram 响应是否同时具有成功 HTTP 状态和 API `ok: true` 标记。 */
    private fun TelegramApiResponse.isTelegramAccepted(): Boolean {
        return status.isSuccess() && try {
            Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }
    }

    /** 返回该响应是否明确表明 Telegram 以非限流的永久 `4xx` 拒绝了请求。 */
    private fun TelegramApiResponse.isPermanentTelegramRejection(): Boolean {
        return status.value in 400..499 && status.value != 429 || try {
            Json.parseToJsonElement(body)
                .jsonObject["error_code"]
                ?.jsonPrimitive
                ?.intOrNull
                ?.let { it in 400..499 && it != 429 }
                ?: false
        } catch (_: Exception) {
            false
        }
    }

    /** 基于一项已登记投递的原文回复，记录一次永久拒绝或切换到固定回退消息。 */
    private fun PendingTelegramReply.afterPermanentTelegramRejection(): PendingTelegramReply {
        check(deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
            "only original replies can record permanent rejections."
        }
        val rejectionCount = permanentRejectionCount + 1
        return if (rejectionCount < 2) {
            copy(permanentRejectionCount = rejectionCount)
        } else {
            copy(
                text = TELEGRAM_REPLY_FALLBACK_MESSAGE,
                replyParameters = null,
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
                deliveryAttempts = 0,
                permanentRejectionCount = 0,
            )
        }
    }

    /** 基于一项已登记投递的原文回复，在可重试失败后清除先前的永久拒绝连续计数。 */
    private fun PendingTelegramReply.afterRetryableTelegramFailure(): PendingTelegramReply {
        check(deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
            "only original replies can reset permanent rejections."
        }
        return if (permanentRejectionCount == 0) this else copy(permanentRejectionCount = 0)
    }

    /**
     * 使用当前活跃会话处理 AI 聊天命令。
     *
     * 当前没有有效会话时不会产生副作用。`/reset` 仅在代理重置任务正常完成且会话仍有效时，才清空当前
     * 会话的队列并清除自动清理计时。重置失败时会保留这些状态并发送失败提示，不会影响其他机器人的队列。
     *
     * @param chatId 发送命令的聊天标识，不能为空。
     * @param text 完整命令文本；首个空白分隔字段作为命令。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
    suspend fun handleCommand(chatId: String, text: String, messageId: Long) {
        activeSession()?.let { handleCommand(it, chatId, text, messageId) }
    }

    private suspend fun handleCommand(session: PollingSession, chatId: String, text: String, messageId: Long) {
        val parts = text.split(Regex("\\s+"), 2)
        when (parts[0]) {
            "/keep" -> {
                if (isCurrent(session)) {
                    session.lastAiReplyAtMillis = System.currentTimeMillis()
                    logger.info("Auto-clean context timer refreshed by keep command for bot {}", session.botId)
                }
            }

            "/reset" -> {
                ensureCurrent(session)
                if (!awaitSuccessfulAgentReset()) {
                    ensureCurrent(session)
                    sendMessageForSession(session, chatId, "会话重置失败，请稍后重试。", ReplyParameters(messageId))
                    logger.warn("Session reset failed by command for bot {}", session.botId)
                    return
                }
                ensureCurrent(session)
                clearQueue(session)
                ensureCurrent(session)
                session.lastAiReplyAtMillis = null
                sendMessageForSession(session, chatId, "会话已重置，待处理消息已清空。", ReplyParameters(messageId))
                logger.info("Session reset and queue cleared by command for bot {}", session.botId)
            }

            "/model" -> {
                if (parts.size > 1) {
                    val requestedModel = parts[1].trim()
                    try {
                        val settingsBeforeSelection = settingsRepository.currentSettingsSnapshot().settings
                        val selectedModel = agentService.availableModels.firstOrNull { model ->
                            model == requestedModel ||
                                    model.removePrefix("models/") == requestedModel.removePrefix("models/")
                        } ?: throw IllegalArgumentException("Unsupported model: $requestedModel")
                        if (!persistSelectedModel(selectedModel, settingsBeforeSelection)) {
                            throw IllegalStateException("AI configuration changed while selecting a model")
                        }
                        if (isCurrent(session)) {
                            session.lastAiReplyAtMillis = null
                        }
                        sendMessageForSession(
                            session,
                            chatId,
                            "已保存模型选择，正在切换模型并重置会话：$selectedModel",
                            ReplyParameters(messageId),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        sendMessageForSession(
                            session,
                            chatId,
                            "不支持的模型：$requestedModel\n使用 /model 查看可用列表。",
                            ReplyParameters(messageId),
                        )
                    }
                } else {
                    val modelSnapshot = agentService.updateModel()
                    if (modelSnapshot == null) {
                        sendMessageForSession(
                            session,
                            chatId,
                            "获取可用模型列表失败，请稍后重试。",
                            ReplyParameters(messageId)
                        )
                        return
                    }
                    val list = modelSnapshot.availableModels.joinToString("\n") { model ->
                        if (model == modelSnapshot.currentModel) "✅ $model" else "      $model"
                    }
                    sendMessageForSession(
                        session,
                        chatId,
                        "当前可用模型列表：\n$list\n\n使用 `/model <模型名称>` 切换模型。",
                        ReplyParameters(messageId),
                    )
                }
            }
        }
    }

    private fun persistSelectedModel(selectedModel: String, expectedSettings: AppSettings): Boolean {
        var persisted = false
        settingsRepository.updateSettings { settings ->
            val aiSettings = settings.ai ?: return@updateSettings settings
            if (!settings.hasSameModelServiceConfiguration(expectedSettings)) {
                settings
            } else {
                persisted = true
                settings.copy(ai = aiSettings.copy(selectedModel = selectedModel))
            }
        }
        return persisted
    }

    private fun AppSettings.hasSameModelServiceConfiguration(expected: AppSettings): Boolean {
        val currentAi = ai ?: return false
        val expectedAi = expected.ai ?: return false
        return currentAi.provider == expectedAi.provider &&
                currentAi.agentEnabled == expectedAi.agentEnabled &&
                currentAi.selectedModel == expectedAi.selectedModel &&
                proxy == expected.proxy &&
                currentAi.httpToolSettings == expectedAi.httpToolSettings &&
                currentAi.mcpServers == expectedAi.mcpServers && when (currentAi.provider) {
            AIProvider.GEMINI -> currentAi.geminiApiKey == expectedAi.geminiApiKey
            AIProvider.OPENAI ->
                currentAi.openAiApiKey == expectedAi.openAiApiKey &&
                        currentAi.openAiBaseUrl == expectedAi.openAiBaseUrl
        }
    }

    private fun typingJob(session: PollingSession, chatId: String): Job = session.scope.launch {
        while (isActive) {
            delay(4000.milliseconds)
            try {
                sendChatActionForSession(session, chatId, "typing")
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                logger.warn(
                    "Failed to send typing action; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
            }
        }
    }

    private suspend fun cleanContextIfNeeded(session: PollingSession, chatId: String) {
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return
        val intervalMinutes = aiSettings.autoCleanContextIntervalMinutes
        val lastReplyAt = session.lastAiReplyAtMillis ?: return
        if (intervalMinutes <= 0 || System.currentTimeMillis() - lastReplyAt < intervalMinutes.minutes.inWholeMilliseconds) {
            return
        }
        ensureCurrent(session)
        if (!awaitSuccessfulAgentReset()) {
            ensureCurrent(session)
            logger.warn(
                "Failed to auto-clean AI context after {} minutes without a successful AI reply.",
                intervalMinutes
            )
            return
        }
        ensureCurrent(session)
        session.lastAiReplyAtMillis = null
        if (!aiSettings.silentContextCleanup) {
            sendMessageForSession(
                session,
                chatId,
                "检测到距离上次对话已超过 $intervalMinutes 分钟，已自动清理上下文。",
            )
        }
        logger.info("Auto-cleaned AI context after {} minutes without a successful AI reply.", intervalMinutes)
    }

    /**
     * 等待代理重置结束，并以 [Job.isCancelled] 判定成功。
     *
     * [Job.join] 不会传播任务自身的失败；当前消费者或轮询会话被取消时仍会原样传播其取消，避免旧会话在
     * token 切换后继续执行。代理返回 `null`、抛出普通异常或返回已取消任务都会返回 `false`。
     */
    private suspend fun awaitSuccessfulAgentReset(): Boolean {
        val resetJob = try {
            agentService.resetSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to start agent session reset; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            return false
        } ?: return false

        resetJob.join()
        return !resetJob.isCancelled
    }

    private fun clearQueue(session: PollingSession) {
        var count = 0
        while (true) {
            val queuedUpdate = session.updateChannel.tryReceive().getOrNull() ?: break
            queuedUpdate.completion.complete(UpdateCompletion.Confirmed)
            count++
        }
        if (count > 0) {
            logger.info("Cleared {} pending updates from current polling session due to reset.", count)
        }
    }

    private suspend fun sendMessageForSession(
        session: PollingSession,
        chatId: String,
        text: String,
        replyParameters: ReplyParameters? = null,
    ): TelegramApiResponse {
        ensureCurrent(session)
        return telegramService.sendMessageForToken(session.token, chatId, text, replyParameters)
    }

    private suspend fun sendChatActionForSession(
        session: PollingSession,
        chatId: String,
        action: String,
    ): TelegramApiResponse {
        ensureCurrent(session)
        return telegramService.sendChatActionForToken(session.token, chatId, action)
    }

    private fun activeSession(): PollingSession? = sessionLock.withLock {
        currentSession?.takeIf { !closed && isTokenGenerationCurrent(it) }
    }

    private fun isCurrent(session: PollingSession): Boolean = sessionLock.withLock {
        !closed && currentSession === session && isTokenGenerationCurrent(session)
    }

    private fun ensureCurrent(session: PollingSession) {
        if (!isCurrent(session)) {
            throw CancellationException("Polling session is no longer current.")
        }
    }

    private fun saveForCurrent(session: PollingSession, save: () -> Unit): Boolean =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession !== session || !isTokenGenerationCurrent(session)) {
                    false
                } else {
                    save()
                    true
                }
            }
        }

    private fun <T> readForCurrent(session: PollingSession, read: () -> T): T? =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession === session && isTokenGenerationCurrent(session)) read() else null
            }
        }

    private fun isTokenGenerationCurrent(session: PollingSession): Boolean =
        isTokenGenerationCurrent(session.token, session.generation)

    /** 判断指定 token 文本及代次仍是设置仓储当前发布的不可合并生命周期。 */
    private fun isTokenGenerationCurrent(token: String, generation: Long): Boolean =
        settingsRepository.telegramTokenUpdateFlow.value.let { tokenUpdate ->
            tokenUpdate.token == token && tokenUpdate.generation == generation
        }

    private fun cancelCurrentSession() {
        val session = sessionLock.withLock { currentSession.also { currentSession = null } } ?: return
        session.updateChannel.close()
        session.scope.cancel()
    }

    /**
     * 停止设置监听及当前轮询会话。
     *
     * 关闭会取消当前会话的在途和排队任务，但不会完成旧队列的确认信号或写入其偏移量。
     */
    override fun close() {
        val jobToCancel = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            settingsJob.also { settingsJob = null }
        }
        jobToCancel?.cancel()
        val pendingReset = sessionLock.withLock {
            pendingAgentReset.also { pendingAgentReset = null }
        }
        pendingReset?.initialResetCompletion?.complete(false)
        pendingReset?.retryCompletion?.complete(false)
        pendingReset?.let { modelSwitchBarrier.complete(it.barrierGeneration) }
        runBlocking { cancelCurrentSession() }
        scope.cancel()
        logger.info("Agent poller stopped.")
    }
}

private fun Update.chatInfo(): ChatInfo? {
    val chat = message?.chat ?: channelPost?.chat ?: myChatMember?.chat ?: return null
    val title = chat.title ?: chat.username ?: "${chat.firstName ?: ""} ${chat.lastName ?: ""}".trim()
    return ChatInfo(id = chat.id.toString(), title = title, type = chat.type)
}
