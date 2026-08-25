package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 可用于恢复决策和脱敏日志的 Agent 初始化失败分类。 */
enum class AgentFailureKind {
    NETWORK,
    TIMEOUT,
    RATE_LIMITED,
    AUTHENTICATION,
    UPSTREAM_HTTP,
    CONFIGURATION,
    INVALID_RESPONSE,
    EMPTY_MODEL_LIST,
    UNKNOWN,
}

/** 初始化失败后的统一恢复动作。 */
enum class RecoveryDisposition {
    /** 使用普通指数退避重新创建一个候选。 */
    RETRY,

    /** 使用不短于低频下限的指数退避重新创建一个候选。 */
    RETRY_LOW_FREQUENCY,

    /** 不启动计时器，只在配置或目标变化后重新尝试。 */
    WAIT_FOR_CONFIGURATION,

    /** 当前实例不应再尝试恢复。 */
    DO_NOT_RETRY,
}

/**
 * 不含响应正文、请求地址、凭据或任意提供商异常文本的初始化失败快照。
 */
data class AgentFailure(
    val kind: AgentFailureKind,
    val disposition: RecoveryDisposition,
    val httpStatus: Int? = null,
    val retryAfter: Duration? = null,
) {
    init {
        require(httpStatus == null || httpStatus in 100..599) { "HTTP status must be valid." }
        require(retryAfter == null || (retryAfter.isFinite() && !retryAfter.isNegative())) {
            "Retry-After must be a finite non-negative duration."
        }
    }

    internal companion object {
        fun classify(error: Throwable): AgentFailure = when (error) {
            is AgentUpstreamHttpException -> error.toAgentFailure()
            is AgentEmptyModelListException -> AgentFailure(
                AgentFailureKind.EMPTY_MODEL_LIST,
                RecoveryDisposition.RETRY_LOW_FREQUENCY,
            )

            is AgentInvalidResponseException,
            is UpstreamResponseTooLargeException,
                -> AgentFailure(
                AgentFailureKind.INVALID_RESPONSE,
                RecoveryDisposition.RETRY_LOW_FREQUENCY,
            )

            is TimeoutCancellationException,
            is SocketTimeoutException,
            is InterruptedIOException,
                -> AgentFailure(AgentFailureKind.TIMEOUT, RecoveryDisposition.RETRY)

            is IOException -> AgentFailure(AgentFailureKind.NETWORK, RecoveryDisposition.RETRY)
            is IllegalArgumentException -> AgentFailure(
                AgentFailureKind.CONFIGURATION,
                RecoveryDisposition.WAIT_FOR_CONFIGURATION,
            )

            else -> AgentFailure(AgentFailureKind.UNKNOWN, RecoveryDisposition.RETRY_LOW_FREQUENCY)
        }

        private fun AgentUpstreamHttpException.toAgentFailure(): AgentFailure = when (statusCode) {
            429 -> AgentFailure(
                kind = AgentFailureKind.RATE_LIMITED,
                disposition = RecoveryDisposition.RETRY,
                httpStatus = statusCode,
                retryAfter = retryAfter,
            )

            401, 403 -> AgentFailure(
                kind = AgentFailureKind.AUTHENTICATION,
                disposition = RecoveryDisposition.WAIT_FOR_CONFIGURATION,
                httpStatus = statusCode,
            )

            408, 409, 425,
            in 500..599,
                -> AgentFailure(
                kind = AgentFailureKind.UPSTREAM_HTTP,
                disposition = RecoveryDisposition.RETRY,
                httpStatus = statusCode,
                retryAfter = retryAfter,
            )

            in 400..499 -> AgentFailure(
                kind = AgentFailureKind.CONFIGURATION,
                disposition = RecoveryDisposition.WAIT_FOR_CONFIGURATION,
                httpStatus = statusCode,
            )

            else -> AgentFailure(
                kind = AgentFailureKind.UPSTREAM_HTTP,
                disposition = RecoveryDisposition.RETRY_LOW_FREQUENCY,
                httpStatus = statusCode,
            )
        }
    }
}

/**
 * AI 上游返回非成功 HTTP 状态。
 *
 * 此异常刻意不保存响应正文、请求 URL 或认证信息；调用方只能依据状态码和经过校验的
 * `Retry-After` 做恢复决策。
 */
class AgentUpstreamHttpException(
    val statusCode: Int,
    val retryAfter: Duration? = null,
) : IOException("AI upstream returned HTTP $statusCode.") {
    init {
        require(statusCode in 100..599) { "HTTP status must be valid." }
        require(retryAfter == null || (retryAfter.isFinite() && !retryAfter.isNegative())) {
            "Retry-After must be a finite non-negative duration."
        }
    }

    internal companion object {
        private const val MAX_RETRY_AFTER_SECONDS = 7L * 24 * 60 * 60

        fun fromResponse(
            statusCode: Int,
            headers: Map<String, List<String>>,
            nowEpochSeconds: Long = System.currentTimeMillis() / 1_000,
        ): AgentUpstreamHttpException = AgentUpstreamHttpException(
            statusCode = statusCode,
            retryAfter = headers.entries
                .firstOrNull { (name, _) -> name.equals("Retry-After", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.let { parseRetryAfter(it, nowEpochSeconds) },
        )

        internal fun parseRetryAfter(value: String, nowEpochSeconds: Long): Duration? {
            val trimmed = value.trim()
            val deltaSeconds = trimmed.toLongOrNull()
            if (deltaSeconds != null) {
                return deltaSeconds
                    .takeIf { it in 0..MAX_RETRY_AFTER_SECONDS }
                    ?.seconds
            }
            val retryEpochSeconds = runCatching {
                ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond()
            }.getOrNull() ?: return null
            return (retryEpochSeconds - nowEpochSeconds)
                .coerceAtLeast(0)
                .takeIf { it <= MAX_RETRY_AFTER_SECONDS }
                ?.seconds
        }
    }
}

/** 提供商成功响应无法按其协议解析；异常消息不包含响应内容。 */
internal class AgentInvalidResponseException(cause: Throwable? = null) :
    IllegalStateException("AI upstream returned an invalid response.", cause)

/** 提供商成功返回了结构有效但不可用的空模型列表。 */
internal class AgentEmptyModelListException :
    IllegalStateException("AI upstream returned no usable models.")
