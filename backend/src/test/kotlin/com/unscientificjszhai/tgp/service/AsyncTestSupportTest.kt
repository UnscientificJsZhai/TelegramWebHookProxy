package com.unscientificjszhai.tgp.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AsyncTestSupportTest {
    @Test
    fun `eventually waits for an asynchronous assertion to become true`() = runTest {
        var ready = false
        launch {
            delay(40.milliseconds)
            ready = true
        }

        eventually { assertTrue(ready) }
    }

    @Test
    fun `eventually timeout retains the failing assertion`() = runTest {
        val original = AssertionError("queued message unexpectedly reached the agent")

        val failure = assertFailsWith<AssertionError> {
            eventually(100.milliseconds) { throw original }
        }

        assertSame(original, failure.cause)
    }

    @Test
    fun `eventually propagates programming errors fatal errors and cancellation`() = runTest {
        for (original in listOf(
            IllegalStateException("invalid mock configuration"),
            LinkageError("missing test dependency"),
            CancellationException("test cancelled"),
        )) {
            var attempts = 0
            val failure = assertFails {
                eventually {
                    attempts++
                    throw original
                }
            }
            // 协程的堆栈恢复可以复制异常，因此检查类型、内容及是否立即传播。
            assertEquals(original::class, failure::class)
            assertEquals(original.message, failure.message)
            assertEquals(1, attempts)
        }
    }

    @Test
    fun `eventually does not replace an enclosing timeout with an assertion failure`() = runTest {
        val result = withTimeoutOrNull(50.milliseconds) {
            eventually(5.seconds) { throw AssertionError("not ready") }
        }

        assertNull(result)
    }

    @Test
    fun `cleanup timeout is suppressed on the original test failure`() = runTest {
        val original = AssertionError("original test failure")

        val failure = assertFailsWith<AssertionError> {
            withTestCleanup(
                timeout = 100.milliseconds,
                cleanup = { awaitCancellation() },
            ) { throw original }
        }

        assertSame(original, failure)
        assertIs<TimeoutCancellationException>(failure.suppressed.single())
    }

    @Test
    fun `cleanup timeout fails an otherwise successful test`() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTestCleanup(
                timeout = 100.milliseconds,
                cleanup = { awaitCancellation() },
            ) { Unit }
        }
    }

    @Test
    fun `cancelled test still runs suspending cleanup`() = runTest {
        var cleanedUp = false

        val result = withTimeoutOrNull(50.milliseconds) {
            withTestCleanup(cleanup = {
                delay(20.milliseconds)
                cleanedUp = true
            }) { awaitCancellation() }
        }

        assertNull(result)
        assertTrue(cleanedUp)
    }
}
