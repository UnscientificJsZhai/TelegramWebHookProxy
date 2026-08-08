package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.*
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * 设置仓储与模型切换屏障协作的测试设计。
 */
class SettingsRepositoryBarrierTest {
    private val tempDirectory = createTempDirectory("settings-barrier-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    /** 设置入口会拒绝非法 UTF-8 与未知 v1 版本，且绝不改写待恢复的原始字节。 */
    @Test
    fun `settings load preserves malformed UTF8 and future version bytes`() {
        val cases = listOf(
            "malformed-utf8" to ("{\"telegramToken\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\"}".encodeToByteArray()),
            "future-version" to """{"schemaVersion":2,"data":{"telegramToken":"future"}}""".encodeToByteArray(),
        )

        cases.forEach { (name, original) ->
            val configFile = File(tempDirectory, "$name-settings.json")
            Files.write(configFile.toPath(), original)

            assertFailsWith<IllegalStateException> {
                SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
            }
            assertEquals(original.toList(), Files.readAllBytes(configFile.toPath()).toList())
        }
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
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
        val initialGeneration = barrier.latestPendingGeneration()
        barrier.complete(initialGeneration)

        settings = settings.copy(ai = settings.ai!!.copy(globalContext = "new context"))
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(agentChatId = "new-agent-chat"))
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(selectedModel = "gpt-next"))
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(geminiApiKey = "unused-key"))
        repository.replaceSettingsForTest(settings)
        assertFalse(barrier.isSwitching)

        settings = settings.copy(ai = settings.ai!!.copy(openAiBaseUrl = "https://example.invalid/v1"))
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS))
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(agentEnabled = false))
        repository.replaceSettingsForTest(settings)
        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证仅变更 Telegram token 也会在新设置发布前开启代理生命周期屏障。
     */
    @Test
    fun `Telegram token changes open a model switch barrier before settings publication`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "telegram-token-barrier.json"), barrier)
        val initial = AppSettings(
            telegramToken = "100:token-a",
            ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key", agentEnabled = true),
        )
        repository.replaceSettingsForTest(initial)
        barrier.complete(barrier.latestPendingGeneration())

        repository.replaceSettingsForTest(initial.copy(telegramToken = "200:token-b"))

        assertTrue(barrier.isSwitching)
        assertNotNull(repository.settingsUpdateFlow.value.switchGeneration)
        assertEquals(barrier.latestPendingGeneration(), repository.settingsUpdateFlow.value.switchGeneration)
    }

    /**
     * 验证 HTTP 工具边界变更会进入代理生命周期屏障，避免旧会话继续声明旧目标。
     */
    @Test
    fun `HTTP tool settings changes open a model switch barrier`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "http-tool-barrier.json"), barrier)
        val initial = AppSettings(ai = AISettings(agentEnabled = true, geminiApiKey = "key"))
        repository.replaceSettingsForTest(initial)
        barrier.complete(barrier.latestPendingGeneration())

        repository.replaceSettingsForTest(
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
        repository.replaceSettingsForTest(initial)
        barrier.complete(barrier.latestPendingGeneration())

        repository.replaceSettingsForTest(
            initial.copy(
                ai = initial.ai!!.copy(
                    mcpServers = listOf(MCPServerConfig("second", "https://second.example/mcp")),
                ),
            ),
        )

        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证旧代理迁移与历史非法 HTTP 工具配置同时存在时，候选无关更新不会覆盖原始字节或发布状态。
     */
    @Test
    fun `legacy proxy migration preserves historical invalid HTTP tool configuration until explicit replacement`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "legacy-proxy-historical-invalid-http-tool.json")
        configFile.writeText(
            """
            {"telegramToken":"100:token","chatId":"old-chat","proxy":{"host":"proxy.example.com","port":8080},"ai":{"provider":"GEMINI","geminiApiKey":"key","httpToolSettings":{"enabled":true,"targets":[{"id":"unsafe","scheme":"http","host":"localhost","port":8080,"path":"/admin","method":"GET"}]}}}
            """.trimIndent(),
        )
        val originalBytes = configFile.readBytes().toList()
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val recoveredSettings = repository.settingsFlow.value
        val recoveredSnapshot = repository.currentSettingsSnapshot()
        val recoveredSettingsUpdate = repository.settingsUpdateFlow.value
        val recoveredTokenUpdate = repository.telegramTokenUpdateFlow.value
        val recoveredAi = requireNotNull(recoveredSettings.ai)

        assertEquals(ProxySettings("proxy.example.com", 8080, ProxyType.HTTP), recoveredSettings.proxy)
        assertFalse(recoveredAi.httpToolSettings.enabled)
        assertTrue(recoveredAi.httpToolSettings.targets.isEmpty())
        assertTrue(repository.hasHistoricalInvalidHttpToolSettings)
        var staleTransformWasCalled = false
        assertFailsWith<SettingsRevisionMismatchException> {
            repository.updateSettings(expectedRevision = "0".repeat(64)) {
                staleTransformWasCalled = true
                it.copy(chatId = "stale-candidate")
            }
        }
        assertFalse(staleTransformWasCalled)
        var transformWasCalled = false

        assertFailsWith<HistoricalInvalidHttpToolConfigurationException> {
            repository.updateSettings { current ->
                transformWasCalled = true
                current.copy(chatId = "candidate-change")
            }
        }

        assertTrue(transformWasCalled)
        assertEquals(recoveredSettings, repository.settingsFlow.value)
        assertEquals(recoveredSnapshot, repository.currentSettingsSnapshot())
        assertEquals(recoveredSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(recoveredTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertFalse(barrier.isSwitching)
        assertEquals(originalBytes, configFile.readBytes().toList())
    }

    /**
     * 验证 HTTP 历史保护与 MCP、OpenAI 历史保护相互独立；即使已授权其他保护字段，遗漏 HTTP 授权仍会失败。
     */
    @Test
    fun `historical invalid HTTP tool settings require their own explicit replacement authorization`() {
        data class HistoryCase(
            val name: String,
            val mcpFragment: String = "",
            val openAiFragment: String = "",
            val authorizesMcp: Boolean = false,
            val authorizesOpenAiBaseUrl: Boolean = false,
        )

        val unsafeHttpToolFragment =
            """"httpToolSettings":{"enabled":true,"targets":[{"id":"unsafe","scheme":"http","host":"localhost","port":8080,"path":"/admin","method":"GET"}]}"""
        val cases = listOf(
            HistoryCase(name = "http-only"),
            HistoryCase(
                name = "http-and-mcp",
                mcpFragment = ",\"mcpServers\":[{\"name\":\"unsafe\",\"url\":\"ftp://mcp.example.com\",\"headers\":{}}]",
                authorizesMcp = true,
            ),
            HistoryCase(
                name = "http-and-openai",
                openAiFragment = ",\"openAiBaseUrl\":\"https://gateway.example.com/v1/%6dodels\"",
                authorizesOpenAiBaseUrl = true,
            ),
            HistoryCase(
                name = "all-three",
                mcpFragment = ",\"mcpServers\":[{\"name\":\"unsafe\",\"url\":\"ftp://mcp.example.com\",\"headers\":{}}]",
                openAiFragment = ",\"openAiBaseUrl\":\"https://gateway.example.com/v1/%6dodels\"",
                authorizesMcp = true,
                authorizesOpenAiBaseUrl = true,
            ),
        )

        cases.forEach { case ->
            val configFile = File(tempDirectory, "historical-invalid-http-tool-${case.name}.json")
            configFile.writeText(
                """{"chatId":"old-chat","ai":{"provider":"OPENAI","openAiApiKey":"key",$unsafeHttpToolFragment${case.mcpFragment}${case.openAiFragment}}}""",
            )
            val originalBytes = configFile.readBytes().toList()
            val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

            assertTrue(repository.hasHistoricalInvalidHttpToolSettings)
            assertEquals(case.authorizesMcp, repository.hasHistoricalInvalidMcp)
            assertEquals(case.authorizesOpenAiBaseUrl, repository.hasHistoricalInvalidOpenAiBaseUrl)
            assertFailsWith<HistoricalInvalidHttpToolConfigurationException> {
                repository.updateSettings(
                    replacesHistoricalInvalidMcpServers = case.authorizesMcp,
                    replacesHistoricalInvalidOpenAiBaseUrl = case.authorizesOpenAiBaseUrl,
                ) { current ->
                    current.copy(chatId = "candidate-change")
                }
            }
            assertTrue(repository.hasHistoricalInvalidHttpToolSettings)
            assertEquals(case.authorizesMcp, repository.hasHistoricalInvalidMcp)
            assertEquals(case.authorizesOpenAiBaseUrl, repository.hasHistoricalInvalidOpenAiBaseUrl)
            assertEquals(originalBytes, configFile.readBytes().toList())
        }
    }

    /** 验证显式以 fail-closed 默认 HTTP 设置修复时会耐久落盘并清除保护，而不会发布伪设置更新。 */
    @Test
    fun `explicit default HTTP tool repair commits without publishing a settings update`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "historical-invalid-http-tool-default-repair.json")
        configFile.writeText(
            """{"ai":{"httpToolSettings":{"enabled":true,"targets":[{"id":"unsafe","scheme":"http","host":"localhost","port":8080,"path":"/admin","method":"GET"}]}}}""",
        )
        val originalBytes = configFile.readBytes().toList()
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val beforeSnapshot = repository.currentSettingsSnapshot()
        val beforeSettingsUpdate = repository.settingsUpdateFlow.value
        val beforeTokenUpdate = repository.telegramTokenUpdateFlow.value

        val update = repository.updateSettings(replacesHistoricalInvalidHttpToolSettings = true) { current -> current }

        assertEquals(beforeSnapshot, update.previous)
        assertEquals(beforeSnapshot, update.current)
        assertEquals(beforeSnapshot, repository.currentSettingsSnapshot())
        assertEquals(beforeSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(beforeTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertFalse(barrier.isSwitching)
        assertFalse(repository.hasHistoricalInvalidHttpToolSettings)
        assertFalse(originalBytes == configFile.readBytes().toList())
    }

    /** 验证显式 HTTP 默认修复在原子替换失败时保留历史保护标记与已发布状态。 */
    @Test
    fun `failed explicit HTTP tool repair retains the historical protection marker`() {
        val configFile = File(tempDirectory, "historical-invalid-http-tool-repair-failure.json")
        configFile.writeText(
            """{"ai":{"httpToolSettings":{"enabled":true,"targets":[{"id":"unsafe","scheme":"http","host":"localhost","port":8080,"path":"/admin","method":"GET"}]}}}""",
        )
        val originalBytes = configFile.readBytes().toList()
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == configFile.toPath()) {
                    throw IOException("injected HTTP repair replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(configFile, barrier, fileOperations)
        val beforeSnapshot = repository.currentSettingsSnapshot()
        val beforeSettingsUpdate = repository.settingsUpdateFlow.value
        val beforeTokenUpdate = repository.telegramTokenUpdateFlow.value

        assertFailsWith<IOException> {
            repository.updateSettings(replacesHistoricalInvalidHttpToolSettings = true) { current -> current }
        }

        assertTrue(repository.hasHistoricalInvalidHttpToolSettings)
        assertEquals(beforeSnapshot, repository.currentSettingsSnapshot())
        assertEquals(beforeSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(beforeTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertFalse(barrier.isSwitching)
        assertEquals(originalBytes, configFile.readBytes().toList())
    }

    /** 验证完整设置替换授权并清除 HTTP、MCP 与 OpenAI 的全部历史保护标记。 */
    @Test
    fun `replaceSettingsForTest fully replaces all historical invalid AI configuration markers`() {
        val configFile = File(tempDirectory, "all-historical-invalid-ai-settings.json")
        configFile.writeText(
            """{"chatId":"old-chat","ai":{"provider":"OPENAI","openAiApiKey":"key","openAiBaseUrl":"https://gateway.example.com/v1/%6dodels","mcpServers":[{"name":"unsafe","url":"ftp://mcp.example.com","headers":{}}],"httpToolSettings":{"enabled":true,"targets":[{"id":"unsafe","scheme":"http","host":"localhost","port":8080,"path":"/admin","method":"GET"}]}}}""",
        )
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
        val recovered = repository.settingsFlow.value

        assertTrue(repository.hasHistoricalInvalidMcp)
        assertTrue(repository.hasHistoricalInvalidOpenAiBaseUrl)
        assertTrue(repository.hasHistoricalInvalidHttpToolSettings)
        repository.replaceSettingsForTest(
            recovered.copy(
                chatId = "fully-replaced",
                ai = recovered.ai!!.copy(openAiBaseUrl = "https://gateway.example.com/v1"),
            ),
        )

        assertFalse(repository.hasHistoricalInvalidMcp)
        assertFalse(repository.hasHistoricalInvalidOpenAiBaseUrl)
        assertFalse(repository.hasHistoricalInvalidHttpToolSettings)
        assertEquals("fully-replaced", repository.settingsFlow.value.chatId)
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
        repository.replaceSettingsForTest(initialSettings)
        val firstUpdate = repository.settingsUpdateFlow.value
        val generation = firstUpdate.switchGeneration

        val latestSettings = initialSettings.copy(chatId = "new-chat-id")
        repository.replaceSettingsForTest(latestSettings)
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

        repository.replaceSettingsForTest(firstSettings)
        val firstGeneration = repository.settingsUpdateFlow.value.switchGeneration

        val latestSettings = firstSettings.copy(
            ai = firstSettings.ai!!.copy(selectedModel = "gemini-next"),
        )
        repository.replaceSettingsForTest(latestSettings)
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

    /** 验证主文件和解码后字段超限均中断构造，且不会读取遗留 `.bak`。 */
    @Test
    fun `oversized persisted settings abort construction without accessing legacy bak`() {
        val oversizedCandidates = listOf(
            "primary-bytes" to "x".repeat(ResourceLimits.SETTINGS_BYTES + 1),
            "telegram-token" to ConfigJson.encodeToString(AppSettings(telegramToken = "密".repeat(86))),
            "global-context" to ConfigJson.encodeToString(
                AppSettings(ai = AISettings(globalContext = "密".repeat(21_846))),
            ),
            "oversized-proxy-credentials" to
                    """{"proxy":{"host":"proxy.example.com","port":8080,"type":"HTTP","username":"${"u".repeat(513)}","password":"pass"}}""",
        )

        oversizedCandidates.forEach { (name, primaryContent) ->
            val configFile = File(tempDirectory, "$name.json")
            val sidecarFile = File(tempDirectory, "$name.json.bak")
            val sidecarContent = ConfigJson.encodeToString(AppSettings(telegramToken = "100:ignored"))
            configFile.writeText(primaryContent)
            sidecarFile.writeText(sidecarContent)

            assertFailsWith<IllegalStateException> {
                SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())
            }
            assertEquals(primaryContent, configFile.readText())
            assertEquals(sidecarContent, sidecarFile.readText())
        }
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

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())
        }
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

        repository.replaceSettingsForTest(
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
            repository.replaceSettingsForTest(AppSettings(ai = AISettings(agentEnabled = true)))
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

        repository.replaceSettingsForTest(AppSettings(telegramToken = "100:A"))
        val firstGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.replaceSettingsForTest(AppSettings(telegramToken = ""))
        val emptyGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.replaceSettingsForTest(AppSettings(telegramToken = "100:A"))
        val restoredGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.replaceSettingsForTest(AppSettings(telegramToken = "100:A", chatId = "unchanged-token"))

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
        repository.replaceSettingsForTest(initialSettings)
        barrier.complete(barrier.latestPendingGeneration())
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
                repository.replaceSettingsForTest(initialSettings.copy(proxy = invalidProxy))
            }
        }

        assertEquals(initialSettings, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertEquals(originalContent, configFile.readText())
        assertFalse(barrier.isSwitching)
    }

    /** 验证代理的必填枚举值损坏时中断构造，且不重写现场文件。 */
    @Test
    fun `unknown required proxy type aborts construction without rewriting the file`() {
        val configFile = File(tempDirectory, "unknown-proxy-type.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"},"ai":{"provider":"OPENAI","openAiApiKey":"key","agentEnabled":true}}
            """.trimIndent()
        configFile.writeText(originalContent)

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
        }
        assertEquals(originalContent, configFile.readText())
    }

    /** 验证旧代理缺失 `type` 时在内存中幂等迁移为 HTTP，下次保存写入当前 schema。 */
    @Test
    fun `legacy proxy without type migrates to HTTP through schema storage`() {
        val configFile = File(tempDirectory, "legacy-proxy-without-type.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":8080},"ai":{"provider":"GEMINI","geminiApiKey":"key"}}
            """.trimIndent()
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals(
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP),
            repository.settingsFlow.value.proxy,
        )
        assertFalse(repository.hasHistoricalInvalidProxy)
        assertEquals(originalContent, configFile.readText())

        repository.updateSettings { current -> current.copy(chatId = "migrated") }

        val persisted = ConfigJson.decodeFromString<AppSettings>(configFile.readText())
        assertEquals(ProxyType.HTTP, persisted.proxy?.type)
        assertEquals("migrated", persisted.chatId)
    }

    /** 验证类型或枚举值损坏的可选字段使用 data class 默认值，且非语义 HTTP schema 修复不设置历史保护。 */
    @Test
    fun `invalid optional fields use schema defaults without rewriting the file`() {
        val configFile = File(tempDirectory, "optional-field-defaults.json")
        val originalContent =
            """
            {"telegramToken":42,"chatId":"kept","ai":{"provider":"FUTURE","selectedModel":"kept-model","agentEnabled":"yes","httpToolSettings":"not-an-object"}}
            """.trimIndent()
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("", repository.settingsFlow.value.telegramToken)
        assertEquals("kept", repository.settingsFlow.value.chatId)
        assertEquals(AIProvider.GEMINI, repository.settingsFlow.value.ai?.provider)
        assertEquals("kept-model", repository.settingsFlow.value.ai?.selectedModel)
        assertFalse(repository.settingsFlow.value.ai?.agentEnabled ?: true)
        assertEquals(HttpToolSettings(), repository.settingsFlow.value.ai?.httpToolSettings)
        assertFalse(repository.hasHistoricalInvalidHttpToolSettings)
        assertEquals(originalContent, configFile.readText())

        repository.updateSettings { current -> current.copy(chatId = "updated") }

        assertEquals("updated", repository.settingsFlow.value.chatId)
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
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":70000,"type":"HTTP"},"ai":{"provider":"GEMINI","geminiApiKey":"key"}}
            """.trimIndent()
        configFile.writeText(originalContent)
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val originalSettings = repository.settingsFlow.value
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value

        assertFailsWith<IllegalArgumentException> {
            repository.replaceSettingsForTest(originalSettings.copy(chatId = "copied-settings-chat"))
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
        repository.replaceSettingsForTest(resolvedSettings)

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
                repository.replaceSettingsForTest(repository.settingsFlow.value.copy(chatId = "new-chat"))
            }
            assertEquals(originalContent, configFile.readText())

            val replacement = repository.settingsFlow.value.copy(proxy = replacementProxy)
            repository.replaceSettingsForTest(replacement)
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

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())
        }
        assertEquals("{ invalid", configFile.readText())
        assertEquals(sidecarContent, sidecarFile.readText())
    }

    /**
     * 验证缺少类型且端口非法的旧代理会在内存中迁移后语义性 fail-closed，且不改写原文件。
     */
    @Test
    fun `invalid old proxy without a type migrates then fails closed without rewriting`() {
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
     * 验证主文件严重损坏时中断构造，且不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `damaged settings primary ignores legacy bak`() {
        val configFile = File(tempDirectory, "recover-settings.json")
        val backupContent = "{\n  \"telegramToken\": \"100:backup\",\n  \"chatId\": \"backup-chat\"\n}\n"
        configFile.writeText("{ invalid")
        File(tempDirectory, "recover-settings.json.bak").writeText(backupContent)

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())
        }
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
            repository.replaceSettingsForTest(
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

    /** 验证替换可见但目录同步失败时，不将未确认耐久的值发布给运行时。 */
    @Test
    fun `directory sync uncertainty leaves settings flows and barrier unchanged`() {
        val configFile = File(tempDirectory, "directory-sync-uncertain.json")
        val initial = AppSettings(telegramToken = "100:old", chatId = "old-chat")
        val requested = initial.copy(
            telegramToken = "200:new",
            ai = AISettings(agentEnabled = true),
        )
        configFile.writeText(ConfigJson.encodeToString(initial))
        var directorySyncAvailable = true
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun forceDirectory(path: Path) {
                if (!directorySyncAvailable) throw IOException("injected directory sync failure")
                DefaultAtomicJsonFileOperations.forceDirectory(path)
            }
        }
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(configFile, barrier, fileOperations)
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value
        directorySyncAvailable = false

        assertFailsWith<JsonStorageDurabilityUnknownException> {
            repository.replaceSettingsForTest(requested)
        }

        assertEquals(initial, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertEquals(requested, ConfigJson.decodeFromString<AppSettings>(configFile.readText()))
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证损坏主文件中断构造，且不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `damaged settings primary aborts construction without touching legacy bak`() {
        val configFile = File(tempDirectory, "settings-recovery-failure.json")
        val backupFile = File(tempDirectory, "settings-recovery-failure.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup = ConfigJson.encodeToString(AppSettings(telegramToken = "100:backup"))
        configFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())
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

    /** 验证主文件损坏时构造失败，不会覆盖任一现场文件。 */
    @Test
    fun `damaged primary aborts construction without overwriting either file`() {
        val configFile = File(tempDirectory, "double-damaged-settings.json")
        val backupFile = File(tempDirectory, "double-damaged-settings.json.bak")
        val damagedPrimary = "{ invalid"
        val damagedBackup = "[ invalid"
        configFile.writeText(damagedPrimary)
        backupFile.writeText(damagedBackup)

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
        }
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

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), rejectBakOperations())
        }
        assertEquals("{ invalid", configFile.readText())
        assertEquals("{\"telegramToken\":\"100:ignored\"}", backupFile.readText())
    }

    /**
     * 验证首次主文件读取 I/O 失败会中断构造；主文件恢复可读但仍损坏时同样中断。
     */
    @Test
    fun `initial primary read failure aborts construction without reading legacy bak`() {
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

        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())

        blockPrimaryRead = false
        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        }
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
        assertFailsWith<IllegalStateException> {
            SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        }
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
