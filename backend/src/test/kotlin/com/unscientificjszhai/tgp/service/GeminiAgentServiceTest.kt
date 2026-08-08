package com.unscientificjszhai.tgp.service

import com.google.common.collect.ImmutableList
import com.google.genai.*
import com.google.genai.Chat
import com.google.genai.types.*
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.*
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionRouter
import com.unscientificjszhai.tgp.utils.JsonStructureLimitExceededException
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Gemini 代理服务模型、工具调用和关闭行为的测试设计。
 */
class GeminiAgentServiceTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRepository: com.unscientificjszhai.tgp.repository.SkillRepository
    private lateinit var service: GeminiAgentService
    private lateinit var tempDirectory: File

    @BeforeTest
    fun setup() {
        tempDirectory = Files.createTempDirectory("gemini-agent-service-test").toFile()
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), ModelSwitchBarrier())
        skillRepository =
            com.unscientificjszhai.tgp.repository.SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        service =
            GeminiAgentService(testScope, settingsRepository, skillRepository, MCPClientService(testScope)) { mockk() }
    }

    @AfterTest
    fun teardown() {
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证初始模型状态的设计。
     *
     * 验证服务使用预期默认模型并提供初始可选模型列表。
     */
    @Test
    fun testDefaultModelAndInitialAvailableModels() {
        assertEquals("models/gemini-3.5-flash-lite", service.currentModel)
        assertEquals(
            listOf(
                "models/gemini-3.5-flash-lite",
                "models/gemini-3.1-flash-lite",
                "models/gemini-2.5-flash",
            ),
            service.availableModels,
        )
    }

    /** 原生 Gemini 模型发现会读取后续页，并过滤没有精确声明 `generateContent` 的模型。 */
    @Test
    fun `raw Gemini model discovery paginates and filters unsupported models`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val rawService = rawDiscoveryService(server)
        try {
            rawService.availableModels = listOf("models/first")
            setPrivateField(rawService, "currentModel", "models/first")
            server.enqueue(
                MockResponse.Builder().body(
                    """{"models":[{"name":"models/first","supportedGenerationMethods":["generateContent"]},{"name":"models/embed","supportedGenerationMethods":["embedContent"]},{"name":" ","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"next-page"}""",
                ).build(),
            )
            server.enqueue(
                MockResponse.Builder().body(
                    """{"models":[{"name":"models/first","supportedGenerationMethods":["generateContent"]},{"name":"models/second","supportedGenerationMethods":["generateContent"]},{"name":"models/invalid-capability","supportedGenerationMethods":["GenerateContent"]}]}""",
                ).build(),
            )

            val snapshot = assertNotNull(rawService.updateModel())

            assertEquals(listOf("models/first", "models/second"), snapshot.availableModels)
            assertEquals("models/first", snapshot.currentModel)
            assertTrue(assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target.endsWith("/models?key=test-key"))
            assertTrue(
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS)).target
                    .contains("pageToken=next-page"),
            )
            Unit
        } finally {
            rawService.close().join()
            server.close()
        }
    }

    /** 重复的原生分页令牌会拒绝整个刷新，并保留已发布的模型快照。 */
    @Test
    fun `raw Gemini model discovery rejects repeated page tokens without publishing a partial snapshot`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            val rawService = rawDiscoveryService(server)
            val beforeModels = rawService.availableModels
            val beforeCurrent = rawService.currentModel
            try {
                server.enqueue(
                    MockResponse.Builder().body(
                        """{"models":[{"name":"models/first","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"loop"}""",
                    ).build(),
                )
                server.enqueue(
                    MockResponse.Builder().body(
                        """{"models":[{"name":"models/second","supportedGenerationMethods":["generateContent"]}],"nextPageToken":"loop"}""",
                    ).build(),
                )

                assertNull(rawService.updateModel())

                assertEquals(beforeModels, rawService.availableModels)
                assertEquals(beforeCurrent, rawService.currentModel)
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
                Unit
            } finally {
                rawService.close().join()
                server.close()
            }
        }

    /** 超过原生模型条目预算会拒绝整个刷新，不得用已读取的前缀替换当前快照。 */
    @Test
    fun `raw Gemini model discovery entry cap preserves the previous snapshot`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val rawService = rawDiscoveryService(server)
        val beforeModels = rawService.availableModels
        val beforeCurrent = rawService.currentModel
        try {
            val models = (0..MAX_GEMINI_MODEL_DISCOVERY_ENTRIES).joinToString(separator = ",") { index ->
                """{"name":"models/$index","supportedGenerationMethods":["generateContent"]}"""
            }
            server.enqueue(MockResponse.Builder().body("""{"models":[$models]}""").build())

            assertNull(rawService.updateModel())

            assertEquals(beforeModels, rawService.availableModels)
            assertEquals(beforeCurrent, rawService.currentModel)
            Unit
        } finally {
            rawService.close().join()
            server.close()
        }
    }

    /** 原生模型发现总时限会取消慢页，且不会提交任何模型快照变更。 */
    @Test
    fun `raw Gemini model discovery deadline preserves the previous snapshot`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val rawService = rawDiscoveryService(
            server,
            AgentExecutionDeadlines(geminiModelDiscovery = 100.milliseconds),
        )
        val beforeModels = rawService.availableModels
        val beforeCurrent = rawService.currentModel
        try {
            server.enqueue(MockResponse.Builder().headersDelay(5, TimeUnit.SECONDS).build())

            assertFailsWith<TimeoutCancellationException> { rawService.updateModel() }

            assertEquals(beforeModels, rawService.availableModels)
            assertEquals(beforeCurrent, rawService.currentModel)
            Unit
        } finally {
            rawService.close().join()
            server.close()
        }
    }

    /**
     * 验证刷新前恢复持久化模型选择的设计。
     *
     * 验证服务创建时会先采用已保存的有效模型。
     */
    @Test
    fun testServiceRestoresPersistedSelectedModelBeforeRefreshing() {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(selectedModel = "models/gemini-custom")),
        )

        val restoredService = newService()

        assertEquals("models/gemini-custom", restoredService.currentModel)
    }

    /**
     * 验证启用 Gemini 的新实例会等待首轮 MCP 连接、工具快照和聊天会话创建后才报告就绪。
     */
    @Test
    fun `initial Gemini readiness waits for the MCP connection and chat`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val server = MockWebServer()
        server.start()
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    mcpServers = mcpServers,
                ),
            ),
        )
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            connectionStarted.complete(Unit)
            releaseConnection.await()
        }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val initializedService = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        try {
            connectionStarted.await()
            val readiness = assertNotNull(initializedService.initializationJob())
            assertFalse(readiness.isCompleted)

            setPrivateField(
                initializedService,
                "rawBaseUrl",
                server.url("/v1beta").toString().trimEnd('/'),
            )
            server.enqueue(
                MockResponse.Builder().body(
                    """{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""",
                ).build(),
            )
            releaseConnection.complete(Unit)
            readiness.join()

            assertFalse(readiness.isCancelled)
        } finally {
            releaseConnection.complete(Unit)
            initializedService.close().join()
            server.close()
        }
    }

    /** 验证 Gemini SDK 与 REST 的实际系统提示词构造都只包含已批准技能。 */
    @Test
    fun `Gemini SDK and REST prompts exclude pending skills`() {
        val approvedDraft = skillRepository.saveSkill(
            Skill(
                id = "approved",
                description = "APPROVED_SKILL_CANARY",
                content = "approved"
            )
        )
        skillRepository.approveSkill(approvedDraft.id, approvedDraft.revision)
        skillRepository.createPendingDraft("PENDING_SKILL_CANARY", "pending")
        val settings = AISettings(globalContext = "SYSTEM_CONTEXT_CANARY")
        val routeSnapshot = LocalFunctionRouter(emptyList()).refresh()
        val sdkMethod =
            GeminiAgentService::class.java.declaredMethods.single { it.name == "createSdkSessionConfig" }.apply {
                isAccessible = true
            }
        val wireMethod =
            GeminiAgentService::class.java.declaredMethods.single { it.name == "createGeminiWireConfig" }.apply {
                isAccessible = true
            }

        val sdkPrompt = sdkMethod.invoke(service, settings, routeSnapshot).toString()
        val wirePrompt = wireMethod.invoke(service, settings, routeSnapshot).toString()

        listOf(sdkPrompt, wirePrompt).forEach { prompt ->
            assertTrue(prompt.contains("APPROVED_SKILL_CANARY"))
            assertFalse(prompt.contains("PENDING_SKILL_CANARY"))
        }
    }

    /**
     * 验证候选初始化总时限会取消挂起的 Gemini 首轮重置与模型发现，不让就绪任务无限等待 MCP。
     */
    @Test
    fun `initial Gemini readiness deadline cancels hanging sibling work`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val connectionStarted = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    mcpServers = mcpServers,
                ),
            ),
        )
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            connectionStarted.complete(Unit)
            awaitCancellation()
        }
        every { mcpClientService.close() } returns Job().apply { complete() }
        val initializedService = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
            AgentExecutionDeadlines(
                mcpBatch = 1.seconds,
                candidateInitialization = 100.milliseconds,
                scheduledTurn = 1.seconds,
            ),
        ) { mockk() }

        try {
            withTimeout(1.seconds) { connectionStarted.await() }
            val readiness = assertNotNull(initializedService.initializationJob())
            withTimeout(1.seconds) { readiness.join() }
            assertTrue(readiness.isCancelled)
        } finally {
            withTimeout(1.seconds) { initializedService.close().join() }
        }
    }

    /**
     * 验证首轮模型发现的 HTTP 失败或空列表会取消 Gemini 候选的组合就绪任务。
     */
    @Test
    fun `initial Gemini readiness rejects failed and empty model discovery`() = runBlocking {
        listOf(
            MockResponse.Builder().code(500).body("upstream failure").build(),
            MockResponse.Builder().body("""{"models":[]}""").build(),
        ).forEach { response ->
            val mcpClientService = mockk<MCPClientService>()
            val connectionStarted = CompletableDeferred<Unit>()
            val releaseConnection = CompletableDeferred<Unit>()
            val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
            val server = MockWebServer()
            server.start()
            settingsRepository.saveSettings(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.GEMINI,
                        geminiApiKey = "test-key",
                        agentEnabled = true,
                        mcpServers = mcpServers,
                    ),
                ),
            )
            coEvery { mcpClientService.connect(mcpServers) } coAnswers {
                connectionStarted.complete(Unit)
                releaseConnection.await()
            }
            every { mcpClientService.getAllTools() } returns emptyList()
            every { mcpClientService.close() } returns Job().apply { complete() }
            val candidate = GeminiAgentService(
                CoroutineScope(EmptyCoroutineContext),
                settingsRepository,
                skillRepository,
                mcpClientService,
            ) { mockk() }

            try {
                connectionStarted.await()
                setPrivateField(candidate, "rawBaseUrl", server.url("/v1beta").toString().trimEnd('/'))
                server.enqueue(response)
                releaseConnection.complete(Unit)

                val readiness = assertNotNull(candidate.initializationJob())
                withTimeout(5.seconds) { readiness.join() }
                assertTrue(readiness.isCancelled)
            } finally {
                releaseConnection.complete(Unit)
                candidate.close().join()
                server.close()
            }
        }
    }

    /** 原生 Gemini `/models` 响应须在读取模型字段前拒绝超过结构上限的 JSON。 */
    @Test
    fun `raw Gemini models response is structure limited before decoding`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val server = MockWebServer()
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        server.start()
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    mcpServers = mcpServers,
                ),
            ),
        )
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            connectionStarted.complete(Unit)
            releaseConnection.await()
        }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val rawService = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        try {
            withTimeout(5.seconds) { connectionStarted.await() }
            setPrivateField(rawService, "rawBaseUrl", server.url("/v1beta").toString().trimEnd('/'))
            server.enqueue(
                MockResponse.Builder().body(
                    """{"models":[{"name":"models/gemini-3.5-flash-lite"}],"ignored":${deeplyNestedJson(65)}}""",
                ).build(),
            )
            releaseConnection.complete(Unit)

            val readiness = assertNotNull(rawService.initializationJob())
            withTimeout(5.seconds) { readiness.join() }

            assertTrue(readiness.isCancelled)
            assertTrue(
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).target
                    .startsWith("/v1beta/models?key=test-key"),
            )
        } finally {
            releaseConnection.complete(Unit)
            rawService.close().join()
            server.close()
        }
    }

    /** 原生 Gemini `generateContent` 响应须在读取候选内容前拒绝超过结构上限的 JSON。 */
    @Test
    fun `raw Gemini generate content response is structure limited before decoding`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val server = MockWebServer()
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        server.start()
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    mcpServers = mcpServers,
                ),
            ),
        )
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            connectionStarted.complete(Unit)
            releaseConnection.await()
        }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val rawService = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        try {
            withTimeout(5.seconds) { connectionStarted.await() }
            setPrivateField(rawService, "rawBaseUrl", server.url("/v1beta").toString().trimEnd('/'))
            server.enqueue(
                MockResponse.Builder().body(
                    """{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""",
                ).build(),
            )
            releaseConnection.complete(Unit)

            val readiness = assertNotNull(rawService.initializationJob())
            withTimeout(5.seconds) { readiness.join() }
            assertFalse(readiness.isCancelled)
            assertTrue(
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).target
                    .startsWith("/v1beta/models?key=test-key"),
            )

            server.enqueue(
                MockResponse.Builder().body(
                    """{"candidates":[{"content":{"role":"model","parts":[{"text":"would be accepted without the limit"}]}}],"ignored":${
                        deeplyNestedJson(
                            65
                        )
                    }}""",
                ).build(),
            )

            assertFailsWith<JsonStructureLimitExceededException> {
                rawService.sendMessage("deep response")
            }
            assertTrue(
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).target
                    .startsWith("/v1beta/models/gemini-3.5-flash-lite:generateContent?key=test-key"),
            )
        } finally {
            releaseConnection.complete(Unit)
            rawService.close().join()
            server.close()
        }
    }

    /**
     * 验证技能存储隔离会使 Gemini 候选初始化失败，而不会将隔离状态降级为空技能提示词。
     */
    @Test
    fun `initial Gemini readiness rejects isolated skill storage`() = runBlocking {
        val skillsFile = File(tempDirectory, "skills.json")
        val invalidSkills = """[{"id":"safe?legacy","description":"invalid","content":"invalid"}]"""
        skillsFile.writeText(invalidSkills)
        skillRepository = com.unscientificjszhai.tgp.repository.SkillRepository.forTesting(skillsFile)
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                ),
            ),
        )
        val candidate = newService()

        try {
            val readiness = assertNotNull(candidate.initializationJob())
            withTimeout(5.seconds) { readiness.join() }

            assertTrue(readiness.isCancelled)
            assertEquals(invalidSkills, skillsFile.readText())
        } finally {
            candidate.close().join()
        }
    }

    /**
     * 验证休眠的历史非法 OpenAI 地址不会阻止 Gemini 在首轮模型发现中完成内存回退，也不会改写原始文件。
     */
    @Test
    fun `Gemini initial fallback keeps dormant historical OpenAI URL protected`() = runBlocking {
        val configFile = File(tempDirectory, "dormant-invalid-openai-url.json")
        val originalContent =
            """{"ai":{"provider":"GEMINI","geminiApiKey":"test-key","openAiBaseUrl":"https://gateway.example.com/v1/%6dodels","selectedModel":"models/retired","agentEnabled":true,"mcpServers":[{"name":"test","url":"https://example.com/mcp","headers":{}}]}}"""
        configFile.writeText(originalContent)
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
        val mcpClientService = mockk<MCPClientService>()
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseInitialConnection = CompletableDeferred<Unit>()
        val connectionCalls = AtomicInteger()
        val mcpServers = checkNotNull(repository.settingsFlow.value.ai).mcpServers
        val server = MockWebServer()
        server.start()
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            if (connectionCalls.incrementAndGet() == 1) {
                connectionStarted.complete(Unit)
                releaseInitialConnection.await()
            }
        }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val candidate = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            repository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        try {
            connectionStarted.await()
            setPrivateField(candidate, "rawBaseUrl", server.url("/v1beta").toString().trimEnd('/'))
            server.enqueue(
                MockResponse.Builder().body(
                    """{"models":[{"name":"models/gemini-3.5-flash-lite","supportedGenerationMethods":["generateContent"]}]}""",
                ).build(),
            )
            releaseInitialConnection.complete(Unit)

            val readiness = assertNotNull(candidate.initializationJob())
            withTimeout(5.seconds) { readiness.join() }
            assertFalse(readiness.isCancelled)
            assertEquals("models/gemini-3.5-flash-lite", candidate.currentModel)
            assertTrue(repository.hasHistoricalInvalidOpenAiBaseUrl)
            assertEquals("models/retired", repository.settingsFlow.value.ai?.selectedModel)
            assertEquals(originalContent, configFile.readText())
        } finally {
            releaseInitialConnection.complete(Unit)
            candidate.close().join()
            server.close()
        }
    }

    /**
     * 验证成功刷新对无效已选模型的回退设计。
     *
     * 验证无效持久化模型会被清空并切换到可用回退模型。
     */
    @Test
    fun testSuccessfulRefreshClearsInvalidPersistedModelAndFallsBack() = runBlocking {
        val models = mockk<Models>()
        val pager = mockk<Pager<Model>>()
        val chats = mockk<Chats>()
        val fallbackChat = mockk<Chat>()
        val unsupportedModel = Model.builder()
            .name("models/embedding-model")
            .supportedActions("embedContent")
            .build()
        val fallbackModel = Model.builder()
            .name("models/fallback-model")
            .supportedActions("generateContent")
            .build()
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(geminiApiKey = "test-key", selectedModel = "models/gemini-3.5-flash-lite")),
        )
        every { models.list(any<ListModelsConfig>()) } returns pager
        every { pager.page() } returns ImmutableList.of(unsupportedModel) andThen ImmutableList.of(fallbackModel)
        every { pager.nextPage() } returns ImmutableList.of(fallbackModel) andThenThrows IndexOutOfBoundsException()
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns fallbackChat
        setPrivateField("configuredApiKey", "test-key")
        injectClient(chats, models)

        service.updateModel()

        assertEquals("models/fallback-model", service.currentModel)
        assertEquals("", settingsRepository.settingsFlow.value.ai?.selectedModel)
    }

    /**
     * 验证刷新失败时保留模型选择的设计。
     *
     * 验证请求模型列表失败不会覆盖已持久化的模型。
     */
    @Test
    fun testFailedRefreshRetainsPersistedSelectedModel() = runBlocking {
        val models = mockk<Models>()
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(geminiApiKey = "test-key", selectedModel = "models/gemini-3.5-flash-lite")),
        )
        every { models.list(any<ListModelsConfig>()) } throws IllegalStateException("network failure")
        injectClient(mockk(), models)

        assertEquals(null, service.updateModel())

        assertEquals("models/gemini-3.5-flash-lite", settingsRepository.settingsFlow.value.ai?.selectedModel)
    }

    /**
     * 验证切换无前缀模型时的名称规范化设计。
     *
     * 验证服务会保留 SDK 所需的 `models/` 前缀。
     */
    @Test
    fun testSwitchingUnprefixedModelsRetainsSdkPrefix() = runBlocking {
        prepareSuccessfulSwitch()
        listOf(
            "gemini-3.5-flash-lite" to "models/gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite" to "models/gemini-3.1-flash-lite",
            "gemini-2.5-flash" to "models/gemini-2.5-flash",
        ).forEach { (modelName, expectedModel) ->
            service.switchModel(modelName)?.join()

            assertEquals(expectedModel, service.currentModel)
        }
    }

    /**
     * 验证切换已有前缀模型的设计。
     *
     * 验证模型名称不会被重复添加或修改前缀。
     */
    @Test
    fun testSwitchingPrefixedModelRetainsItsName() = runBlocking {
        prepareSuccessfulSwitch()
        val switchJob = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        switchJob.join()

        assertFalse(switchJob.isCancelled)
        assertEquals("models/gemini-3.1-flash-lite", service.currentModel)
    }

    /**
     * 验证动态可用模型切换时的名称规范化设计。
     *
     * 验证无前缀的动态模型会以 SDK 兼容名称保存。
     */
    @Test
    fun testSwitchingUnprefixedDynamicallyAvailableModelAddsPrefix() = runBlocking {
        GeminiAgentService::class.java.getDeclaredField("availableModels").apply {
            isAccessible = true
            set(service, listOf("models/custom-model"))
        }

        prepareSuccessfulSwitch()
        val switchJob = assertNotNull(service.switchModel("custom-model"))
        switchJob.join()

        assertEquals("models/custom-model", service.currentModel)
    }

    /**
     * 验证候选会话创建失败时保留原有会话状态，并以任务取消而非 [Job.join] 抛异常报告失败。
     */
    @Test
    fun `failed Gemini reset keeps the previous chat route and pending history`() = runBlocking {
        val oldChat = mockk<Chat>()
        val recoveredChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val preservedHistory = listOf(Content.fromParts(Part.fromText("待恢复历史")))
        val originalRoute = LocalFunctionRouter(emptyList()).refresh()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        var failResetCandidate = true
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } answers {
            if (failResetCandidate) {
                failResetCandidate = false
                throw IllegalStateException("create failed")
            }
            recoveredChat
        }
        every { recoveredChat.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("旧会话回复"))
        injectClient(chats)
        GeminiAgentService::class.java.getDeclaredField("chat").apply { isAccessible = true }.set(service, oldChat)
        GeminiAgentService::class.java.getDeclaredField("chatFunctionRouteSnapshot").apply {
            isAccessible = true
        }.set(service, originalRoute)
        setPrivateField("savedHistory", preservedHistory)

        val resetJob = assertNotNull(service.resetSession())
        resetJob.join()

        assertTrue(resetJob.isCancelled)
        assertSame(oldChat, privateField("chat"))
        assertSame(originalRoute, privateField("chatFunctionRouteSnapshot"))
        assertEquals(preservedHistory, privateField("savedHistory"))
        assertEquals("models/gemini-3.5-flash-lite", service.currentModel)
        assertEquals("旧会话回复", service.sendMessage("继续使用旧会话"))
        assertSame(recoveredChat, privateField("chat"))
        Unit
    }

    /**
     * 验证候选创建成功时才会替换会话状态。
     */
    @Test
    fun `successful Gemini reset atomically replaces the session`() = runBlocking {
        val oldChat = mockk<Chat>()
        val newChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val originalRoute = LocalFunctionRouter(emptyList()).refresh()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns newChat
        every { newChat.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("新会话回复"))
        every { newChat.getHistory(true) } returns ImmutableList.of()
        injectClient(chats)
        GeminiAgentService::class.java.getDeclaredField("chat").apply { isAccessible = true }.set(service, oldChat)
        GeminiAgentService::class.java.getDeclaredField("chatFunctionRouteSnapshot").apply {
            isAccessible = true
        }.set(service, originalRoute)
        setPrivateField("savedHistory", listOf(Content.fromParts(Part.fromText("旧历史"))))

        val resetJob = assertNotNull(service.resetSession())
        resetJob.join()

        assertFalse(resetJob.isCancelled)
        assertSame(newChat, privateField("chat"))
        assertNotSame(originalRoute, privateField("chatFunctionRouteSnapshot"))
        assertNull(privateField("savedHistory"))
        assertEquals("新会话回复", service.sendMessage("新会话"))
    }

    /**
     * 验证模型切换在候选创建失败时不会乐观改写模型或历史。
     */
    @Test
    fun `failed model switch rolls back chat route history and model`() = runBlocking {
        val oldChat = mockk<Chat>()
        val recoveredChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val preservedHistory = listOf(Content.fromParts(Part.fromText("原待恢复历史")))
        val capturedHistory = listOf(Content.fromParts(Part.fromText("当前会话历史")))
        val originalRoute = LocalFunctionRouter(emptyList()).refresh()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { oldChat.getHistory(true) } returns ImmutableList.copyOf(capturedHistory)
        var failSwitchCandidate = true
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } answers {
            if (failSwitchCandidate) {
                failSwitchCandidate = false
                throw IllegalStateException("create failed")
            }
            recoveredChat
        }
        every { recoveredChat.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("原会话回复"))
        injectClient(chats)
        GeminiAgentService::class.java.getDeclaredField("chat").apply { isAccessible = true }.set(service, oldChat)
        GeminiAgentService::class.java.getDeclaredField("chatFunctionRouteSnapshot").apply {
            isAccessible = true
        }.set(service, originalRoute)
        setPrivateField("savedHistory", preservedHistory)

        val switchJob = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        assertEquals("models/gemini-3.5-flash-lite", service.currentModel)
        switchJob.join()

        assertTrue(switchJob.isCancelled)
        assertEquals("models/gemini-3.5-flash-lite", service.currentModel)
        assertSame(oldChat, privateField("chat"))
        assertSame(originalRoute, privateField("chatFunctionRouteSnapshot"))
        assertEquals(preservedHistory, privateField("savedHistory"))
        assertEquals("原会话回复", service.sendMessage("仍走原会话"))
        assertSame(recoveredChat, privateField("chat"))
        Unit
    }

    /**
     * 验证并发模型切换按最后一次选择线性化，过期候选不会覆盖最新会话。
     */
    @Test
    fun `concurrent model switches commit only the latest candidate`() = runBlocking {
        val chats = mockk<Chats>()
        val firstCandidate = mockk<Chat>()
        val latestCandidate = mockk<Chat>()
        val firstCreateStarted = CountDownLatch(1)
        val releaseFirstCreate = CountDownLatch(1)
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chats.create("models/gemini-3.1-flash-lite", any<GenerateContentConfig>()) } answers {
            firstCreateStarted.countDown()
            check(releaseFirstCreate.await(5, TimeUnit.SECONDS))
            firstCandidate
        }
        every { chats.create("models/gemini-2.5-flash", any<GenerateContentConfig>()) } returns latestCandidate
        injectClient(chats)

        val firstSwitch = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        assertTrue(firstCreateStarted.await(5, TimeUnit.SECONDS))
        val latestSwitch = assertNotNull(service.switchModel("models/gemini-2.5-flash"))
        releaseFirstCreate.countDown()
        firstSwitch.join()
        latestSwitch.join()

        assertTrue(firstSwitch.isCancelled)
        assertFalse(latestSwitch.isCancelled)
        assertEquals("models/gemini-2.5-flash", service.currentModel)
        assertSame(latestCandidate, privateField("chat"))
    }

    /**
     * 验证同一目标模型的并发切换共享同一个候选任务；首个候选失败时，两个调用方都能观察到取消状态，
     * 后续重试不会复用已结束任务。
     */
    @Test
    fun `same model switches share a failed candidate job and allow retry`() = runBlocking {
        val chats = mockk<Chats>()
        val recoveredCandidate = mockk<Chat>()
        val firstCreateStarted = CountDownLatch(1)
        val releaseFirstCreate = CountDownLatch(1)
        var createAttempts = 0
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chats.create("models/gemini-3.1-flash-lite", any<GenerateContentConfig>()) } answers {
            if (createAttempts++ == 0) {
                firstCreateStarted.countDown()
                check(releaseFirstCreate.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("first candidate failed")
            }
            recoveredCandidate
        }
        injectClient(chats)

        val firstSwitch = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        val secondSwitch = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        assertSame(firstSwitch, secondSwitch)
        assertTrue(firstCreateStarted.await(5, TimeUnit.SECONDS))
        releaseFirstCreate.countDown()
        firstSwitch.join()
        secondSwitch.join()

        assertTrue(firstSwitch.isCancelled)
        assertTrue(secondSwitch.isCancelled)
        assertEquals("models/gemini-3.5-flash-lite", service.currentModel)
        assertNull(privateField("pendingModelSwitchJob"))

        val retrySwitch = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        assertNotSame(firstSwitch, retrySwitch)
        retrySwitch.join()
        assertFalse(retrySwitch.isCancelled)
        assertEquals("models/gemini-3.1-flash-lite", service.currentModel)
        assertSame(recoveredCandidate, privateField("chat"))
    }

    /**
     * 验证模型切换会以更高的重置代次取代已经开始但尚未提交的人工 reset，最终只发布最新模型候选。
     */
    @Test
    fun `concurrent reset and model switch publish the latest selected model`() = runBlocking {
        val chats = mockk<Chats>()
        val resetCandidate = mockk<Chat>()
        val switchCandidate = mockk<Chat>()
        val resetCreateStarted = CountDownLatch(1)
        val releaseResetCreate = CountDownLatch(1)
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chats.create("models/gemini-3.5-flash-lite", any<GenerateContentConfig>()) } answers {
            resetCreateStarted.countDown()
            check(releaseResetCreate.await(5, TimeUnit.SECONDS))
            resetCandidate
        }
        every { chats.create("models/gemini-3.1-flash-lite", any<GenerateContentConfig>()) } returns switchCandidate
        injectClient(chats)

        val resetJob = assertNotNull(service.resetSession())
        assertTrue(resetCreateStarted.await(5, TimeUnit.SECONDS))
        val switchJob = assertNotNull(service.switchModel("models/gemini-3.1-flash-lite"))
        releaseResetCreate.countDown()
        resetJob.join()
        switchJob.join()

        assertTrue(resetJob.isCancelled)
        assertFalse(switchJob.isCancelled)
        assertEquals("models/gemini-3.1-flash-lite", service.currentModel)
        assertSame(switchCandidate, privateField("chat"))
    }

    /**
     * 验证不支持模型的拒绝设计。
     *
     * 验证切换到不在可用列表中的模型会抛出参数异常。
     */
    @Test
    fun testSwitchingUnsupportedModelFails() {
        assertFailsWith<IllegalArgumentException> {
            service.switchModel("unsupported-model")
        }
    }

    /**
     * 验证工具调用响应标识保留的设计。
     *
     * 验证生成的函数响应包含原调用的标识和名称。
     */
    @Test
    fun testFunctionResponseRetainsCallIdAndName() {
        val functionCall = FunctionCall.builder()
            .id("call-1")
            .name("sample_function")
            .args(emptyMap())
            .build()

        val part = service.createFunctionResponsePart(
            functionCall,
            buildJsonObject { put("status", "ok") },
        )
        val response = part.functionResponse().get()

        assertEquals("call-1", response.id().get())
        assertEquals("sample_function", response.name().get())
        assertEquals("ok", response.response().get()["status"])
    }

    /**
     * 验证无调用标识的旧版工具调用兼容设计。
     *
     * 验证旧版调用仍可生成可用的函数响应。
     */
    @Test
    fun testLegacyFunctionCallWithoutIdStillGeneratesResponse() {
        val functionCall = FunctionCall.builder()
            .name("legacy_function")
            .args(emptyMap())
            .build()

        val response = service.createFunctionResponsePart(
            functionCall,
            buildJsonObject { put("error", "failed") },
        ).functionResponse().get()

        assertFalse(response.id().isPresent)
        assertEquals("legacy_function", response.name().get())
        assertEquals("failed", response.response().get()["error"])
    }

    /**
     * 验证未知工具调用的错误响应设计。
     *
     * 验证每个未知调用都会得到与其标识和名称对应的错误响应。
     */
    @Test
    fun testUnknownFunctionCallsProduceMatchingErrorResponses() = runTest {
        val activeChat = mockk<Chat>()
        val firstCandidate = mockk<Chat>()
        val finalCandidate = mockk<Chat>()
        val chats = mockk<Chats>()
        val firstCall = FunctionCall.builder().id("call-1").name("missing_one").args(emptyMap()).build()
        val secondCall = FunctionCall.builder().id("call-2").name("missing_two").args(emptyMap()).build()
        val toolCallResponse = responseWithParts(
            Part.builder().functionCall(firstCall).build(),
            Part.builder().functionCall(secondCall).build(),
        )
        val finalResponse = responseWithParts(Part.fromText("完成"))
        val sentFunctionResults = slot<List<Content>>()
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany listOf(
            firstCandidate,
            finalCandidate
        )
        every { firstCandidate.sendMessage(any<List<Content>>()) } returns toolCallResponse
        every { finalCandidate.sendMessage(capture(sentFunctionResults)) } returns finalResponse
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        assertEquals("完成", service.sendMessage("执行未知工具"))

        val functionResponses = sentFunctionResults.captured.last().parts().get().map { it.functionResponse().get() }
        assertEquals(listOf("call-1", "call-2"), functionResponses.map { it.id().get() })
        assertEquals(listOf("missing_one", "missing_two"), functionResponses.map { it.name().get() })
        assertEquals("Function missing_one not found", functionResponses[0].response().get()["error"])
        assertEquals("Function missing_two not found", functionResponses[1].response().get()["error"])
    }

    /** 验证 SDK Gemini 的部分、缺失和未知终态均不会提交历史，后续 `STOP` 回合仍能正常提交。 */
    @Test
    fun `SDK Gemini rejects every non STOP terminal state without committing history`() = runTest {
        val activeChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val incompleteCandidates = List(4) { mockk<Chat>() }
        val succeedingCandidate = mockk<Chat>()
        val incompleteReasons = listOf(
            FinishReason(FinishReason.Known.MAX_TOKENS),
            FinishReason(FinishReason.Known.SAFETY),
            null,
            FinishReason("FUTURE_REASON"),
        )
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany
                (incompleteCandidates + succeedingCandidate)
        incompleteCandidates.zip(incompleteReasons).forEach { (candidateChat, finishReason) ->
            every { candidateChat.sendMessage(any<List<Content>>()) } returns responseWithParts(
                Part.fromText("partial reply"),
                finishReason = finishReason,
            )
        }
        every { succeedingCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.fromText("normal reply"),
        )
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        repeat(incompleteCandidates.size) {
            assertFailsWith<AgentTurnFailedException> { service.sendMessage("must rollback") }
            assertSame(activeChat, privateField("chat"))
            assertEquals(emptyList<Content>(), privateField("savedHistory"))
        }

        assertEquals("normal reply", service.sendMessage("normal retry"))
        @Suppress("UNCHECKED_CAST")
        assertEquals(2, (privateField("savedHistory") as List<Content>).size)
    }

    /** 验证 SDK Gemini 以异常终态返回的工具调用既不执行本地函数，也不会发起后续回合。 */
    @Test
    fun `SDK Gemini invalid tool terminal state does not execute or follow up`() = runTest {
        val activeChat = mockk<Chat>()
        val candidateChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val executedFunctions = mutableListOf<String>()
        val provider = object : LocalFunctionProvider() {
            override val providedFunctions: List<FunctionDeclaration> = listOf(
                FunctionDeclaration.builder()
                    .name("must_not_execute")
                    .parameters(Schema.fromJson("""{"type":"OBJECT"}"""))
                    .build(),
            )

            override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
                executedFunctions += functionName
                return buildJsonObject { put("status", "executed") }
            }
        }
        val functionCall = FunctionCall.builder().name("must_not_execute").args(emptyMap()).build()
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns candidateChat
        every { candidateChat.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.builder().functionCall(functionCall).build(),
            finishReason = FinishReason(FinishReason.Known.MAX_TOKENS),
        )
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("chatFunctionRouteSnapshot", LocalFunctionRouter(listOf(provider)).refresh())
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        assertFailsWith<AgentTurnFailedException> { service.sendMessage("must not execute tool") }

        assertEquals(emptyList<String>(), executedFunctions)
        assertSame(activeChat, privateField("chat"))
        assertEquals(emptyList<Content>(), privateField("savedHistory"))
        verify(exactly = 1) { chats.create(any<String>(), any<GenerateContentConfig>()) }
        verify(exactly = 1) { candidateChat.sendMessage(any<List<Content>>()) }
    }

    /** 验证工具成功但后续 Gemini 模型请求失败时，外部副作用已发生而会话历史不会提交。 */
    @Test
    fun `Gemini model failure after successful tool keeps side effect and prior history`() = runTest {
        val activeChat = mockk<Chat>()
        val firstCandidate = mockk<Chat>()
        val secondCandidate = mockk<Chat>()
        val chats = mockk<Chats>()
        val providerCalls = mutableListOf<String>()
        val provider = object : LocalFunctionProvider() {
            override val providedFunctions: List<FunctionDeclaration> = listOf(
                FunctionDeclaration.builder()
                    .name("observable_side_effect")
                    .parameters(Schema.fromJson("""{"type":"OBJECT"}"""))
                    .build(),
            )

            override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
                providerCalls += functionName
                return buildJsonObject { put("status", "created") }
            }
        }
        val functionCall = FunctionCall.builder()
            .id("side-effect-call")
            .name("observable_side_effect")
            .args(emptyMap())
            .build()
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany listOf(
            firstCandidate,
            secondCandidate
        )
        every { firstCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.builder().functionCall(functionCall).build(),
        )
        every { secondCandidate.sendMessage(any<List<Content>>()) } throws IllegalStateException("upstream failure")
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("localFunctionProviders", listOf(provider))
        setPrivateField("chatFunctionRouteSnapshot", LocalFunctionRouter(listOf(provider)).refresh())
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        assertFailsWith<IllegalStateException> { service.sendMessage("执行副作用") }
        assertEquals(listOf("observable_side_effect"), providerCalls)
        assertSame(activeChat, privateField("chat"))
        assertEquals(emptyList<Content>(), privateField("savedHistory"))
    }

    /**
     * 验证 SDK 候选 Chat 在裁剪历史和工具响应后重建，且已提交历史可供下一回合继续使用。
     */
    @Test
    fun `SDK candidate rebuilding keeps complete tool turns and a valid history prefix`() = runTest {
        val activeChat = mockk<Chat>()
        val firstCandidate = mockk<Chat>()
        val toolCandidate = mockk<Chat>()
        val nextCandidate = mockk<Chat>()
        val chats = mockk<Chats>()
        val oldFunction = FunctionCall.builder().id("old-call").name("missing").args(emptyMap()).build()
        val currentFunction = FunctionCall.builder().id("current-call").name("missing").args(emptyMap()).build()
        val persistedHistory = buildList {
            add(Content.builder().role("user").parts(listOf(Part.fromText("旧工具回合"))).build())
            add(Content.builder().role("model").parts(listOf(Part.builder().functionCall(oldFunction).build())).build())
            add(
                Content.builder().role("user").parts(
                    listOf(service.createFunctionResponsePart(oldFunction, buildJsonObject { put("ok", true) })),
                ).build(),
            )
            add(Content.builder().role("model").parts(listOf(Part.fromText("旧工具完成"))).build())
            repeat(30) { index ->
                add(Content.builder().role("user").parts(listOf(Part.fromText("旧用户$index"))).build())
                add(Content.builder().role("model").parts(listOf(Part.fromText("旧模型$index"))).build())
            }
        }
        val firstRequest = slot<List<Content>>()
        val toolRequest = slot<List<Content>>()
        val nextRequest = slot<List<Content>>()
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany listOf(
            firstCandidate,
            toolCandidate,
            nextCandidate,
        )
        every { firstCandidate.sendMessage(capture(firstRequest)) } returns responseWithParts(
            Part.builder().functionCall(currentFunction).build(),
        )
        every { toolCandidate.sendMessage(capture(toolRequest)) } returns responseWithParts(Part.fromText("工具完成"))
        every { nextCandidate.sendMessage(capture(nextRequest)) } returns responseWithParts(Part.fromText("下一轮完成"))
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", persistedHistory)

        assertEquals("工具完成", service.sendMessage("当前工具回合"))

        assertEquals("user", firstRequest.captured.first().role().get())
        assertFalse(firstRequest.captured.first().parts().get().any { it.functionResponse().isPresent })
        assertEquals("user", toolRequest.captured.first().role().get())
        val functionResponseIndex = toolRequest.captured.indexOfFirst { content ->
            content.parts().get().any { it.functionResponse().isPresent }
        }
        assertTrue(functionResponseIndex > 0)
        assertEquals("model", toolRequest.captured[functionResponseIndex - 1].role().get())
        assertTrue(toolRequest.captured[functionResponseIndex - 1].parts().get().any { it.functionCall().isPresent })

        @Suppress("UNCHECKED_CAST")
        val saved = privateField("savedHistory") as List<Content>
        assertEquals("user", saved.first().role().get())
        assertTrue(saved.any { content -> content.parts().get().any { it.functionResponse().isPresent } })
        assertTrue(saved.size <= 64)

        assertEquals("下一轮完成", service.sendMessage("后续回合"))
        assertEquals("user", nextRequest.captured.first().role().get())
        assertTrue(nextRequest.captured.size <= 64)
    }

    /**
     * 验证 SDK 候选历史达到字节预留阈值时只删除最旧的完整回合。
     *
     * 每个旧回合的 user/model 内容约为 2.2 MiB；两个回合超过 4 MiB 的下一回合预留。新请求必须
     * 从第二个普通 user 内容开始，保留第二回合而不携带最旧回合的任一侧。
     */
    @Test
    fun `SDK byte budget removes only oldest complete turn before new request`() = runTest {
        val activeChat = mockk<Chat>()
        val candidateChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val request = slot<List<Content>>()
        val largeText = "x".repeat(1_100 * 1024)
        fun historicalContent(role: String, marker: String) = Content.builder()
            .role(role)
            .parts(listOf(Part.fromText("$marker|$largeText")))
            .build()

        val persistedHistory = listOf(
            historicalContent("user", "最旧用户"),
            historicalContent("model", "最旧模型"),
            historicalContent("user", "较新用户"),
            historicalContent("model", "较新模型"),
        )
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns candidateChat
        every { candidateChat.sendMessage(capture(request)) } returns responseWithParts(Part.fromText("完成"))
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", persistedHistory)

        assertEquals("完成", service.sendMessage("当前回合"))

        val markers = request.captured.map { content ->
            content.parts().get().single().text().get().substringBefore('|')
        }
        assertEquals(listOf("较新用户", "较新模型", "当前回合"), markers)
        assertEquals("user", request.captured.first().role().get())
        assertFalse(request.captured.first().parts().get().any { it.functionResponse().isPresent })
    }

    /**
     * 验证 SDK 当前回合本身超过 8 MiB 时不发布候选 Chat 或候选历史，并允许后续小回合恢复。
     */
    @Test
    fun `oversized current SDK turn rolls back candidate session and later recovers`() = runTest {
        val activeChat = mockk<Chat>()
        val failedCandidate = mockk<Chat>()
        val recoveredCandidate = mockk<Chat>()
        val chats = mockk<Chats>()
        val committedHistory = listOf(
            Content.builder().role("user").parts(listOf(Part.fromText("已提交用户"))).build(),
            Content.builder().role("model").parts(listOf(Part.fromText("已提交回复"))).build(),
        )
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany listOf(
            failedCandidate,
            recoveredCandidate,
        )
        every { failedCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.fromText("x".repeat(8 * 1024 * 1024)),
        )
        every { recoveredCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("已恢复"))
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", committedHistory)

        assertFailsWith<AgentTurnFailedException> { service.sendMessage("会超限") }
        assertSame(activeChat, privateField("chat"))
        assertEquals(committedHistory, privateField("savedHistory"))

        assertEquals("已恢复", service.sendMessage("小消息"))
        assertSame(recoveredCandidate, privateField("chat"))
        @Suppress("UNCHECKED_CAST")
        val recoveredHistory = privateField("savedHistory") as List<Content>
        assertEquals("小消息", recoveredHistory[2].parts().get().single().text().get())
        assertEquals("已恢复", recoveredHistory[3].parts().get().single().text().get())
    }

    /**
     * 验证工具调用轮次上限的设计。
     *
     * 验证达到最大轮次后会停止调用并报告异常。
     */
    @Test
    fun testToolCallsStopAfterMaximumRounds() = runTest {
        val activeChat = mockk<Chat>()
        val candidateChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val functionCall = FunctionCall.builder().name("missing").args(emptyMap()).build()
        val toolCallResponse = responseWithParts(Part.builder().functionCall(functionCall).build())
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        var recover = false
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns candidateChat
        every { candidateChat.sendMessage(any<List<Content>>()) } answers {
            if (recover) responseWithParts(Part.fromText("新会话")) else toolCallResponse
        }
        every { candidateChat.getHistory(true) } returns ImmutableList.of()
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        val exception = assertFailsWith<IllegalStateException> {
            service.sendMessage("持续调用工具")
        }

        assertEquals("工具调用轮次超过上限（$MAX_TOOL_CALL_ROUNDS 轮）。", exception.message)
        verify(exactly = MAX_TOOL_CALL_ROUNDS + 1) { candidateChat.sendMessage(any<List<Content>>()) }
        val recoveryJob = assertNotNull(privateField("resetSessionJob") as? Job)
        withTimeout(5.seconds) { recoveryJob.join() }
        assertFalse(recoveryJob.isCancelled)
        assertEquals(candidateChat, GeminiAgentService::class.java.getDeclaredField("chat").apply {
            isAccessible = true
        }.get(service))
        recover = true
        assertEquals("新会话", service.sendMessage("继续对话"))
        verify(exactly = MAX_TOOL_CALL_ROUNDS + 2) { candidateChat.sendMessage(any<List<Content>>()) }
    }

    /**
     * 验证 SDK 工具超限后的恢复会阻塞后续发送；取消等待者不会取消恢复，恢复完成后新回合只读取新会话。
     */
    @Test
    fun `SDK tool-limit recovery is a fail-closed send barrier`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val recoveryStarted = CompletableDeferred<Unit>()
        val releaseRecovery = CompletableDeferred<Unit>()
        val chats = mockk<Chats>()
        val exhaustedCandidate = mockk<Chat>()
        val recoveredSession = mockk<Chat>()
        val postRecoveryCandidate = mockk<Chat>()
        val recoveredHistory = Content.fromParts(Part.fromText("恢复后的会话历史"))
        val postRecoveryRequest = slot<List<Content>>()
        val functionCall = FunctionCall.builder().name("missing").args(emptyMap()).build()
        val connectionCount = AtomicInteger()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        coEvery { mcpClientService.connect(any()) } coAnswers {
            if (connectionCount.incrementAndGet() == 1) {
                recoveryStarted.complete(Unit)
                releaseRecovery.await()
            }
        }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        service = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany
                (List(MAX_TOOL_CALL_ROUNDS + 1) { exhaustedCandidate } + listOf(
                    recoveredSession,
                    postRecoveryCandidate
                ))
        every { exhaustedCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.builder().functionCall(functionCall).build(),
        )
        every { recoveredSession.getHistory(true) } returns ImmutableList.of(recoveredHistory)
        every { postRecoveryCandidate.sendMessage(capture(postRecoveryRequest)) } returns responseWithParts(
            Part.fromText("恢复后回复"),
        )
        injectClient(chats)
        injectChat(mockk())
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        assertFailsWith<IllegalStateException> { service.sendMessage("触发工具超限") }
        withTimeout(5.seconds) { recoveryStarted.await() }
        val recoveryJob = assertNotNull(privateField("resetSessionJob") as? Job)
        val waitingSend = async(start = CoroutineStart.UNDISPATCHED) { service.sendMessage("不得提前发送") }

        assertFalse(waitingSend.isCompleted)
        verify(exactly = 0) { postRecoveryCandidate.sendMessage(any<List<Content>>()) }
        waitingSend.cancelAndJoin()
        assertTrue(waitingSend.isCancelled)
        assertFalse(recoveryJob.isCancelled)

        releaseRecovery.complete(Unit)
        withTimeout(5.seconds) { recoveryJob.join() }
        assertFalse(recoveryJob.isCancelled)
        assertEquals("恢复后回复", service.sendMessage("读取恢复会话"))
        assertEquals("恢复后的会话历史", postRecoveryRequest.captured.first().parts().get().single().text().get())
    }

    /** 验证 SDK 工具超限恢复失败保持旧会话封闭，但后续成功的人工 reset 会解除旧恢复标记。 */
    @Test
    fun `failed SDK tool-limit recovery is superseded by a successful public reset`() = runBlocking {
        val chats = mockk<Chats>()
        val exhaustedCandidate = mockk<Chat>()
        val recoveredCandidate = mockk<Chat>()
        val functionCall = FunctionCall.builder().name("missing").args(emptyMap()).build()
        val creationCount = AtomicInteger()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } answers {
            when (creationCount.incrementAndGet()) {
                in 1..(MAX_TOOL_CALL_ROUNDS + 1) -> exhaustedCandidate
                MAX_TOOL_CALL_ROUNDS + 2 -> throw IllegalStateException("recovery creation failed")
                else -> recoveredCandidate
            }
        }
        every { exhaustedCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.builder().functionCall(functionCall).build(),
        )
        every { recoveredCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("人工重置后回复"))
        every { recoveredCandidate.getHistory(true) } returns ImmutableList.of()
        injectClient(chats)
        injectChat(mockk())
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        assertFailsWith<IllegalStateException> { service.sendMessage("触发工具超限") }
        val recoveryJob = assertNotNull(privateField("resetSessionJob") as? Job)
        withTimeout(5.seconds) { recoveryJob.join() }

        assertTrue(recoveryJob.isCancelled)
        assertFailsWith<IllegalStateException> { service.sendMessage("不能回退到旧会话") }
        verify(exactly = MAX_TOOL_CALL_ROUNDS + 1) { exhaustedCandidate.sendMessage(any<List<Content>>()) }

        val manualReset = assertNotNull(service.resetSession())
        withTimeout(5.seconds) { manualReset.join() }
        assertFalse(manualReset.isCancelled)
        assertEquals("人工重置后回复", service.sendMessage("恢复服务"))
    }

    /** 验证被取消的 SDK 工具超限恢复同样封闭旧会话。 */
    @Test
    fun `cancelled SDK tool-limit recovery rejects subsequent sends`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val recoveryStarted = CompletableDeferred<Unit>()
        val neverReleaseRecovery = CompletableDeferred<Unit>()
        val chats = mockk<Chats>()
        val exhaustedCandidate = mockk<Chat>()
        val recoveredCandidate = mockk<Chat>()
        val functionCall = FunctionCall.builder().name("missing").args(emptyMap()).build()
        val connectionCount = AtomicInteger()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        coEvery { mcpClientService.connect(any()) } coAnswers {
            if (connectionCount.incrementAndGet() == 1) {
                recoveryStarted.complete(Unit)
                neverReleaseRecovery.await()
            }
        }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        service = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returnsMany
                (List(MAX_TOOL_CALL_ROUNDS + 1) { exhaustedCandidate } + listOf(recoveredCandidate, recoveredCandidate))
        every { exhaustedCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(
            Part.builder().functionCall(functionCall).build(),
        )
        every { recoveredCandidate.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("取消恢复后回复"))
        every { recoveredCandidate.getHistory(true) } returns ImmutableList.of()
        injectClient(chats)
        injectChat(mockk())
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        assertFailsWith<IllegalStateException> { service.sendMessage("触发工具超限") }
        withTimeout(5.seconds) { recoveryStarted.await() }
        val recoveryJob = assertNotNull(privateField("resetSessionJob") as? Job)
        recoveryJob.cancelAndJoin()

        assertTrue(recoveryJob.isCancelled)
        assertFailsWith<IllegalStateException> { service.sendMessage("不能回退到旧会话") }
        verify(exactly = MAX_TOOL_CALL_ROUNDS + 1) { exhaustedCandidate.sendMessage(any<List<Content>>()) }

        val manualReset = assertNotNull(service.resetSession())
        withTimeout(5.seconds) { manualReset.join() }
        assertFalse(manualReset.isCancelled)
        assertEquals("取消恢复后回复", service.sendMessage("恢复服务"))
    }

    /**
     * 验证关闭服务时等待在途消息的设计。
     *
     * 验证会话资源仅在已开始的消息处理完成后才释放。
     */
    @Test
    fun test关闭会等待在途消息完成后再释放会话() = runBlocking {
        val activeChat = mockk<Chat>()
        val candidateChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns candidateChat
        every { candidateChat.sendMessage(any<List<Content>>()) } answers {
            requestStarted.countDown()
            check(releaseRequest.await(5, TimeUnit.SECONDS))
            responseWithParts(Part.fromText("完成"))
        }
        injectClient(chats)
        injectChat(activeChat)
        setPrivateField("sdkSessionConfig", GenerateContentConfig.builder().build())
        setPrivateField("savedHistory", emptyList<Content>())

        val inFlightMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

        val closeJob = service.close()
        assertFalse(closeJob.isCompleted)

        releaseRequest.countDown()
        assertEquals("完成", withTimeout(5.seconds) { inFlightMessage.await() })
        withTimeout(5.seconds) { closeJob.join() }
        assertTrue(closeJob.isCompleted)
    }

    /**
     * 验证 Gemini 关闭在调用方取消等待任务后仍会完成客户端和所属 MCP 服务的终态清理。
     *
     * MCP 清理未完成时，重试关闭取得的等待任务不能提前完成。
     */
    @Test
    fun `Gemini close survives cancellation of its returned wait job`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val client = mockk<Client>()
        val mcpCloseJob = Job()
        val mcpCloseCalled = CompletableDeferred<Unit>()
        every { client.close() } returns Unit
        every { mcpClientService.close() } answers {
            mcpCloseCalled.complete(Unit)
            mcpCloseJob
        }
        val closingService = GeminiAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }
        setPrivateField(closingService, "client", client)

        val cancelledWaitJob = closingService.close()
        cancelledWaitJob.cancel()
        val retryWaitJob = closingService.close()
        withTimeout(5.seconds) { mcpCloseCalled.await() }
        assertFalse(retryWaitJob.isCompleted)

        mcpCloseJob.complete()
        withTimeout(5.seconds) { retryWaitJob.join() }
        verify(exactly = 1) { client.close() }
        verify(exactly = 1) { mcpClientService.close() }
    }

    private fun responseWithParts(
        vararg parts: Part,
        finishReason: FinishReason? = FinishReason(FinishReason.Known.STOP),
    ): GenerateContentResponse {
        val candidate = Candidate.builder().content(
            Content.builder().role("model").parts(parts.toList()).build(),
        )
        finishReason?.let(candidate::finishReason)
        return GenerateContentResponse.builder().candidates(candidate.build()).build()
    }

    private fun prepareSuccessfulSwitch() {
        val chats = mockk<Chats>()
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns mockk()
        injectClient(chats)
    }

    private fun privateField(name: String): Any? = GeminiAgentService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }.get(service)

    private fun injectChat(chat: Chat) {
        GeminiAgentService::class.java.getDeclaredField("chat").apply { isAccessible = true }.set(service, chat)
        GeminiAgentService::class.java.getDeclaredField("chatFunctionRouteSnapshot").apply {
            isAccessible = true
        }.set(service, LocalFunctionRouter(emptyList()).refresh())
    }

    private fun injectClient(chats: Chats, models: Models? = null) {
        val client = Client.builder().apiKey("test").build()
        Client::class.java.getDeclaredField("chats").apply { isAccessible = true }.set(client, chats)
        models?.let {
            Client::class.java.getDeclaredField("models").apply { isAccessible = true }.set(client, it)
        }
        GeminiAgentService::class.java.getDeclaredField("client").apply { isAccessible = true }.set(service, client)
    }

    private fun newService(): GeminiAgentService {
        val testScope = CoroutineScope(EmptyCoroutineContext)
        return GeminiAgentService(
            testScope,
            settingsRepository,
            skillRepository,
            MCPClientService(testScope),
        ) { mockk() }
    }

    /** 构造不触发初始会话的原生模型发现服务，并将其传输定向到测试服务器。 */
    private fun rawDiscoveryService(
        server: MockWebServer,
        deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    ): GeminiAgentService {
        val testScope = CoroutineScope(EmptyCoroutineContext)
        return GeminiAgentService(
            testScope,
            settingsRepository,
            skillRepository,
            MCPClientService(testScope),
            deadlines,
        ) { mockk() }.also { rawService ->
            setPrivateField(rawService, "rawApiKey", "test-key")
            setPrivateField(rawService, "rawBaseUrl", server.url("/v1beta").toString().trimEnd('/'))
            setPrivateField(rawService, "rawTransport", CancellableOkHttpTransport(OkHttpClient()))
        }
    }

    private fun setPrivateField(name: String, value: Any?) {
        GeminiAgentService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun setPrivateField(target: GeminiAgentService, name: String, value: Any?) {
        GeminiAgentService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    /** 构造恰好超过统一限制的深 JSON，让原生 Gemini 响应在字段读取前被拒绝。 */
    private fun deeplyNestedJson(depth: Int): String = buildString {
        repeat(depth) { append("{\"next\":") }
        append("\"leaf\"")
        repeat(depth) { append('}') }
    }

}
