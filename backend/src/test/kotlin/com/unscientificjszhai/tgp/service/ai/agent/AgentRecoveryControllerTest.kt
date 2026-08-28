package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AIProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class AgentRecoveryControllerTest {
    private data class Candidate(val id: Int)

    @Test
    fun `uses complete capped backoff sequence before publishing a fresh candidate`() = runBlocking {
        val delays = mutableListOf<Duration>()
        val closed = mutableListOf<Int>()
        var created = 0
        var published: Int? = null
        val controller = controller(
            create = { Candidate(++created) },
            initialize = { candidate ->
                if (candidate.id <= 8) failed(AgentFailureKind.NETWORK) else AgentInitializationResult.Ready
            },
            publish = { candidate -> published = candidate.id; true },
            close = { candidate -> closed += candidate.id; completedJob() },
            retryDelay = { delays += it },
        )

        controller.replaceTarget("target", AIProvider.OPENAI, 7, resetFailures = true) {}
        val ready = withTimeout(5.seconds) {
            controller.availability.first { it.state == AgentAvailabilityState.READY }
        }

        assertEquals(
            listOf(2, 4, 8, 16, 30, 60, 120, 300).map { it.seconds },
            delays,
        )
        assertEquals(9, created)
        assertEquals((1..8).toList(), closed.sorted())
        assertEquals(9, published)
        assertEquals(9, ready.attempt)
        controller.close().join()
    }

    @Test
    fun `uses the greater of jittered backoff and retry after`() = runBlocking {
        val delays = mutableListOf<Duration>()
        var attempts = 0
        val controller = controller(
            create = { Candidate(++attempts) },
            initialize = {
                if (attempts == 1) {
                    AgentInitializationResult.Failed(
                        AgentFailure(
                            kind = AgentFailureKind.RATE_LIMITED,
                            disposition = RecoveryDisposition.RETRY,
                            httpStatus = 429,
                            retryAfter = 20.seconds,
                        ),
                    )
                } else {
                    AgentInitializationResult.Ready
                }
            },
            retryDelay = { delays += it },
        )

        controller.replaceTarget("target", AIProvider.GEMINI, 1, resetFailures = true) {}
        withTimeout(5.seconds) { controller.availability.first { it.state == AgentAvailabilityState.READY } }

        assertEquals(listOf(20.seconds), delays)
        controller.close().join()
    }

    @Test
    fun `invalid response uses low frequency retry floor`() = runBlocking {
        val delays = mutableListOf<Duration>()
        var attempts = 0
        val controller = controller(
            create = { Candidate(++attempts) },
            initialize = {
                if (attempts == 1) {
                    AgentInitializationResult.Failed(
                        AgentFailure(
                            AgentFailureKind.INVALID_RESPONSE,
                            RecoveryDisposition.RETRY_LOW_FREQUENCY,
                        ),
                    )
                } else {
                    AgentInitializationResult.Ready
                }
            },
            retryDelay = { delays += it },
        )

        controller.replaceTarget("target", AIProvider.OPENAI, 1, resetFailures = true) {}
        withTimeout(5.seconds) { controller.availability.first { it.state == AgentAvailabilityState.READY } }

        assertEquals(listOf(30.seconds), delays)
        controller.close().join()
    }

    @Test
    fun `configuration failure remains blocked until a target event`() = runBlocking {
        val delays = mutableListOf<Duration>()
        var attempts = 0
        val controller = controller(
            create = { Candidate(++attempts) },
            initialize = {
                if (attempts == 1) {
                    AgentInitializationResult.Failed(
                        AgentFailure(
                            AgentFailureKind.AUTHENTICATION,
                            RecoveryDisposition.WAIT_FOR_CONFIGURATION,
                            httpStatus = 401,
                        ),
                    )
                } else {
                    AgentInitializationResult.Ready
                }
            },
            retryDelay = { delays += it },
        )

        controller.replaceTarget("first", AIProvider.OPENAI, 1, resetFailures = true) {}
        val blocked = withTimeout(5.seconds) {
            controller.availability.first { it.state == AgentAvailabilityState.BLOCKED }
        }
        assertEquals(1, blocked.attempt)
        assertEquals(1, attempts)
        assertTrue(delays.isEmpty())

        controller.replaceTarget("second", AIProvider.OPENAI, 2, resetFailures = false) {}
        val ready = withTimeout(5.seconds) {
            controller.availability.first { it.state == AgentAvailabilityState.READY }
        }
        assertEquals(2, attempts)
        assertEquals(2, ready.attempt)
        controller.close().join()
    }

    @Test
    fun `successful recovery clears failures for a later same provider target`() = runBlocking {
        var attempts = 0
        val controller = controller(
            create = { Candidate(++attempts) },
            initialize = {
                if (attempts == 1) failed(AgentFailureKind.NETWORK) else AgentInitializationResult.Ready
            },
        )

        controller.replaceTarget("first", AIProvider.OPENAI, 1, resetFailures = true) {}
        val firstReady = withTimeout(5.seconds) {
            controller.availability.first { it.state == AgentAvailabilityState.READY }
        }
        assertEquals(2, firstReady.attempt)

        val observed = firstReady.sequence
        controller.replaceTarget("second", AIProvider.OPENAI, 2, resetFailures = false) {}
        val secondReady = withTimeout(5.seconds) {
            controller.availability.first {
                it.sequence > observed && it.state == AgentAvailabilityState.READY
            }
        }
        assertEquals(1, secondReady.attempt)
        controller.close().join()
    }

    @Test
    fun `superseded non cooperative candidate cannot publish and initialization stays serialized`() = runBlocking {
        val oldGate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val published = mutableListOf<String>()
        val closed = mutableListOf<Int>()
        var created = 0
        val controller = controller(
            create = { Candidate(++created) },
            initialize = { candidate ->
                val count = active.incrementAndGet()
                maximumActive.updateAndGet { maxOf(it, count) }
                try {
                    if (candidate.id == 1) {
                        started.complete(Unit)
                        withContext(NonCancellable) { oldGate.await() }
                    }
                    AgentInitializationResult.Ready
                } finally {
                    active.decrementAndGet()
                }
            },
            publishWithTarget = { target, _ -> published += target; true },
            close = { candidate -> closed += candidate.id; completedJob() },
        )

        controller.replaceTarget("old", AIProvider.GEMINI, 1, resetFailures = true) {}
        started.await()
        controller.replaceTarget("new", AIProvider.OPENAI, 2, resetFailures = true) {}
        oldGate.complete(Unit)
        withTimeout(5.seconds) { controller.availability.first { it.state == AgentAvailabilityState.READY } }

        assertEquals(listOf("new"), published)
        assertTrue(1 in closed)
        assertEquals(1, maximumActive.get())
        controller.close().join()
    }

    @Test
    fun `two unfinished failed cleanups pause creation of a third candidate`() = runBlocking {
        val cleanupJobs = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val createdSignal = Channel<Unit>(Channel.UNLIMITED)
        val cleanupStarted = Channel<Int>(Channel.UNLIMITED)
        val thirdAcquireStarted = CompletableDeferred<Unit>()
        val cleanupPermits = ObservableSemaphore(Semaphore(2), thirdAcquireStarted)
        var created = 0
        val controller = controller(
            create = {
                Candidate(++created).also { createdSignal.trySend(Unit) }
            },
            initialize = { candidate ->
                if (candidate.id <= 2) failed(AgentFailureKind.NETWORK) else AgentInitializationResult.Ready
            },
            close = { candidate ->
                CompletableDeferred<Unit>().also {
                    cleanupJobs[candidate.id] = it
                    check(cleanupStarted.trySend(candidate.id).isSuccess)
                }
            },
            cleanupPermits = cleanupPermits,
        )

        controller.replaceTarget("target", AIProvider.OPENAI, 1, resetFailures = true) {}
        repeat(2) { withTimeout(5.seconds) { createdSignal.receive() } }
        assertEquals(setOf(1, 2), buildSet {
            repeat(2) { add(withTimeout(5.seconds) { cleanupStarted.receive() }) }
        })
        withTimeout(5.seconds) { thirdAcquireStarted.await() }
        assertEquals(2, created)

        cleanupJobs.getValue(1).complete(Unit)
        withTimeout(5.seconds) { createdSignal.receive() }
        withTimeout(5.seconds) { controller.availability.first { it.state == AgentAvailabilityState.READY } }
        assertEquals(3, created)

        cleanupJobs.getValue(2).complete(Unit)
        controller.close().join()
    }

    @Test
    fun `close cancels a scheduled retry and prevents future creation or publication`() = runBlocking {
        var created = 0
        var published = 0
        val controller = controller(
            create = { Candidate(++created) },
            initialize = { failed(AgentFailureKind.NETWORK) },
            publish = { published++; true },
            retryDelay = { awaitCancellation() },
        )

        controller.replaceTarget("target", AIProvider.GEMINI, 1, resetFailures = true) {}
        withTimeout(5.seconds) {
            controller.availability.first { it.state == AgentAvailabilityState.RETRY_SCHEDULED }
        }
        controller.close().join()

        assertEquals(1, created)
        assertEquals(0, published)
        assertEquals(AgentAvailabilityState.CLOSED, controller.availability.value.state)
    }

    private fun CoroutineScope.controller(
        create: suspend (String) -> Candidate,
        initialize: suspend (Candidate) -> AgentInitializationResult,
        publish: (Candidate) -> Boolean = { true },
        publishWithTarget: ((String, Candidate) -> Boolean)? = null,
        close: (Candidate) -> Job? = { completedJob() },
        retryDelay: suspend (Duration) -> Unit = {},
        cleanupPermits: Semaphore = Semaphore(2),
    ): AgentRecoveryController<String, Candidate> = AgentRecoveryController(
        parentScope = this,
        logger = LoggerFactory.getLogger("AgentRecoveryControllerTest"),
        createCandidate = create,
        initializeCandidate = initialize,
        publishCandidate = publishWithTarget ?: { _, candidate -> publish(candidate) },
        closeCandidate = close,
        cleanupPermits = cleanupPermits,
        retryDelay = retryDelay,
        jitter = { it },
        monotonicMillis = { 1_000L },
    )

    private fun failed(kind: AgentFailureKind): AgentInitializationResult.Failed =
        AgentInitializationResult.Failed(AgentFailure(kind, RecoveryDisposition.RETRY))

    private class ObservableSemaphore(
        private val delegate: Semaphore,
        private val thirdAcquireStarted: CompletableDeferred<Unit>,
    ) : Semaphore by delegate {
        private val acquireCalls = AtomicInteger()

        override suspend fun acquire() {
            // 第三次获取发生在两个清理许可均未释放时；先发布进入点，再让真实信号量执行挂起。
            if (acquireCalls.incrementAndGet() == 3) {
                thirdAcquireStarted.complete(Unit)
            }
            delegate.acquire()
        }
    }

    private fun completedJob(): Job = CompletableDeferred(Unit).also { it.complete(Unit) }
}
