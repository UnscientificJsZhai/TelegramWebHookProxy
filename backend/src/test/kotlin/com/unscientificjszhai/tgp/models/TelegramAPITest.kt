package com.unscientificjszhai.tgp.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TelegramAPITest {
    @Test
    fun `getUpdates response decodes retry after parameters`() {
        val response = Json.decodeFromString<GetUpdatesResponse>(
            """{"ok":false,"error_code":429,"description":"Too Many Requests","parameters":{"retry_after":7}}""",
        )

        assertEquals(429, response.errorCode)
        assertEquals(7, response.parameters?.retryAfter)
    }

    @Test
    fun `getUpdates response remains compatible when parameters are absent`() {
        val response = Json.decodeFromString<GetUpdatesResponse>("""{"ok":true,"result":[]}""")

        assertEquals(emptyList(), response.result)
        assertNull(response.parameters)
    }

    @Test
    fun `message decodes sender and remains compatible when sender is absent`() {
        val messageWithSender = Json.decodeFromString<Message>(
            """{"message_id":1,"chat":{"id":123,"type":"private"},"text":"hello","from":{"id":123,"is_bot":false,"first_name":"Test"}}""",
        )
        val messageWithoutSender = Json.decodeFromString<Message>(
            """{"message_id":2,"chat":{"id":123,"type":"private"},"text":"hello"}""",
        )

        assertEquals(123, messageWithSender.from?.id)
        assertNull(messageWithoutSender.from)
    }
}
