package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.HttpCallTarget
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置仓储与模型切换屏障协作的测试设计。
 */
class SettingsRepositoryBarrierTest {
    private val tempDirectory = createTempDirectory("settings-barrier-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证会开启模型切换屏障的设置范围。
     *
     * 验证仅影响代理生命周期的设置变更会创建屏障代次。
     */
    @Test
    fun `only agent lifecycle settings open a model switch barrier`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        var settings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "openai-key",
                agentEnabled = true,
                selectedModel = "gpt-first",
            ),
        )
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        val initialGeneration = barrier.latestPendingGeneration()
        barrier.complete(initialGeneration)

        settings = settings.copy(ai = settings.ai!!.copy(globalContext = "new context"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(agentChatId = "new-agent-chat"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(selectedModel = "gpt-next"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(geminiApiKey = "unused-key"))
        repository.saveSettings(settings)
        assertFalse(barrier.isSwitching)

        settings = settings.copy(ai = settings.ai!!.copy(openAiBaseUrl = "https://example.invalid/v1"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(agentEnabled = false))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证 HTTP 工具边界变更会进入代理生命周期屏障，避免旧会话继续声明旧目标。
     */
    @Test
    fun `HTTP tool settings changes open a model switch barrier`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "http-tool-barrier.json"), barrier)
        val initial = AppSettings(ai = AISettings(agentEnabled = true, geminiApiKey = "key"))
        repository.saveSettings(initial)
        barrier.complete(barrier.latestPendingGeneration())

        repository.saveSettings(
            initial.copy(
                ai = initial.ai!!.copy(
                    httpToolSettings = HttpToolSettings(
                        targets = listOf(HttpCallTarget("public", host = "api.example.com", path = "/v1/status")),
                    ),
                ),
            ),
        )

        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证 MCP 连接配置变化会阻止旧代理继续使用其工具声明。
     */
    @Test
    fun `MCP server settings changes open a model switch barrier`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "mcp-server-barrier.json"), barrier)
        val initial = AppSettings(
            ai = AISettings(
                agentEnabled = true,
                geminiApiKey = "key",
                mcpServers = listOf(MCPServerConfig("first", "https://first.example/mcp")),
            ),
        )
        repository.saveSettings(initial)
        barrier.complete(barrier.latestPendingGeneration())

        repository.saveSettings(
            initial.copy(
                ai = initial.ai!!.copy(
                    mcpServers = listOf(MCPServerConfig("second", "https://second.example/mcp")),
                ),
            ),
        )

        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证历史非法 HTTP 工具设置在加载时安全禁用，且仓储不会持久化新的非法目标。
     */
    @Test
    fun `invalid persisted HTTP tool settings fail closed and saves are validated`() {
        val configFile = File(tempDirectory, "invalid-http-tool.json")
        configFile.writeText(
            """
            {
              "ai": {
                "httpToolSettings": {
                  "enabled": true,
                  "targets": [{
                    "id": "unsafe",
                    "scheme": "http",
                    "host": "localhost",
                    "port": 8080,
                    "path": "/admin",
                    "method": "GET"
                  }]
                }
              }
            }
            """.trimIndent(),
        )
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertFalse(repository.settingsFlow.value.ai!!.httpToolSettings.enabled)
        assertTrue(repository.settingsFlow.value.ai!!.httpToolSettings.targets.isEmpty())
        assertFailsWith<IllegalArgumentException> {
            repository.saveSettings(
                AppSettings(
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
                ),
            )
        }
    }

    /**
     * 验证无关保存对待处理屏障代次的传递设计。
     *
     * 验证最新设置快照会携带仍待处理的最高代次。
     */
    @Test
    fun `unrelated save carries an open generation to its latest settings snapshot`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "key", agentEnabled = true),
        )
        repository.saveSettings(initialSettings)
        val firstUpdate = repository.settingsUpdateFlow.value
        val generation = firstUpdate.switchGeneration

        val latestSettings = initialSettings.copy(chatId = "new-chat-id")
        repository.saveSettings(latestSettings)
        val latestUpdate = repository.settingsUpdateFlow.value

        assertEquals(latestSettings, latestUpdate.settings)
        assertEquals(generation, latestUpdate.switchGeneration)
    }

    /**
     * 验证合并设置快照释放屏障代次的设计。
     *
     * 验证最新生命周期快照会释放被合并的较早代次。
     */
    @Test
    fun `latest lifecycle snapshot releases conflated earlier generations`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        val firstSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.GEMINI,
                geminiApiKey = "key",
                agentEnabled = true,
            ),
        )

        repository.saveSettings(firstSettings)
        val firstGeneration = repository.settingsUpdateFlow.value.switchGeneration

        val latestSettings = firstSettings.copy(
            ai = firstSettings.ai!!.copy(selectedModel = "gemini-next"),
        )
        repository.saveSettings(latestSettings)
        val latestUpdate = repository.settingsUpdateFlow.value

        assertEquals(latestSettings, latestUpdate.settings)
        assertTrue(latestUpdate.switchGeneration!! > firstGeneration!!)

        // 模拟首次更新被 StateFlow 合并后，生命周期收集器只能观察到
        // 最新快照的情况。
        barrier.completeThrough(latestUpdate.switchGeneration)

        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证历史非法 OpenAI 地址保留原始值，且只有显式地址替换可以解除写入保护。
     */
    @Test
    fun `historical invalid OpenAI base URL is preserved until explicitly replaced`() {
        val configFile = File(tempDirectory, "historical-invalid-openai-url.json")
        val originalContent =
            """{"chatId":"old-chat","ai":{"provider":"OPENAI","openAiApiKey":"key","openAiBaseUrl":"https://gateway.example.com/v1/%6dodels","agentEnabled":true}}"""
        configFile.writeText(originalContent)
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertTrue(repository.hasHistoricalInvalidOpenAiBaseUrl)
        assertEquals("https://gateway.example.com/v1/%6dodels", repository.settingsFlow.value.ai?.openAiBaseUrl)
        assertEquals(originalContent, configFile.readText())

        assertFailsWith<HistoricalInvalidOpenAiBaseUrlConfigurationException> {
            repository.updateSettings { current -> current.copy(chatId = "unrelated-change") }
        }
        assertEquals(originalContent, configFile.readText())

        repository.updateSettings(replacesHistoricalInvalidOpenAiBaseUrl = true) { current ->
            current.copy(ai = current.ai!!.copy(openAiBaseUrl = "https://gateway.example.com/v1"))
        }

        assertFalse(repository.hasHistoricalInvalidOpenAiBaseUrl)
        assertEquals("https://gateway.example.com/v1", repository.settingsFlow.value.ai?.openAiBaseUrl)
        assertFalse(configFile.readText() == originalContent)
    }

    /**
     * 验证 Gemini 可以保留休眠的历史 OpenAI 地址；保护标记不清空该原始字段。
     */
    @Test
    fun `dormant historical invalid OpenAI base URL is retained for Gemini settings`() {
        val configFile = File(tempDirectory, "dormant-invalid-openai-url.json")
        val originalContent =
            """{"ai":{"provider":"GEMINI","geminiApiKey":"key","openAiBaseUrl":"https://gateway.example.com/v1/chat/%63ompletions","agentEnabled":true}}"""
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals(AIProvider.GEMINI, repository.settingsFlow.value.ai?.provider)
        assertEquals("key", repository.settingsFlow.value.ai?.geminiApiKey)
        assertEquals(
            "https://gateway.example.com/v1/chat/%63ompletions",
            repository.settingsFlow.value.ai?.openAiBaseUrl
        )
        assertTrue(repository.hasHistoricalInvalidOpenAiBaseUrl)
        assertEquals(originalContent, configFile.readText())
    }

    /**
     * 验证损坏主文件不会读取遗留 `.bak` 中的历史 OpenAI 地址。
     */
    @Test
    fun `damaged primary ignores legacy bak historical invalid OpenAI base URL`() {
        val configFile = File(tempDirectory, "invalid-openai-primary.json")
        val sidecarFile = File(tempDirectory, "invalid-openai-primary.json.bak")
        val historicalContent =
            """{"ai":{"provider":"OPENAI","openAiApiKey":"key","openAiBaseUrl":"https://gateway.example.com/v1/audio/%74ranscriptions","agentEnabled":true}}"""
        configFile.writeText("{ damaged")
        sidecarFile.writeText(historicalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())

        assertFalse(repository.hasHistoricalInvalidOpenAiBaseUrl)
        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertEquals("{ damaged", configFile.readText())
        assertEquals(historicalContent, sidecarFile.readText())
    }

    /**
     * 验证设置快照只覆盖设置代次，不能让设置处理器完成独立的认证清理代次。
     */
    @Test
    fun `settings snapshot excludes an earlier external barrier generation`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "external-generation.json"), barrier)
        val authenticationGeneration = barrier.beginExternalSwitch()

        repository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "key", agentEnabled = true)),
        )
        val settingsGeneration = repository.settingsUpdateFlow.value.switchGeneration

        assertEquals(barrier.latestPendingSettingsGeneration(), settingsGeneration)
        barrier.completeSettingsThrough(settingsGeneration)
        assertTrue(barrier.isSwitching)

        barrier.complete(authenticationGeneration)
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证设置写入失败时的屏障回滚设计。
     *
     * 验证失败写入创建的屏障代次会被取消。
     */
    @Test
    fun `a failed settings write cancels its switch generation`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "settings.json")
        val repository = SettingsRepository.forTesting(configFile, barrier)

        tempDirectory.deleteRecursively()
        tempDirectory.writeText("not a directory")

        assertFailsWith<IOException> {
            repository.saveSettings(AppSettings(ai = AISettings(agentEnabled = true)))
        }
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证每次 Telegram token 实际变化都会递增独立代次，普通设置保存不会重启轮询生命周期。
     */
    @Test
    fun `telegram token generation records rapid restoration without unrelated changes`() {
        val repository =
            SettingsRepository.forTesting(File(tempDirectory, "token-generation.json"), ModelSwitchBarrier())
        val initialGeneration = repository.telegramTokenUpdateFlow.value.generation

        repository.saveSettings(AppSettings(telegramToken = "100:A"))
        val firstGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.saveSettings(AppSettings(telegramToken = ""))
        val emptyGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.saveSettings(AppSettings(telegramToken = "100:A"))
        val restoredGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.saveSettings(AppSettings(telegramToken = "100:A", chatId = "unchanged-token"))

        assertEquals(initialGeneration + 1, firstGeneration)
        assertEquals(firstGeneration + 1, emptyGeneration)
        assertEquals(emptyGeneration + 1, restoredGeneration)
        assertEquals(restoredGeneration, repository.telegramTokenUpdateFlow.value.generation)
    }

    /**
     * 验证非法代理会在创建屏障、写盘和发布设置流之前被拒绝。
     */
    @Test
    fun `invalid proxy save has no side effects`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "invalid-proxy-settings.json")
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val initialSettings = AppSettings(telegramToken = "100:original", chatId = "original-chat")
        repository.saveSettings(initialSettings)
        val originalContent = configFile.readText()
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value

        listOf(
            ProxySettings("proxy.example.com", 65536, ProxyType.HTTP),
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, username = "user"),
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, password = "password"),
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, username = " ", password = " "),
            ProxySettings("proxy.example.com", 1080, ProxyType.SOCKS, username = "user", password = "password"),
        ).forEach { invalidProxy ->
            assertFailsWith<IllegalArgumentException> {
                repository.saveSettings(initialSettings.copy(proxy = invalidProxy))
            }
        }

        assertEquals(initialSettings, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertEquals(originalContent, configFile.readText())
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证未知历史代理类型不会丢弃其他设置，也不会重写原始配置文件。
     */
    @Test
    fun `unknown historical proxy type preserves the remaining settings without rewriting the file`() {
        val configFile = File(tempDirectory, "unknown-proxy-type.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"},"ai":{"provider":"OPENAI","openAiApiKey":"key","agentEnabled":true}}
            """.trimIndent()
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("100:token", repository.settingsFlow.value.telegramToken)
        assertEquals("chat", repository.settingsFlow.value.chatId)
        assertEquals(AIProvider.OPENAI, repository.settingsFlow.value.ai?.provider)
        assertEquals("key", repository.settingsFlow.value.ai?.openAiApiKey)
        assertEquals(null, repository.settingsFlow.value.proxy)
        assertTrue(repository.hasHistoricalInvalidProxy)
        assertEquals(originalContent, configFile.readText())
    }

    /**
     * 验证历史非法代理必须由完整设置显式替换为合法代理，复制当前设置的保存没有副作用。
     */
    @Test
    fun `historical invalid proxy rejects copied settings until a valid proxy explicitly resolves it`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "historical-proxy-resolution.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"},"ai":{"provider":"GEMINI","geminiApiKey":"key"}}
            """.trimIndent()
        configFile.writeText(originalContent)
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val originalSettings = repository.settingsFlow.value
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value

        assertFailsWith<IllegalArgumentException> {
            repository.saveSettings(originalSettings.copy(chatId = "copied-settings-chat"))
        }

        assertEquals(originalSettings, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertTrue(repository.hasHistoricalInvalidProxy)
        assertFalse(barrier.isSwitching)
        assertEquals(originalContent, configFile.readText())

        val resolvedSettings = originalSettings.copy(
            chatId = "resolved-chat",
            proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS),
        )
        repository.saveSettings(resolvedSettings)

        assertEquals(resolvedSettings, repository.settingsFlow.value)
        assertFalse(repository.hasHistoricalInvalidProxy)
        assertTrue(barrier.isSwitching)
    }

    /** 验证历史非法认证凭据会禁用代理，并要求显式提供合法替代设置。 */
    @Test
    fun `historical invalid proxy credentials fail closed until explicitly replaced`() {
        val invalidProxies = listOf(
            """{"host":"proxy.example.com","port":8080,"type":"HTTP","username":"user"}""" to
                    ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, "user", "password"),
            """{"host":"proxy.example.com","port":8080,"type":"HTTP","password":"password"}""" to
                    ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, "user", "password"),
            """{"host":"proxy.example.com","port":8080,"type":"HTTP","username":" ","password":" "}""" to
                    ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, "user", "password"),
            """{"host":"proxy.example.com","port":1080,"type":"SOCKS","username":"user","password":"password"}""" to
                    ProxySettings("proxy.example.com", 1080, ProxyType.SOCKS),
        )

        invalidProxies.forEachIndexed { index, (proxyJson, replacementProxy) ->
            val configFile = File(tempDirectory, "historical-proxy-credentials-$index.json")
            val originalContent = """{"telegramToken":"100:token","chatId":"chat","proxy":$proxyJson}"""
            configFile.writeText(originalContent)
            val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

            assertEquals(null, repository.settingsFlow.value.proxy)
            assertTrue(repository.hasHistoricalInvalidProxy)
            assertFailsWith<IllegalArgumentException> {
                repository.saveSettings(repository.settingsFlow.value.copy(chatId = "new-chat"))
            }
            assertEquals(originalContent, configFile.readText())

            val replacement = repository.settingsFlow.value.copy(proxy = replacementProxy)
            repository.saveSettings(replacement)
            assertEquals(replacement, repository.settingsFlow.value)
            assertFalse(repository.hasHistoricalInvalidProxy)
        }
    }

    /**
     * 验证历史非法 MCP 列表 fail-closed 为无连接列表，保留其余 AI 设置和原始文件直到显式替换。
     */
    @Test
    fun `historical invalid MCP configuration preserves AI settings until explicitly replaced`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "historical-invalid-mcp.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","ai":{"provider":"OPENAI","openAiApiKey":"key","agentEnabled":true,"mcpServers":[{"name":"unsafe","url":"ftp://mcp.example.com","headers":{}}]}}
            """.trimIndent()
        configFile.writeText(originalContent)
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val recoveredSettings = repository.settingsFlow.value

        assertEquals(AIProvider.OPENAI, recoveredSettings.ai?.provider)
        assertEquals("key", recoveredSettings.ai?.openAiApiKey)
        assertTrue(recoveredSettings.ai?.mcpServers.orEmpty().isEmpty())
        assertTrue(repository.hasHistoricalInvalidMcp)
        assertEquals(originalContent, configFile.readText())

        assertFailsWith<IllegalArgumentException> {
            repository.updateSettings { current -> current.copy(chatId = "unrelated-change") }
        }
        assertEquals(recoveredSettings, repository.settingsFlow.value)
        assertEquals(originalContent, configFile.readText())
        assertFalse(barrier.isSwitching)

        val resolved = recoveredSettings.copy(
            chatId = "resolved-chat",
            ai = recoveredSettings.ai!!.copy(mcpServers = emptyList()),
        )
        repository.updateSettings(replacesHistoricalInvalidMcpServers = true) { resolved }

        assertEquals(resolved, repository.settingsFlow.value)
        assertFalse(repository.hasHistoricalInvalidMcp)
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证损坏主文件不会读取遗留 `.bak` 中的历史非法 MCP 配置。
     */
    @Test
    fun `damaged primary ignores legacy bak historical invalid MCP configuration`() {
        val configFile = File(tempDirectory, "invalid-mcp-primary.json")
        val sidecarFile = File(tempDirectory, "invalid-mcp-primary.json.bak")
        val sidecarContent =
            """
            {"telegramToken":"100:token","ai":{"geminiApiKey":"key","mcpServers":[{"name":"bad","url":"https://user:secret@mcp.example.com","headers":{}}]}}
            """.trimIndent()
        configFile.writeText("{ invalid")
        sidecarFile.writeText(sidecarContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())

        assertTrue(repository.settingsFlow.value.ai?.mcpServers.orEmpty().isEmpty())
        assertFalse(repository.hasHistoricalInvalidMcp)
        assertEquals("{ invalid", configFile.readText())
        assertEquals(sidecarContent, sidecarFile.readText())
    }

    /**
     * 验证缺少类型且端口非法的旧代理不会被迁移或改写，并保留其他设置。
     */
    @Test
    fun `invalid old proxy without a type remains untouched and preserves the remaining settings`() {
        val configFile = File(tempDirectory, "invalid-old-proxy.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":70000},"ai":{"provider":"GEMINI","geminiApiKey":"key"}}
            """.trimIndent()
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("100:token", repository.settingsFlow.value.telegramToken)
        assertEquals("chat", repository.settingsFlow.value.chatId)
        assertEquals(AIProvider.GEMINI, repository.settingsFlow.value.ai?.provider)
        assertEquals("key", repository.settingsFlow.value.ai?.geminiApiKey)
        assertEquals(null, repository.settingsFlow.value.proxy)
        assertTrue(repository.hasHistoricalInvalidProxy)
        assertEquals(originalContent, configFile.readText())
    }

    /**
     * 验证主文件语义损坏时返回安全默认值，且不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `damaged settings primary ignores legacy bak`() {
        val configFile = File(tempDirectory, "recover-settings.json")
        val backupContent = "{\n  \"telegramToken\": \"100:backup\",\n  \"chatId\": \"backup-chat\"\n}\n"
        configFile.writeText("{ invalid")
        File(tempDirectory, "recover-settings.json.bak").writeText(backupContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertEquals("{ invalid", configFile.readText())
        assertEquals(backupContent, File(tempDirectory, "recover-settings.json.bak").readText())
    }

    /**
     * 验证主替换失败会取消新屏障，且不会推进设置流或 token 代次。
     */
    @Test
    fun `primary replace failure leaves settings flows and barrier unchanged`() {
        val configFile = File(tempDirectory, "primary-replace-failure.json")
        val initial = AppSettings(telegramToken = "100:old", chatId = "old-chat")
        configFile.writeText(ConfigJson.encodeToString(initial))
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == configFile.toPath()) {
                    throw IOException("injected primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(configFile, barrier, fileOperations)
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value
        val originalContent = configFile.readText()

        assertFailsWith<IOException> {
            repository.saveSettings(
                initial.copy(
                    telegramToken = "200:new",
                    ai = AISettings(agentEnabled = true),
                ),
            )
        }

        assertEquals(initial, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertEquals(originalContent, configFile.readText())
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证损坏主文件后续保存被拒绝，且不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `damaged settings primary disables later saves without touching legacy bak`() {
        val configFile = File(tempDirectory, "settings-recovery-failure.json")
        val backupFile = File(tempDirectory, "settings-recovery-failure.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup = ConfigJson.encodeToString(AppSettings(telegramToken = "100:backup"))
        configFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertFailsWith<IllegalStateException> {
            repository.saveSettings(AppSettings(telegramToken = "200:new"))
        }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())
    }

    /**
     * 验证主设置文件缺失时使用默认值，且不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `missing settings primary ignores legacy bak`() {
        val configFile = File(tempDirectory, "missing-settings.json")
        val backupFile = File(tempDirectory, "missing-settings.json.bak")
        val backupContent = "{\n  \"telegramToken\": \"100:backup\",\n  \"chatId\": \"backup-chat\"\n}\n"
        backupFile.writeText(backupContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertFalse(configFile.exists())
        assertEquals(backupContent, backupFile.readText())
    }

    /**
     * 验证双坏设置文件只提供安全默认值，后续保存不会覆盖任一现场文件。
     */
    @Test
    fun `double damaged settings files reject saves without overwriting either file`() {
        val configFile = File(tempDirectory, "double-damaged-settings.json")
        val backupFile = File(tempDirectory, "double-damaged-settings.json.bak")
        val damagedPrimary = "{ invalid"
        val damagedBackup = "[ invalid"
        configFile.writeText(damagedPrimary)
        backupFile.writeText(damagedBackup)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(damagedBackup, backupFile.readText())
    }

    /** 验证主文件损坏时遗留 `.bak` 文件不可读也不会影响安全失败。 */
    @Test
    fun `damaged settings primary does not access unreadable legacy bak`() {
        val configFile = File(tempDirectory, "pending-settings.json")
        val backupFile = File(tempDirectory, "pending-settings.json.bak")
        configFile.writeText("{ invalid")
        backupFile.writeText("{\"telegramToken\":\"100:ignored\"}")

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())

        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:blocked")) }
        assertEquals("{ invalid", configFile.readText())
        assertEquals("{\"telegramToken\":\"100:ignored\"}", backupFile.readText())
    }

    /**
     * 验证首次主文件读取 I/O 失败会阻断默认状态写入；主文件恢复可读但仍损坏时继续拒绝保存。
     */
    @Test
    fun `initial primary read failure is revalidated without reading legacy bak`() {
        val configFile = File(tempDirectory, "initial-io-settings.json")
        val backupFile = File(tempDirectory, "initial-io-settings.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup = "{\n  \"telegramToken\": \"100:ignored\"\n}\n"
        configFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        var blockPrimaryRead = true
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                if (blockPrimaryRead && path == configFile.toPath()) {
                    throw IOException("injected primary read failure")
                }
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be read" }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }
        }

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:blocked")) }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())

        blockPrimaryRead = false
        assertFailsWith<IllegalStateException> {
            repository.saveSettings(AppSettings(telegramToken = "200:committed"))
        }
        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())
    }

    /**
     * 验证损坏主文件不会从遗留 `.bak` 发布设置快照。
     */
    @Test
    fun `damaged primary never publishes legacy bak sidecar snapshot`() {
        val configFile = File(tempDirectory, "invalid-primary-no-sidecar-snapshot.json")
        val sidecarFile = File(tempDirectory, "invalid-primary-no-sidecar-snapshot.json.bak")
        val ignoredSettings = AppSettings(telegramToken = "100:ignored", chatId = "ignored-chat")
        val sidecarContent = ConfigJson.encodeToString(ignoredSettings)
        configFile.writeText("{ invalid")
        sidecarFile.writeText(sidecarContent)
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be read" }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }
        }
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertEquals(0, repository.telegramTokenUpdateFlow.value.generation)
        assertEquals("{ invalid", configFile.readText())
        assertEquals(sidecarContent, sidecarFile.readText())
    }

    private fun rejectBakOperations(): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be read" }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }

            override fun writeAndForce(path: Path, bytes: ByteArray) {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be written" }
                DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
            }
        }
}
