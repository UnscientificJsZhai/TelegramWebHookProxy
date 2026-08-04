package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.HttpCallTarget
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun testSettingsApi() = withTestApi { repository, _, _ ->
        val testSettings = AppSettings(
            telegramToken = "api_token",
            chatId = "456",
            proxy = null,
            ai = AISettings(
                geminiApiKey = "test_gemini_key",
                agentEnabled = true,
                agentChatId = "456",
                globalContext = "system prompt",
            ),
        )

        client.post("/api/settings") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(Json.encodeToString(testSettings))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        client.get("/api/settings").apply {
            assertEquals(HttpStatusCode.OK, status)
            val receivedSettings = Json.decodeFromString<AppSettings>(bodyAsText())

            assertEquals("api_token", receivedSettings.telegramToken)
            assertEquals("456", receivedSettings.chatId)
            assertEquals(true, receivedSettings.ai?.agentEnabled)
            assertEquals("test_gemini_key", receivedSettings.ai?.geminiApiKey)
            assertEquals("system prompt", receivedSettings.ai?.globalContext)
        }
        assertEquals(testSettings, repository.settingsFlow.value)
    }

    /**
     * 验证设置 API 会在进入仓储前拒绝不安全的 HTTP 工具目标。
     */
    @Test
    fun `settings API rejects invalid HTTP tool targets`() = withTestApi { repository, _, _ ->
        val unsafe = AppSettings(
            ai = AISettings(
                httpToolSettings = HttpToolSettings(
                    enabled = true,
                    targets = listOf(
                        HttpCallTarget(
                            id = "unsafe",
                            scheme = "http",
                            host = "localhost",
                            path = "/admin",
                        ),
                    ),
                ),
            ),
        )

        client.post("/api/settings") {
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(Json.encodeToString(unsafe))
        }.apply {
            assertEquals(HttpStatusCode.BadRequest, status)
        }
        assertEquals(AppSettings(), repository.settingsFlow.value)
    }

    /**
     * 验证设置 API 清空已选模型的设计。
     *
     * 验证仅提供商或当前提供商的 API 密钥变更会清空已选模型，普通更新会保留该值。
     */
    @Test
    fun testSettingsApiClearsSelectedModelOnlyForProviderOrActiveApiKeyChanges() = withTestApi { _, _, _ ->
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

    /**
     * 验证非法代理和未知协议会返回 400，且不会保存设置或更新机器人指令。
     */
    @Test
    fun `invalid settings requests return bad request without side effects`() =
        withTestApi { repository, telegramService, configFile ->
            val original = AppSettings(telegramToken = "100:original", chatId = "old-chat")
            repository.saveSettings(original)
            val originalContent = configFile.readText()

            client.post("/api/settings") {
                contentType(ContentType.Application.Json)
                setBody(
                    Json.encodeToString(
                        original.copy(
                            telegramToken = "200:new",
                            proxy = ProxySettings("proxy.example.com", 0, ProxyType.HTTP),
                        ),
                    ),
                )
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }

            client.post("/api/settings") {
                contentType(ContentType.Application.Json)
                setBody("""{"proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"}}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }

            assertEquals(original, repository.settingsFlow.value)
            assertEquals(originalContent, configFile.readText())
            coVerify(exactly = 0) { telegramService.updateBotCommands(any(), any()) }
        }

    /**
     * 验证历史未知代理类型不会在更新默认聊天时被静默覆盖，完整设置可显式修复它。
     */
    @Test
    fun `chat settings rejects historical invalid proxy without rewriting it`() {
        val temporaryDirectory = createTempDirectory("api-invalid-history-test").toFile()
        try {
            val configFile = temporaryDirectory.resolve("settings.json")
            val historicalContent =
                """{"telegramToken":"100:token","chatId":"old-chat","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"}}"""
            configFile.writeText(historicalContent)
            val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
            val originalContent = configFile.readText()
            val recoveredSettings = repository.settingsFlow.value
            val telegramService = mockk<TelegramService>(relaxed = true)
            val appComponent = mockk<AppComponent>()
            every { appComponent.settingsRepository } returns repository
            every { appComponent.telegramService } returns telegramService

            testApplication {
                application { configureTestApi(appComponent) }

                client.post("/api/settings/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertEquals(HttpStatusCode.BadRequest, status)
                }
                assertEquals(recoveredSettings, repository.settingsFlow.value)
                assertEquals(originalContent, configFile.readText())

                val resolvedSettings = recoveredSettings.copy(
                    chatId = "resolved-chat",
                    proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS),
                )
                client.post("/api/settings") {
                    contentType(ContentType.Application.Json)
                    setBody(Json.encodeToString(resolvedSettings))
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                }

                assertEquals(resolvedSettings, repository.settingsFlow.value)
                assertFalse(repository.hasHistoricalInvalidProxy)
            }

            assertEquals(historicalContent, originalContent)
            assertFalse(configFile.readText() == originalContent)
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证聊天设置 API 在恢复快照的本次请求返回冲突后，下一次复制操作使用恢复后的 token。
     */
    @Test
    fun `chat settings retries from recovered settings instead of overwriting recovered token`() {
        val temporaryDirectory = createTempDirectory("api-settings-recovery-test").toFile()
        try {
            val configFile = temporaryDirectory.resolve("settings.json")
            val backupFile = temporaryDirectory.resolve("settings.json.bak")
            val recovered = AppSettings(telegramToken = "100:backup", chatId = "old-chat")
            configFile.writeText("{ invalid")
            backupFile.writeText(ConfigJson.encodeToString(recovered))
            var blockBackupRead = true
            val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
                override fun readAllBytes(path: Path): ByteArray {
                    if (blockBackupRead && path == backupFile.toPath()) {
                        throw IOException("injected backup read failure")
                    }
                    return DefaultAtomicJsonFileOperations.readAllBytes(path)
                }
            }
            val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
            val telegramService = mockk<TelegramService>(relaxed = true)
            val appComponent = mockk<AppComponent>()
            every { appComponent.settingsRepository } returns repository
            every { appComponent.telegramService } returns telegramService

            testApplication {
                application { configureTestApi(appComponent) }

                client.post("/api/settings/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertTrue(status == HttpStatusCode.Conflict || status == HttpStatusCode.InternalServerError)
                }

                blockBackupRead = false
                client.post("/api/settings/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertEquals(HttpStatusCode.Conflict, status)
                }
                assertEquals(recovered, repository.settingsFlow.value)

                client.post("/api/settings/chat") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                }
                assertEquals("100:backup", repository.settingsFlow.value.telegramToken)
                assertEquals("new-chat", repository.settingsFlow.value.chatId)
                assertEquals(
                    "100:backup",
                    ConfigJson.decodeFromString<AppSettings>(configFile.readText()).telegramToken
                )
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun withTestApi(test: suspend ApplicationTestBuilder.(SettingsRepository, TelegramService, File) -> Unit) {
        val temporaryDirectory = createTempDirectory("api-settings-test").toFile()
        try {
            val configFile = temporaryDirectory.resolve("settings.json")
            val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
            val telegramService = mockk<TelegramService>(relaxed = true)
            val appComponent = mockk<AppComponent>()
            every { appComponent.settingsRepository } returns repository
            every { appComponent.telegramService } returns telegramService

            testApplication {
                application { configureTestApi(appComponent) }
                test(repository, telegramService, configFile)
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun Application.configureTestApi(appComponent: AppComponent) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        apiModule(appComponent)
    }
}
