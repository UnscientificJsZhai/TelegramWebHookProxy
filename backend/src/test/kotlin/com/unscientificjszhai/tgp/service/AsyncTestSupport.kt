package com.unscientificjszhai.tgp.service

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** 仅重试尚未满足的断言；超时保留最后一次失败，配置错误和取消直接传播。 */
internal suspend fun eventually(timeout: Duration = 3.seconds, assertion: () -> Unit) {
    var lastFailure: AssertionError? = null
    val completed = withTimeoutOrNull(timeout) {
        while (true) {
            try {
                assertion()
                return@withTimeoutOrNull true
            } catch (failure: AssertionError) {
                lastFailure = failure
            }
            delay(20.milliseconds)
        }
    }
    if (completed != true) {
        throw AssertionError("Condition was not met within $timeout", lastFailure)
    }
}

/** 即使测试已取消也执行有界清理；清理失败不能覆盖测试本身的失败。 */
internal suspend fun <T> withTestCleanup(
    timeout: Duration = 5.seconds,
    cleanup: suspend () -> Unit,
    block: suspend () -> T,
): T {
    var testFailure: Throwable? = null
    try {
        return block()
    } catch (failure: Throwable) {
        testFailure = failure
        throw failure
    } finally {
        try {
            withContext(NonCancellable) {
                withTimeout(timeout) { cleanup() }
            }
        } catch (cleanupFailure: Throwable) {
            val failure = testFailure ?: throw cleanupFailure
            if (failure !== cleanupFailure) failure.addSuppressed(cleanupFailure)
        }
    }
}
