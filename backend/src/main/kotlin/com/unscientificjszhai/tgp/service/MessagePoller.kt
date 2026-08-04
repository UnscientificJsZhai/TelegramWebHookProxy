package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.repository.botIdFromTelegramToken
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AudioTranscriptionFailedException
import com.unscientificjszhai.tgp.service.ai.agent.AudioTranscriptionTooLargeException
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.http.isSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.random.Random

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

    @Volatile
    private var closed = false

    private var settingsJob: Job? = null
    private var currentSession: PollingSession? = null
    private var pendingAuthenticationReset: PendingAuthenticationReset? = null
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
        val outboxSignal: Channel<Unit>,
        var pollJob: Job? = null,
        var consumerJob: Job? = null,
        var outboxJob: Job? = null,
        var lastAiReplyAtMillis: Long? = null,
        var consecutivePollingFailures: Int = 0,
        var initialOffsetResolved: Boolean = false,
    )

    /**
     * 认证失败后必须成功清除的 Agent 上下文状态。
     *
     * 实例仅在 [sessionLock] 保护下读写。初次重置失败时保留外部屏障代次；下一次 token 会话安装会复用
     * 该实例并在安装前重试，防止新 bot 继承旧 bot 的上下文。
     */
    private class PendingAuthenticationReset(
        val generation: Long,
        val initialResetCompletion: CompletableDeferred<Boolean> = CompletableDeferred(),
        var retryCompletion: CompletableDeferred<Boolean>? = null,
    )

    private data class QueuedUpdate(
        val update: Update,
        val entryTime: Long,
        val completion: CompletableDeferred<UpdateCompletion>,
        val persistAgentResult: Boolean,
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
        val sameSession = sessionLock.withLock {
            currentSession?.let { it.token == token && it.generation == generation } == true
        }
        if (sameSession) {
            return
        }

        if (!awaitAuthenticationResetBeforeSession()) {
            logger.warn(
                "Refusing to start polling session at token generation {} until authentication reset succeeds.",
                generation
            )
            return
        }

        val previous = sessionLock.withLock {
            currentSession.also { currentSession = null }
        }
        if (previous != null) {
            previous.updateChannel.close()
            previous.scope.cancel()
            previous.scope.coroutineContext[Job]?.join()
            // AgentService 的会话是全局的；token 切换时必须显式清除，避免 A 的上下文泄漏给 B。
            if (!awaitSuccessfulAgentReset()) {
                logger.warn("Failed to reset agent session while switching polling session; continuing with new bot session.")
            }
            logger.info("Cancelled polling session for bot {} at generation {}", previous.botId, previous.generation)
        }

        val botId = token.botIdFromTelegramToken() ?: run {
            logger.info("Agent poller paused due to empty or invalid token.")
            return
        }
        val sessionScope = scope + SupervisorJob(scope.coroutineContext[Job])
        val session = PollingSession(
            token = token,
            botId = botId,
            generation = generation,
            scope = sessionScope,
            updateChannel = Channel(capacity = 10),
            outboxSignal = Channel(capacity = Channel.CONFLATED),
        )
        val installed = synchronized(lifecycleLock) {
            if (closed) {
                false
            } else {
                sessionLock.withLock {
                    currentSession = session
                }
                true
            }
        }
        if (!installed) {
            sessionScope.cancel()
            return
        }
        session.consumerJob = session.scope.launch { consumeQueue(session) }
        session.outboxJob = session.scope.launch { consumeOutbox(session) }
        session.pollJob = session.scope.launch { runPolling(session) }
        logger.info("Started polling session for bot {} at generation {}", botId, generation)
    }

    private suspend fun runPolling(session: PollingSession) {
        var retryOffset: Long? = null
        while (currentCoroutineContext().isActive) {
            try {
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
                if (e is SocketTimeoutException || e.cause is SocketTimeoutException) {
                    logger.warn(
                        "Polling request timed out for bot {} at generation {} ({}).",
                        session.botId,
                        session.generation,
                        e::class.simpleName,
                    )
                } else {
                    logger.warn(
                        "Polling request failed for bot {} at generation {} ({}).",
                        session.botId,
                        session.generation,
                        e::class.simpleName,
                    )
                }
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
                when (val admission = enqueueUpdate(session, update, persistAgentResult = true)) {
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
                    "Failed to admit update {}; preserving its offset for retry ({}).",
                    update.updateId,
                    e::class.simpleName,
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
                    "Telegram authentication failed for bot {} at generation {} with HTTP {}: {}. Polling session will stop.",
                    session.botId,
                    session.generation,
                    response.errorCode,
                    response.description ?: "no description",
                )
                terminateAuthenticationFailedSession(session)
                false
            }

            409 -> {
                logger.error(
                    "Telegram getUpdates conflict for bot {} at generation {}: another getUpdates consumer exists ({}).",
                    session.botId,
                    session.generation,
                    response.description ?: "no description",
                )
                delayAfterFailure(session)
            }

            429 -> {
                val retryAfter = response.parameters?.retryAfter?.takeIf { it > 0 }?.seconds
                logger.warn(
                    "Telegram rate limited bot {} at generation {} (retry_after={}): {}.",
                    session.botId,
                    session.generation,
                    retryAfter?.inWholeSeconds ?: "ignored",
                    response.description ?: "no description",
                )
                delayAfterFailure(session, retryAfter)
            }

            else -> {
                logger.warn(
                    "Telegram getUpdates failed for bot {} at generation {} with API error {}: {}.",
                    session.botId,
                    session.generation,
                    response.errorCode ?: "unknown",
                    response.description ?: "no description",
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
     * 认证失败不能由当前已经持久化的设置快照覆盖，因此登记为外部代次。初次重置失败时保留代次和
     * [PendingAuthenticationReset]；后续 token 会话只能在重试成功后安装。会话 scope 的取消会取消调用
     * 本方法的轮询协程，故清理必须置于 [NonCancellable] 中；不会等待该 scope，以免等待自身造成死锁。
     */
    private suspend fun terminateAuthenticationFailedSession(session: PollingSession) {
        val authenticationGeneration = modelSwitchBarrier.beginExternalSwitch()
        val pendingReset = PendingAuthenticationReset(authenticationGeneration)
        val shouldReset = settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (!closed && currentSession === session && isTokenGenerationCurrent(session)) {
                    currentSession = null
                    session.updateChannel.close()
                    pendingAuthenticationReset = pendingReset
                    true
                } else {
                    false
                }
            }
        }
        if (!shouldReset) {
            modelSwitchBarrier.cancel(authenticationGeneration)
            return
        }

        withContext(NonCancellable) {
            try {
                session.scope.cancel(CancellationException("Telegram authentication failed"))
                modelSwitchBarrier.awaitInFlightRequests()
                val resetSucceeded = attemptAuthenticationAgentReset()
                val shouldReleaseBarrier = sessionLock.withLock {
                    if (pendingAuthenticationReset === pendingReset && resetSucceeded) {
                        pendingAuthenticationReset = null
                        true
                    } else {
                        false
                    }
                }
                pendingReset.initialResetCompletion.complete(resetSucceeded)
                if (shouldReleaseBarrier) {
                    modelSwitchBarrier.complete(authenticationGeneration)
                } else if (!resetSucceeded) {
                    logger.warn(
                        "Agent session reset failed after Telegram authentication failure; blocking new polling sessions until a retry succeeds.",
                    )
                }
            } catch (e: Exception) {
                // NonCancellable 区域中的意外失败也必须通知等待的新 token 会话，并保持 fail-closed 屏障。
                pendingReset.initialResetCompletion.complete(false)
                logger.warn(
                    "Authentication reset cleanup failed before completion; new polling sessions remain blocked.",
                    e
                )
            }
        }
    }

    /**
     * 等待认证失败的初次重置完成，并在失败时由即将安装的新 token 会话执行一次串行重试。
     *
     * 返回 `false` 时保留认证外部代次；调用方不得安装会话。每次失败重试都会清除其等待器，以便后续 token
     * 生命周期事件可以再次尝试恢复。服务关闭时会结束所有等待器并释放代次，因为之后不会再处理消息。
     */
    private suspend fun awaitAuthenticationResetBeforeSession(): Boolean {
        val pendingReset = sessionLock.withLock { pendingAuthenticationReset } ?: return true
        if (pendingReset.initialResetCompletion.await()) {
            return true
        }

        val retry = sessionLock.withLock {
            if (closed || pendingAuthenticationReset !== pendingReset) {
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

        val resetSucceeded = withContext(NonCancellable) {
            modelSwitchBarrier.awaitInFlightRequests()
            attemptAuthenticationAgentReset()
        }
        val shouldReleaseBarrier = sessionLock.withLock {
            when {
                pendingAuthenticationReset !== pendingReset -> false
                resetSucceeded -> {
                    pendingAuthenticationReset = null
                    true
                }

                else -> {
                    pendingReset.retryCompletion = null
                    false
                }
            }
        }
        retry.completion.complete(resetSucceeded && shouldReleaseBarrier)
        if (shouldReleaseBarrier) {
            modelSwitchBarrier.complete(pendingReset.generation)
        } else {
            logger.warn("Authentication reset retry failed; polling session remains blocked.")
        }
        return retry.completion.await()
    }

    /** 执行一次认证失败后的 Agent 重置，并将所有失败语义降级为可重试结果。 */
    private suspend fun attemptAuthenticationAgentReset(): Boolean = try {
        awaitSuccessfulAgentReset()
    } catch (e: CancellationException) {
        logger.warn("Agent session reset was cancelled after Telegram authentication failure.", e)
        false
    } catch (e: Exception) {
        logger.warn("Failed to reset agent session after Telegram authentication failure.", e)
        false
    }

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
                    processUpdate(session, queuedUpdate.update, queuedUpdate.persistAgentResult)
                }
            } catch (_: TimeoutCancellationException) {
                handleProcessingTimeout(session, queuedUpdate.update)
                UpdateCompletion.Retry
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error processing update ${queuedUpdate.update.updateId}", e)
                UpdateCompletion.Retry
            }

            currentCoroutineContext().ensureActive()
            if (isCurrent(session)) {
                queuedUpdate.completion.complete(completion)
            }
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

    /** 读取并尝试投递一项当前会话可见的 outbox 记录。 */
    private suspend fun deliverNextPendingReply(session: PollingSession): OutboxDelivery {
        val reply = try {
            readForCurrent(session) { updatesRepository.getPendingTelegramReplies(session.botId).firstOrNull() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to read Telegram outbox for bot {}; retrying later.", session.botId, e)
            return OutboxDelivery.RETRY
        } ?: return if (isCurrent(session)) OutboxDelivery.EMPTY else OutboxDelivery.RETRY

        val accepted = try {
            ensureCurrent(session)
            telegramService.sendMessageForToken(session.token, reply.chatId, reply.text, reply.replyParameters)
                .isTelegramAccepted()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Telegram outbox send failed for update {} of bot {} ({}); retrying later.",
                reply.updateId,
                session.botId,
                e::class.simpleName,
            )
            false
        }
        if (!accepted) {
            logger.warn(
                "Telegram rejected outbox reply for update {} of bot {}; retrying later.",
                reply.updateId,
                session.botId
            )
            return OutboxDelivery.RETRY
        }

        val removed = try {
            saveForCurrent(session) {
                updatesRepository.deletePendingTelegramReply(session.botId, reply.updateId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to acknowledge delivered Telegram outbox reply {}; retaining it.", reply.updateId, e)
            false
        }
        return if (removed) OutboxDelivery.DELIVERED else OutboxDelivery.RETRY
    }

    private suspend fun processUpdate(
        session: PollingSession,
        update: Update,
        persistAgentResult: Boolean,
    ): UpdateCompletion {
        val message = update.message ?: return UpdateCompletion.Confirmed
        val chatId = message.chat.id.toString()
        return when {
            message.text?.startsWith("/") == true -> {
                handleCommand(session, chatId, message.text, message.messageId)
                UpdateCompletion.Confirmed
            }

            message.voice != null -> completeVoiceAgentUpdate(
                session,
                update.updateId,
                chatId,
                message,
                persistAgentResult
            )

            message.text != null -> completeTextAgentUpdate(
                session,
                update.updateId,
                chatId,
                message,
                persistAgentResult
            )

            else -> UpdateCompletion.Confirmed
        }
    }

    private suspend fun handleProcessingTimeout(session: PollingSession, update: Update) {
        val message = update.message ?: return
        logger.warn("Update ${update.updateId} processing timed out after 10 minutes.")
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
            logger.warn("Failed to send timeout notification", e)
        }
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
        activeSession()?.let { enqueueUpdate(it, update, persistAgentResult = true) }
    }

    /**
     * 仅供测试把更新放入当前队列而不触发 durable Agent 提交协议。
     *
     * 此方法只用于构造队列满、会话取消等测试前置条件；生产代码必须调用 [handleUpdate] 或由轮询路径
     * 调用 [enqueueUpdate]，两者都会持久化成功的 Agent 回合及其 outbox。当前没有有效会话时不产生副作用。
     *
     * @param update 要加入测试队列的 Telegram 更新；不含可处理消息时不会入队。
     */
    internal suspend fun enqueueUpdateForTesting(update: Update) {
        activeSession()?.let { enqueueUpdate(it, update, persistAgentResult = false) }
    }

    private suspend fun enqueueUpdate(
        session: PollingSession,
        update: Update,
        persistAgentResult: Boolean,
    ): UpdateAdmission {
        if (!isCurrent(session)) {
            return UpdateAdmission.Confirmed
        }
        val message = update.message ?: return UpdateAdmission.Confirmed
        if (message.text == null && message.voice == null) {
            return UpdateAdmission.Confirmed
        }
        val chatId = message.chat.id.toString()
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
                    "AI availability check failed for update {}; preserving its offset for retry ({}).",
                    update.updateId,
                    e::class.simpleName,
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
                    QueuedUpdate(update, System.currentTimeMillis(), completion, persistAgentResult),
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
        persistAgentResult: Boolean,
    ): UpdateCompletion {
        val reply = try {
            cleanContextIfNeeded(session, chatId)
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    agentService.sendMessage(checkNotNull(message.text))
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to handle AI message", e)
            sendProcessingError(session, chatId, "AI 处理消息时出错，请稍后重试。", message.messageId)
            return UpdateCompletion.Retry
        }

        return persistAgentResult(
            session,
            updateId,
            reply.takeIf { it.isNotBlank() }?.let {
                PendingTelegramReply(updateId, chatId, it, ReplyParameters(messageId = message.messageId))
            },
            persistAgentResult,
        )
    }

    /** 完成一条语音 Agent 回合，并把成功生成的回复原子写入 outbox 与偏移量。 */
    private suspend fun completeVoiceAgentUpdate(
        session: PollingSession,
        updateId: Long,
        chatId: String,
        message: Message,
        persistAgentResult: Boolean,
    ): UpdateCompletion {
        val voice = checkNotNull(message.voice)
        val reply = try {
            cleanContextIfNeeded(session, chatId)
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    val filePath = telegramService.getFileForToken(session.token, voice.fileId).result?.filePath
                        ?: throw IllegalStateException("Failed to get file path for voice message")
                    ensureCurrent(session)
                    val audioData = telegramService.downloadFileForToken(session.token, filePath)
                    ensureCurrent(session)
                    agentService.sendMessage(
                        message.caption,
                        listOf(MediaData(audioData, voice.mimeType ?: "audio/ogg")),
                    )
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioTranscriptionTooLargeException) {
            logger.warn("Voice message exceeded the local transcription size limit.", e)
            sendProcessingError(
                session,
                chatId,
                "语音文件过大，最大支持 24 MiB，请发送更短的语音消息。",
                message.messageId
            )
            return UpdateCompletion.Retry
        } catch (e: AudioTranscriptionFailedException) {
            logger.warn("Voice transcription failed.", e)
            sendProcessingError(session, chatId, "语音转写失败，请稍后重试。", message.messageId)
            return UpdateCompletion.Retry
        } catch (e: Exception) {
            logger.error("Failed to handle voice message", e)
            sendProcessingError(session, chatId, "处理语音消息时出错，请稍后重试。", message.messageId)
            return UpdateCompletion.Retry
        }

        return persistAgentResult(
            session,
            updateId,
            reply.takeIf { it.isNotBlank() }?.let {
                PendingTelegramReply(updateId, chatId, it, ReplyParameters(messageId = message.messageId))
            },
            persistAgentResult,
        )
    }

    /** 将 Agent 的完成事实与 outbox 一并提交；写入失败时绝不确认该更新。 */
    private suspend fun persistAgentResult(
        session: PollingSession,
        updateId: Long,
        reply: PendingTelegramReply?,
        persistAgentResult: Boolean,
    ): UpdateCompletion = try {
        if (!persistAgentResult) {
            reply?.let { sendMessageForSession(session, it.chatId, it.text, it.replyParameters) }
            return UpdateCompletion.Confirmed
        }
        // Agent 正常返回后，即使 token 已切换，也必须按捕获的 bot 标识留下完成事实；否则未来切回
        // 该 bot 时会重跑已发生副作用的回合。仓储按 bot 隔离且单次提交包含 offset/outbox，因此旧会话
        // 不会污染新 bot；旧会话本身也不会获得删除 outbox 的权限。
        withContext(NonCancellable) {
            updatesRepository.completeAgentUpdate(session.botId, updateId, reply)
        }
        if (reply != null) {
            signalOutboxForBot(session.botId)
            if (isCurrent(session)) {
                session.lastAiReplyAtMillis = System.currentTimeMillis()
            }
        }
        UpdateCompletion.Persisted
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.error("Failed to persist completed Agent update {}; preserving its offset for retry.", updateId, e)
        UpdateCompletion.Retry
    }

    /** 唤醒当前持有同一 bot 标识的 outbox worker；不同 bot 的轮换绝不共享投递信号。 */
    private fun signalOutboxForBot(botId: String) {
        sessionLock.withLock {
            currentSession
                ?.takeIf { !closed && it.botId == botId }
                ?.outboxSignal
                ?.trySend(Unit)
        }
    }

    /** 向用户发送处理失败提示；该提示沿用既有即时反馈语义，主回复仍不会绕过 outbox。 */
    private suspend fun sendProcessingError(session: PollingSession, chatId: String, text: String, messageId: Long) {
        try {
            sendMessageForSession(session, chatId, text, ReplyParameters(messageId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to send processing error notification", e)
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
                "Queue full notification request failed for update {} ({}).",
                updateId,
                e::class.simpleName,
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
                    logger.info("Auto-clean context timer refreshed by keep command in chat {}", chatId)
                }
            }

            "/reset" -> {
                ensureCurrent(session)
                if (!awaitSuccessfulAgentReset()) {
                    ensureCurrent(session)
                    sendMessageForSession(session, chatId, "会话重置失败，请稍后重试。", ReplyParameters(messageId))
                    logger.warn("Session reset failed by command in chat {}", chatId)
                    return
                }
                ensureCurrent(session)
                clearQueue(session)
                ensureCurrent(session)
                session.lastAiReplyAtMillis = null
                sendMessageForSession(session, chatId, "会话已重置，待处理消息已清空。", ReplyParameters(messageId))
                logger.info("Session reset and queue cleared by command in chat {}", chatId)
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
        return !(
                currentAi.provider != expectedAi.provider ||
                        currentAi.agentEnabled != expectedAi.agentEnabled ||
                        currentAi.selectedModel != expectedAi.selectedModel ||
                        proxy != expected.proxy ||
                        currentAi.httpToolSettings != expectedAi.httpToolSettings ||
                        currentAi.mcpServers != expectedAi.mcpServers
                ) && when (currentAi.provider) {
            AIProvider.GEMINI -> currentAi.geminiApiKey == expectedAi.geminiApiKey
            AIProvider.OPENAI ->
                currentAi.openAiApiKey == expectedAi.openAiApiKey &&
                        currentAi.openAiBaseUrl == expectedAi.openAiBaseUrl
        }
    }

    /**
     * 使用当前活跃会话将文本消息交给 AI 代理。
     *
     * 当前没有有效会话时不会产生副作用；所有 Telegram 调用都使用会话开始时捕获的 token。
     *
     * @param chatId 接收回复的聊天标识，不能为空。
     * @param text 要发送给 AI 的文本，允许为空字符串。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
    @Suppress("unused")
    suspend fun handleAiMessage(chatId: String, text: String, messageId: Long) {
        activeSession()?.let { handleAiMessage(it, chatId, text, messageId) }
    }

    private suspend fun handleAiMessage(session: PollingSession, chatId: String, text: String, messageId: Long) {
        cleanContextIfNeeded(session, chatId)
        try {
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    val reply = agentService.sendMessage(text)
                    typingJob.cancel()
                    if (reply.isNotBlank()) {
                        val response = sendMessageForSession(session, chatId, reply, ReplyParameters(messageId))
                        if (response.status.isSuccess() && isCurrent(session)) {
                            session.lastAiReplyAtMillis = System.currentTimeMillis()
                        }
                    }
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to handle AI message", e)
            sendMessageForSession(session, chatId, "AI 处理消息时出错，请稍后重试。", ReplyParameters(messageId))
        }
    }

    /**
     * 使用当前活跃会话下载并处理语音消息。
     *
     * 当前没有有效会话时不会产生副作用；文件下载、聊天动作、正常回复和错误提示均使用会话
     * 开始时捕获的 token。
     *
     * @param chatId 接收回复的聊天标识，不能为空。
     * @param voice 要处理的 Telegram 语音文件，必须包含有效文件标识。
     * @param caption 语音消息的可选说明文字；没有说明时为 `null`。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
    @Suppress("unused")
    suspend fun handleVoiceMessage(chatId: String, voice: Voice, caption: String?, messageId: Long) {
        activeSession()?.let { handleVoiceMessage(it, chatId, voice, caption, messageId) }
    }

    private suspend fun handleVoiceMessage(
        session: PollingSession,
        chatId: String,
        voice: Voice,
        caption: String?,
        messageId: Long,
    ) {
        cleanContextIfNeeded(session, chatId)
        try {
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    val filePath = telegramService.getFileForToken(session.token, voice.fileId).result?.filePath
                        ?: throw IllegalStateException("Failed to get file path for voice message")
                    ensureCurrent(session)
                    val audioData = telegramService.downloadFileForToken(session.token, filePath)
                    ensureCurrent(session)
                    val reply =
                        agentService.sendMessage(caption, listOf(MediaData(audioData, voice.mimeType ?: "audio/ogg")))
                    typingJob.cancel()
                    if (reply.isNotBlank()) {
                        val response = sendMessageForSession(session, chatId, reply, ReplyParameters(messageId))
                        if (response.status.isSuccess() && isCurrent(session)) {
                            session.lastAiReplyAtMillis = System.currentTimeMillis()
                        }
                    }
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioTranscriptionTooLargeException) {
            logger.warn("Voice message exceeded the local transcription size limit.", e)
            sendMessageForSession(
                session,
                chatId,
                "语音文件过大，最大支持 24 MiB，请发送更短的语音消息。",
                ReplyParameters(messageId),
            )
        } catch (e: AudioTranscriptionFailedException) {
            logger.warn("Voice transcription failed.", e)
            sendMessageForSession(session, chatId, "语音转写失败，请稍后重试。", ReplyParameters(messageId))
        } catch (e: Exception) {
            logger.error("Failed to handle voice message", e)
            sendMessageForSession(session, chatId, "处理语音消息时出错，请稍后重试。", ReplyParameters(messageId))
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
                logger.warn("Failed to send typing action", e)
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
            logger.warn("Failed to start agent session reset", e)
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
        settingsRepository.telegramTokenUpdateFlow.value.let { tokenUpdate ->
            tokenUpdate.token == session.token && tokenUpdate.generation == session.generation
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
            pendingAuthenticationReset.also { pendingAuthenticationReset = null }
        }
        pendingReset?.initialResetCompletion?.complete(false)
        pendingReset?.retryCompletion?.complete(false)
        pendingReset?.let { modelSwitchBarrier.complete(it.generation) }
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
