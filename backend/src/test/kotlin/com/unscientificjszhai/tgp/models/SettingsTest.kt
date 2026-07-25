package com.unscientificjszhai.tgp.models

import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsTest {

    @Test
    fun testAppSettingsSerialization() {
        val appSettings = AppSettings(
            telegramToken = "token",
            chatId = "123",
            ai = AISettings(
                geminiApiKey = "test_key",
                selectedModel = "models/gemini-test",
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

        val jsonString = ConfigJson.encodeToString(appSettings)
        val jsonElement = ConfigJson.parseToJsonElement(jsonString).jsonObject

        assertTrue(jsonElement.containsKey("ai"))
        val aiElement = jsonElement["ai"]?.jsonObject
        assertNotNull(aiElement)
        assertTrue(aiElement.containsKey("provider"))
        assertTrue(aiElement.containsKey("geminiApiKey"))
        assertTrue(aiElement.containsKey("openAiApiKey"))
        assertTrue(aiElement.containsKey("openAiBaseUrl"))
        assertTrue(aiElement.containsKey("selectedModel"))
        assertTrue(aiElement.containsKey("autoCleanContextIntervalMinutes"))
        assertTrue(aiElement.containsKey("silentContextCleanup"))
        assertTrue(aiElement.containsKey("mcpServers"))
    }

    @Test
    fun testAISettingsDefaultValuesInSerialization() {
        val aiSettings = AISettings()
        val jsonString = ConfigJson.encodeToString(aiSettings)
        val jsonElement = ConfigJson.parseToJsonElement(jsonString).jsonObject

        // 验证即使是默认值，也会被序列化出来
        assertTrue(jsonElement.containsKey("provider"), "Provider field should be present even if default")
        assertTrue(jsonElement.containsKey("openAiApiKey"), "openAiApiKey field should be present even if default")
        assertTrue(jsonElement.containsKey("openAiBaseUrl"), "openAiBaseUrl field should be present even if default")
        assertTrue(jsonElement.containsKey("selectedModel"), "selectedModel field should be present even if default")
        assertTrue(
            jsonElement.containsKey("autoCleanContextIntervalMinutes"),
            "autoCleanContextIntervalMinutes field should be present even if default",
        )
        assertTrue(
            jsonElement.containsKey("silentContextCleanup"),
            "silentContextCleanup field should be present even if default",
        )
    }

    @Test
    fun testAISettingsDeserializeOldJsonUsesDefaults() {
        val jsonString = """
            {
              "provider": "GEMINI",
              "geminiApiKey": "test_key",
              "openAiApiKey": "",
              "openAiBaseUrl": "",
              "agentEnabled": true,
              "agentChatId": "123",
              "globalContext": "context",
              "mcpServers": []
            }
        """.trimIndent()

        val aiSettings = ConfigJson.decodeFromString<AISettings>(jsonString)

        assertEquals(0, aiSettings.autoCleanContextIntervalMinutes)
        assertEquals(false, aiSettings.silentContextCleanup)
        assertEquals("", aiSettings.selectedModel)
    }
}
