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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val completeSettingsJson = Json {
    encodeDefaults = true
    explicitNulls = true
}

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

        val revision = currentSettingsETag()
        client.put("/api/settings") {
            header(HttpHeaders.IfMatch, revision)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(completeSettingsJson.encodeToString(testSettings))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(testSettings, Json.decodeFromString<AppSettings>(bodyAsText()))
            assertEquals(
                "\"${repository.currentSettingsSnapshot().revision}\"",
                headers[HttpHeaders.ETag],
            )
        }

        client.get("/api/settings").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(
                "\"${repository.currentSettingsSnapshot().revision}\"",
                headers[HttpHeaders.ETag],
            )
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
     * 验证完整设置写入强制使用单个强 ETag，且过期请求不会提交。
     */
    @Test
    fun `settings API enforces strong If-Match preconditions`() = withTestApi { repository, _, configFile ->
        val requested = AppSettings(telegramToken = "100:requested")

        client.put("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody(completeSettingsJson.encodeToString(requested))
        }.apply {
            assertEquals(HttpStatusCode(428, "Precondition Required"), status)
        }

        listOf(
            "W/\"${repository.currentSettingsSnapshot().revision}\"",
            "\"${repository.currentSettingsSnapshot().revision}\", \"other\"",
            "\"not-a-sha256\"",
        ).forEach { invalid ->
            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, invalid)
                contentType(ContentType.Application.Json)
                setBody(completeSettingsJson.encodeToString(requested))
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }
        }

        val staleETag = currentSettingsETag()
        repository.updateSettings { it.copy(chatId = "concurrent-chat") }
        val contentBeforeStaleWrite = configFile.readText()
        client.put("/api/settings") {
            header(HttpHeaders.IfMatch, staleETag)
            contentType(ContentType.Application.Json)
            setBody(completeSettingsJson.encodeToString(requested))
        }.apply {
            assertEquals(HttpStatusCode.PreconditionFailed, status)
        }

        assertEquals("concurrent-chat", repository.settingsFlow.value.chatId)
        assertEquals(contentBeforeStaleWrite, configFile.readText())
    }

    /**
     * 验证聊天局部更新会使旧的完整设置提交失败，避免静默回滚聊天标识。
     */
    @Test
    fun `chat update cannot be overwritten by stale full settings save`() = withTestApi { repository, _, _ ->
        val staleETag = currentSettingsETag()

        client.post("/api/settings/chat") {
            header(HttpHeaders.IfMatch, staleETag)
            contentType(ContentType.Application.Json)
            setBody("""{"chatId":"new-chat"}""")
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        client.put("/api/settings") {
            header(HttpHeaders.IfMatch, staleETag)
            contentType(ContentType.Application.Json)
            setBody(completeSettingsJson.encodeToString(AppSettings(telegramToken = "100:new-token")))
        }.apply {
            assertEquals(HttpStatusCode.PreconditionFailed, status)
        }

        assertEquals("new-chat", repository.settingsFlow.value.chatId)
        assertEquals("", repository.settingsFlow.value.telegramToken)
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

        val revision = currentSettingsETag()
        client.put("/api/settings") {
            header(HttpHeaders.IfMatch, revision)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(completeSettingsJson.encodeToString(unsafe))
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
            val revision = currentSettingsETag()
            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(completeSettingsJson.encodeToString(settings))
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
     * 验证有效 AI 提供方切换会重新发布机器人模型命令。
     */
    @Test
    fun `provider switch refreshes bot model commands`() = withTestApi { repository, telegramService, _ ->
        val geminiSettings = AppSettings(
            telegramToken = "100:token",
            ai = AISettings(provider = AIProvider.GEMINI, agentEnabled = true, geminiApiKey = "gemini-key"),
        )
        repository.saveSettings(geminiSettings)
        val openAiSettings = geminiSettings.copy(
            ai = geminiSettings.ai!!.copy(
                provider = AIProvider.OPENAI,
                openAiApiKey = "openai-key",
            ),
        )

        val revision = currentSettingsETag()
        client.put("/api/settings") {
            header(HttpHeaders.IfMatch, revision)
            contentType(ContentType.Application.Json)
            setBody(completeSettingsJson.encodeToString(openAiSettings))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        coVerify(exactly = 1) { telegramService.updateBotCommands("100:token", AIProvider.OPENAI) }
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

            val revision = currentSettingsETag()
            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                contentType(ContentType.Application.Json)
                setBody(
                    completeSettingsJson.encodeToString(
                        original.copy(
                            telegramToken = "200:new",
                            proxy = ProxySettings("proxy.example.com", 0, ProxyType.HTTP),
                        ),
                    ),
                )
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }

            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
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
                    header(HttpHeaders.IfMatch, currentSettingsETag())
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
                val revision = currentSettingsETag()
                client.put("/api/settings") {
                    header(HttpHeaders.IfMatch, revision)
                    contentType(ContentType.Application.Json)
                    setBody(completeSettingsJson.encodeToString(resolvedSettings))
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
                    header(HttpHeaders.IfMatch, currentSettingsETag())
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertTrue(status == HttpStatusCode.Conflict || status == HttpStatusCode.InternalServerError)
                }

                blockBackupRead = false
                client.post("/api/settings/chat") {
                    header(HttpHeaders.IfMatch, currentSettingsETag())
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertEquals(HttpStatusCode.Conflict, status)
                }
                assertEquals(recovered, repository.settingsFlow.value)

                client.post("/api/settings/chat") {
                    header(HttpHeaders.IfMatch, currentSettingsETag())
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

    /**
     * 验证历史非法 MCP 列表不能被无关聊天更新覆盖，只有请求中明确给出 `mcpServers` 的更新才能替换它。
     */
    @Test
    fun `settings routes require an explicit MCP replacement for historical invalid configuration`() {
        val temporaryDirectory = createTempDirectory("api-invalid-mcp-history-test").toFile()
        try {
            val configFile = temporaryDirectory.resolve("settings.json")
            val historicalContent =
                """{"telegramToken":"100:token","chatId":"old-chat","ai":{"provider":"OPENAI","openAiApiKey":"key","mcpServers":[{"name":"unsafe","url":"ftp://mcp.example.com","headers":{}}]}}"""
            configFile.writeText(historicalContent)
            val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
            val recoveredSettings = repository.settingsFlow.value
            val recoveredUpdate = repository.settingsUpdateFlow.value
            val telegramService = mockk<TelegramService>(relaxed = true)
            val appComponent = mockk<AppComponent>()
            every { appComponent.settingsRepository } returns repository
            every { appComponent.telegramService } returns telegramService

            testApplication {
                application { configureTestApi(appComponent) }

                client.post("/api/settings/chat") {
                    header(HttpHeaders.IfMatch, currentSettingsETag())
                    contentType(ContentType.Application.Json)
                    setBody("""{"chatId":"new-chat"}""")
                }.apply {
                    assertEquals(HttpStatusCode.Conflict, status)
                }
                assertEquals(recoveredSettings, repository.settingsFlow.value)
                assertEquals(historicalContent, configFile.readText())

                client.patch("/api/settings") {
                    header(HttpHeaders.IfMatch, currentSettingsETag())
                    contentType(ContentType.Application.Json)
                    setBody("""{"ai":{"mcpServers":[]}}""")
                }.apply {
                    assertEquals(HttpStatusCode.OK, status)
                }
                assertFalse(repository.hasHistoricalInvalidMcp)
                assertTrue(repository.settingsFlow.value.ai?.mcpServers.orEmpty().isEmpty())
                assertEquals(recoveredUpdate, repository.settingsUpdateFlow.value)
                assertFalse(configFile.readText() == historicalContent)
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证完整写入拒绝顶层和嵌套未知字段、缺失嵌套字段及宽松 JSON，且错误不回显敏感请求内容。
     */
    @Test
    fun `strict complete settings reject unknown incomplete and malformed JSON safely`() =
        withTestApi { repository, _, _ ->
            val original =
                AppSettings(telegramToken = "100:stored-secret", ai = AISettings(geminiApiKey = "stored-key"))
            repository.saveSettings(original)
            val revision = currentSettingsETag()

            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                contentType(ContentType.Application.Json)
                setBody("""{"telegramToken":"100:request-secret","chatId":"","proxy":null,"ai":null,"telegramTokne":"typo"}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
                assertFalse(bodyAsText().contains("request-secret"))
                assertFalse(bodyAsText().contains("stored-secret"))
            }

            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                contentType(ContentType.Application.Json)
                setBody("""{"telegramToken":"","chatId":"","proxy":null,"ai":{}}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }

            val complete = completeSettingsJson.encodeToJsonElement(original).jsonObject
            val aiWithUnknownField = buildJsonObject {
                complete.forEach { (key, value) -> put(key, value) }
                put("ai", buildJsonObject {
                    complete.getValue("ai").jsonObject.forEach { (key, value) -> put(key, value) }
                    put("unknownNestedField", true)
                })
            }
            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                contentType(ContentType.Application.Json)
                setBody(aiWithUnknownField.toString())
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }

            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                contentType(ContentType.Application.Json)
                setBody("""{"telegramToken":"","chatId":"","proxy":null,"ai":null,}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }
            assertEquals(original, repository.settingsFlow.value)
        }

    /**
     * 验证 PATCH 区分缺失、显式 null 和值；对象递归合并而列表及动态 headers 映射整体替换。
     */
    @Test
    fun `settings patch merges objects and replaces collections with strict null semantics`() =
        withTestApi { repository, _, _ ->
            val original = AppSettings(
                telegramToken = "100:token",
                proxy = ProxySettings(
                    "proxy.example.com",
                    1080,
                    ProxyType.HTTP,
                    username = "user",
                    password = "password"
                ),
                ai = AISettings(
                    selectedModel = "models/kept",
                    mcpServers = listOf(
                        com.unscientificjszhai.tgp.models.MCPServerConfig(
                            name = "old",
                            url = "https://old.example.com/mcp",
                            headers = mapOf("X-Old" to "old"),
                        ),
                    ),
                ),
            )
            repository.saveSettings(original)
            val revision = currentSettingsETag()

            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, revision)
                contentType(ContentType.Application.Json)
                setBody(
                    """
                {"proxy":{"username":null},"ai":{"mcpServers":[
                  {"name":"replacement","url":"https://new.example.com/mcp","headers":{"Authorization":"new","X-Dynamic":"allowed"}}
                ]}}
                """.trimIndent(),
                )
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
                assertEquals("models/kept", Json.decodeFromString<AppSettings>(bodyAsText()).ai?.selectedModel)
                assertNotNull(headers[HttpHeaders.ETag])
            }
            val patched = repository.settingsFlow.value
            assertEquals("proxy.example.com", patched.proxy?.host)
            assertEquals(null, patched.proxy?.username)
            assertEquals("password", patched.proxy?.password)
            assertEquals(
                mapOf("Authorization" to "new", "X-Dynamic" to "allowed"),
                patched.ai?.mcpServers?.single()?.headers,
            )

            val invalidNullRevision = currentSettingsETag()
            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, invalidNullRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"telegramToken":null}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }
            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, invalidNullRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"ai":{"geminiApiKey":null}}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }
            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, invalidNullRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"ai":{"unknownNestedField":"no"}}""")
            }.apply {
                assertEquals(HttpStatusCode.BadRequest, status)
            }
            assertEquals(patched, repository.settingsFlow.value)

            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, invalidNullRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"proxy":null,"ai":null}""")
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }
            assertEquals(null, repository.settingsFlow.value.proxy)
            assertEquals(null, repository.settingsFlow.value.ai)
        }

    /**
     * 验证 PUT、PATCH 和兼容聊天端点共享 ETag 条件写入、模型清理和机器人命令副作用。
     */
    @Test
    fun `settings write routes share etag model clearing and command side effects`() =
        withTestApi { repository, telegramService, _ ->
            val original = AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "gemini-key",
                    selectedModel = "models/selected",
                    agentEnabled = true,
                ),
            )
            repository.saveSettings(original)
            val staleRevision = currentSettingsETag()

            val afterKeyPatch = client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, staleRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"ai":{"geminiApiKey":"replacement-key"}}""")
            }
            assertEquals(HttpStatusCode.OK, afterKeyPatch.status)
            val keyPatchRevision = assertNotNull(afterKeyPatch.headers[HttpHeaders.ETag])
            assertEquals("", repository.settingsFlow.value.ai?.selectedModel)

            client.put("/api/settings") {
                header(HttpHeaders.IfMatch, staleRevision)
                contentType(ContentType.Application.Json)
                setBody(completeSettingsJson.encodeToString(repository.settingsFlow.value))
            }.apply {
                assertEquals(HttpStatusCode.PreconditionFailed, status)
            }

            val afterChatUpdate = client.post("/api/settings/chat") {
                header(HttpHeaders.IfMatch, keyPatchRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"chatId":"new-chat"}""")
            }
            assertEquals(HttpStatusCode.OK, afterChatUpdate.status)
            val chatRevision = assertNotNull(afterChatUpdate.headers[HttpHeaders.ETag])

            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, keyPatchRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"telegramToken":"200:stale"}""")
            }.apply {
                assertEquals(HttpStatusCode.PreconditionFailed, status)
            }

            client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, chatRevision)
                contentType(ContentType.Application.Json)
                setBody("""{"ai":{"provider":"OPENAI","openAiApiKey":"openai-key","selectedModel":"gpt-selected"}}""")
            }.apply {
                assertEquals(HttpStatusCode.OK, status)
            }
            assertEquals("", repository.settingsFlow.value.ai?.selectedModel)
            coVerify(exactly = 1) { telegramService.updateBotCommands("100:token", AIProvider.OPENAI) }
        }

    /** 验证兼容 POST 仍使用与 PUT 相同的完整严格设置契约。 */
    @Test
    fun `post settings remains a strict complete replacement compatibility route`() = withTestApi { repository, _, _ ->
        val requested = AppSettings(telegramToken = "100:post-compatible")
        val revision = currentSettingsETag()

        client.post("/api/settings") {
            header(HttpHeaders.IfMatch, revision)
            contentType(ContentType.Application.Json)
            setBody(completeSettingsJson.encodeToString(requested))
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertNotNull(headers[HttpHeaders.ETag])
        }
        assertEquals(requested, repository.settingsFlow.value)
    }

    /**
     * 验证读取和成功写入响应包含严格 PUT 所需的默认值及显式 null，可直接作为完整请求体回写。
     */
    @Test
    fun `settings responses are complete strict PUT representations`() = withTestApi { repository, _, _ ->
        val original = AppSettings(
            telegramToken = "100:roundtrip",
            proxy = ProxySettings("proxy.example.com", 1080, ProxyType.HTTP, username = null, password = null),
            ai = AISettings(),
        )
        repository.saveSettings(original)

        val getResponse = client.get("/api/settings")
        assertEquals(HttpStatusCode.OK, getResponse.status)
        val body = getResponse.bodyAsText()
        assertTrue(body.contains("\"username\":null"))
        assertTrue(body.contains("\"password\":null"))
        assertTrue(body.contains("\"httpToolSettings\""))
        val revision = assertNotNull(getResponse.headers[HttpHeaders.ETag])

        client.put("/api/settings") {
            header(HttpHeaders.IfMatch, revision)
            contentType(ContentType.Application.Json)
            setBody(body)
        }.apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals(original, completeSettingsJson.decodeFromString<AppSettings>(bodyAsText()))
        }
        assertEquals(original, repository.settingsFlow.value)
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

    private suspend fun ApplicationTestBuilder.currentSettingsETag(): String =
        client.get("/api/settings").headers[HttpHeaders.ETag]
            ?: error("设置读取响应缺少 ETag")
}
