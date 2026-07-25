package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelSwitchBarrierTest {
    @Test
    fun `completing a generation releases every older pending generation`() = runTest {
        val barrier = ModelSwitchBarrier()
        barrier.beginSwitch()
        val coveredGeneration = barrier.beginSwitch()
        val newerGeneration = barrier.beginSwitch()
        val waitingRequest = async { barrier.awaitReady() }

        runCurrent()
        assertFalse(waitingRequest.isCompleted)

        barrier.completeThrough(coveredGeneration)
        runCurrent()

        assertFalse(waitingRequest.isCompleted)
        assertTrue(barrier.isSwitching)

        barrier.complete(newerGeneration)
        waitingRequest.await()

        assertFalse(barrier.isSwitching)
    }

    @Test
    fun `completion of an older generation does not release waiters for a newer one`() = runTest {
        val barrier = ModelSwitchBarrier()
        val firstGeneration = barrier.beginSwitch()
        val waitingRequest = async { barrier.awaitReady() }

        runCurrent()
        assertFalse(waitingRequest.isCompleted)

        val secondGeneration = barrier.beginSwitch()
        barrier.complete(firstGeneration)
        runCurrent()

        assertFalse(waitingRequest.isCompleted)
        assertTrue(barrier.isSwitching)

        barrier.complete(secondGeneration)
        waitingRequest.await()
        assertFalse(barrier.isSwitching)
    }

    @Test
    fun `cancelling a failed newer write restores the older pending generation`() = runTest {
        val barrier = ModelSwitchBarrier()
        val firstGeneration = barrier.beginSwitch()
        val secondGeneration = barrier.beginSwitch()
        val waitingRequest = async { barrier.awaitReady() }

        barrier.cancel(secondGeneration)
        runCurrent()
        assertFalse(waitingRequest.isCompleted)

        barrier.complete(firstGeneration)
        waitingRequest.await()
    }

    @Test
    fun `a switch waits for requests admitted before its generation`() = runTest {
        val barrier = ModelSwitchBarrier()
        val requestStarted = CompletableDeferred<Unit>()
        val allowRequestToFinish = CompletableDeferred<Unit>()
        val request = async {
            barrier.runWhenReady {
                requestStarted.complete(Unit)
                allowRequestToFinish.await()
            }
        }
        requestStarted.await()

        barrier.beginSwitch()
        val drained = async { barrier.awaitInFlightRequests() }
        runCurrent()
        assertFalse(drained.isCompleted)

        allowRequestToFinish.complete(Unit)
        request.await()
        drained.await()
    }
}
