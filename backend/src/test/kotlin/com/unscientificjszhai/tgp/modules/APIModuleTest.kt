package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设置 HTTP API 的测试设计。
 */
class APIModuleTest {

    /**
     * 验证设置 API 的读写设计。
     *
     * 验证写入的完整设置可通过读取接口无损获取。
     */
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

    /**
     * 验证设置 API 清空已选模型的设计。
     *
     * 验证仅提供商或当前提供商的 API 密钥变更会清空已选模型，普通更新会保留该值。
     */
    @Test
    fun testSettingsApiClearsSelectedModelOnlyForProviderOrActiveApiKeyChanges() = testApplication {
        application {
            module()
        }

        val baseSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.GEMINI,
                geminiApiKey = "gemini-key",
            ),
        )

        suspend fun save(settings: AppSettings): AppSettings {
            client.post("/api/settings") {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(Json.encodeToString(settings))
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }
            return client.get("/api/settings").let { response ->
                assertEquals(HttpStatusCode.OK, response.status)
                Json.decodeFromString(response.bodyAsText())
            }
        }

        save(baseSettings)
        val selectedGemini = baseSettings.copy(
            ai = baseSettings.ai!!.copy(selectedModel = "models/gemini-selected"),
        )
        assertEquals("models/gemini-selected", save(selectedGemini).ai?.selectedModel)

        val ordinaryUpdate = selectedGemini.copy(
            ai = selectedGemini.ai!!.copy(
                globalContext = "updated prompt",
                openAiBaseUrl = "https://example.invalid/v1",
            ),
        )
        assertEquals("models/gemini-selected", save(ordinaryUpdate).ai?.selectedModel)

        val changedGeminiKey = ordinaryUpdate.copy(
            ai = ordinaryUpdate.ai!!.copy(geminiApiKey = "new-gemini-key"),
        )
        assertEquals("", save(changedGeminiKey).ai?.selectedModel)

        val selectedAgain = changedGeminiKey.copy(
            ai = changedGeminiKey.ai!!.copy(selectedModel = "models/gemini-selected"),
        )
        assertEquals("models/gemini-selected", save(selectedAgain).ai?.selectedModel)

        val switchedProvider = selectedAgain.copy(
            ai = selectedAgain.ai!!.copy(
                provider = AIProvider.OPENAI,
                openAiApiKey = "openai-key",
                selectedModel = "gpt-selected",
            ),
        )
        assertEquals("", save(switchedProvider).ai?.selectedModel)

        val selectedOpenAi = switchedProvider.copy(
            ai = switchedProvider.ai!!.copy(selectedModel = "gpt-selected"),
        )
        assertEquals("gpt-selected", save(selectedOpenAi).ai?.selectedModel)

        val changedOpenAiKey = selectedOpenAi.copy(
            ai = selectedOpenAi.ai!!.copy(openAiApiKey = "new-openai-key"),
        )
        assertEquals("", save(changedOpenAiKey).ai?.selectedModel)
    }
}
