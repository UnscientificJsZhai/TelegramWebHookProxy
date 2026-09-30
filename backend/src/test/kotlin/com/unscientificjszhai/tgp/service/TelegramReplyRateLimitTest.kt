package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.utils.ConfigJson
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal class TelegramReplyRateLimitTest {
    @Test
    fun `HTTP and payload 429 recognize retry after with safe default`() {
        assertEquals(
            30,
            TelegramApiResponse(
                HttpStatusCode.TooManyRequests,
                """{"ok":false,"error_code":429,"parameters":{"retry_after":30}}""",
            ).rateLimitRetryAfterSeconds(),
        )
        assertEquals(1, TelegramApiResponse(HttpStatusCode.TooManyRequests, "not-json").rateLimitRetryAfterSeconds())
        assertEquals(
            1,
            TelegramApiResponse(
                HttpStatusCode.OK,
                """{"ok":false,"error_code":429,"parameters":{"retry_after":"bad"}}""",
            ).rateLimitRetryAfterSeconds(),
        )
        assertNull(TelegramApiResponse(HttpStatusCode.BadRequest, "not-json").rateLimitRetryAfterSeconds())
    }

    @Test
    fun `explicit Telegram failure recognizes HTTP error status and payload ok false`() {
        assertTrue(TelegramApiResponse(HttpStatusCode.BadRequest, "not-json").isExplicitTelegramFailure())
        assertTrue(
            TelegramApiResponse(
                HttpStatusCode.OK,
                """{"ok":false,"error_code":400,"description":"Chat not found"}""",
            ).isExplicitTelegramFailure(),
        )
        assertFalse(TelegramApiResponse(HttpStatusCode.OK, """{"ok":true,"result":{}}""").isExplicitTelegramFailure())
        assertFalse(TelegramApiResponse(HttpStatusCode.OK, "not-json").isExplicitTelegramFailure())
    }

    @Test
    fun `deadline saturates and old pending replies default to immediately deliverable`() {
        assertEquals(31_000, telegramReplyRateLimitDeadline(1_000, 30))
        assertEquals(Long.MAX_VALUE, telegramReplyRateLimitDeadline(Long.MAX_VALUE - 5, 1))
        assertEquals(Long.MAX_VALUE, telegramReplyRateLimitDeadline(1_000, Long.MAX_VALUE))
        val oldReply = ConfigJson.decodeFromString<PendingTelegramReply>(
            """{"updateId":11,"chatId":"123","text":"old"}""",
        )
        assertEquals(0, oldReply.nextDeliveryAtEpochMillis)
        assertEquals(0, oldReply.fallbackFailureCount)
    }
}
