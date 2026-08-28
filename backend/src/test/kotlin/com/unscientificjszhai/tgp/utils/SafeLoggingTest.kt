package com.unscientificjszhai.tgp.utils

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.CoreConstants
import org.w3c.dom.Element
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 验证默认日志布局对消息插值和异常图均不会输出 secret canary。 */
class SafeLoggingTest {
    /**
     * 验证实际 PatternLayout encoder 会脱敏所有受支持的 URL 与 HTTP 凭据形式，并通过 `%nopex` 抑制
     * Throwable 的 cause 与 suppressed 文本。
     */
    @Test
    fun `pattern encoder redacts interpolated credentials and suppresses throwable graph`() {
        val telegramToken = "TELEGRAM_TOKEN_CANARY"
        val geminiKey = "GEMINI_KEY_CANARY"
        val bearerToken = "BEARER_CANARY"
        val basicToken = "BASIC_CANARY"
        val headerKey = "HEADER_KEY_CANARY"
        val quotedBearer = "QUOTED_BEARER_CANARY"
        val quotedBasic = "QUOTED_BASIC_CANARY"
        val escapedBearer = "ESCAPED_BEARER_CANARY"
        val throwableCanary = "THROWABLE_CANARY"
        val suppressedCanary = "SUPPRESSED_CANARY"
        val failure = IllegalStateException(throwableCanary, IllegalArgumentException("cause-$throwableCanary"))
        failure.addSuppressed(IllegalStateException(suppressedCanary))

        val rendered = encodeWithDefaultSafePattern(
            message = "diagnostic botId={} taskId={} urls={} {} {} headers={} {} {} keyHeader={} quoted={} {} {} proxy={}",
            throwable = failure,
            arguments = arrayOf(
                "123456",
                "task-42",
                "https://api.telegram.org/bot$telegramToken/sendMessage",
                "https://api.telegram.org/file/bot$telegramToken/documents/report",
                "https://api.telegram.org:443/bot$telegramToken/getUpdates",
                "https://generativelanguage.googleapis.com/v1/models?key=$geminiKey&safe=1",
                "Authorization: Bearer $bearerToken",
                "Proxy-Authorization: Basic $basicToken",
                "X-Api-Key: $headerKey",
                "Authorization: Bearer \"$quotedBearer\"",
                "Basic '$quotedBasic'",
                "Authorization: Bearer \\\"$escapedBearer\\\"",
                "https://proxy-user:proxy-password@example.test",
            ),
        )

        listOf(
            telegramToken,
            geminiKey,
            bearerToken,
            basicToken,
            headerKey,
            quotedBearer,
            quotedBasic,
            escapedBearer,
            throwableCanary,
            suppressedCanary,
            "proxy-password",
        ).forEach { canary -> assertFalse(rendered.contains(canary), "Leaked canary: $canary") }
        assertContains(rendered, "botId=123456")
        assertContains(rendered, "taskId=task-42")
        assertContains(rendered, "[REDACTED]")
    }

    /** 验证每一个已配置 appender 都使用同一安全消息转换器并抑制自动异常输出。 */
    @Test
    fun `every configured appender uses the safe message pattern`() {
        val resource = requireNotNull(javaClass.classLoader.getResource("logback.xml"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(resource.openStream())
        val appenders = (0 until document.getElementsByTagName("appender").length)
            .map { document.getElementsByTagName("appender").item(it) as Element }

        assertTrue(appenders.isNotEmpty())
        appenders.forEach { appender ->
            val patterns = (0 until appender.getElementsByTagName("pattern").length)
                .map { appender.getElementsByTagName("pattern").item(it).textContent }
            assertTrue(patterns.isNotEmpty(), "Appender ${appender.getAttribute("name")} has no pattern")
            patterns.forEach { pattern ->
                assertTrue(pattern.contains("%safeMsg"), "Appender pattern must redact formatted messages: $pattern")
                assertTrue(pattern.contains("%nopex"), "Appender pattern must suppress Throwable output: $pattern")
            }
        }
    }

    private fun encodeWithDefaultSafePattern(
        message: String,
        throwable: Throwable,
        arguments: Array<Any>,
    ): String {
        val context = LoggerContext()
        context.putObject(
            CoreConstants.PATTERN_RULE_REGISTRY,
            mapOf("safeMsg" to SafeLogMessageConverter::class.java.name),
        )
        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            pattern = "%safeMsg%n%nopex"
            charset = StandardCharsets.UTF_8
            start()
        }
        try {
            return encoder.encode(
                LoggingEvent(
                    SafeLoggingTest::class.java.name,
                    context.getLogger("safe-logging-test"),
                    Level.ERROR,
                    message,
                    throwable,
                    arguments,
                ),
            )
                .toString(StandardCharsets.UTF_8)
        } finally {
            encoder.stop()
            context.stop()
        }
    }
}
