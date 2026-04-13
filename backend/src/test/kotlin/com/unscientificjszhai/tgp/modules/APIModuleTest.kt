package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class APIModuleTest {

    @Test
    fun testSettingsApi() = testApplication {
        application {
            module()
        }

        val testSettings = AppSettings(
            telegramToken = "api_token",
            chatId = "456",
            proxy = null,
            ai = AISettings(
                geminiApiKey = "test_gemini_key",
                agentEnabled = true,
                agentChatId = "456",
                globalContext = "system prompt"
            )
        )

        val jsonString = Json.encodeToString(testSettings)

        client.post("/api/settings") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(jsonString)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        client.get("/api/settings").apply {
            assertEquals(HttpStatusCode.OK, status)
            val responseText = bodyAsText()
            val receivedSettings = Json.decodeFromString<AppSettings>(responseText)
            
            assertEquals("api_token", receivedSettings.telegramToken)
            assertEquals("456", receivedSettings.chatId)
            assertEquals(true, receivedSettings.ai?.agentEnabled)
            assertEquals("test_gemini_key", receivedSettings.ai?.geminiApiKey)
            assertEquals("system prompt", receivedSettings.ai?.globalContext)
        }
    }
}