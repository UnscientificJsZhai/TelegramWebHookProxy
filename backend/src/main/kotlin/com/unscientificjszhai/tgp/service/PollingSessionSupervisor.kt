package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.*
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilityState
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.slf4j.Logger
import java.io.IOException
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 尚未完成的全局 Agent 上下文清除状态；仅在 runtime 的 session lock 下读写。
 *
 * @property barrierGeneration 为本次重置关闭的模型屏障代次。
 * @property source 触发本次重置的生命周期事件。
 * @property initialResetCompletion 首次重置执行的完成信号。
 * @property retryCompletion 当前串行重试的共享完成信号。
 */
private class PendingAgentReset(
    val barrierGeneration: Long,
    val source: AgentResetSource,
    val initialResetCompletion: CompletableDeferred<Boolean> = CompletableDeferred(),
    var retryCompletion: CompletableDeferred<Boolean>? = null,
)

private enum class AgentResetSource {
    TOKEN_ROTATION,
    AUTHENTICATION_FAILURE,
}

private sealed interface SessionReplacement {
    data object NoOp : SessionReplacement
    data object Install : SessionReplacement

    /**
     * 需要先取消旧会话并重置 Agent 的替换操作。
     *
     * @property previous 被新 token 代次替换的旧会话。
     * @property pendingReset 在安装新会话前必须完成的 Agent 重置。
     */
    data class ResetPrevious(
        val previous: PollingSession,
        val pendingReset: PendingAgentReset,
    ) : SessionReplacement

    /**
     * 需要等待既有 Agent 重置收敛的替换操作。
     *
     * @property pendingReset 必须完成或成功重试的既有重置。
     */
    data class AwaitPending(val pendingReset: PendingAgentReset) : SessionReplacement
}

private enum class SessionInstallation {
    INSTALLED,
    INVALID_TOKEN,
    NOT_CURRENT,
}

private sealed interface PollingAttempt {
    data object Succeeded : PollingAttempt
    data object Stopped : PollingAttempt

    /**
     * Telegram API 返回业务失败的轮询结果。
     *
     * @property response 需要按状态码分类处理的 API 响应。
     */
    data class ApiFailure(val response: GetUpdatesResponse) : PollingAttempt
    data object LocalRetry : PollingAttempt

    /**
     * 检查点已持久化、需要等待 Agent 可用性变化的轮询结果。
     *
     * @property observedSequence 已观察到的 Agent 可用性事件序列。
     * @property observedSettingsVersion 已观察到的设置代次。
     */
    data class WaitingForAgent(
        val observedSequence: Long,
        val observedSettingsVersion: Long,
    ) : PollingAttempt
}

/**
 * 监督 token 生命周期和每代 [PollingSession]；poll、consumer、outbox 与 typing 均使用同一 session scope。
 *
 * @param runtime 持有根任务、当前会话和唯一会话锁的共享运行时。
 * @param telegramService 执行 Telegram 轮询请求的服务。
 * @param agentService 提供 Agent 可用性与会话重置的服务。
 * @param settingsRepository 提供 token 更新流、设置代次和生命周期锁的仓储。
 * @param updatesRepository 持久化轮询 offset、检查点、聊天与 Agent journal 的仓储。
 * @param modelSwitchBarrier 在 token 切换或认证失败期间关闭 Agent 准入的共享屏障。
 * @param admissionPolicy 接纳单条 Telegram 更新的策略。
 * @param cleanupCoordinator 执行 Agent 上下文重置的协调器。
 * @param outboxWorker 投递持久化 Telegram 回复的 worker。
 * @param processor 串行消费当前会话更新的处理器。
 * @param logger 记录轮询生命周期与恢复结果的日志器。
 */
internal class PollingSessionSupervisor(
    private val runtime: MessagePollingRuntime,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    private val admissionPolicy: UpdateAdmissionPolicy,
    private val cleanupCoordinator: ContextCleanupCoordinator,
    private val outboxWorker: TelegramReplyOutboxWorker,
    private val processor: AgentTurnProcessor,
    private val logger: Logger,
) {
    private val lifecycleLock = Any()
    private var settingsJob: Job? = null
    private var pendingAgentReset: PendingAgentReset? = null

    /** 执行轮询失败退避的可替换函数。 */
    var retryDelay: suspend (Duration) -> Unit = { delay(it) }

    /** 为本地指数退避生成附加抖动的可替换函数。 */
    var retryJitter: (Duration) -> Duration = { backoff ->
        if (backoff <= Duration.ZERO) {
            Duration.ZERO
        } else {
            Random.nextLong((backoff.inWholeMilliseconds / 5) + 1).milliseconds
        }
    }

    /** 启动唯一的 token 设置监听器；重复调用不会创建额外监听器。 */
    fun start() {
        val started = synchronized(lifecycleLock) {
            if (runtime.closed || settingsJob != null) {
                false
            } else {
                settingsJob = runtime.scope.launch {
                    modelSwitchBarrier.awaitReady()
                    currentCoroutineContext().ensureActive()
                    if (runtime.closed) {
                        return@launch
                    }
                    settingsRepository.telegramTokenUpdateFlow.collect { tokenUpdate ->
                        currentCoroutineContext().ensureActive()
                        if (!runtime.closed) {
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
        if (runtime.closed) {
            return
        }
        val replacement = settingsRepository.withTelegramTokenLifecycleLock {
            runtime.withSessionLock {
                when {
                    runtime.closed || !runtime.isTokenGenerationCurrent(token, generation) -> SessionReplacement.NoOp
                    runtime.currentSession?.let { it.token == token && it.generation == generation } == true -> SessionReplacement.NoOp
                    pendingAgentReset != null -> SessionReplacement.AwaitPending(checkNotNull(pendingAgentReset))
                    runtime.currentSession == null -> SessionReplacement.Install
                    else -> {
                        // 必须在摘除旧会话之前创建屏障：否则新 Bot 的 Agent 请求可能在旧上下文被清除前准入。
                        val pendingReset = PendingAgentReset(
                            barrierGeneration = modelSwitchBarrier.beginExternalSwitch(),
                            source = AgentResetSource.TOKEN_ROTATION,
                        )
                        val previous = checkNotNull(runtime.currentSession)
                        runtime.currentSession = null
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
                if (runtime.closed) {
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
                if (runtime.closed) {
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
     *
     * @param token 待安装代次的 Telegram Bot token。
     * @param generation 待安装的设置代次。
     * @param pendingReset 安装前已成功完成的普通 token 轮换重置。
     * @return 会话已安装、token 无效或代次已经失效的结果。
     */
    private fun installCurrentTokenSession(
        token: String,
        generation: Long,
        pendingReset: PendingAgentReset?,
    ): SessionInstallation {
        val botId = token.botIdFromTelegramToken() ?: return SessionInstallation.INVALID_TOKEN
        val sessionScope = runtime.scope + SupervisorJob(runtime.scope.coroutineContext[Job])
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
            runtime.withSessionLock {
                if (
                    runtime.closed ||
                    !runtime.isTokenGenerationCurrent(token, generation) ||
                    (pendingReset != null && pendingAgentReset !== pendingReset)
                ) {
                    null
                } else {
                    runtime.currentSession = session
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
        processor.start(session)
        session.outboxJob = session.scope.launch { outboxWorker.run(session) }
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
     *
     * @param session 等待恢复事件的当前轮询会话。
     * @param observedSequence 准入时观察到的 Agent 可用性事件序列。
     * @param observedSettingsVersion 准入时观察到的设置代次。
     * @return 应返回主循环重新判断时为 `true`；会话或 Agent 已关闭时为 `false`。
     */
    private suspend fun awaitAgentAvailabilityChange(
        session: PollingSession,
        observedSequence: Long,
        observedSettingsVersion: Long,
    ): Boolean {
        var sequence = observedSequence
        while (runtime.isCurrent(session)) {
            val snapshot = agentService.availability.first { state -> state.sequence != sequence }
            if (!runtime.isCurrent(session)) return false
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
     *
     * @param session 执行本轮请求的当前轮询会话。
     * @return 本轮成功、停止、本地重试、API 失败或等待 Agent 的精确结果。
     */
    private suspend fun pollOnce(session: PollingSession): PollingAttempt {
        if (!runtime.isCurrent(session)) {
            return PollingAttempt.Stopped
        }
        val snapshot = runtime.readForCurrent(session) { updatesRepository.getData(session.botId) }
            ?: return PollingAttempt.Stopped
        var lastStoredId = snapshot.lastUpdateId
        val initialRetryCheckpoint = snapshot.retryCheckpoint
        val resolvingInitialOffset =
            lastStoredId == 0L && initialRetryCheckpoint == null && !session.initialOffsetResolved
        val (targetUpdateId, response) = if (resolvingInitialOffset) {
            val initialResponse = telegramService.getUpdatesForToken(session.token, offset = -1, timeout = 0)
            if (!runtime.isCurrent(session)) return PollingAttempt.Stopped
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
        if (!runtime.isCurrent(session)) {
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
        val responseSnapshot = runtime.readForCurrent(session) { updatesRepository.getData(session.botId) }
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
                    val skipped = runtime.writeForCurrent(session) {
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
                when (val admission = admissionPolicy.enqueueUpdate(session, update, expectedRetryTarget)) {
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
                if (!runtime.saveForCurrent(session) {
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
                        val confirmed = runtime.writeForCurrent(session) {
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
            !runtime.isCurrent(session) -> PollingAttempt.Stopped
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
     *
     * @param session 重试检查点所属的当前轮询会话。
     * @param checkpoint 待调和的持久化重试检查点。
     * @return 未发现 journal、已结算或必须继续重试的结果。
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
                when (processor.completeFinalAgentTurn(session, entry, checkpoint.targetUpdateId)) {
                    UpdateCompletion.Persisted -> DurableRetryReconciliation.Settled
                    UpdateCompletion.Confirmed,
                    UpdateCompletion.Retry,
                        -> DurableRetryReconciliation.Retry
                }

            AgentTurnJournalStatus.IN_PROGRESS ->
                when (processor.confirmDurableInProgressTurn(session, entry, checkpoint.targetUpdateId)) {
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
     *
     * @param session 检查点所属的当前轮询会话。
     * @param targetUpdateId 需要在下一轮精确重试的 Telegram 更新标识。
     * @param waitingForAgent 准入策略观察到的可选 Agent 等待状态。
     * @return 停止、本地重试或等待 Agent 的轮询结果。
     */
    private fun persistLocalRetryCheckpoint(
        session: PollingSession,
        targetUpdateId: Long,
        waitingForAgent: UpdateAdmission.WaitingForAgent? = null,
    ): PollingAttempt = try {
        val currentData = runtime.readForCurrent(session) {
            updatesRepository.getData(session.botId)
        } ?: return PollingAttempt.Stopped
        val expectedTarget = currentData.retryCheckpoint?.targetUpdateId
        if (expectedTarget != null && expectedTarget != targetUpdateId) {
            return PollingAttempt.LocalRetry
        }
        val recorded = runtime.writeForCurrent(session) {
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

    /**
     * 分类 Telegram API 的失败；认证失败会仅在本会话仍当前时终止整套轮询资源。
     *
     * @param session 收到失败响应的当前轮询会话。
     * @param response 待分类的 Telegram `getUpdates` 响应。
     * @return 会话仍可继续轮询时返回 `true`，必须停止时返回 `false`。
     */
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
     *
     * @param session 发生失败的当前轮询会话。
     * @param retryAfter Telegram 建议的最短等待时间。
     * @return 等待结束后会话仍为当前会话时返回 `true`。
     */
    private suspend fun delayAfterFailure(session: PollingSession, retryAfter: Duration? = null): Boolean {
        if (!runtime.isCurrent(session)) {
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
        return runtime.isCurrent(session)
    }

    /**
     * 计算 `1, 2, 4, …, 60` 秒的本地指数退避上限。
     *
     * @param failureCount 当前会话的连续失败次数。
     * @return 截断到 60 秒的指数退避时间。
     */
    private fun localBackoff(failureCount: Int): Duration =
        (1L shl (failureCount - 1).coerceIn(0, 6)).seconds.coerceAtMost(60.seconds)

    /**
     * 原子摘除仍为当前代次的认证失败会话，并在外部屏障代次内清除 Agent 上下文。
     *
     * 认证失败不能由当前已经持久化的设置快照覆盖，因此登记为外部代次。初次重置失败时保留
     * [PendingAgentReset]；后续 token 会话只能在重试成功后安装。认证失败会先取消旧会话，再由服务根 scope
     * 执行可取消的重置；因此关闭根 scope 可以中断悬挂的重置等待，而正常认证失败仍不会等待自身会话终止。
     *
     * @param session 收到认证失败且可能需要终止的当前轮询会话。
     */
    private fun terminateAuthenticationFailedSession(session: PollingSession) {
        val pendingReset = settingsRepository.withTelegramTokenLifecycleLock {
            runtime.withSessionLock {
                if (!runtime.closed && runtime.currentSession === session && runtime.isTokenGenerationCurrent(session)) {
                    // 认证失败也必须在摘除会话前关闭 Agent 准入，避免并发请求使用即将清除的上下文。
                    val pending = PendingAgentReset(
                        barrierGeneration = modelSwitchBarrier.beginExternalSwitch(),
                        source = AgentResetSource.AUTHENTICATION_FAILURE,
                    )
                    runtime.currentSession = null
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
        runtime.scope.launch {
            if (!completeInitialAgentReset(pendingReset)) {
                if (!runtime.closed) {
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
     *
     * @param pendingReset 必须在新会话安装前收敛的 Agent 重置。
     * @return 重置已成功且仍属于当前服务时返回 `true`。
     */
    private suspend fun awaitPendingAgentResetBeforeSession(pendingReset: PendingAgentReset): Boolean {
        if (pendingReset.initialResetCompletion.await()) {
            return pendingReset.source == AgentResetSource.AUTHENTICATION_FAILURE || isPendingAgentResetCurrent(
                pendingReset
            )
        }

        var completedWhileLocked: Boolean? = null
        val lockedRetry = runtime.withSessionLock {
            if (runtime.closed || pendingAgentReset !== pendingReset) {
                completedWhileLocked = !runtime.closed
                null
            } else {
                pendingReset.retryCompletion?.let { existing -> AuthenticationRetry(existing, isOwner = false) }
                    ?: CompletableDeferred<Boolean>().let { completion ->
                        pendingReset.retryCompletion = completion
                        AuthenticationRetry(completion, isOwner = true)
                    }
            }
        }
        completedWhileLocked?.let { return it }
        val retry = checkNotNull(lockedRetry)
        if (!retry.isOwner) {
            return retry.completion.await()
        }

        val resetSucceeded = performAgentReset()
        val shouldReleaseBarrier = runtime.withSessionLock {
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
     *
     * @param pendingReset 当前服务已登记的首次 Agent 重置。
     * @return 重置成功且待处理状态仍有效时返回 `true`。
     */
    private suspend fun completeInitialAgentReset(pendingReset: PendingAgentReset): Boolean {
        val resetSucceeded = performAgentReset()
        val shouldReleaseBarrier = runtime.withSessionLock {
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

    /**
     * 在外部屏障已关闭时执行一次可取消的 Agent 重置，并把所有失败语义转换为可重试的 `false`。
     *
     * @return Agent 上下文重置成功时返回 `true`。
     */
    private suspend fun performAgentReset(): Boolean = try {
        modelSwitchBarrier.awaitInFlightRequests()
        cleanupCoordinator.awaitSuccessfulAgentReset()
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

    /**
     * 判断待处理 Agent 重置仍归当前未关闭服务所有。
     *
     * @param pendingReset 待复核的 Agent 重置状态。
     * @return 服务未关闭且状态仍是当前登记对象时返回 `true`。
     */
    private fun isPendingAgentResetCurrent(pendingReset: PendingAgentReset): Boolean = runtime.withSessionLock {
        !runtime.closed && pendingAgentReset === pendingReset
    }

    /**
     * 当前 token 不可建立轮询会话时结束已成功的普通轮换重置。
     *
     * 空或非法 token 本身不会让新 Bot 接收工作；此处只在它仍是当前代次时释放已经完成的上下文清除屏障，
     * 避免永久阻塞应用中与 Telegram 无关的 Agent 请求。
     *
     * @param pendingReset 已成功但尚未随新会话安装提交的普通轮换重置。
     * @param token 无法建立会话的当前 Telegram token。
     * @param generation 当前设置代次。
     */
    private fun completePendingResetForInvalidToken(
        pendingReset: PendingAgentReset,
        token: String,
        generation: Long,
    ) {
        val shouldReleaseBarrier = settingsRepository.withTelegramTokenLifecycleLock {
            runtime.withSessionLock {
                if (
                    !runtime.closed &&
                    runtime.currentSession == null &&
                    pendingAgentReset === pendingReset &&
                    runtime.isTokenGenerationCurrent(token, generation)
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

    /**
     * 待处理 Agent 重置的串行重试等待器。
     *
     * @property completion 所有等待方共享的重试结果信号。
     * @property isOwner 当前调用方是否负责实际执行本次重试。
     */
    private data class AuthenticationRetry(
        val completion: CompletableDeferred<Boolean>,
        val isOwner: Boolean,
    )

    /** 摘除并取消当前会话；调用方已经先关闭全局准入。 */
    private fun detachAndCancelCurrentSession() {
        val session =
            runtime.withSessionLock { runtime.currentSession.also { runtime.currentSession = null } } ?: return
        session.updateChannel.close()
        session.consumerResume.close()
        session.outboxSignal.close()
        session.scope.cancel(CancellationException("Message poller stopped."))
    }

    private fun confirmedSignal(): CompletableDeferred<UpdateCompletion> =
        CompletableDeferred<UpdateCompletion>().also { it.complete(UpdateCompletion.Confirmed) }

    /**
     * 请求停止设置监听及当前轮询会话。
     *
     * 此方法是无等待、可重复的停止准入：返回后不会再安装 token 会话或接纳新队列项，已运行协程会被取消。
     * 调用 [awaitStopped] 或 [closeAndJoin] 才会等待本服务根任务及其所有子协程（包括不可取消收尾）完成。
     */
    internal fun requestStop() {
        val jobToCancel = synchronized(lifecycleLock) {
            if (runtime.closed) {
                return
            }
            runtime.closed = true
            settingsJob.also { settingsJob = null }
        }
        jobToCancel?.cancel()
        runtime.withSessionLock {
            pendingAgentReset?.let { pendingReset ->
                // 在同一个临界区内先摘除状态、释放所有等待器并放行屏障，防止取消的 reset 与新会话安装交错。
                pendingAgentReset = null
                pendingReset.initialResetCompletion.complete(false)
                pendingReset.retryCompletion?.complete(false)
                modelSwitchBarrier.complete(pendingReset.barrierGeneration)
            }
        }
        detachAndCancelCurrentSession()
        runtime.scopeJob.cancel(CancellationException("Message poller stopped."))
        logger.info("Agent poller stopped.")
    }

    /**
     * 等待此前停止请求完全结束。
     *
     * 等待范围是本服务拥有的根任务及所有子协程；调用方应先调用 [requestStop] 或 [MessagePoller.close]，否则活跃轮询会
     * 继续运行，等待不会自行触发停止。重复等待安全，且不会在生命周期或会话锁内执行。
     */
    internal suspend fun awaitStopped() {
        runtime.scopeJob.join()
    }

    /** 请求停止并等待本服务拥有的全部协程结束。 */
    internal suspend fun closeAndJoin() {
        requestStop()
        awaitStopped()
    }

}

/**
 * 从 Telegram 更新提取可持久化的聊天摘要。
 *
 * @receiver 待检查的 Telegram 更新。
 * @return 更新携带聊天时返回聊天摘要，否则返回 `null`。
 */
private fun Update.chatInfo(): ChatInfo? {
    val chat = message?.chat ?: channelPost?.chat ?: myChatMember?.chat ?: return null
    val title = chat.title ?: chat.username ?: "${chat.firstName ?: ""} ${chat.lastName ?: ""}".trim()
    return ChatInfo(id = chat.id.toString(), title = title, type = chat.type)
}
