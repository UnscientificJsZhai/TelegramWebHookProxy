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
/**
 * 模型切换屏障代次与请求协调的测试设计。
 */
class ModelSwitchBarrierTest {
    /**
     * 验证完成新代次时释放较早等待者的设计。
     *
     * 验证完成一个代次会释放其自身及所有更早待处理代次。
     */
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

    /**
     * 验证较早代次完成不会误释放较新等待者的设计。
     *
     * 验证较新代次仍会保持等待状态。
     */
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

    /**
     * 验证取消失败写入对应代次的恢复设计。
     *
     * 验证取消较新代次后，较早待处理代次仍可等待完成。
     */
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

    /**
     * 验证设置流完成其代次时不会提前释放认证等外部生命周期代次。
     */
    @Test
    fun `completing a later settings generation keeps an earlier external generation blocked`() = runTest {
        val barrier = ModelSwitchBarrier()
        val authenticationGeneration = barrier.beginExternalSwitch()
        val settingsGeneration = barrier.beginSwitch()
        val waitingRequest = async { barrier.awaitReady() }

        runCurrent()
        assertFalse(waitingRequest.isCompleted)

        barrier.completeSettingsThrough(settingsGeneration)
        runCurrent()

        assertTrue(barrier.isSwitching)
        assertFalse(waitingRequest.isCompleted)

        barrier.complete(authenticationGeneration)
        waitingRequest.await()
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证模型切换等待先前请求的设计。
     *
     * 验证切换会等待在其代次前已获准的请求结束。
     */
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
