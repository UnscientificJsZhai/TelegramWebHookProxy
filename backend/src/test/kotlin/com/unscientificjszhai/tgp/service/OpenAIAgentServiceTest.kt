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

    @Test
    fun testPreferredModelFallbackOrder() {
        assertEquals("gpt-5.6-luna", service.preferredModel(listOf("gpt-5.6-luna", "gpt-4o")))
        assertEquals("gpt-4o", service.preferredModel(listOf("third-party", "gpt-4o")))
        assertEquals("third-party", service.preferredModel(listOf("third-party", "gpt-4o-mini")))
        assertEquals(null, service.preferredModel(emptyList()))
    }

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

        val closeJob = assertNotNull(resettableService.close())
        assertFalse(closeJob.isCompleted)

        releaseFirstRequest.countDown()
        assertEquals("在途回复", withTimeout(5.seconds) { firstMessage.await() })
        withTimeout(5.seconds) { closeJob.join() }

        assertTrue(resetJob.isCancelled)
        assertTrue(switchJob.isCancelled)
        coVerify(exactly = 0) { mcpClientService.connect(mcpServers) }
    }

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

    @Test
    fun testGpt56RequestSetsReasoningEffortNone() = runBlocking {
        val params = service.createChatCompletionParams(emptyList())

        assertEquals(ReasoningEffort.NONE, params.reasoningEffort().get())
    }

    @Test
    fun testLegacyModelRequestDoesNotSetReasoningEffort() = runBlocking {
        service.switchModel(ChatModel.GPT_4O.toString())?.join()

        val params = service.createChatCompletionParams(emptyList())

        assertFalse(params.reasoningEffort().isPresent)
    }

    private fun injectClient(client: OpenAIClient) {
        OpenAIAgentService::class.java.getDeclaredField("client").apply { isAccessible = true }.set(service, client)
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
