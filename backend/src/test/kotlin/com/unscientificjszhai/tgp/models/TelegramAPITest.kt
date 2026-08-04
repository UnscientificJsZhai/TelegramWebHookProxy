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
}
