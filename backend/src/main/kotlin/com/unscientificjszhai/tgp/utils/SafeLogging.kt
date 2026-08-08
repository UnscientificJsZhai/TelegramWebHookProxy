@file:Suppress("CanConvertToMultiDollarString", "CanUnescapeDollarLiteral", "RegExpRedundantEscape")

package com.unscientificjszhai.tgp.utils

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 为应用日志提供稳定的失败分类和文本脱敏。
 *
 * 所有输入都应被视为不可信：异常消息、HTTP URL、请求头和用户正文都可能包含凭据。调用方只能把
 * [failureCategory] 的结果和经业务确认可公开的标识写入日志，不能记录异常对象、异常文本或请求正文。
 */
internal object SafeLogging {
    /** 可写入日志的有限失败类别。 */
    enum class FailureCategory(val wireName: String) {
        CANCELLED("cancelled"),
        TIMEOUT("timeout"),
        NETWORK("network"),
        INVALID_RESPONSE("invalid_response"),
        VALIDATION("validation"),
        UNEXPECTED("unexpected"),
    }

    /**
     * 将异常及其原因链映射为有限、稳定的失败类别。
     *
     * @param throwable 本次失败的异常；不会读取或返回异常消息。
     * @return 可安全写入日志的失败类别。
     */
    fun failureCategory(throwable: Throwable): FailureCategory {
        var current: Throwable? = throwable
        val visited = mutableSetOf<Throwable>()
        while (current != null && visited.add(current)) {
            when (current) {
                is TimeoutCancellationException,
                is SocketTimeoutException,
                    -> return FailureCategory.TIMEOUT

                is CancellationException -> return FailureCategory.CANCELLED
                is IOException -> return FailureCategory.NETWORK
                is SerializationException -> return FailureCategory.INVALID_RESPONSE
                is IllegalArgumentException -> return FailureCategory.VALIDATION
            }
            current = current.cause
        }
        return FailureCategory.UNEXPECTED
    }

    /**
     * 脱敏一条已格式化日志文本。
     *
     * 未识别的 Telegram token 片段和已知鉴权载荷会采用替换而非解析失败回退，避免 URL 轻微畸形时
     * 泄露 secret。此方法不处理 Throwable；日志配置必须同时使用 `%nopex` 禁止其自动输出。
     *
     * @param text 可能包含外部服务 URL、查询参数或 HTTP 鉴权头的文本。
     * @return 保留诊断结构、但所有识别到的凭据均替换为固定占位符的文本。
     */
    fun redactFormattedMessage(text: String): String =
        REDACTION_RULES.fold(text) { value, rule -> rule.replace(value, "$" + "1[REDACTED]$" + "2") }

    private val REDACTION_RULES = listOf(
        // Telegram token 位于 URL path，不能假定 token 符合当前的数字或长度格式。
        Regex("(?i)(https?://api\\.telegram\\.org(?::\\d{1,5})?/(?:file/)?bot)[^/?\\s\\\"']+()"),
        // URL userinfo；用户名也可能是凭据的一部分，因此整个 userinfo 采用 fail-closed 替换。
        Regex("(?i)(https?://)[^/@\\s]+(@)"),
        // URL 查询凭据；同时覆盖常见 snake_case、kebab-case 和 camelCase 名称。
        Regex(
            "(?i)([?&;](?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|key|secret|password)=)[^&#\\s]+()",
        ),
        // 日志库或 HTTP 客户端可能将敏感查询参数格式化为普通 key-value 或 JSON 风格的头字段。
        Regex(
            "(?i)(\\b(?:x-?api-?key|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|key|secret|password)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s,;\\\"']+([\\\"']?)",
        ),
        // 明文或结构化 HTTP 头中的 Bearer / Basic 值。
        Regex("""(?i)(\b(?:proxy-)?authorization\s*[:=]\s*(?:bearer|basic)\s+(?:\\?["'])?)[^\s,;\\"']+((?:\\?["'])?)"""),
        Regex("""(?i)(\b(?:bearer|basic)\s+(?:\\?["'])?)[^\s,;\\"']+((?:\\?["'])?)"""),
    )
}

/**
 * 将 Logback 的已格式化消息统一脱敏的模式转换器。
 *
 * 此类由 `logback.xml` 反射创建；所有 appender 的 pattern 都必须使用 `%safeMsg` 并追加 `%nopex`，
 * 以避免异常堆栈绕过本转换器。
 */
class SafeLogMessageConverter : ClassicConverter() {
    /**
     * 返回已格式化且脱敏的日志消息。
     *
     * @param event 当前 Logback 事件；为 `null` 时返回空文本。
     * @return 可供 encoder 输出的安全日志消息。
     */
    override fun convert(event: ILoggingEvent?): String =
        SafeLogging.redactFormattedMessage(event?.formattedMessage.orEmpty())
}
