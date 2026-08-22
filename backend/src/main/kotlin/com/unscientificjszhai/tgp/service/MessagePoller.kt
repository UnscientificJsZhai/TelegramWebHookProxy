package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.*
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilityState
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.MAX_AGENT_TEXT_BYTES
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.TelegramTextChunks
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val TELEGRAM_REPLY_FALLBACK_MESSAGE = "抱歉，上一条回复未能发送。"
private const val AGENT_TURN_FAILURE_REPLY = "抱歉，该消息未能处理。"

/**
 * 后台轮询 Telegram 更新，并将授权用户私聊的消息依次交给 AI 代理处理。
 *
 * 每个有效 token 生命周期拥有唯一的轮询会话。会话捕获 token、bot 标识、token 代次、队列、
 * 子作用域和上下文清理计时；token 更换、清空或代次变化时会先取消旧会话的在途及排队任务，
 * 再创建新会话。旧会话永远不会确认排队完成或推进偏移量。
 * 应用在 `ApplicationStopPreparing` 中先调用 [requestStop] 关闭启动、会话安装和队列准入，再等待
 * [awaitStopped]；[close] 仅保留为不等待的 [AutoCloseable] 兼容入口。
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

    /** 服务拥有的根任务；其完成覆盖所有轮询会话及其 Agent 重置等待。 */
    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + scopeJob)
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
        val updateChannel: Channel<QueuedWork>,
        val consumerResume: Channel<Unit>,
        val outboxSignal: Channel<Unit>,
        var pollJob: Job? = null,
        var consumerJob: Job? = null,
        var consumerRestartedAfterError: Boolean = false,
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

    /**
     * 已授权更新绑定的不可变设置租约。
     *
     * 票据只在共享模型屏障已放行时创建。消费者在每个会产生副作用的步骤前都必须确认 [generation] 和
     * [agentChatId] 仍与当前设置相符；不相符的排队工作会被静默确认，绝不能在新身份下执行。
     */
    private data class AdmissionTicket(
        val agentChatId: String,
        val generation: Long,
    )

    /** 保留 Telegram 原消息中的授权事实，以便每个副作用在屏障内复核私聊和发送者身份。 */
    private data class AuthorizedMessageContext(
        val chatId: String,
        val chatType: String,
        val fromId: String?,
        val messageId: Long,
    ) {
        /** 仅当当前 AI 设置仍授权原消息的私聊、聊天和发送者时返回 `true`。 */
        fun matches(aiSettings: AISettings): Boolean =
            chatType == "private" &&
                    chatId == aiSettings.agentChatId &&
                    fromId == aiSettings.agentChatId
    }

    /**
     * 当前轮询会话待结算的一项队列工作。
     *
     * 新授权消息使用 [Authorized]；journal 回放以 [DurableFinal] 或 [DurableInProgress] 表示，避免依赖
     * 当前授权或重新进入 Agent。三种工作均携带原更新与完成信号，以保证同一批次的偏移量按顺序结算。
     */
    private sealed interface QueuedWork {
        val update: Update
        val entryTime: Long
        val completion: CompletableDeferred<UpdateCompletion>
        val expectedRetryCheckpointTarget: Long?

        /** 已通过当前设置授权、但每个副作用前仍须复核 [ticket] 的新消息。 */
        data class Authorized(
            override val update: Update,
            override val entryTime: Long,
            override val completion: CompletableDeferred<UpdateCompletion>,
            override val expectedRetryCheckpointTarget: Long?,
            val ticket: AdmissionTicket,
        ) : QueuedWork

        /** 已持久化最终回复的回放；只能提交 outbox 和偏移量。 */
        data class DurableFinal(
            override val update: Update,
            override val entryTime: Long,
            override val completion: CompletableDeferred<UpdateCompletion>,
            override val expectedRetryCheckpointTarget: Long?,
            val entry: AgentTurnJournalEntry,
        ) : QueuedWork

        /** 已无从安全重放的进行中回放；只能在没有本地 owner 时静默确认。 */
        data class DurableInProgress(
            override val update: Update,
            override val entryTime: Long,
            override val completion: CompletableDeferred<UpdateCompletion>,
            override val expectedRetryCheckpointTarget: Long?,
            val entry: AgentTurnJournalEntry,
        ) : QueuedWork
    }

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

        /** 当前设置已授权消息，但 Agent 正在恢复；必须持久化检查点后等待该序列变化。 */
        data class WaitingForAgent(
            val observedSequence: Long,
            val observedSettingsVersion: Long,
        ) : UpdateAdmission
    }

    /** 屏障放行后的本地接纳判定；队满通知必须在屏障外发送。 */
    private sealed interface BarrierAdmission {
        /** 更新无需排队或会话已不再当前。 */
        data object Confirmed : BarrierAdmission

        /** 稳定配置与当前代理不一致，必须保留偏移量等待后续轮询。 */
        data object Retry : BarrierAdmission

        /** Agent 初始化或恢复尚未结束；轮询器应停止调用 getUpdates 并等待状态变化。 */
        data class WaitingForAgent(
            val observedSequence: Long,
            val observedSettingsVersion: Long,
        ) : BarrierAdmission

        /** 更新已提交给当前会话的消费者队列。 */
        data class Enqueued(val completion: CompletableDeferred<UpdateCompletion>) : BarrierAdmission

        /** 当前会话的消费者队列已满，等待在屏障外发送 Telegram 提示。 */
        data class QueueFull(
            val authorization: AuthorizedMessageContext,
            val ticket: AdmissionTicket,
        ) : BarrierAdmission
    }

    /** 在 token 生命周期与会话锁内提交队列的结果。 */
    private enum class QueueOfferResult {
        ENQUEUED,
        FULL,
        NOT_CURRENT,
    }

    /** 单次初始化或长轮询请求的结果；失败结果绝不包含可推进偏移量的更新。 */
    private sealed interface PollingAttempt {
        data object Succeeded : PollingAttempt
        data object Stopped : PollingAttempt
        data class ApiFailure(val response: GetUpdatesResponse) : PollingAttempt
        data object LocalRetry : PollingAttempt
        data class WaitingForAgent(
            val observedSequence: Long,
            val observedSettingsVersion: Long,
        ) : PollingAttempt
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
                replacement.previous.scope.coroutineContext[Job]?.join()
                currentCoroutineContext().ensureActive()
                if (closed) {
                    return
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
                currentCoroutineContext().ensureActive()
                if (closed) {
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

    /** 仅供回归测试在 `/model` 已完成票据复核、但尚未执行仓储 CAS 时构造确定性竞争。 */
    @Volatile
    internal var beforeModelSelectionPersistForTesting: (() -> Unit)? = null

    /** 仅供回归测试在 `/model` 已取得单次 ready-agent 准入后触发确定性的设置切换竞争。 */
    @Volatile
    internal var beforeModelRefreshForTesting: (() -> Unit)? = null

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
        startQueueConsumer(session)
        session.outboxJob = session.scope.launch { consumeOutbox(session) }
        session.pollJob = session.scope.launch { runPolling(session) }
        logger.info("Started polling session for bot {} at generation {}", botId, generation)
        return SessionInstallation.INSTALLED
    }

    private suspend fun runPolling(session: PollingSession) {
        var resumeConsumerAfterRetry = false
        while (currentCoroutineContext().isActive) {
            try {
                if (resumeConsumerAfterRetry) {
                    // 前一项回合未能安全提交时，消费者已把当批后续更新标记为 Retry 并暂停。检查点已先
                    // 持久化，因此下一轮从仓储快照重取目标后才允许它消费新批次。
                    session.consumerResume.trySend(Unit)
                    resumeConsumerAfterRetry = false
                }
                when (val attempt = pollOnce(session)) {
                    PollingAttempt.Succeeded -> {
                        session.consecutivePollingFailures = 0
                        // 成功轮询沿用既有短暂让步；失败路径绝不会再叠加这段延迟。
                        delay(1000.milliseconds)
                    }

                    PollingAttempt.Stopped -> return
                    PollingAttempt.LocalRetry -> {
                        resumeConsumerAfterRetry = true
                        if (!delayAfterFailure(session)) {
                            return
                        }
                    }

                    is PollingAttempt.WaitingForAgent -> {
                        // This is not a Telegram failure: keep both counters and polling backoff untouched. The
                        // checkpoint is already durable, and the StateFlow predicate prevents a lost wakeup if the
                        // Agent changed immediately before collection began.
                        if (!awaitAgentAvailabilityChange(
                                session,
                                attempt.observedSequence,
                                attempt.observedSettingsVersion,
                            )
                        ) {
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
     * 等待恢复状态出现有意义的变化，不在非 READY 的每个重试转换后重新拉取同一 Telegram 更新。
     *
     * 同一设置版本内的 INITIALIZING/RETRY_SCHEDULED/BLOCKED 转换只更新观察序列并继续等待；READY、
     * DISABLED、CLOSED 或任意设置版本变化会返回主循环，让同一 offset 按最新授权重新判断。
     */
    private suspend fun awaitAgentAvailabilityChange(
        session: PollingSession,
        observedSequence: Long,
        observedSettingsVersion: Long,
    ): Boolean {
        var sequence = observedSequence
        while (isCurrent(session)) {
            val snapshot = agentService.availability.first { state -> state.sequence != sequence }
            if (!isCurrent(session)) return false
            if (settingsRepository.currentSettingsSnapshot().generation != observedSettingsVersion) return true
            when (snapshot.state) {
                AgentAvailabilityState.READY,
                AgentAvailabilityState.DISABLED,
                    -> return true

                AgentAvailabilityState.CLOSED -> return false
                AgentAvailabilityState.INITIALIZING,
                AgentAvailabilityState.RETRY_SCHEDULED,
                AgentAvailabilityState.BLOCKED,
                    -> sequence = snapshot.sequence
            }
        }
        return false
    }

    /**
     * 执行一次初始化或正常长轮询；每轮都从持久化快照决定唯一请求偏移量。
     *
     * 尚未解决的 [RetryCheckpoint] 优先于经检查加一的 `lastUpdateId + 1`。检查点存在时禁止 `-1` 初始化，并且只有
     * 成功确认其精确目标、durable 调和或已审计的 Telegram gap 才能在同一次文件提交中清除它。`-1` 初始化
     * 返回的最新更新同样经过普通授权、durable claim 和 Agent 等待流程，绝不再作为偏移量基线直接丢弃。
     */
    private suspend fun pollOnce(session: PollingSession): PollingAttempt {
        if (!isCurrent(session)) {
            return PollingAttempt.Stopped
        }
        val snapshot = readForCurrent(session) { updatesRepository.getData(session.botId) }
            ?: return PollingAttempt.Stopped
        var lastStoredId = snapshot.lastUpdateId
        val initialRetryCheckpoint = snapshot.retryCheckpoint
        val resolvingInitialOffset =
            lastStoredId == 0L && initialRetryCheckpoint == null && !session.initialOffsetResolved
        val (targetUpdateId, response) = if (resolvingInitialOffset) {
            val initialResponse = telegramService.getUpdatesForToken(session.token, offset = -1, timeout = 0)
            if (!isCurrent(session)) return PollingAttempt.Stopped
            if (!initialResponse.ok) return PollingAttempt.ApiFailure(initialResponse)
            if (initialResponse.result.any { !isPersistableTelegramUpdateId(it.updateId) }) {
                logger.error(
                    "Initial Telegram response for bot {} contains an update ID outside the persistable offset range; retrying without a checkpoint.",
                    session.botId,
                )
                return PollingAttempt.LocalRetry
            }
            session.initialOffsetResolved = true
            val firstReturnedId = initialResponse.result.minOfOrNull { it.updateId }
                ?: return PollingAttempt.Succeeded
            firstReturnedId to initialResponse
        } else {
            val target = initialRetryCheckpoint?.targetUpdateId ?: Math.addExact(lastStoredId, 1L)
            target to telegramService.getUpdatesForToken(
                session.token,
                offset = target,
                timeout = 30,
            )
        }
        if (!isCurrent(session)) {
            return PollingAttempt.Stopped
        }
        if (!response.ok) {
            return PollingAttempt.ApiFailure(response)
        }
        if (response.result.any { !isPersistableTelegramUpdateId(it.updateId) }) {
            logger.error(
                "Telegram response for bot {} contains an update ID outside the persistable offset range; retrying without a checkpoint.",
                session.botId,
            )
            return PollingAttempt.LocalRetry
        }
        // 本轮长轮询期间，消费者或公开入口可能已写入一个检查点或推进 offset。必须以响应后的持久化
        // 快照重新决定是否可处理本批响应，不能让较早的请求快照覆盖新事实。
        val responseSnapshot = readForCurrent(session) { updatesRepository.getData(session.botId) }
            ?: return PollingAttempt.Stopped
        lastStoredId = maxOf(lastStoredId, responseSnapshot.lastUpdateId)
        val retryCheckpoint = responseSnapshot.retryCheckpoint
        if (retryCheckpoint != null && retryCheckpoint.targetUpdateId != targetUpdateId) {
            return PollingAttempt.LocalRetry
        }
        if (retryCheckpoint != null) {
            when (reconcileDurableRetryCheckpoint(session, retryCheckpoint)) {
                DurableRetryReconciliation.Settled -> return PollingAttempt.Succeeded
                DurableRetryReconciliation.Retry -> return persistLocalRetryCheckpoint(session, targetUpdateId)
                DurableRetryReconciliation.None -> Unit
            }
            val firstAvailableId = response.result.minOfOrNull { it.updateId }
            when {
                firstAvailableId == null -> return persistLocalRetryCheckpoint(session, targetUpdateId)
                firstAvailableId < targetUpdateId -> {
                    logger.warn(
                        "Retry checkpoint {} for bot {} received an earlier update {}; retaining checkpoint.",
                        targetUpdateId,
                        session.botId,
                        firstAvailableId,
                    )
                    return persistLocalRetryCheckpoint(session, targetUpdateId)
                }

                firstAvailableId > targetUpdateId -> {
                    val skipped = writeForCurrent(session) {
                        updatesRepository.skipRetryCheckpointGap(session.botId, targetUpdateId, firstAvailableId)
                    } ?: return PollingAttempt.Stopped
                    when (skipped) {
                        is RetryCheckpointGapResult.Skipped -> {
                            val checkpoint = skipped.checkpoint
                            logger.warn(
                                "Skipping expired Telegram retry gap for bot {}; target={}, observedFirst={}, ageMillis={}, retryCount={}",
                                session.botId,
                                targetUpdateId,
                                firstAvailableId,
                                (System.currentTimeMillis() - checkpoint.firstRetryAtMillis).coerceAtLeast(0),
                                checkpoint.retryCount,
                            )
                            // 此轮只提交目标本身。下一轮才会从 target + 1 请求，避免同一响应越过审计点。
                            return PollingAttempt.Succeeded
                        }

                        RetryCheckpointGapResult.Stale -> return PollingAttempt.LocalRetry
                    }
                }
            }
        }

        val completions = mutableListOf<Pair<Long, CompletableDeferred<UpdateCompletion>>>()
        val discoveredChats = LinkedHashMap<String, ChatInfo>()
        var mustRetry = false
        var retryUpdateId: Long? = null
        var waitingForAgent: UpdateAdmission.WaitingForAgent? = null
        for (update in response.result.asSequence().filter { it.updateId > lastStoredId }) {
            try {
                val expectedRetryTarget = retryCheckpoint?.targetUpdateId?.takeIf { it == update.updateId }
                when (val admission = enqueueUpdate(session, update, expectedRetryTarget)) {
                    UpdateAdmission.Confirmed -> {
                        update.chatInfo()?.let { chat ->
                            // LinkedHashMap assignment keeps an existing key at its old position. Remove first so a
                            // repeated chat in the same Telegram batch receives the same final-observation ordering
                            // that mergeChats uses to assign LRU recency.
                            discoveredChats.remove(chat.id)
                            discoveredChats[chat.id] = chat
                        }
                        completions += update.updateId to confirmedSignal()
                    }

                    is UpdateAdmission.Enqueued -> {
                        update.chatInfo()?.let { chat ->
                            discoveredChats.remove(chat.id)
                            discoveredChats[chat.id] = chat
                        }
                        completions += update.updateId to admission.completion
                    }

                    UpdateAdmission.Retry -> {
                        mustRetry = true
                        retryUpdateId = update.updateId
                        waitingForAgent = null
                        break
                    }

                    is UpdateAdmission.WaitingForAgent -> {
                        mustRetry = true
                        retryUpdateId = update.updateId
                        waitingForAgent = admission
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
                waitingForAgent = null
                break
            }
        }
        if (discoveredChats.isNotEmpty()) {
            try {
                if (!saveForCurrent(session) {
                        updatesRepository.mergeChats(session.botId, discoveredChats.values)
                    }
                ) {
                    return PollingAttempt.Stopped
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // Chat discovery is an auxiliary cache, never a precondition for acknowledging Telegram updates.
                // In particular, a temporarily unwritable updates.json must not cause Telegram to redeliver the
                // same unauthorized group traffic forever. The completion/offset path below remains authoritative.
                logger.warn(
                    "Unable to save discovered chats for bot {}; continuing update completion; category={}",
                    session.botId,
                    SafeLogging.failureCategory(e).wireName,
                )
            }
        }
        for ((updateId, completion) in completions.sortedBy { it.first }) {
            when (completion.await()) {
                UpdateCompletion.Persisted -> {
                    // Agent 回合及其可能的 outbox 已在同一次提交中确认偏移量。
                    lastStoredId = maxOf(lastStoredId, updateId)
                }

                UpdateCompletion.Confirmed -> {
                    if (updateId > lastStoredId) {
                        val expectedRetryTarget = retryCheckpoint?.targetUpdateId?.takeIf { it == updateId }
                        val confirmed = writeForCurrent(session) {
                            updatesRepository.confirmProcessedUpdate(session.botId, updateId, expectedRetryTarget)
                        } ?: return PollingAttempt.Stopped
                        if (confirmed != RetryCheckpointCommitResult.Committed) {
                            mustRetry = true
                            retryUpdateId = updateId
                            waitingForAgent = null
                            break
                        }
                        lastStoredId = updateId
                    }
                }

                UpdateCompletion.Retry -> {
                    mustRetry = true
                    retryUpdateId = updateId
                    waitingForAgent = null
                    break
                }
            }
        }
        return when {
            !isCurrent(session) -> PollingAttempt.Stopped
            mustRetry -> persistLocalRetryCheckpoint(
                session,
                checkNotNull(retryUpdateId),
                waitingForAgent,
            )

            else -> PollingAttempt.Succeeded
        }
    }

    /** 重试检查点目标在收到响应后可执行的 durable 调和结果。 */
    private enum class DurableRetryReconciliation {
        /** 没有该目标的 durable journal，调用方仍需检查 Telegram 响应。 */
        None,

        /** FINAL 或孤立 IN_PROGRESS 已原子确认目标和检查点；下一轮读取新的持久化偏移量。 */
        Settled,

        /** 仍有本地 owner 或持久化调和失败；检查点必须保持并重试。 */
        Retry,
    }

    /**
     * 在 gap 判定前优先结算重试目标已有的 durable Agent 状态。
     *
     * FINAL 只能写入 outbox 和偏移量，孤立 IN_PROGRESS 只能静默确认；本地 owner 仍存活时绝不能以
     * Telegram 的缺失响应跳过它。
     */
    private suspend fun reconcileDurableRetryCheckpoint(
        session: PollingSession,
        checkpoint: RetryCheckpoint,
    ): DurableRetryReconciliation = try {
        val entry = withContext(NonCancellable) {
            updatesRepository.findAgentTurn(session.botId, checkpoint.targetUpdateId)
        } ?: return DurableRetryReconciliation.None
        when (entry.status) {
            AgentTurnJournalStatus.FINAL ->
                when (completeFinalAgentTurn(session, entry, checkpoint.targetUpdateId)) {
                    UpdateCompletion.Persisted -> DurableRetryReconciliation.Settled
                    UpdateCompletion.Confirmed,
                    UpdateCompletion.Retry,
                        -> DurableRetryReconciliation.Retry
                }

            AgentTurnJournalStatus.IN_PROGRESS ->
                when (confirmDurableInProgressTurn(session, entry, checkpoint.targetUpdateId)) {
                    UpdateCompletion.Persisted -> DurableRetryReconciliation.Settled
                    UpdateCompletion.Confirmed,
                    UpdateCompletion.Retry,
                        -> DurableRetryReconciliation.Retry
                }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(
            "Unable to reconcile durable retry target {} for bot {}; category={}",
            checkpoint.targetUpdateId,
            session.botId,
            SafeLogging.failureCategory(e).wireName,
        )
        DurableRetryReconciliation.Retry
    }

    /**
     * 在返回本地重试或 Agent 等待前，先以当前仓储快照条件写入检查点。
     *
     * 如果另一条路径已经改变目标，本方法不覆盖它；下一轮从新的持久化快照重新选择 offset。文件写入失败时
     * 保持仓储原样时返回 [PollingAttempt.LocalRetry]，使轮询循环恢复等待中的消费者后按本地故障退避；
     * 只有检查点成功提交且 [waitingForAgent] 非空时才返回 [PollingAttempt.WaitingForAgent]。
     */
    private fun persistLocalRetryCheckpoint(
        session: PollingSession,
        targetUpdateId: Long,
        waitingForAgent: UpdateAdmission.WaitingForAgent? = null,
    ): PollingAttempt = try {
        val currentData = readForCurrent(session) {
            updatesRepository.getData(session.botId)
        } ?: return PollingAttempt.Stopped
        val expectedTarget = currentData.retryCheckpoint?.targetUpdateId
        if (expectedTarget != null && expectedTarget != targetUpdateId) {
            return PollingAttempt.LocalRetry
        }
        val recorded = writeForCurrent(session) {
            updatesRepository.recordRetryCheckpoint(
                botId = session.botId,
                targetUpdateId = targetUpdateId,
                expectedTargetUpdateId = expectedTarget,
                nowMillis = System.currentTimeMillis(),
            )
        } ?: return PollingAttempt.Stopped
        when (recorded) {
            RetryCheckpointRecordResult.Stale -> {
                logger.info(
                    "Retry checkpoint changed before recording bot {} target {}; rereading durable state.",
                    session.botId,
                    targetUpdateId,
                )
                PollingAttempt.LocalRetry
            }

            is RetryCheckpointRecordResult.Recorded -> waitingForAgent?.let { waiting ->
                PollingAttempt.WaitingForAgent(
                    observedSequence = waiting.observedSequence,
                    observedSettingsVersion = waiting.observedSettingsVersion,
                )
            } ?: PollingAttempt.LocalRetry
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(
            "Unable to persist retry checkpoint for bot {} target {}; retaining durable state and resuming consumer; category={}",
            session.botId,
            targetUpdateId,
            SafeLogging.failureCategory(e).wireName,
        )
        PollingAttempt.LocalRetry
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
     * [PendingAgentReset]；后续 token 会话只能在重试成功后安装。认证失败会先取消旧会话，再由服务根 scope
     * 执行可取消的重置；因此关闭根 scope 可以中断悬挂的重置等待，而正常认证失败仍不会等待自身会话终止。
     */
    private fun terminateAuthenticationFailedSession(session: PollingSession) {
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

        session.scope.cancel(CancellationException("Telegram authentication failed"))
        scope.launch {
            if (!completeInitialAgentReset(pendingReset)) {
                if (!closed) {
                    logger.warn(
                        "Agent session reset failed after Telegram authentication failure; blocking new polling sessions until a retry succeeds.",
                    )
                }
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

                pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE -> {
                    pendingAgentReset = null
                    true
                }

                else -> false
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
                pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE ||
                        isPendingAgentResetCurrent(pendingReset)
                )
    }

    /** 在外部屏障已关闭时执行一次可取消的 Agent 重置，并把所有失败语义转换为可重试的 `false`。 */
    private suspend fun performAgentReset(): Boolean = try {
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

    /**
     * 启动当前会话唯一的队列消费者，并在允许的栈溢出错误后至多恢复一次。
     *
     * 消费者退出前会把当前和排队的 completion 全部标记为 [UpdateCompletion.Retry]。恢复只针对已知会由
     * 不可信深层输入触发、且本身不会损坏进程状态的错误；其他 Error 保持终止语义，绝不在同一会话盲目
     * 重启。
     */
    private fun startQueueConsumer(session: PollingSession) {
        // SupervisorJob 不会把子协程错误传播给 session scope；提供局部 handler 使已经在 finally 中结算并
        // 经 completion callback 决定恢复策略的错误不会泄漏为测试框架或进程级“未捕获异常”。
        val consumer = session.scope.launch(CoroutineExceptionHandler { _, cause ->
            logger.error(
                "Queue consumer exited after completing retry signals for bot {}; type={}",
                session.botId,
                cause::class.qualifiedName,
            )
        }) { consumeQueue(session) }
        session.consumerJob = consumer
        consumer.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) {
                return@invokeOnCompletion
            }
            if (!isRecoverableQueueConsumerFailure(cause)) {
                terminateFatalQueueConsumerSession(session, consumer)
                logger.error(
                    "Queue consumer stopped with a fatal error for bot {}; the session was terminated and will not restart; type={}",
                    session.botId,
                    cause::class.qualifiedName,
                )
                return@invokeOnCompletion
            }
            session.scope.launch {
                val shouldRestart = sessionLock.withLock {
                    if (
                        closed ||
                        currentSession !== session ||
                        session.consumerJob !== consumer ||
                        session.consumerRestartedAfterError
                    ) {
                        false
                    } else {
                        session.consumerRestartedAfterError = true
                        true
                    }
                }
                if (shouldRestart && isCurrent(session)) {
                    logger.warn(
                        "Queue consumer hit a recoverable error for bot {}; restarting it once after queue retry completion.",
                        session.botId,
                    )
                    startQueueConsumer(session)
                }
            }
        }
    }

    /** 深层 JSON 的历史故障会抛出 StackOverflowError；内存、链接等真正致命错误绝不恢复。 */
    private fun isRecoverableQueueConsumerFailure(cause: Throwable): Boolean = cause is StackOverflowError

    /**
     * 原子摘除因 fatal Error 失去消费者的会话，并结算可能恰好在消费者 finally 之后入队的工作。
     *
     * 先在 [sessionLock] 内关闭准入和通道，之后才取消 polling scope；这样轮询协程不会继续向无人消费的
     * channel 投递 completion 并永远等待。已在 finally 前入队的工作仍以 Retry 结算，保持 offset 重拉语义。
     */
    private fun terminateFatalQueueConsumerSession(session: PollingSession, consumer: Job) {
        val shouldTerminate = sessionLock.withLock {
            if (closed || currentSession !== session || session.consumerJob !== consumer) {
                false
            } else {
                currentSession = null
                session.updateChannel.close()
                session.consumerResume.close()
                session.outboxSignal.close()
                true
            }
        }
        if (shouldTerminate) {
            // `consumeQueue.finally` 已完成当时可见项目；此处覆盖它结束与 completion callback 之间的竞态。
            drainQueuedUpdatesAsRetry(session)
            session.pollJob?.cancel(CancellationException("Queue consumer stopped after fatal error."))
            session.scope.cancel(CancellationException("Queue consumer stopped after fatal error."))
        }
    }

    /**
     * 串行消费队列，并保证任何异常（包括 Error）都不会遗留未完成的批次 deferred。
     *
     * 所有退出路径都会先把正在处理和仍排队的更新标为 Retry，再将原异常重新抛出。因此 fatal 错误不会被
     * 误当普通业务失败吞掉，StackOverflowError 则可由 [startQueueConsumer] 的受控恢复路径观察到。
     */
    private suspend fun consumeQueue(session: PollingSession) {
        var currentWork: QueuedWork? = null
        try {
            while (currentCoroutineContext().isActive) {
                val queuedWork = session.updateChannel.receiveCatching().getOrNull() ?: return
                currentWork = queuedWork
                val deadline = queuedWork.entryTime + processingTimeout.inWholeMilliseconds
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                val completion = try {
                    withTimeout(remaining.milliseconds) {
                        processQueuedWork(session, queuedWork)
                    }
                } catch (_: TimeoutCancellationException) {
                    handleProcessingTimeout(session, queuedWork)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Error processing update {}; category={}",
                        queuedWork.update.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    UpdateCompletion.Retry
                }

                currentCoroutineContext().ensureActive()
                if (isCurrent(session)) {
                    queuedWork.completion.complete(completion)
                    currentWork = null
                    if (completion == UpdateCompletion.Retry) {
                        // 本批更高 update 不得越过失败回合写入 offset；全部退回下次从失败 update 开始的轮询。
                        drainQueuedUpdatesAsRetry(session)
                        session.consumerResume.receiveCatching().getOrNull() ?: return
                    }
                }
            }
        } finally {
            // CompletableDeferred.complete 是幂等的；即使异常恰好发生在成功结算之后，也不会覆盖既有结果。
            currentWork?.completion?.complete(UpdateCompletion.Retry)
            drainQueuedUpdatesAsRetry(session)
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
     * 每次网络请求前先在当前 token 代次保护下持久化当前片段的投递次数。原文首片段携带引用参数并收到明确
     * 的永久 `4xx` 时，先清除引用并重发相同原文；其他原文片段收到第二次永久 `4xx` 后，原子切换为固定回退
     * 消息。回退消息被接受或最多三次投递均失败时，都会终止整条回复，绝不继续发送缺失片段后的原文尾部。
     * 网络异常、`429`、未由有效 Telegram 响应正文声明非限流永久 `4xx` 的其他 HTTP 状态和无效响应正文均保留
     * 当前片段以便重试。
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
            telegramService.sendMessageForToken(
                session.token,
                reply.chatId,
                reply.deliveryText(),
                reply.deliveryReplyParameters(),
            )
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
                        updatesRepository.discardExhaustedPendingTelegramReplyFallback(session.botId, reply)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Failed to discard exhausted Telegram fallback chunk for reply {}; retaining it; category={}",
                        reply.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    false
                }
                if (exhaustedRemoved) {
                    logger.warn(
                        "Telegram fallback reply {} of bot {} was rejected three times; terminating that reply.",
                        reply.updateId,
                        session.botId,
                    )
                    return OutboxDelivery.DELIVERED
                }
                return OutboxDelivery.RETRY
            }
            if (reply.deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
                val replacement = when {
                    response?.isPermanentTelegramRejection() == true && reply.shouldRetryFirstChunkWithoutReplyParameters() ->
                        reply.withoutFirstChunkReplyParameters()

                    response?.isPermanentTelegramRejection() == true -> reply.afterPermanentTelegramRejection()
                    else -> reply.afterRetryableTelegramFailure()
                }
                if (replacement == reply) {
                    logger.warn(
                        "Telegram did not accept outbox reply chunk for update {} of bot {}; retrying later.",
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
                } else if (response?.isPermanentTelegramRejection() == true &&
                    reply.shouldRetryFirstChunkWithoutReplyParameters()
                ) {
                    logger.warn(
                        "Telegram permanently rejected the quoted first chunk for outbox reply {} of bot {}; retrying original without reply parameters.",
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
                    "Telegram did not accept outbox reply for update {} of bot {}; retrying later.",
                    reply.updateId,
                    session.botId,
                )
            }
            return OutboxDelivery.RETRY
        }

        val removed = try {
            saveForCurrent(session) {
                updatesRepository.advancePendingTelegramReplyDelivery(session.botId, reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to persist delivered Telegram outbox reply chunk {}; retaining it; category={}",
                reply.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            false
        }
        return if (removed) OutboxDelivery.DELIVERED else OutboxDelivery.RETRY
    }

    /** 按不可变队列工作结算更新；durable 回放绝不依赖当前授权或重新进入 Agent。 */
    private suspend fun processQueuedWork(
        session: PollingSession,
        work: QueuedWork,
    ): UpdateCompletion = when (work) {
        is QueuedWork.DurableFinal -> completeFinalAgentTurn(session, work.entry, work.expectedRetryCheckpointTarget)
        is QueuedWork.DurableInProgress ->
            confirmDurableInProgressTurn(session, work.entry, work.expectedRetryCheckpointTarget)

        is QueuedWork.Authorized ->
            processAuthorizedUpdate(session, work.update, work.ticket, work.expectedRetryCheckpointTarget)
    }

    /** 处理持有当前授权票据的新消息；不产生副作用的分支可直接静默确认。 */
    private suspend fun processAuthorizedUpdate(
        session: PollingSession,
        update: Update,
        ticket: AdmissionTicket,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val message = update.message ?: return UpdateCompletion.Confirmed
        val authorization = message.toAuthorizedMessageContext()
        return when {
            message.text?.startsWith("/") == true -> {
                handleAuthorizedCommand(
                    session,
                    ticket,
                    authorization,
                    update.updateId,
                    expectedRetryCheckpointTarget,
                    message.text,
                )
            }

            message.voice != null ->
                completeVoiceAgentUpdate(
                    session,
                    ticket,
                    update.updateId,
                    authorization,
                    message,
                    expectedRetryCheckpointTarget,
                )

            message.text != null ->
                completeTextAgentUpdate(
                    session,
                    ticket,
                    update.updateId,
                    authorization,
                    message,
                    expectedRetryCheckpointTarget,
                )

            else -> UpdateCompletion.Confirmed
        }
    }

    /**
     * 处理消费者超时。
     *
     * Durable FINAL 始终提交 outbox；没有本地 owner 的 IN_PROGRESS 则静默确认偏移量，绝不重放 Agent
     * 或创建回复。尚未 claim 的持票据消息会重新验证授权后发送超时提示并确认；授权失效则直接静默确认。
     */
    private suspend fun handleProcessingTimeout(
        session: PollingSession,
        work: QueuedWork,
    ): UpdateCompletion = when (work) {
        is QueuedWork.DurableFinal -> completeFinalAgentTurn(session, work.entry, work.expectedRetryCheckpointTarget)
        is QueuedWork.DurableInProgress ->
            confirmDurableInProgressTurn(session, work.entry, work.expectedRetryCheckpointTarget)

        is QueuedWork.Authorized -> handleAuthorizedProcessingTimeout(session, work)
    }

    /**
     * 结算持票据工作超时。
     *
     * 已有 durable 记录时按其终态结算；尚未 claim 的文本或语音消息则必须重新验证票据后发送一次超时
     * 提示并确认偏移量。否则队列等待本身耗尽超时时间的更新会永远以没有 journal 的状态重试。
     */
    private suspend fun handleAuthorizedProcessingTimeout(
        session: PollingSession,
        work: QueuedWork.Authorized,
    ): UpdateCompletion {
        val update = work.update
        val message = update.message ?: return UpdateCompletion.Retry
        val authorization = message.toAuthorizedMessageContext()
        if (
            !message.text.orEmpty().startsWith("/") &&
            (message.text != null || message.voice != null)
        ) {
            return finalizeTimedOutDurableAgentTurn(
                session,
                work.ticket,
                authorization,
                update.updateId,
                work.expectedRetryCheckpointTarget,
            )
        }
        logger.warn("Non-durable update {} processing timed out.", update.updateId)
        return sendAuthorizedTimeoutNotification(
            session,
            work.ticket,
            authorization,
            update.updateId,
            work.expectedRetryCheckpointTarget,
        )
    }

    /**
     * 结算超时的生产回合。
     *
     * 若已建立 journal，则遵循 durable FINAL / IN_PROGRESS 规则；若尚未 claim，则在发送超时提示的同一
     * 票据检查中确认更新。配置已变化时 [sendAuthorizedTimeoutNotification] 不会产生副作用而直接确认。
     */
    private suspend fun finalizeTimedOutDurableAgentTurn(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion = try {
        val entry = withContext(NonCancellable) {
            updatesRepository.getData(session.botId).agentTurnJournal.singleOrNull { it.updateId == updateId }
        } ?: return sendAuthorizedTimeoutNotification(
            session,
            ticket,
            authorization,
            updateId,
            expectedRetryCheckpointTarget,
        )
        when (entry.status) {
            AgentTurnJournalStatus.FINAL -> completeFinalAgentTurn(session, entry, expectedRetryCheckpointTarget)
            AgentTurnJournalStatus.IN_PROGRESS ->
                confirmDurableInProgressTurn(session, entry, expectedRetryCheckpointTarget)
        }
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

    /** 在仍持有效票据时发送超时提示；Telegram 明确接受后才确认尚未 claim 的更新。 */
    private suspend fun sendAuthorizedTimeoutNotification(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion = sendAuthorizedCommandReply(
        session,
        ticket,
        authorization,
        updateId,
        expectedRetryCheckpointTarget,
        "抱歉，该消息处理超时（超过10分钟）。",
    )

    private suspend fun enqueueUpdate(
        session: PollingSession,
        update: Update,
        expectedRetryCheckpointTarget: Long? = null,
    ): UpdateAdmission {
        if (!isCurrent(session)) {
            return UpdateAdmission.Confirmed
        }
        val message = update.message ?: return UpdateAdmission.Confirmed
        if (message.text == null && message.voice == null) {
            return UpdateAdmission.Confirmed
        }
        val authorization = message.toAuthorizedMessageContext()
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
            val work = when (existingTurn.status) {
                AgentTurnJournalStatus.FINAL -> QueuedWork.DurableFinal(
                    update = update,
                    entryTime = System.currentTimeMillis(),
                    completion = completion,
                    expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                    entry = existingTurn,
                )

                AgentTurnJournalStatus.IN_PROGRESS -> QueuedWork.DurableInProgress(
                    update = update,
                    entryTime = System.currentTimeMillis(),
                    completion = completion,
                    expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                    entry = existingTurn,
                )
            }
            return when (
                offerUpdateForCurrent(
                    session,
                    work,
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

            val snapshot = settingsRepository.currentSettingsSnapshot()
            val aiSettings = snapshot.settings.ai
                ?: return@runWhenReady BarrierAdmission.Confirmed
            if (
                !aiSettings.agentEnabled ||
                aiSettings.requiredApiKey().isBlank() ||
                !authorization.matches(aiSettings)
            ) {
                return@runWhenReady BarrierAdmission.Confirmed
            }

            val availabilityBeforeCheck = agentService.availability.value
            if (availabilityBeforeCheck.state.isAgentRecoveryWaitState()) {
                return@runWhenReady BarrierAdmission.WaitingForAgent(
                    observedSequence = availabilityBeforeCheck.sequence,
                    observedSettingsVersion = snapshot.generation,
                )
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
                // The state can change between the first snapshot and the readiness check. Capture it again so the
                // poller waits on the exact sequence that explains this fail-closed result.
                val unavailable = agentService.availability.value
                if (unavailable.state.isAgentRecoveryWaitState()) {
                    return@runWhenReady BarrierAdmission.WaitingForAgent(
                        observedSequence = unavailable.sequence,
                        observedSettingsVersion = snapshot.generation,
                    )
                }
                logger.warn(
                    "AI remains unavailable after the model switch barrier for update {}; preserving its offset for retry.",
                    update.updateId,
                )
                return@runWhenReady BarrierAdmission.Retry
            }

            val ticket = AdmissionTicket(
                agentChatId = aiSettings.agentChatId,
                generation = snapshot.generation,
            )
            val completion = CompletableDeferred<UpdateCompletion>()
            when (
                offerUpdateForCurrent(
                    session,
                    QueuedWork.Authorized(
                        update = update,
                        entryTime = System.currentTimeMillis(),
                        completion = completion,
                        expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                        ticket = ticket,
                    ),
                )
            ) {
                QueueOfferResult.ENQUEUED -> BarrierAdmission.Enqueued(completion)
                QueueOfferResult.FULL -> BarrierAdmission.QueueFull(authorization, ticket)
                QueueOfferResult.NOT_CURRENT -> BarrierAdmission.Confirmed
            }
        }
        return when (admission) {
            BarrierAdmission.Confirmed -> UpdateAdmission.Confirmed
            BarrierAdmission.Retry -> UpdateAdmission.Retry
            is BarrierAdmission.WaitingForAgent -> UpdateAdmission.WaitingForAgent(
                observedSequence = admission.observedSequence,
                observedSettingsVersion = admission.observedSettingsVersion,
            )

            is BarrierAdmission.Enqueued -> UpdateAdmission.Enqueued(admission.completion)
            is BarrierAdmission.QueueFull -> notifyQueueFull(
                session,
                update.updateId,
                admission.authorization,
                admission.ticket
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
        ticket: AdmissionTicket,
        updateId: Long,
        authorization: AuthorizedMessageContext,
        message: Message,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val text = checkNotNull(message.text)
        if (!isWithinAgentTextLimit(text)) {
            logger.warn("Text input for update {} exceeds the local pre-claim limit.", updateId)
            return UpdateCompletion.Retry
        }
        return try {
            if (!cleanContextIfNeeded(session, ticket, authorization)) {
                return UpdateCompletion.Confirmed
            }
            when (sendAuthorizedChatAction(session, ticket, authorization, "typing")) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> Unit
            }
            coroutineScope {
                val typingJob = typingJob(session, ticket, authorization)
                try {
                    runDurableAgentTurn(
                        session = session,
                        ticket = ticket,
                        updateId = updateId,
                        authorization = authorization,
                        chatId = authorization.chatId,
                        replyParameters = ReplyParameters(messageId = message.messageId),
                        expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                        request = DurableAgentRequest.Text(text),
                    )
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
        ticket: AdmissionTicket,
        updateId: Long,
        authorization: AuthorizedMessageContext,
        message: Message,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val voice = checkNotNull(message.voice)
        if (!isWithinAgentTextLimit(message.caption)) {
            logger.warn("Voice caption for update {} exceeds the local pre-claim limit.", updateId)
            return UpdateCompletion.Retry
        }
        val audioData = try {
            // 下载和本地输入校验必须在 claim 前完成：文件不可用时既不会进入 Agent，也不会留下一个
            // 会阻止用户重传的进行中账本记录。
            val fileResponse = when (val result = runWhenAuthorized(session, ticket, authorization) {
                telegramService.getFileForToken(session.token, voice.fileId)
            }) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> result.value
            }
            val filePath = fileResponse.result?.filePath
                ?: throw IllegalStateException("Failed to get file path for voice message")
            when (val result = runWhenAuthorized(session, ticket, authorization) {
                telegramService.downloadFileForToken(session.token, filePath)
            }) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> result.value
            }
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
            if (!cleanContextIfNeeded(session, ticket, authorization)) {
                return UpdateCompletion.Confirmed
            }
            when (sendAuthorizedChatAction(session, ticket, authorization, "typing")) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> Unit
            }
            coroutineScope {
                val typingJob = typingJob(session, ticket, authorization)
                try {
                    runDurableAgentTurn(
                        session = session,
                        ticket = ticket,
                        updateId = updateId,
                        authorization = authorization,
                        chatId = authorization.chatId,
                        replyParameters = ReplyParameters(messageId = message.messageId),
                        expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                        request = DurableAgentRequest.Voice(
                            message.caption,
                            listOf(MediaData(audioData, voice.mimeType ?: "audio/ogg")),
                        ),
                    )
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
     * 本方法在 [AgentService.withReadyService] 的唯一屏障准入中完成授权复核、claim、发送、终态持久化和
     * offset/outbox 提交。作用域内绝不能再次进入 [runWhenAuthorized]，也不能经由委派 [agentService] 发送，
     * 否则模型切换若恰在两次准入之间开始会循环等待。新 IN_PROGRESS claim 后只直接调用一次已就绪底层 Agent；
     * 本进程 owner 捕获到 Agent 异常时会将其显式固化为失败 FINAL；任何没有本地 owner 的进行中记录则会由
     * durable 回放路径静默确认。因此重启和会话轮换不会自动重放模型或工具副作用，同时正常运行中的用户请求
     * 仍会获得既有失败反馈。
     */
    private suspend fun runDurableAgentTurn(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters,
        expectedRetryCheckpointTarget: Long?,
        request: DurableAgentRequest,
    ): UpdateCompletion = agentService.withReadyService { readyAgent ->
        runDurableAgentTurnWithReadyService(
            session = session,
            ticket = ticket,
            authorization = authorization,
            updateId = updateId,
            chatId = chatId,
            replyParameters = replyParameters,
            expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
            readyAgent = readyAgent,
            request = request,
        )
    }

    /** 在已准入的底层 Agent 上完成 durable 回合；此处不允许再次进入模型切换屏障。 */
    private suspend fun runDurableAgentTurnWithReadyService(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters,
        expectedRetryCheckpointTarget: Long?,
        readyAgent: AgentService,
        request: DurableAgentRequest,
    ): UpdateCompletion {
        val key = AgentTurnKey(session.botId, updateId)
        val owner = acquireAgentTurnOwner(key) ?: return UpdateCompletion.Retry
        try {
            if (!isDurableAgentTurnAuthorized(session, ticket, authorization)) {
                return UpdateCompletion.Confirmed
            }
            val claim = withContext(NonCancellable) {
                updatesRepository.claimAgentTurn(session.botId, updateId, chatId, replyParameters)
            }
            return when (claim) {
                AgentTurnClaim.CLAIMED -> {
                    val finalized = try {
                        // 新设置可在 claim 后持久化，但切换会等待当前 ready-agent 作用域退出。已 claim 的
                        // 回合不可安全重放，必须继续使用本次准入取得的底层 Agent 完成，而不能再等待屏障或
                        // 因新代次静默留下 IN_PROGRESS。
                        val reply = request.sendWith(readyAgent).takeIf { it.isNotBlank() }
                        withContext(NonCancellable) {
                            updatesRepository.finalizeAgentTurn(session.botId, updateId, reply)
                        }
                    } catch (e: CancellationException) {
                        // 取消时结果不确定；留下 IN_PROGRESS 使下一次无 owner 静默确认，而不是猜测重放。
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
                    finalized?.let {
                        completeFinalAgentTurn(session, it, expectedRetryCheckpointTarget)
                    } ?: UpdateCompletion.Retry
                }

                is AgentTurnClaim.FINAL ->
                    completeFinalAgentTurn(session, claim.entry, expectedRetryCheckpointTarget)

                is AgentTurnClaim.InProgress -> UpdateCompletion.Retry

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

    /** 单次 durable Agent 回合可发送的输入；文本和语音均只调用一次已就绪底层 Agent。 */
    private sealed interface DurableAgentRequest {
        /** 仅包含文本的 Agent 输入。 */
        data class Text(val text: String) : DurableAgentRequest

        /** 包含语音媒体及可选配文的 Agent 输入。 */
        data class Voice(
            val caption: String?,
            val mediaData: List<MediaData>,
        ) : DurableAgentRequest

        /** 直接在当前已就绪的底层服务上发送，不经由委派服务再次进入模型切换屏障。 */
        suspend fun sendWith(readyAgent: AgentService): String = when (this) {
            is Text -> readyAgent.sendMessage(text, emptyList())
            is Voice -> readyAgent.sendMessage(caption, mediaData)
        }
    }

    /** 静默结算不存在本地 owner 的 durable IN_PROGRESS；绝不降级为 outbox 回复或重放 Agent。 */
    private suspend fun confirmDurableInProgressTurn(
        session: PollingSession,
        entry: AgentTurnJournalEntry,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val key = AgentTurnKey(session.botId, entry.updateId)
        val hasActiveOwner = sessionLock.withLock { agentTurnOwners[key]?.isCompleted == false }
        if (hasActiveOwner) {
            return UpdateCompletion.Retry
        }
        return try {
            val confirmed = withContext(NonCancellable) {
                updatesRepository.confirmInProgressAgentTurnWithoutReply(
                    session.botId,
                    entry.updateId,
                    expectedRetryCheckpointTarget,
                )
            }
            if (confirmed) UpdateCompletion.Persisted else UpdateCompletion.Retry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "In-progress Agent turn {} could not be silently confirmed; category={}",
                entry.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    /** 将已持久化 FINAL 结果写入 outbox 和更新偏移量，成功后尽力清理账本残留。 */
    private suspend fun completeFinalAgentTurn(
        session: PollingSession,
        entry: AgentTurnJournalEntry,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val reply = entry.reply?.let {
            PendingTelegramReply(entry.updateId, entry.chatId, it, entry.replyParameters)
        }
        return try {
            // FINAL 已经持久化。此提交失败时保留 FINAL，下次只会重试此处，绝不会返回 Agent。
            val committed = withContext(NonCancellable) {
                updatesRepository.completeAgentUpdateAtRetryCheckpoint(
                    session.botId,
                    entry.updateId,
                    reply,
                    expectedRetryCheckpointTarget,
                )
            }
            if (committed != RetryCheckpointCommitResult.Committed) {
                return UpdateCompletion.Retry
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
        authorization: AuthorizedMessageContext,
        ticket: AdmissionTicket,
    ): UpdateAdmission {
        logger.warn("Update {} rejected: queue is full.", updateId)
        val notification = try {
            sendAuthorizedMessage(
                session,
                ticket,
                authorization,
                "抱歉，当前处理队列已满（最多同时排队10条消息），请稍后再试。",
                ReplyParameters(messageId = authorization.messageId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Queue full notification request failed for update {}; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateAdmission.Retry
        }
        if (notification is AuthorizedEffect.Confirmed) {
            return UpdateAdmission.Confirmed
        }
        val notificationAccepted = (notification as AuthorizedEffect.Executed).value?.isTelegramAccepted() == true
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
    private fun offerUpdateForCurrent(session: PollingSession, queuedWork: QueuedWork): QueueOfferResult =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (closed || currentSession !== session || !isTokenGenerationCurrent(session)) {
                    QueueOfferResult.NOT_CURRENT
                } else {
                    val result = session.updateChannel.trySend(queuedWork)
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

    /** 从不可变 Telegram 更新提取后续副作用需要复核的授权上下文。 */
    private fun Message.toAuthorizedMessageContext(): AuthorizedMessageContext = AuthorizedMessageContext(
        chatId = chat.id.toString(),
        chatType = chat.type,
        fromId = from?.id?.toString(),
        messageId = messageId,
    )

    private fun confirmedSignal(): CompletableDeferred<UpdateCompletion> =
        CompletableDeferred<UpdateCompletion>().also { it.complete(UpdateCompletion.Confirmed) }

    /** 判断 Telegram 响应是否同时具有成功 HTTP 状态和 API `ok: true` 标记。 */
    private fun TelegramApiResponse.isTelegramAccepted(): Boolean =
        status.isSuccess() && try {
            JsonStructureLimits.parseToJsonElement(Json, body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }

    /** 返回该响应是否明确表明 Telegram 以非限流的永久 `4xx` 拒绝了请求。 */
    private fun TelegramApiResponse.isPermanentTelegramRejection(): Boolean {
        if (status.value == 429) return false
        if (status.value in 400..499) return true
        return try {
            JsonStructureLimits.parseToJsonElement(Json, body)
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
                replyParameters = null,
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
                deliveryAttempts = 0,
                permanentRejectionCount = 0,
            )
        }
    }

    /** 判断当前快照是否是仍可清除引用并重试原文的首个原文片段。 */
    private fun PendingTelegramReply.shouldRetryFirstChunkWithoutReplyParameters(): Boolean =
        deliveryStage == TelegramReplyDeliveryStage.ORIGINAL &&
                nextChunkStart == 0 &&
                replyParameters != null

    /** 清除首片段的引用参数，并把该原文片段恢复为未登记投递的初始状态。 */
    private fun PendingTelegramReply.withoutFirstChunkReplyParameters(): PendingTelegramReply {
        check(shouldRetryFirstChunkWithoutReplyParameters()) {
            "only an original first chunk with reply parameters can retry without them."
        }
        return copy(
            replyParameters = null,
            deliveryAttempts = 0,
            permanentRejectionCount = 0,
        )
    }

    /** 基于一项已登记投递的原文回复，在可重试失败后清除先前的永久拒绝连续计数。 */
    private fun PendingTelegramReply.afterRetryableTelegramFailure(): PendingTelegramReply {
        check(deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
            "only original replies can reset permanent rejections."
        }
        return if (permanentRejectionCount == 0) this else copy(permanentRejectionCount = 0)
    }

    /** 返回当前 durable outbox 片段本次实际要发送的纯文本，绝不改写持久化原文。 */
    private fun PendingTelegramReply.deliveryText(): String = when (deliveryStage) {
        TelegramReplyDeliveryStage.ORIGINAL -> TelegramTextChunks.chunkAt(text, nextChunkStart)
        TelegramReplyDeliveryStage.FALLBACK -> TELEGRAM_REPLY_FALLBACK_MESSAGE
    }

    /** 只有原文第一个片段允许携带对入站消息的引用参数。 */
    private fun PendingTelegramReply.deliveryReplyParameters(): ReplyParameters? =
        replyParameters.takeIf {
            deliveryStage == TelegramReplyDeliveryStage.ORIGINAL && nextChunkStart == 0
        }

    /** 使用入队时获取的授权票据执行命令；测试必须通过完整 [Update] 进入同一准入路径。 */
    private suspend fun handleAuthorizedCommand(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
        text: String,
    ): UpdateCompletion {
        val parts = text.split(Regex("\\s+"), 2)
        return when (parts[0]) {
            "/keep" -> {
                when (runWhenAuthorized(session, ticket, authorization) {
                    session.lastAiReplyAtMillis = System.currentTimeMillis()
                    logger.info("Auto-clean context timer refreshed by keep command for bot {}", session.botId)
                }) {
                    AuthorizedEffect.Confirmed -> UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> UpdateCompletion.Confirmed
                }
            }

            "/reset" -> {
                val reset = when (val result =
                    runWhenAuthorized(session, ticket, authorization) { awaitSuccessfulAgentReset() }) {
                    AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> result.value
                }
                if (!reset) {
                    logger.warn("Session reset failed by command for bot {}", session.botId)
                    return sendAuthorizedCommandReply(
                        session,
                        ticket,
                        authorization,
                        updateId,
                        expectedRetryCheckpointTarget,
                        "会话重置失败，请稍后重试。",
                    )
                }
                when (runWhenAuthorized(session, ticket, authorization) { clearQueue(session) }) {
                    AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> Unit
                }
                when (runWhenAuthorized(session, ticket, authorization) { session.lastAiReplyAtMillis = null }) {
                    AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> Unit
                }
                logger.info("Session reset and queue cleared by command for bot {}", session.botId)
                sendAuthorizedCommandReply(
                    session,
                    ticket,
                    authorization,
                    updateId,
                    expectedRetryCheckpointTarget,
                    "会话已重置，待处理消息已清空。",
                )
            }

            "/model" -> {
                if (parts.size > 1) {
                    val requestedModel = parts[1].trim()
                    try {
                        val selectedModel = when (val result = runWhenAuthorized(session, ticket, authorization) {
                            agentService.availableModels.firstOrNull { model ->
                                model == requestedModel ||
                                        model.removePrefix("models/") == requestedModel.removePrefix("models/")
                            }
                        }) {
                            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                            is AuthorizedEffect.Executed -> result.value
                        } ?: throw IllegalArgumentException("Unsupported model: $requestedModel")
                        val update = when (val result = runWhenAuthorized(session, ticket, authorization) {
                            beforeModelSelectionPersistForTesting?.invoke()
                            persistSelectedModel(selectedModel, ticket.generation)
                        }) {
                            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                            is AuthorizedEffect.Executed -> result.value
                        }
                        val successorAi = update.current.settings.ai ?: return UpdateCompletion.Confirmed
                        val successorTicket = AdmissionTicket(successorAi.agentChatId, update.current.generation)
                        when (runWhenAuthorized(session, successorTicket, authorization) {
                            session.lastAiReplyAtMillis = null
                        }) {
                            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                            is AuthorizedEffect.Executed -> Unit
                        }
                        sendAuthorizedCommandReply(
                            session,
                            successorTicket,
                            authorization,
                            updateId,
                            expectedRetryCheckpointTarget,
                            "已保存模型选择，正在切换模型并重置会话：$selectedModel",
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SettingsGenerationMismatchException) {
                        UpdateCompletion.Confirmed
                    } catch (_: Exception) {
                        sendAuthorizedCommandReply(
                            session,
                            ticket,
                            authorization,
                            updateId,
                            expectedRetryCheckpointTarget,
                            "不支持的模型：$requestedModel\n使用 /model 查看可用列表。",
                        )
                    }
                } else {
                    val modelSnapshot = when (val result = runWhenAuthorizedWithReadyAgent(
                        session,
                        ticket,
                        authorization,
                    ) { readyAgent ->
                        beforeModelRefreshForTesting?.invoke()
                        readyAgent.updateModel()
                    }) {
                        AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                        is AuthorizedEffect.Executed -> result.value
                    }
                    if (modelSnapshot == null) {
                        return sendAuthorizedCommandReply(
                            session,
                            ticket,
                            authorization,
                            updateId,
                            expectedRetryCheckpointTarget,
                            "获取可用模型列表失败，请稍后重试。",
                        )
                    }
                    val list = modelSnapshot.availableModels.joinToString("\n") { model ->
                        if (model == modelSnapshot.currentModel) "✅ $model" else "      $model"
                    }
                    sendAuthorizedCommandReply(
                        session,
                        ticket,
                        authorization,
                        updateId,
                        expectedRetryCheckpointTarget,
                        "当前可用模型列表：\n$list\n\n使用 `/model <模型名称>` 切换模型。",
                    )
                }
            }

            else -> UpdateCompletion.Confirmed
        }
    }

    /** 以授权票据代次执行模型选择 CAS；成功提交后由调用方离开旧屏障再用 successor 票据通知用户。 */
    private fun persistSelectedModel(selectedModel: String, expectedGeneration: Long) =
        settingsRepository.updateSettings(expectedGeneration = expectedGeneration) { settings ->
            val aiSettings = checkNotNull(settings.ai) { "AI configuration is unavailable." }
            settings.copy(ai = aiSettings.copy(selectedModel = selectedModel))
        }

    /**
     * 在仍持有匹配授权票据时把命令回复和源更新偏移量原子写入 outbox。
     *
     * 命令处理不直接进行 Telegram 网络请求，因此长模型列表在中间片段发送失败、进程重启或 token 轮换后仍
     * 会从持久化 cursor 继续；票据或会话失配时不写入旧会话并静默确认。
     */
    private suspend fun sendAuthorizedCommandReply(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
        text: String,
    ): UpdateCompletion {
        val committed = when (val result = runWhenAuthorized(session, ticket, authorization) {
            writeForCurrent(session) {
                updatesRepository.completeAgentUpdateAtRetryCheckpoint(
                    botId = session.botId,
                    updateId = updateId,
                    reply = PendingTelegramReply(
                        updateId = updateId,
                        chatId = authorization.chatId,
                        text = text,
                        replyParameters = ReplyParameters(authorization.messageId),
                    ),
                    expectedRetryTarget = expectedRetryCheckpointTarget,
                )
            }
        }) {
            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
            is AuthorizedEffect.Executed -> result.value
        }
        if (committed == RetryCheckpointCommitResult.Committed) {
            signalOutboxForBot(session.botId)
            return UpdateCompletion.Persisted
        }
        return if (isCurrent(session)) UpdateCompletion.Retry else UpdateCompletion.Confirmed
    }

    private fun typingJob(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
    ): Job = session.scope.launch {
        while (isActive) {
            delay(4000.milliseconds)
            try {
                when (sendAuthorizedChatAction(session, ticket, authorization, "typing")) {
                    AuthorizedEffect.Confirmed -> return@launch
                    is AuthorizedEffect.Executed -> Unit
                }
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

    private data class ContextCleanupResult(
        val intervalMinutes: Int,
        val silent: Boolean,
        val resetSucceeded: Boolean,
    )

    /** 在每次清理、重置和通知前复核票据；`false` 表示授权已失效，调用方应静默确认原消息。 */
    private suspend fun cleanContextIfNeeded(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
    ): Boolean {
        val outcome = when (val result = runWhenAuthorized(session, ticket, authorization) { settings ->
            val aiSettings = checkNotNull(settings.ai)
            val intervalMinutes = aiSettings.autoCleanContextIntervalMinutes
            val lastReplyAt = session.lastAiReplyAtMillis
            if (
                lastReplyAt == null ||
                intervalMinutes <= 0 ||
                System.currentTimeMillis() - lastReplyAt < intervalMinutes.minutes.inWholeMilliseconds
            ) {
                null
            } else {
                ContextCleanupResult(intervalMinutes, aiSettings.silentContextCleanup, awaitSuccessfulAgentReset())
            }
        }) {
            AuthorizedEffect.Confirmed -> return false
            is AuthorizedEffect.Executed -> result.value
        } ?: return true

        if (!outcome.resetSucceeded) {
            logger.warn(
                "Failed to auto-clean AI context after {} minutes without a successful AI reply.",
                outcome.intervalMinutes
            )
            return true
        }
        when (runWhenAuthorized(session, ticket, authorization) { session.lastAiReplyAtMillis = null }) {
            AuthorizedEffect.Confirmed -> return false
            is AuthorizedEffect.Executed -> Unit
        }
        if (!outcome.silent) {
            when (
                sendAuthorizedMessage(
                    session,
                    ticket,
                    authorization,
                    "检测到距离上次对话已超过 ${outcome.intervalMinutes} 分钟，已自动清理上下文。",
                )
            ) {
                AuthorizedEffect.Confirmed -> return false
                is AuthorizedEffect.Executed -> Unit
            }
        }
        logger.info("Auto-cleaned AI context after {} minutes without a successful AI reply.", outcome.intervalMinutes)
        return true
    }

    /**
     * 等待代理重置结束，并以 [Job.isCancelled] 判定成功。
     *
     * [Job.join] 不会传播任务自身的失败；当前消费者或轮询会话被取消时会先取消尚未结束的 reset 任务，再原样
     * 传播取消，避免旧会话在 token 切换或服务关闭后继续执行。代理返回 `null`、抛出普通异常或返回已取消任务
     * 都会返回 `false`。
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

        try {
            resetJob.join()
        } catch (e: CancellationException) {
            resetJob.cancel(e)
            throw e
        }
        return !resetJob.isCancelled
    }

    private suspend fun clearQueue(session: PollingSession) {
        var count = 0
        while (true) {
            val queuedWork = session.updateChannel.tryReceive().getOrNull() ?: break
            val completion = when (queuedWork) {
                is QueuedWork.Authorized -> UpdateCompletion.Confirmed
                is QueuedWork.DurableFinal ->
                    completeFinalAgentTurn(session, queuedWork.entry, queuedWork.expectedRetryCheckpointTarget)

                is QueuedWork.DurableInProgress ->
                    confirmDurableInProgressTurn(session, queuedWork.entry, queuedWork.expectedRetryCheckpointTarget)
            }
            queuedWork.completion.complete(completion)
            count++
        }
        if (count > 0) {
            logger.info("Cleared {} pending updates from current polling session due to reset.", count)
        }
    }

    /** 票据受当前设置、当前会话和共享模型屏障共同保护的执行结果。 */
    private sealed interface AuthorizedEffect<out T> {
        /** 授权票据或会话已失效；调用方必须静默确认，不得执行副作用。 */
        data object Confirmed : AuthorizedEffect<Nothing>

        /** 在同一屏障准入内复核通过并已执行 [value] 对应操作。 */
        data class Executed<T>(val value: T) : AuthorizedEffect<T>
    }

    /**
     * 在已由 [AgentService.withReadyService] 准入的 durable Agent 回合中复核授权。
     *
     * 此方法刻意不进入 [ModelSwitchBarrier]：外层就绪作用域是该回合唯一的屏障准入。设置切换可以在该
     * 作用域开始后持久化新快照，但必须等待该回合退出；所以只在 durable claim 前以当前快照复核代次、AI
     * 配置、原消息身份和会话身份。claim 成功后的回合不可安全重放，必须在同一 ready-agent 作用域内完成。
     * 调用方在 `false` 时不得执行新的 Agent 副作用。
     */
    private fun isDurableAgentTurnAuthorized(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
    ): Boolean {
        val snapshot = settingsRepository.currentSettingsSnapshot()
        val aiSettings = snapshot.settings.ai
        return snapshot.generation == ticket.generation &&
                aiSettings != null &&
                aiSettings.agentEnabled &&
                aiSettings.requiredApiKey().isNotBlank() &&
                aiSettings.agentChatId == ticket.agentChatId &&
                authorization.matches(aiSettings) &&
                isCurrent(session)
    }

    /**
     * 在共享模型屏障内同时复核票据、当前设置和会话后执行一项副作用。
     *
     * 配置 generation、AI 代理身份、原始消息的私聊/聊天/发送者身份、会话或 token 生命周期任一失配时均不调用 [action]，而返回
     * [AuthorizedEffect.Confirmed]。调用方不得把检查移出此方法后在屏障外执行副作用。
     */
    private suspend fun <T> runWhenAuthorized(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        action: suspend (AppSettings) -> T,
    ): AuthorizedEffect<T> = modelSwitchBarrier.runWhenReady {
        val snapshot = settingsRepository.currentSettingsSnapshot()
        val aiSettings = snapshot.settings.ai
        if (
            snapshot.generation != ticket.generation ||
            aiSettings == null ||
            !aiSettings.agentEnabled ||
            aiSettings.requiredApiKey().isBlank() ||
            aiSettings.agentChatId != ticket.agentChatId ||
            !authorization.matches(aiSettings) ||
            !isCurrent(session)
        ) {
            AuthorizedEffect.Confirmed
        } else {
            AuthorizedEffect.Executed(action(snapshot.settings))
        }
    }

    /**
     * 在委派服务的单次就绪准入内复核授权并直接操作已选中的底层 Agent。
     *
     * 此路径用于本身需要 [AgentService.withReadyService] 的操作。若先通过 [runWhenAuthorized] 进入模型屏障，
     * 再递归调用委派服务的就绪准入，设置切换可能夹在两次准入之间并形成互相等待。
     */
    private suspend fun <T> runWhenAuthorizedWithReadyAgent(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        action: suspend (AgentService) -> T,
    ): AuthorizedEffect<T> = agentService.withReadyService { readyAgent ->
        val snapshot = settingsRepository.currentSettingsSnapshot()
        val aiSettings = snapshot.settings.ai
        if (
            snapshot.generation != ticket.generation ||
            aiSettings == null ||
            !aiSettings.agentEnabled ||
            aiSettings.requiredApiKey().isBlank() ||
            aiSettings.agentChatId != ticket.agentChatId ||
            !authorization.matches(aiSettings) ||
            !isCurrent(session)
        ) {
            AuthorizedEffect.Confirmed
        } else {
            AuthorizedEffect.Executed(action(readyAgent))
        }
    }

    /** 在授权仍有效时发送 Telegram 回复。 */
    private suspend fun sendAuthorizedMessage(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        text: String,
        replyParameters: ReplyParameters? = null,
    ): AuthorizedEffect<TelegramApiResponse?> = runWhenAuthorized(session, ticket, authorization) {
        telegramService.sendMessageForToken(session.token, authorization.chatId, text, replyParameters)
    }

    /** 在授权仍有效时向 Telegram 发送聊天动作。 */
    private suspend fun sendAuthorizedChatAction(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        action: String,
    ): AuthorizedEffect<TelegramApiResponse?> = runWhenAuthorized(session, ticket, authorization) {
        telegramService.sendChatActionForToken(session.token, authorization.chatId, action)
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

    /** 在当前 token 生命周期内执行可返回结果的短暂持久化操作；会话切换时返回 `null`。 */
    private fun <T> writeForCurrent(session: PollingSession, write: () -> T): T? =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession !== session || !isTokenGenerationCurrent(session)) {
                    null
                } else {
                    write()
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

    private fun detachAndCancelCurrentSession() {
        val session = sessionLock.withLock { currentSession.also { currentSession = null } } ?: return
        session.updateChannel.close()
        session.consumerResume.close()
        session.outboxSignal.close()
        session.scope.cancel(CancellationException("Message poller stopped."))
    }

    /**
     * 请求停止设置监听及当前轮询会话。
     *
     * 此方法是无等待、可重复的停止准入：返回后不会再安装 token 会话或接纳新队列项，已运行协程会被取消。
     * 调用 [awaitStopped] 或 [closeAndJoin] 才会等待本服务根任务及其所有子协程（包括不可取消收尾）完成。
     */
    internal fun requestStop() {
        val jobToCancel = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            settingsJob.also { settingsJob = null }
        }
        jobToCancel?.cancel()
        sessionLock.withLock {
            pendingAgentReset?.let { pendingReset ->
                // 在同一个临界区内先摘除状态、释放所有等待器并放行屏障，防止取消的 reset 与新会话安装交错。
                pendingAgentReset = null
                pendingReset.initialResetCompletion.complete(false)
                pendingReset.retryCompletion?.complete(false)
                modelSwitchBarrier.complete(pendingReset.barrierGeneration)
            }
        }
        detachAndCancelCurrentSession()
        scopeJob.cancel(CancellationException("Message poller stopped."))
        logger.info("Agent poller stopped.")
    }

    /**
     * 等待此前停止请求完全结束。
     *
     * 等待范围是本服务拥有的根任务及所有子协程；调用方应先调用 [requestStop] 或 [close]，否则活跃轮询会
     * 继续运行，等待不会自行触发停止。重复等待安全，且不会在生命周期或会话锁内执行。
     */
    internal suspend fun awaitStopped() {
        scopeJob.join()
    }

    /** 请求停止并等待本服务拥有的全部协程结束。 */
    internal suspend fun closeAndJoin() {
        requestStop()
        awaitStopped()
    }

    /**
     * 请求停止设置监听及当前轮询会话。
     *
     * 这是 [AutoCloseable] 兼容入口，只负责同步关闭准入和取消，不会等待在途或不可取消工作结束；需要等待时
     * 使用内部的 [closeAndJoin]。
     */
    override fun close() = requestStop()
}

private fun AgentAvailabilityState.isAgentRecoveryWaitState(): Boolean = when (this) {
    AgentAvailabilityState.INITIALIZING,
    AgentAvailabilityState.RETRY_SCHEDULED,
    AgentAvailabilityState.BLOCKED,
        -> true

    AgentAvailabilityState.DISABLED,
    AgentAvailabilityState.READY,
    AgentAvailabilityState.CLOSED,
        -> false
}

private fun Update.chatInfo(): ChatInfo? {
    val chat = message?.chat ?: channelPost?.chat ?: myChatMember?.chat ?: return null
    val title = chat.title ?: chat.username ?: "${chat.firstName ?: ""} ${chat.lastName ?: ""}".trim()
    return ChatInfo(id = chat.id.toString(), title = title, type = chat.type)
}
