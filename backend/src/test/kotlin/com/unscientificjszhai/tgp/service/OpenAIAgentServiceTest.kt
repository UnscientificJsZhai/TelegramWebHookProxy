package com.unscientificjszhai.tgp.service

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.*
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ChatService
import com.openai.services.blocking.ModelService
import com.openai.services.blocking.chat.ChatCompletionService
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.service.ai.agent.MAX_TOOL_CALL_ROUNDS
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import com.unscientificjszhai.tgp.service.ai.function.HttpCallingFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * OpenAI 代理服务的模型刷新、会话生命周期、并发与请求参数测试设计。
 */
class OpenAIAgentServiceTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRepository: SkillRepository
    private lateinit var service: OpenAIAgentService
    private lateinit var tempDirectory: File

    @BeforeTest
    fun setup() {
        tempDirectory = Files.createTempDirectory("openai-agent-service-test").toFile()
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), ModelSwitchBarrier())
        skillRepository = SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        service = OpenAIAgentService(
            testScope,
            settingsRepository,
            skillRepository,
            MCPClientService(testScope),
        ) { mockk() }
    }

    @AfterTest
    fun teardown() {
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证初始模型选择的设计。
     *
     * 验证默认模型为 Luna，且可选列表保留 GPT-4o。
     */
    @Test
    fun testDefaultModelIsLunaAndRetainsGpt4oOptions() {
        assertEquals("gpt-5.6-luna", service.currentModel)
        assertEquals(
            listOf(
                "gpt-5.6-luna",
                ChatModel.GPT_4O.toString(),
                ChatModel.GPT_4O_MINI.toString(),
            ),
            service.availableModels,
        )
    }

    /**
     * 验证首选模型回退顺序的设计。
     *
     * 验证服务会按既定优先级从可用模型中选择回退值。
     */
    @Test
    fun testPreferredModelFallbackOrder() {
        assertEquals("gpt-5.6-luna", service.preferredModel(listOf("gpt-5.6-luna", "gpt-4o")))
        assertEquals("gpt-4o", service.preferredModel(listOf("third-party", "gpt-4o")))
        assertEquals("third-party", service.preferredModel(listOf("third-party", "gpt-4o-mini")))
        assertEquals(null, service.preferredModel(emptyList()))
    }

    /**
     * 验证刷新前恢复持久化模型选择的设计。
     *
     * 验证服务创建时会先采用已保存的有效模型。
     */
    @Test
    fun testServiceRestoresPersistedSelectedModelBeforeRefreshing() {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, selectedModel = "gpt-4o")),
        )

        val restoredService = newService()

        assertEquals("gpt-4o", restoredService.currentModel)
    }

    /**
     * 验证成功刷新对无效已选模型的回退设计。
     *
     * 验证无效持久化模型会被清空并切换到可用回退模型。
     */
    @Test
    fun testSuccessfulRefreshClearsInvalidPersistedModelAndFallsBack() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val fallbackModel = mockk<Model>()
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-key",
                    selectedModel = "gpt-5.6-luna",
                ),
            ),
        )
        every { client.models() } returns modelService
        every { modelService.list() } returns page
        every { page.data() } returns listOf(fallbackModel)
        every { fallbackModel.id() } returns "fallback-model"
        setPrivateField("configuredApiKey", "test-key")
        setPrivateField("configuredBaseUrl", "")
        injectClient(client)

        service.updateModel()

        assertEquals("fallback-model", service.currentModel)
        assertEquals("", settingsRepository.settingsFlow.value.ai?.selectedModel)
    }

    /**
     * 验证刷新失败时保留模型选择的设计。
     *
     * 验证请求模型列表失败不会覆盖已持久化的模型。
     */
    @Test
    fun testFailedRefreshRetainsPersistedSelectedModel() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-key",
                    selectedModel = "gpt-5.6-luna",
                ),
            ),
        )
        every { client.models() } returns modelService
        every { modelService.list() } throws IllegalStateException("network failure")
        injectClient(client)

        assertEquals(null, service.updateModel())

        assertEquals("gpt-5.6-luna", settingsRepository.settingsFlow.value.ai?.selectedModel)
    }

    /**
     * 验证 Luna 不可用时的模型回退设计。
     *
     * 验证刷新模型列表后会选择 GPT-4o 作为回退模型。
     */
    @Test
    fun testModelRefreshFallsBackToGpt4oWhenLunaUnavailable() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val thirdPartyModel = mockk<Model>()
        val gpt4oModel = mockk<Model>()
        every { client.models() } returns modelService
        every { modelService.list() } returns page
        every { page.data() } returns listOf(thirdPartyModel, gpt4oModel)
        every { thirdPartyModel.id() } returns "third-party"
        every { gpt4oModel.id() } returns ChatModel.GPT_4O.toString()
        injectClient(client)

        val snapshot = service.updateModel()

        assertEquals(listOf("third-party", ChatModel.GPT_4O.toString()), service.availableModels)
        assertEquals(ChatModel.GPT_4O.toString(), service.currentModel)
        assertEquals(service.currentModel, snapshot?.currentModel)
        assertEquals(service.availableModels, snapshot?.availableModels)
    }

    /**
     * 验证模型刷新失败时的缓存保留设计。
     *
     * 验证方法返回失败且现有可用模型列表不变。
     */
    @Test
    fun testModelRefreshFailureReturnsFalseAndRetainsCachedModels() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        every { client.models() } returns modelService
        every { modelService.list() } throws IllegalStateException("network failure")
        injectClient(client)
        val cachedModels = service.availableModels

        assertEquals(null, service.updateModel())

        assertEquals(cachedModels, service.availableModels)
    }

    /**
     * 验证模型刷新取消的传播设计。
     *
     * 验证底层调用取消会原样传递给调用方。
     */
    @Test
    fun testModelRefreshPropagatesCancellation() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        every { client.models() } returns modelService
        every { modelService.list() } throws CancellationException("cancelled")
        injectClient(client)

        assertFailsWith<CancellationException> {
            service.updateModel()
        }
        Unit
    }

    /**
     * 验证模型刷新与并发切换的竞争处理设计。
     *
     * 验证已开始的刷新不会覆盖并发完成的模型选择。
     */
    @Test
    fun testModelRefreshDoesNotOverrideConcurrentModelSwitch() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val thirdPartyModel = mockk<Model>()
        val refreshStarted = CountDownLatch(1)
        val continueRefresh = CountDownLatch(1)
        every { client.models() } returns modelService
        every { modelService.list() } answers {
            refreshStarted.countDown()
            check(continueRefresh.await(5, TimeUnit.SECONDS))
            page
        }
        every { page.data() } returns listOf(thirdPartyModel)
        every { thirdPartyModel.id() } returns "third-party"
        injectClient(client)

        val refreshJob = launch(Dispatchers.Default) { service.updateModel() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        service.switchModel(ChatModel.GPT_4O.toString())
        continueRefresh.countDown()
        refreshJob.join()

        assertEquals(ChatModel.GPT_4O.toString(), service.currentModel)
        assertTrue(service.currentModel in service.availableModels)
    }

    /**
     * 验证排队模型切换与已开始刷新之间的竞争处理设计。
     *
     * 验证排队切换会阻止已开始的刷新覆盖最终选择。
     */
    @Test
    fun test排队模型切换会阻止已开始的模型刷新覆盖选择() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val thirdPartyModel = mockk<Model>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            firstRequestStarted.countDown()
            check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
            textResponse("在途回复")
        }
        every { client.models() } returns modelService
        every { modelService.list() } answers {
            refreshStarted.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            page
        }
        every { page.data() } returns listOf(thirdPartyModel)
        every { thirdPartyModel.id() } returns "third-party"
        injectClient(client)

        val firstMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val refreshJob = async(Dispatchers.Default) { service.updateModel() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        val switchJob = assertNotNull(service.switchModel(ChatModel.GPT_4O.toString()))

        assertFalse(switchJob.isCompleted)
        assertEquals("gpt-5.6-luna", service.currentModel)
        releaseRefresh.countDown()
        assertFalse(refreshJob.isCompleted)

        releaseFirstRequest.countDown()
        assertEquals("在途回复", firstMessage.await())
        assertEquals(null, refreshJob.await())
        switchJob.join()
        assertEquals(ChatModel.GPT_4O.toString(), service.currentModel)
        assertTrue(ChatModel.GPT_4O.toString() in service.availableModels)
    }

    /**
     * 验证回退模型刷新与在途消息的协调设计。
     *
     * 验证服务会等待在途消息结束后再重置会话并应用回退模型。
     */
    @Test
    fun testFallback模型刷新会等待在途消息后重置会话() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val fallbackModel = mockk<Model>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val refreshResultReady = CountDownLatch(1)
        val fallbackModelName = "fallback-model"
        settingsRepository.saveSettings(AppSettings(ai = AISettings(globalContext = "重置系统提示词")))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            when (requests.size) {
                1 -> {
                    firstRequestStarted.countDown()
                    check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
                    textResponse("在途回复")
                }

                2 -> textResponse("新会话回复")
                else -> error("不应发起额外的模型请求")
            }
        }
        every { client.models() } returns modelService
        every { modelService.list() } returns page
        every { page.data() } answers {
            refreshResultReady.countDown()
            listOf(fallbackModel)
        }
        every { fallbackModel.id() } returns fallbackModelName
        injectClient(client)
        assertNotNull(service.resetSession()).join()

        val firstMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val refreshJob = async(Dispatchers.Default) { service.updateModel() }
        assertTrue(refreshResultReady.await(5, TimeUnit.SECONDS))

        assertFalse(refreshJob.isCompleted)
        assertEquals("gpt-5.6-luna", service.currentModel)
        releaseFirstRequest.countDown()
        assertEquals("在途回复", firstMessage.await())
        val snapshot = refreshJob.await()

        assertEquals(fallbackModelName, snapshot?.currentModel)
        assertEquals(fallbackModelName, service.currentModel)
        assertEquals(listOf(fallbackModelName), service.availableModels)
        val historyAfterRefresh = service.createChatCompletionParams(emptyList()).messages()
        assertEquals(1, historyAfterRefresh.size)
        assertTrue(historyAfterRefresh.single().isSystem())

        assertEquals("新会话回复", service.sendMessage("下一条消息"))
        assertEquals(2, requests.last().messages().size)
        assertEquals(fallbackModelName, requests.last().model().asString())
        assertTrue(requests.last().messages()[0].isSystem())
        assertTrue(requests.last().messages()[1].isUser())
    }

    /**
     * 验证重置会话的 MCP 连接任务返回设计。
     *
     * 验证重置操作返回并启动新的 MCP 连接任务。
     */
    @Test
    fun testResetSessionReturnsMcpConnectionJob() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(AppSettings(ai = AISettings(mcpServers = mcpServers)))
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            connectionStarted.complete(Unit)
            releaseConnection.await()
        }
        val resettableService = OpenAIAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        val resetJob = assertNotNull(resettableService.resetSession())
        connectionStarted.await()
        assertFalse(resetJob.isCompleted)
        releaseConnection.complete(Unit)
        resetJob.join()

        assertTrue(resetJob.isCompleted)
        coVerify(exactly = 1) { mcpClientService.connect(mcpServers) }
    }

    /**
     * 验证启用 OpenAI 的新实例只会在首轮 MCP 连接与工具快照完成后报告就绪。
     */
    @Test
    fun `initial OpenAI readiness waits for the MCP connection`() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val connectionStarted = CompletableDeferred<Unit>()
        val releaseConnection = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-key",
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
        val initializedService = OpenAIAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        try {
            connectionStarted.await()
            val readiness = assertNotNull(initializedService.initializationJob())
            assertFalse(readiness.isCompleted)

            releaseConnection.complete(Unit)
            readiness.join()

            assertFalse(readiness.isCancelled)
        } finally {
            releaseConnection.complete(Unit)
            initializedService.close().join()
        }
    }

    /**
     * 验证连续重置时 MCP 连接的串行设计。
     *
     * 验证后一连接仅在前一连接任务结束后启动。
     */
    @Test
    fun test连续重置会在前一MCP连接结束后启动新连接() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val firstConnectionStarted = CompletableDeferred<Unit>()
        val firstConnectionCancelled = CompletableDeferred<Unit>()
        val secondConnectionStartedAfterFirstEnded = CompletableDeferred<Boolean>()
        val releaseSecondConnection = CompletableDeferred<Unit>()
        val connectionCalls = AtomicInteger()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(AppSettings(ai = AISettings(mcpServers = mcpServers)))
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            when (connectionCalls.incrementAndGet()) {
                1 -> {
                    firstConnectionStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        firstConnectionCancelled.complete(Unit)
                    }
                }

                2 -> {
                    secondConnectionStartedAfterFirstEnded.complete(firstConnectionCancelled.isCompleted)
                    releaseSecondConnection.await()
                }

                else -> error("不应创建额外的 MCP 连接")
            }
        }
        val resettableService = OpenAIAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        val firstResetJob = assertNotNull(resettableService.resetSession())
        firstConnectionStarted.await()
        val secondResetJob = assertNotNull(resettableService.resetSession())

        assertTrue(secondConnectionStartedAfterFirstEnded.await())
        releaseSecondConnection.complete(Unit)
        firstResetJob.join()
        secondResetJob.join()

        assertEquals(2, connectionCalls.get())
        coVerify(exactly = 2) { mcpClientService.connect(mcpServers) }
    }

    /**
     * 验证取消重置时 MCP 连接任务的处理设计。
     *
     * 验证取消会终止并等待相关连接任务结束。
     */
    @Test
    fun test取消重置会取消并等待MCP连接任务() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val connectionStarted = CompletableDeferred<Unit>()
        val connectionCancelled = CompletableDeferred<Unit>()
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(AppSettings(ai = AISettings(mcpServers = mcpServers)))
        coEvery { mcpClientService.connect(mcpServers) } coAnswers {
            connectionStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                connectionCancelled.complete(Unit)
            }
        }
        val resettableService = OpenAIAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }

        val resetJob = assertNotNull(resettableService.resetSession())
        connectionStarted.await()
        resetJob.cancel()
        resetJob.join()

        assertTrue(resetJob.isCancelled)
        assertTrue(connectionCancelled.isCompleted)
        coVerify(exactly = 1) { mcpClientService.connect(mcpServers) }
    }

    /**
     * 验证关闭服务对排队重置的处理设计。
     *
     * 验证关闭会取消尚未执行的重置且不会建立 MCP 连接。
     */
    @Test
    fun test关闭会取消排队重置且不会连接MCP() = runBlocking {
        val mcpClientService = mockk<MCPClientService>()
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val mcpServers = listOf(MCPServerConfig(name = "test", url = "https://example.com/mcp"))
        settingsRepository.saveSettings(AppSettings(ai = AISettings(mcpServers = mcpServers)))
        every { mcpClientService.close() } returns Job().apply { complete() }
        every { mcpClientService.getAllTools() } returns emptyList()
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            firstRequestStarted.countDown()
            check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
            textResponse("在途回复")
        }
        val resettableService = OpenAIAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }
        OpenAIAgentService::class.java.getDeclaredField("client").apply {
            isAccessible = true
        }.set(resettableService, client)

        val firstMessage = async(Dispatchers.Default) { resettableService.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val resetJob = assertNotNull(resettableService.resetSession())
        val switchJob = assertNotNull(resettableService.switchModel(ChatModel.GPT_4O.toString()))

        val closeJob = resettableService.close()
        assertFalse(closeJob.isCompleted)

        releaseFirstRequest.countDown()
        assertEquals("在途回复", withTimeout(5.seconds) { firstMessage.await() })
        withTimeout(5.seconds) { closeJob.join() }

        assertTrue(resetJob.isCancelled)
        assertTrue(switchJob.isCancelled)
        coVerify(exactly = 0) { mcpClientService.connect(mcpServers) }
        verify(exactly = 1) { mcpClientService.close() }
    }

    /**
     * 验证 OpenAI SDK 客户端的终态关闭设计。
     *
     * 验证重复关闭不会重复关闭同一 SDK 客户端。
     */
    @Test
    fun test关闭会恰好关闭OpenAISDK客户端一次() = runBlocking {
        val client = mockk<OpenAIClient>()
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val closingService = newService(mcpClientService)
        injectClient(closingService, client)

        withTimeout(5.seconds) { closingService.close().join() }
        withTimeout(5.seconds) { closingService.close().join() }

        verify(exactly = 1) { client.close() }
        verify(exactly = 1) { mcpClientService.close() }
    }

    /**
     * 验证关闭状态发布与后续调用的竞争处理设计。
     *
     * 验证 [OpenAIAgentService.close] 返回后立即并发发起的消息、会话重置和模型刷新均不会启动工作。
     */
    @Test
    fun test关闭返回后并发消息重置和模型刷新均被拒绝() = runBlocking {
        val client = mockk<OpenAIClient>()
        val mcpClientService = mockk<MCPClientService>()
        val mcpCloseJob = Job()
        every { mcpClientService.close() } returns mcpCloseJob
        val closingService = newService(mcpClientService)
        injectClient(closingService, client)

        val closeJob = closingService.close()
        assertFalse(closeJob.isCompleted)
        val sendFailure = async(Dispatchers.Default) {
            try {
                closingService.sendMessage("关闭后的消息")
                null
            } catch (e: Exception) {
                e
            }
        }
        val resetJob = async(Dispatchers.Default) { closingService.resetSession() }
        val refreshResult = async(Dispatchers.Default) { closingService.updateModel() }

        assertIs<IllegalStateException>(sendFailure.await())
        assertEquals(null, resetJob.await())
        assertEquals(null, refreshResult.await())
        verify(exactly = 0) { client.chat() }
        verify(exactly = 0) { client.models() }
        coVerify(exactly = 0) { mcpClientService.connect(any()) }

        mcpCloseJob.complete()
        withTimeout(5.seconds) { closeJob.join() }
    }

    /**
     * 验证 OpenAI 关闭在调用方取消等待任务后仍会完成其所属 MCP 服务的终态清理。
     *
     * MCP 清理未完成时，重试关闭取得的等待任务不能提前完成。
     */
    @Test
    fun `OpenAI close survives cancellation of its returned wait job`() = runBlocking {
        val client = mockk<OpenAIClient>()
        val mcpClientService = mockk<MCPClientService>()
        val mcpCloseJob = Job()
        val mcpCloseCalled = CompletableDeferred<Unit>()
        every { mcpClientService.close() } answers {
            mcpCloseCalled.complete(Unit)
            mcpCloseJob
        }
        val closingService = OpenAIAgentService(
            CoroutineScope(EmptyCoroutineContext),
            settingsRepository,
            skillRepository,
            mcpClientService,
        ) { mockk() }
        injectClient(closingService, client)

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

    /**
     * 验证 SDK 客户端关闭失败时的资源清理设计。
     *
     * 验证 SDK 关闭异常不会跳过 HTTP 工具客户端或 MCP 连接的关闭。
     */
    @Test
    fun testSDK关闭异常仍关闭HTTP工具和MCP连接() = runBlocking {
        val client = mockk<OpenAIClient>()
        val httpCallingFunctionProvider = mockk<HttpCallingFunctionProvider>()
        val mcpClientService = mockk<MCPClientService>()
        every { client.close() } throws IllegalStateException("SDK close failure")
        every { mcpClientService.close() } returns Job().apply { complete() }
        val closingService = newService(mcpClientService)
        setPrivateField(closingService, "httpCallingFunctionProvider", httpCallingFunctionProvider)
        injectClient(closingService, client)

        withTimeout(5.seconds) { closingService.close().join() }

        verify(exactly = 1) { client.close() }
        verify(exactly = 1) { httpCallingFunctionProvider.close() }
        verify(exactly = 1) { mcpClientService.close() }
    }

    /**
     * 验证关闭与在途模型刷新的协调设计。
     *
     * 验证关闭会等待已经取得 SDK 客户端的模型刷新退出后，再关闭该客户端。
     */
    @Test
    fun test关闭等待在途模型刷新后再关闭SDK客户端() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val mcpClientService = mockk<MCPClientService>()
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        every { client.models() } returns modelService
        every { modelService.list() } answers {
            refreshStarted.countDown()
            check(releaseRefresh.await(5, TimeUnit.SECONDS))
            page
        }
        every { page.data() } returns emptyList()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val closingService = newService(mcpClientService)
        injectClient(closingService, client)

        val refreshJob = async(Dispatchers.Default) { closingService.updateModel() }
        assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))
        val closeJob = closingService.close()

        assertFalse(closeJob.isCompleted)
        verify(exactly = 0) { client.close() }
        releaseRefresh.countDown()
        assertEquals(null, withTimeout(5.seconds) { refreshJob.await() })
        withTimeout(5.seconds) { closeJob.join() }

        verify(exactly = 1) { client.close() }
    }

    /**
     * 验证初始模型刷新取消与 SDK 关闭的协调设计。
     *
     * 验证服务关闭会先取消并等待初始模型刷新任务完成，再关闭 SDK 客户端。
     */
    @Test
    fun test关闭等待初始模型刷新取消完成后再关闭SDK客户端() = runBlocking {
        val client = mockk<OpenAIClient>()
        val mcpClientService = mockk<MCPClientService>()
        val initialRefreshCancelling = CompletableDeferred<Unit>()
        val releaseInitialRefresh = CompletableDeferred<Unit>()
        every { mcpClientService.close() } returns Job().apply { complete() }
        val closingService = newService(mcpClientService)
        injectClient(closingService, client)
        val initialRefreshJob = serviceScope(closingService).launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                initialRefreshCancelling.complete(Unit)
                withContext(NonCancellable) { releaseInitialRefresh.await() }
            }
        }
        setPrivateField(closingService, "initialModelUpdateJob", initialRefreshJob)

        val closeJob = closingService.close()
        withTimeout(5.seconds) { initialRefreshCancelling.await() }
        assertFalse(closeJob.isCompleted)
        verify(exactly = 0) { client.close() }

        releaseInitialRefresh.complete(Unit)
        withTimeout(5.seconds) { initialRefreshJob.join() }
        withTimeout(5.seconds) { closeJob.join() }

        verify(exactly = 1) { client.close() }
    }

    /**
     * 验证被新选择失效的排队模型切换设计。
     *
     * 验证失效切换不会重置会话或覆盖最新选择。
     */
    @Test
    fun test排队模型切换被最新选择失效且不会重置会话() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val requests = mutableListOf<ChatCompletionCreateParams>()
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            firstRequestStarted.countDown()
            check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
            textResponse("第一条回复")
        }
        injectClient(client)

        val firstMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val switchToGpt4o = assertNotNull(service.switchModel(ChatModel.GPT_4O.toString()))
        val switchBackToDefault = assertNotNull(service.switchModel("gpt-5.6-luna"))

        releaseFirstRequest.countDown()
        assertEquals("第一条回复", withTimeout(5.seconds) { firstMessage.await() })
        withTimeout(5.seconds) {
            switchToGpt4o.join()
            switchBackToDefault.join()
        }

        assertEquals("gpt-5.6-luna", service.currentModel)
        assertEquals(1, requests.size)
        assertEquals(2, service.createChatCompletionParams(emptyList()).messages().size)
    }

    /**
     * 验证工具调用达到上限后的事务回滚设计。
     *
     * 验证失败会抛出未完成回合异常，且不会清空既有会话。
     */
    @Test
    fun test工具调用达到上限后回滚历史并保留后续会话() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val providerCalls = mutableListOf<String>()
        val toolCallResponse = toolCallResponse(functionToolCall("call-limit", "success", "{}"))
        setPrivateField("localFunctionProviders", listOf(RecordingFunctionProvider(providerCalls)))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            if (requests.size <= MAX_TOOL_CALL_ROUNDS + 1) {
                toolCallResponse
            } else {
                textResponse("新会话完成")
            }
        }
        injectClient(client)

        assertFailsWith<AgentTurnFailedException> {
            service.sendMessage("持续调用工具")
        }
        assertTrue(service.createChatCompletionParams(emptyList()).messages().isEmpty())
        val nextReply = service.sendMessage("继续对话")

        assertEquals("新会话完成", nextReply)
        assertEquals(MAX_TOOL_CALL_ROUNDS + 2, requests.size)
        assertEquals(21, requests[MAX_TOOL_CALL_ROUNDS].messages().size)
        assertEquals(1, requests.last().messages().size)
        assertEquals(2, service.createChatCompletionParams(emptyList()).messages().size)
        assertEquals(List(MAX_TOOL_CALL_ROUNDS) { "success" }, providerCalls)
    }

    /**
     * 验证每个有效工具标识均紧邻对应工具结果，且可恢复的工具错误不会中断回合。
     */
    @Test
    fun `tool results preserve every valid id and stable errors`() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val providerCalls = mutableListOf<String>()
        setPrivateField("localFunctionProviders", listOf(RecordingFunctionProvider(providerCalls)))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            when (requests.size) {
                1 -> toolCallResponse(
                    functionToolCall("call-success", "success", "{}"),
                    functionToolCall("call-invalid", "success", "[]"),
                    functionToolCall("call-unknown", "unknown", "{}"),
                    functionToolCall("call-throws", "throws", "{}"),
                    customToolCall("call-custom"),
                )

                2 -> textResponse("完成")
                else -> error("不应发起额外模型请求")
            }
        }
        injectClient(client)

        assertEquals("完成", service.sendMessage("执行工具"))
        assertEquals(listOf("success", "throws"), providerCalls)
        val toolMessages = requests[1].messages().filter { it.isTool() }
        assertEquals(
            listOf("call-success", "call-invalid", "call-unknown", "call-throws", "call-custom"),
            toolMessages.map { it.asTool().toolCallId() },
        )
        assertEquals(
            listOf(null, "invalid_arguments", "unknown_tool", "tool_execution_failed", "unsupported_tool"),
            toolMessages.map { toolMessage ->
                Json.parseToJsonElement(toolMessage.asTool().content().asText())
                    .let { it as JsonObject }["error"]?.toString()?.removeSurrounding("\"")
            },
        )
        assertEquals(8, service.createChatCompletionParams(emptyList()).messages().size)
    }

    /**
     * 验证工具已执行后模型失败或取消时只回滚历史，不回滚外部工具副作用。
     */
    @Test
    fun `model failure and cancellation after a tool keep the prior history`() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val providerCalls = mutableListOf<String>()
        val responses = ArrayDeque<Any>(
            listOf(
                textResponse("既有回复"),
                toolCallResponse(functionToolCall("call-fail", "success", "{}")),
                IllegalStateException("upstream failure"),
                toolCallResponse(functionToolCall("call-cancel", "success", "{}")),
                CancellationException("cancelled"),
            ),
        )
        setPrivateField("localFunctionProviders", listOf(RecordingFunctionProvider(providerCalls)))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            when (val response = responses.removeFirst()) {
                is ChatCompletion -> response
                is Exception -> throw response
                else -> error("未知响应")
            }
        }
        injectClient(client)

        assertEquals("既有回复", service.sendMessage("既有消息"))
        val baseline = service.createChatCompletionParams(emptyList()).messages()
        assertFailsWith<AgentTurnFailedException> { service.sendMessage("会失败") }
        assertEquals(baseline, service.createChatCompletionParams(emptyList()).messages())
        assertFailsWith<CancellationException> { service.sendMessage("会取消") }
        assertEquals(baseline, service.createChatCompletionParams(emptyList()).messages())
        assertEquals(listOf("success", "success"), providerCalls)
    }

    /**
     * 验证一批工具调用中任一标识无效或重复时，整批均不会执行且不会提交。
     */
    @Test
    fun `invalid tool ids fail the whole batch before executing providers`() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val providerCalls = mutableListOf<String>()
        setPrivateField("localFunctionProviders", listOf(RecordingFunctionProvider(providerCalls)))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } returnsMany listOf(
            textResponse("既有回复"),
            toolCallResponse(
                functionToolCall("call-valid", "success", "{}"),
                functionToolCall("", "success", "{}"),
            ),
            toolCallResponse(
                functionToolCall("call-duplicate", "success", "{}"),
                functionToolCall("call-duplicate", "success", "{}"),
            ),
        )
        injectClient(client)

        assertEquals("既有回复", service.sendMessage("既有消息"))
        val baseline = service.createChatCompletionParams(emptyList()).messages()
        assertFailsWith<AgentTurnFailedException> { service.sendMessage("混合标识调用") }
        assertEquals(baseline, service.createChatCompletionParams(emptyList()).messages())
        assertFailsWith<AgentTurnFailedException> { service.sendMessage("重复标识调用") }
        assertEquals(baseline, service.createChatCompletionParams(emptyList()).messages())
        assertTrue(providerCalls.isEmpty())
    }

    /**
     * 验证仅停止完成原因可提交终态 assistant，未完成或自相矛盾的协议响应会回滚回合。
     */
    @Test
    fun `non terminal OpenAI finish reasons roll back the turn`() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } returnsMany listOf(
            textResponse("既有回复"),
            textResponse("被截断", ChatCompletion.Choice.FinishReason.LENGTH),
            textResponse("被过滤", ChatCompletion.Choice.FinishReason.CONTENT_FILTER),
            responseWithoutToolCalls(ChatCompletion.Choice.FinishReason.TOOL_CALLS),
            responseWithoutToolCalls(ChatCompletion.Choice.FinishReason.FUNCTION_CALL),
            toolCallResponse(ChatCompletion.Choice.FinishReason.STOP, functionToolCall("call-stop", "success", "{}")),
        )
        injectClient(client)

        assertEquals("既有回复", service.sendMessage("既有消息"))
        val baseline = service.createChatCompletionParams(emptyList()).messages()
        repeat(5) {
            assertFailsWith<AgentTurnFailedException> { service.sendMessage("协议失败$it") }
            assertEquals(baseline, service.createChatCompletionParams(emptyList()).messages())
        }
    }

    /**
     * 验证发送消息取消的传播设计。
     *
     * 验证底层请求取消会原样传递给调用方。
     */
    @Test
    fun test发送消息取消会向调用方透传() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } throws CancellationException("请求已取消")
        injectClient(client)

        assertFailsWith<CancellationException> {
            service.sendMessage("取消请求")
        }
        Unit
    }

    /**
     * 验证重置会话与在途请求的协调设计。
     *
     * 验证重置等待在途请求完成且新会话不保留旧历史。
     */
    @Test
    fun test重置会等待在途请求且不会保留旧历史() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        settingsRepository.saveSettings(AppSettings(ai = AISettings(globalContext = "重置系统提示词")))
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            firstRequestStarted.countDown()
            check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
            textResponse("首个回复")
        }
        injectClient(client)
        assertNotNull(service.resetSession()).join()

        val firstMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val resetJob = assertNotNull(service.resetSession())

        assertFalse(resetJob.isCompleted)
        releaseFirstRequest.countDown()
        assertEquals("首个回复", firstMessage.await())
        resetJob.join()

        val historyAfterReset = service.createChatCompletionParams(emptyList()).messages()
        assertEquals(1, historyAfterReset.size)
        assertTrue(historyAfterReset.single().isSystem())
    }

    /**
     * 验证模型切换与在途请求的协调设计。
     *
     * 验证切换等待在途请求完成，并在重置后使用新模型。
     */
    @Test
    fun test模型切换会等待在途请求并在重置后使用新模型() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val secondMessageStarted = CountDownLatch(1)
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            when (requests.size) {
                1 -> {
                    firstRequestStarted.countDown()
                    check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
                    textResponse("第一条回复")
                }

                2 -> textResponse("第二条回复")
                else -> error("不应发起额外的模型请求")
            }
        }
        injectClient(client)

        val firstMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val switchJob = assertNotNull(service.switchModel(ChatModel.GPT_4O.toString()))
        assertFalse(switchJob.isCompleted)
        assertEquals("gpt-5.6-luna", service.currentModel)
        val secondMessage = async(Dispatchers.Default) {
            secondMessageStarted.countDown()
            service.sendMessage("第二条消息")
        }
        assertTrue(secondMessageStarted.await(5, TimeUnit.SECONDS))

        releaseFirstRequest.countDown()
        assertEquals("第一条回复", firstMessage.await())
        switchJob.join()
        assertEquals(ChatModel.GPT_4O.toString(), service.currentModel)
        assertEquals("第二条回复", secondMessage.await())

        assertEquals(2, requests.size)
        assertEquals("gpt-5.6-luna", requests.first().model().asString())
        assertEquals(ChatModel.GPT_4O.toString(), requests.last().model().asString())
        assertEquals(1, requests.first().messages().size)
        assertEquals(1, requests.last().messages().size)
    }

    /**
     * 验证并发消息的对话事务串行设计。
     *
     * 验证并发请求会按完整对话事务顺序执行。
     */
    @Test
    fun test并发消息会按完整对话事务串行执行() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val firstRequestStarted = CountDownLatch(1)
        val releaseFirstRequest = CountDownLatch(1)
        val secondMessageStarted = CountDownLatch(1)
        val secondRequestStarted = CountDownLatch(1)
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            when (requests.size) {
                1 -> {
                    firstRequestStarted.countDown()
                    check(releaseFirstRequest.await(5, TimeUnit.SECONDS))
                    textResponse("第一条回复")
                }

                2 -> {
                    secondRequestStarted.countDown()
                    textResponse("第二条回复")
                }

                else -> error("不应发起额外的模型请求")
            }
        }
        injectClient(client)

        val firstMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(firstRequestStarted.await(5, TimeUnit.SECONDS))
        val secondMessage = async(Dispatchers.Default) {
            secondMessageStarted.countDown()
            service.sendMessage("第二条消息")
        }

        assertTrue(secondMessageStarted.await(5, TimeUnit.SECONDS))
        assertFalse(secondRequestStarted.await(200, TimeUnit.MILLISECONDS))
        releaseFirstRequest.countDown()
        assertEquals("第一条回复", firstMessage.await())
        assertTrue(secondRequestStarted.await(5, TimeUnit.SECONDS))
        assertEquals("第二条回复", secondMessage.await())

        assertEquals(2, requests.size)
        assertEquals(1, requests.first().messages().size)
        assertEquals(3, requests.last().messages().size)
        assertTrue(requests.last().messages()[1].isAssistant())
    }

    /**
     * 验证 GPT-5.6 请求的推理强度参数设计。
     *
     * 验证请求会显式设置不使用推理强度。
     */
    @Test
    fun testGpt56RequestSetsReasoningEffortNone() = runBlocking {
        val params = service.createChatCompletionParams(emptyList())

        assertEquals(ReasoningEffort.NONE, params.reasoningEffort().get())
    }

    /**
     * 验证旧模型请求的推理强度兼容设计。
     *
     * 验证旧模型请求不会设置该参数。
     */
    @Test
    fun testLegacyModelRequestDoesNotSetReasoningEffort() = runBlocking {
        service.switchModel(ChatModel.GPT_4O.toString())?.join()

        val params = service.createChatCompletionParams(emptyList())

        assertFalse(params.reasoningEffort().isPresent)
    }

    private fun injectClient(client: OpenAIClient) = injectClient(service, client)

    private fun injectClient(target: OpenAIAgentService, client: OpenAIClient) {
        setPrivateField(target, "client", client)
    }

    private fun newService(mcpClientService: MCPClientService? = null): OpenAIAgentService {
        val testScope = CoroutineScope(EmptyCoroutineContext)
        return OpenAIAgentService(
            testScope,
            settingsRepository,
            skillRepository,
            mcpClientService ?: MCPClientService(testScope),
        ) { mockk() }
    }

    private fun setPrivateField(name: String, value: Any?) {
        setPrivateField(service, name, value)
    }

    private fun setPrivateField(target: OpenAIAgentService, name: String, value: Any?) {
        OpenAIAgentService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun serviceScope(target: OpenAIAgentService): CoroutineScope =
        OpenAIAgentService::class.java.getDeclaredField("scope").apply { isAccessible = true }
            .get(target) as CoroutineScope

    private fun toolCallResponse(): ChatCompletion = toolCallResponse(
        ChatCompletion.Choice.FinishReason.TOOL_CALLS,
        functionToolCall("call-first", "missing_first", "{}"),
        functionToolCall("call-second", "missing_second", "{}"),
    )

    private fun toolCallResponse(vararg toolCalls: ChatCompletionMessageToolCall): ChatCompletion =
        toolCallResponse(ChatCompletion.Choice.FinishReason.TOOL_CALLS, *toolCalls)

    private fun toolCallResponse(
        finishReason: ChatCompletion.Choice.FinishReason,
        vararg toolCalls: ChatCompletionMessageToolCall,
    ): ChatCompletion {
        val message = ChatCompletionMessage.builder()
            .content(null)
            .refusal(null)
            .toolCalls(toolCalls.toList())
            .build()
        return completionResponse(message, finishReason)
    }

    private fun responseWithoutToolCalls(finishReason: ChatCompletion.Choice.FinishReason): ChatCompletion =
        completionResponse(
            ChatCompletionMessage.builder()
                .content(null)
                .refusal(null)
                .build(),
            finishReason,
        )

    private fun completionResponse(
        message: ChatCompletionMessage,
        finishReason: ChatCompletion.Choice.FinishReason,
    ): ChatCompletion {
        val choice = ChatCompletion.Choice.builder()
            .finishReason(finishReason)
            .index(0)
            .logprobs(null)
            .message(message)
            .build()
        return ChatCompletion.builder()
            .id("completion")
            .choices(listOf(choice))
            .created(0)
            .model("test-model")
            .build()
    }

    private fun functionToolCall(
        id: String,
        name: String,
        arguments: String,
    ): ChatCompletionMessageToolCall =
        ChatCompletionMessageToolCall.ofFunction(
            ChatCompletionMessageFunctionToolCall.builder()
                .id(id)
                .function(
                    ChatCompletionMessageFunctionToolCall.Function.builder()
                        .name(name)
                        .arguments(arguments)
                        .build(),
                )
                .build(),
        )

    private fun customToolCall(id: String): ChatCompletionMessageToolCall =
        ChatCompletionMessageToolCall.ofCustom(
            ChatCompletionMessageCustomToolCall.builder()
                .id(id)
                .custom(
                    ChatCompletionMessageCustomToolCall.Custom.builder()
                        .name("custom")
                        .input("input")
                        .build(),
                )
                .build(),
        )

    private class RecordingFunctionProvider(
        private val calls: MutableList<String>,
    ) : LocalFunctionProvider() {
        override val providedFunctions: List<FunctionDeclaration> = listOf("success", "throws").map { name ->
            FunctionDeclaration.builder()
                .name(name)
                .parameters(Schema.fromJson("""{"type":"OBJECT"}"""))
                .build()
        }

        override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
            calls += functionName
            return when (functionName) {
                "success" -> buildJsonObject { put("status", "ok") }
                "throws" -> throw IllegalStateException("provider secret should not be exposed")
                else -> error("不支持的测试函数")
            }
        }
    }

    private fun textResponse(
        content: String,
        finishReason: ChatCompletion.Choice.FinishReason = ChatCompletion.Choice.FinishReason.STOP,
    ): ChatCompletion {
        val message = ChatCompletionMessage.builder()
            .content(content)
            .refusal(null)
            .build()
        return completionResponse(message, finishReason)
    }

}
