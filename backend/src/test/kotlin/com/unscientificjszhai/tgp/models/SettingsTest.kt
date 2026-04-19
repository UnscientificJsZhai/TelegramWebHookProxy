package com.unscientificjszhai.tgp.models

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun testAppSettingsSerialization() {
        val appSettings = AppSettings(
            telegramToken = "token",
            chatId = "123",
            ai = AISettings(
                geminiApiKey = "test_key",
                agentEnabled = true,
                agentChatId = "123",
                globalContext = "context",
                mcpServers = listOf(
                    MCPServerConfig(
                        name = "server1",
                        url = "http://localhost:3000",
                        headers = mapOf("Authorization" to "Bearer token")
                    )
                )
            )
        )

        val jsonString = Json.encodeToString(appSettings)
        assertTrue(jsonString.contains("\"ai\":"))
        assertTrue(jsonString.contains("\"geminiApiKey\":\"test_key\""))
        assertTrue(jsonString.contains("\"mcpServers\":"))
        assertTrue(jsonString.contains("\"name\":\"server1\""))
    }
}