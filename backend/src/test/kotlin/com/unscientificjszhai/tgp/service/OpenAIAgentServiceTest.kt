package com.unscientificjszhai.tgp.service

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
import com.unscientificjszhai.tgp.service.ai.agent.MAX_TOOL_CALL_ROUNDS
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
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
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"))
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
        every { mcpClientService.disconnectAll() } returns Job().apply { complete() }
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
     * 验证工具调用达到上限后的会话恢复设计。
     *
     * 验证历史会被重置，后续处理使用新会话继续执行。
     */
    @Test
    fun test工具调用达到上限后重置历史并使用新会话继续处理() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val toolCallResponse = toolCallResponse()
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

        val limitReply = service.sendMessage("持续调用工具")
        val nextReply = service.sendMessage("继续对话")

        assertEquals("Error: 工具调用轮次超过上限（10 轮）。", limitReply)
        assertEquals("新会话完成", nextReply)
        assertEquals(MAX_TOOL_CALL_ROUNDS + 2, requests.size)
        assertEquals(31, requests[MAX_TOOL_CALL_ROUNDS].messages().size)
        assertEquals(1, requests.last().messages().size)
        assertEquals(2, service.createChatCompletionParams(emptyList()).messages().size)
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

    private fun injectClient(client: OpenAIClient) {
        OpenAIAgentService::class.java.getDeclaredField("client").apply { isAccessible = true }.set(service, client)
    }

    private fun newService(): OpenAIAgentService {
        val testScope = CoroutineScope(EmptyCoroutineContext)
        return OpenAIAgentService(
            testScope,
            settingsRepository,
            skillRepository,
            MCPClientService(testScope),
        ) { mockk() }
    }

    private fun setPrivateField(name: String, value: Any?) {
        OpenAIAgentService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

    private fun toolCallResponse(): ChatCompletion {
        val toolCalls = listOf("first", "second").map { name ->
            ChatCompletionMessageToolCall.ofFunction(
                ChatCompletionMessageFunctionToolCall.builder()
                    .id("call-$name")
                    .function(
                        ChatCompletionMessageFunctionToolCall.Function.builder()
                            .name("missing_$name")
                            .arguments("{}")
                            .build(),
                    )
                    .build(),
            )
        }
        val message = ChatCompletionMessage.builder()
            .content(null)
            .refusal(null)
            .toolCalls(toolCalls)
            .build()
        val choice = ChatCompletion.Choice.builder()
            .finishReason(ChatCompletion.Choice.FinishReason.TOOL_CALLS)
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

    private fun textResponse(content: String): ChatCompletion {
        val message = ChatCompletionMessage.builder()
            .content(content)
            .refusal(null)
            .build()
        val choice = ChatCompletion.Choice.builder()
            .finishReason(ChatCompletion.Choice.FinishReason.STOP)
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

}
