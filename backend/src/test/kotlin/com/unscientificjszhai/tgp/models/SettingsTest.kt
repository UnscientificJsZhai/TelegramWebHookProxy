package com.unscientificjszhai.tgp.models

import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 应用与 AI 设置序列化兼容性的测试设计。
 */
class SettingsTest {

    /**
     * 验证完整应用设置的序列化设计。
     *
     * 验证序列化结果包含 AI 配置及其关键字段。
     */
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

    /**
     * 验证 AI 设置默认值的序列化设计。
     *
     * 验证默认值字段不会在序列化时被省略。
     */
    @Test
    fun testAISettingsDefaultValuesInSerialization() {
        val aiSettings = AISettings()
        val jsonString = ConfigJson.encodeToString(aiSettings)
        val jsonElement = ConfigJson.parseToJsonElement(jsonString).jsonObject

        // 验证默认值字段仍会写入序列化结果。
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

    /**
     * 验证旧版 AI 设置 JSON 的反序列化兼容设计。
     *
     * 验证缺失的新增字段会使用当前定义的默认值。
     */
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
