package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AIProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.slf4j.Logger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToLong
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 与具体 AI 提供商无关的单候选恢复状态机。
 *
 * [T] 是委派层不可变目标，[C] 是持有独立网络和 MCP 资源的候选。控制器只通过回调创建、初始化、
 * 原子发布和关闭候选，因此不依赖 Gemini、OpenAI 或 Dagger 组件细节。
 */
internal class AgentRecoveryController<T : Any, C : Any>(
    parentScope: CoroutineScope,
    private val logger: Logger,
    private val createCandidate: suspend (T) -> C,
    private val initializeCandidate: suspend (C) -> AgentInitializationResult,
    private val publishCandidate: (T, C) -> Boolean,
    private val closeCandidate: (C) -> Job?,
    private val prepareFirstAttempt: suspend (T) -> Unit = {},
    private val retryDelay: suspend (Duration) -> Unit = { delay(it) },
    private val jitter: (Duration) -> Duration = ::defaultRecoveryJitter,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cleanupPermits: Semaphore = Semaphore(MAX_INCOMPLETE_CLEANUPS),
) {
    private companion object {
        const val MAX_INCOMPLETE_CLEANUPS = 2
        val LOW_FREQUENCY_RETRY_FLOOR = 30.seconds
        val CAPPED_LOG_INTERVAL = 1.hours
        val BACKOFF = listOf(
            2.seconds,
            4.seconds,
            8.seconds,
            16.seconds,
            30.seconds,
            1.minutes,
            2.minutes,
            5.minutes,
        )
    }

    private data class Request<T>(
        val target: T,
        val provider: AIProvider,
        val settingsVersion: Long,
        val epoch: Long,
        val firstAttempt: FirstAttemptCompletion,
    )

    private class FirstAttemptCompletion(private val callback: () -> Unit) {
        private val completed = AtomicBoolean(false)
        fun complete() {
            if (completed.compareAndSet(false, true)) callback()
        }
    }

    private data class FailureSignature(
        val kind: AgentFailureKind,
        val status: Int?,
    )

    private val lock = Any()
    private val initializationMutex = Mutex()
    private val cleanupLock = Any()
    private val cleanupJobs = mutableSetOf<Job>()
    private val controllerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + controllerJob)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _availability = MutableStateFlow(
        AgentAvailabilitySnapshot(
            state = AgentAvailabilityState.DISABLED,
            sequence = 0,
            settingsVersion = -1,
        ),
    )
    val availability: StateFlow<AgentAvailabilitySnapshot> = _availability.asStateFlow()

    private var closed = false
    private var epoch = 0L
    private var activeRequest: Request<T>? = null
    private var recoveryJob: Job? = null
    private var failureCount = 0
    private var failureStartedAtMillis: Long? = null
    private var lastCappedSignature: FailureSignature? = null
    private var lastCappedLogAtMillis: Long = Long.MIN_VALUE
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeJob: Job? = null

    /**
     * 抢占旧目标并立即启动新目标的首个候选。
     *
     * [resetFailures] 只应在提供商、密钥、基础地址或代理身份变化时为 `true`；技能和会话配置变化会创建
     * 新候选，但可保留已有上游退避计数。
     */
    fun replaceTarget(
        target: T,
        provider: AIProvider,
        settingsVersion: Long,
        resetFailures: Boolean,
        onFirstAttemptFinished: () -> Unit,
    ) {
        val firstAttempt = FirstAttemptCompletion(onFirstAttemptFinished)
        var supersededJob: Job? = null
        val start = synchronized(lock) {
            if (closed) {
                null
            } else {
                supersededJob = recoveryJob
                if (resetFailures) resetFailuresLocked()
                val request = Request(target, provider, settingsVersion, ++epoch, firstAttempt)
                activeRequest = request
                transitionLocked(
                    state = AgentAvailabilityState.INITIALIZING,
                    request = request,
                    attempt = failureCount + 1,
                )
                scope.launch(start = CoroutineStart.LAZY) { recover(request) }.also { job ->
                    recoveryJob = job
                    job.invokeOnCompletion {
                        request.firstAttempt.complete()
                        synchronized(lock) {
                            if (recoveryJob === job) recoveryJob = null
                        }
                    }
                }
            }
        }
        if (start == null) {
            firstAttempt.complete()
            return
        }
        supersededJob?.cancel(CancellationException("Agent recovery target was superseded."))
        start.start()
    }

    /** 禁用当前目标并唤醒所有 availability 等待者。 */
    fun disable(settingsVersion: Long, onFirstAttemptFinished: () -> Unit = {}) {
        var activeJob: Job? = null
        synchronized(lock) {
            if (!closed) {
                activeJob = recoveryJob
                recoveryJob = null
                activeRequest = null
                ++epoch
                resetFailuresLocked()
                transitionLocked(AgentAvailabilityState.DISABLED, settingsVersion = settingsVersion)
            }
        }
        activeJob?.cancel(CancellationException("Agent recovery was disabled."))
        onFirstAttemptFinished()
    }

    /** 将确定的配置故障标为阻塞，不创建计时重试。 */
    fun block(
        provider: AIProvider,
        settingsVersion: Long,
        failure: AgentFailure,
        onFirstAttemptFinished: () -> Unit = {},
    ) {
        require(failure.disposition == RecoveryDisposition.WAIT_FOR_CONFIGURATION)
        var activeJob: Job? = null
        synchronized(lock) {
            if (!closed) {
                activeJob = recoveryJob
                recoveryJob = null
                activeRequest = null
                ++epoch
                failureCount = (failureCount + 1).coerceAtLeast(1)
                failureStartedAtMillis = failureStartedAtMillis ?: monotonicMillis()
                transitionLocked(
                    state = AgentAvailabilityState.BLOCKED,
                    provider = provider,
                    settingsVersion = settingsVersion,
                    attempt = failureCount,
                    failure = failure,
                )
            }
        }
        activeJob?.cancel(CancellationException("Agent recovery was blocked by configuration."))
        onFirstAttemptFinished()
    }

    private suspend fun recover(request: Request<T>) {
        var firstAttemptPending = true
        while (currentCoroutineContext().isActive && isCurrent(request)) {
            var candidate: C? = null
            var permitHeld = false
            var published = false
            try {
                val initializationResult = try {
                    cleanupPermits.acquire()
                    permitHeld = true
                    initializationMutex.withLock {
                        ensureCurrent(request)
                        if (firstAttemptPending) {
                            prepareFirstAttempt(request.target)
                        }
                        ensureCurrent(request)
                        val createdCandidate = createCandidate(request.target)
                        candidate = createdCandidate
                        initializeCandidate(createdCandidate)
                    }
                } catch (e: TimeoutCancellationException) {
                    if (!currentCoroutineContext().isActive) throw e
                    AgentInitializationResult.Failed(AgentFailure.classify(e))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    AgentInitializationResult.Failed(AgentFailure.classify(e))
                } finally {
                    if (firstAttemptPending) {
                        firstAttemptPending = false
                        request.firstAttempt.complete()
                    }
                }

                when (initializationResult) {
                    AgentInitializationResult.Ready -> {
                        val readyCandidate = checkNotNull(candidate)
                        if (isCurrent(request) && publishCandidate(request.target, readyCandidate)) {
                            published = true
                            candidate = null
                            cleanupPermits.release()
                            permitHeld = false
                            val success = synchronized(lock) {
                                if (!isCurrentLocked(request)) {
                                    false
                                } else {
                                    val attempts = failureCount + 1
                                    val failureDuration = failureStartedAtMillis
                                        ?.let { (monotonicMillis() - it).coerceAtLeast(0) }
                                        ?: 0
                                    transitionLocked(
                                        state = AgentAvailabilityState.READY,
                                        request = request,
                                        attempt = attempts,
                                        recoveryDurationMillis = failureDuration,
                                    )
                                    resetFailuresLocked()
                                    true
                                }
                            }
                            if (success) return
                        }
                        return
                    }

                    is AgentInitializationResult.Failed -> {
                        val failedCandidate = candidate
                        candidate = null
                        if (failedCandidate != null) {
                            trackCandidateCleanup(failedCandidate) { cleanupPermits.release() }
                            permitHeld = false
                        } else if (permitHeld) {
                            cleanupPermits.release()
                            permitHeld = false
                        }
                        if (!recordFailureAndWait(request, initializationResult.failure)) return
                    }
                }

                // A retry timer completed and the same target still owns the state machine.
                synchronized(lock) {
                    if (isCurrentLocked(request)) {
                        transitionLocked(
                            state = AgentAvailabilityState.INITIALIZING,
                            request = request,
                            attempt = failureCount + 1,
                        )
                    }
                }
            } finally {
                val abandonedCandidate = candidate
                if (!published && abandonedCandidate != null) {
                    trackCandidateCleanup(abandonedCandidate) { cleanupPermits.release() }
                    permitHeld = false
                }
                if (permitHeld) cleanupPermits.release()
            }
        }
    }

    /** 返回 `true` 表示定时等待已结束且应创建下一个候选。 */
    private suspend fun recordFailureAndWait(request: Request<T>, failure: AgentFailure): Boolean {
        val retryDuration = synchronized(lock) {
            if (!isCurrentLocked(request)) return false
            failureCount++
            failureStartedAtMillis = failureStartedAtMillis ?: monotonicMillis()
            when (failure.disposition) {
                RecoveryDisposition.WAIT_FOR_CONFIGURATION,
                RecoveryDisposition.DO_NOT_RETRY,
                    -> {
                    transitionLocked(
                        state = AgentAvailabilityState.BLOCKED,
                        request = request,
                        attempt = failureCount,
                        failure = failure,
                    )
                    return false
                }

                RecoveryDisposition.RETRY,
                RecoveryDisposition.RETRY_LOW_FREQUENCY,
                    -> {
                    val base = BACKOFF[(failureCount - 1).coerceAtMost(BACKOFF.lastIndex)].let { duration ->
                        if (failure.disposition == RecoveryDisposition.RETRY_LOW_FREQUENCY) {
                            maxOf(duration, LOW_FREQUENCY_RETRY_FLOOR)
                        } else {
                            duration
                        }
                    }
                    val jittered = jitter(base).coerceAtLeast(Duration.ZERO).let { duration ->
                        if (failure.disposition == RecoveryDisposition.RETRY_LOW_FREQUENCY) {
                            maxOf(duration, LOW_FREQUENCY_RETRY_FLOOR)
                        } else {
                            duration
                        }
                    }
                    val actual = maxOf(jittered, failure.retryAfter ?: Duration.ZERO)
                    transitionLocked(
                        state = AgentAvailabilityState.RETRY_SCHEDULED,
                        request = request,
                        attempt = failureCount,
                        failure = failure,
                        nextAttemptAtMillis = monotonicMillis().saturatingAdd(actual.inWholeMilliseconds),
                    )
                    actual
                }
            }
        }
        retryDelay(retryDuration)
        return isCurrent(request)
    }

    /**
     * 启动候选关闭并继续持有其清理许可，直到关闭任务实际结束。
     *
     * 许可在候选创建前获取，所以旧目标取消与新目标启动交错时也不可能出现第三个未完成候选清理。
     */
    private fun trackCandidateCleanup(candidate: C, releasePermit: () -> Unit) {
        val cleanup = try {
            closeCandidate(candidate)
        } catch (_: Exception) {
            null
        }
        if (cleanup == null) {
            releasePermit()
            return
        }
        synchronized(cleanupLock) { cleanupJobs.add(cleanup) }
        closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                cleanup.join()
            } catch (_: CancellationException) {
                // The provider owns the real cleanup task; cancellation is already a terminal cleanup result.
            } finally {
                synchronized(cleanupLock) { cleanupJobs.remove(cleanup) }
                releasePermit()
            }
        }
    }

    private fun ensureCurrent(request: Request<T>) {
        if (!isCurrent(request)) throw CancellationException("Agent recovery target is stale.")
    }

    private fun isCurrent(request: Request<T>): Boolean = synchronized(lock) { isCurrentLocked(request) }

    private fun isCurrentLocked(request: Request<T>): Boolean =
        !closed && activeRequest === request && epoch == request.epoch

    private fun resetFailuresLocked() {
        failureCount = 0
        failureStartedAtMillis = null
        lastCappedSignature = null
        lastCappedLogAtMillis = Long.MIN_VALUE
    }

    private fun transitionLocked(
        state: AgentAvailabilityState,
        request: Request<T>? = null,
        provider: AIProvider? = request?.provider,
        settingsVersion: Long = request?.settingsVersion ?: _availability.value.settingsVersion,
        attempt: Int = 0,
        failure: AgentFailure? = null,
        nextAttemptAtMillis: Long? = null,
        recoveryDurationMillis: Long? = null,
    ) {
        if (closed && state != AgentAvailabilityState.CLOSED) return
        val snapshot = AgentAvailabilitySnapshot(
            state = state,
            sequence = _availability.value.sequence + 1,
            settingsVersion = settingsVersion,
            provider = provider,
            attempt = attempt,
            failure = failure,
            nextAttemptAtMillis = nextAttemptAtMillis,
        )
        _availability.value = snapshot
        logTransition(snapshot, recoveryDurationMillis)
    }

    /** 每个转换恰好记录一条安全日志；封顶后的同类重复转换降为 debug，并至少每小时恢复一次 info。 */
    private fun logTransition(snapshot: AgentAvailabilitySnapshot, recoveryDurationMillis: Long?) {
        val now = monotonicMillis()
        val capped = snapshot.attempt > BACKOFF.size
        val signature = snapshot.failure?.let { FailureSignature(it.kind, it.httpStatus) }
        val detailed = when {
            snapshot.state == AgentAvailabilityState.READY ||
                    snapshot.state == AgentAvailabilityState.DISABLED ||
                    snapshot.state == AgentAvailabilityState.CLOSED -> true

            !capped -> true
            signature == null -> false
            signature != lastCappedSignature || now.saturatingElapsedSince(lastCappedLogAtMillis) >= CAPPED_LOG_INTERVAL.inWholeMilliseconds -> {
                lastCappedSignature = signature
                lastCappedLogAtMillis = now
                true
            }

            else -> false
        }
        val arguments = arrayOf<Any>(
            snapshot.state,
            snapshot.provider ?: "none",
            snapshot.settingsVersion,
            snapshot.attempt,
            snapshot.failure?.kind ?: "none",
            snapshot.failure?.httpStatus ?: "none",
            snapshot.nextAttemptAtMillis ?: "none",
            recoveryDurationMillis ?: "none",
        )
        if (detailed) {
            logger.info(
                "Agent availability transition; state={} provider={} settingsVersion={} attempt={} failureKind={} httpStatus={} nextAttemptAtMillis={} recoveryDurationMillis={}",
                *arguments,
            )
        } else {
            logger.debug(
                "Agent availability transition; state={} provider={} settingsVersion={} attempt={} failureKind={} httpStatus={} nextAttemptAtMillis={} recoveryDurationMillis={}",
                *arguments,
            )
        }
    }

    /** 终态关闭计时器、初始化候选和所有失败候选清理。 */
    fun close(): Job {
        val (completion, activeJob) = synchronized(lock) {
            closeCompletion?.let { return closeWaiter(it) }
            val newCompletion = CompletableDeferred<Unit>()
            closeCompletion = newCompletion
            closed = true
            ++epoch
            activeRequest = null
            val active = recoveryJob
            recoveryJob = null
            transitionLocked(
                state = AgentAvailabilityState.CLOSED,
                provider = _availability.value.provider,
                settingsVersion = _availability.value.settingsVersion,
            )
            newCompletion to active
        }
        activeJob?.cancel(CancellationException("Agent recovery controller closed."))
        closingScope.launch {
            withContext(NonCancellable) {
                try {
                    activeJob?.join()
                    controllerJob.cancelAndJoin()
                    drainCleanupJobs()
                } finally {
                    completion.complete(Unit)
                }
            }
        }
        return closeWaiter(completion)
    }

    private fun closeWaiter(completion: CompletableDeferred<Unit>): Job = synchronized(lock) {
        if (closeJob == null || closeJob?.isCancelled == true) {
            closeJob = closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { completion.await() }
            }
        }
        closeJob!!
    }

    private suspend fun drainCleanupJobs() {
        while (true) {
            val jobs = synchronized(cleanupLock) {
                cleanupJobs.removeAll { it.isCompleted }
                cleanupJobs.toList()
            }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }
}

private fun defaultRecoveryJitter(backoff: Duration): Duration {
    if (backoff <= Duration.ZERO) return Duration.ZERO
    return (backoff.inWholeMilliseconds * Random.nextDouble(0.5, 1.5)).roundToLong().milliseconds
}

private fun Long.saturatingAdd(other: Long): Long =
    if (other > 0 && this > Long.MAX_VALUE - other) Long.MAX_VALUE else this + other

private fun Long.saturatingElapsedSince(earlier: Long): Long = when {
    earlier == Long.MIN_VALUE -> Long.MAX_VALUE
    this >= earlier -> this - earlier
    else -> 0
}
