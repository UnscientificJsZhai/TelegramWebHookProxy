package com.unscientificjszhai.tgp.service

import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.ChatCompletion
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionMessage
import com.openai.models.chat.completions.ChatCompletionMessageFunctionToolCall
import com.openai.models.chat.completions.ChatCompletionMessageToolCall
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
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
            CoroutineScope(kotlin.coroutines.EmptyCoroutineContext),
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
    fun testToolCallLimitStopsBeforeEleventhToolExecution() = runBlocking {
        val client = mockk<OpenAIClient>()
        val chatService = mockk<ChatService>()
        val chatCompletionService = mockk<ChatCompletionService>()
        val requests = mutableListOf<ChatCompletionCreateParams>()
        val response = toolCallResponse()
        every { client.chat() } returns chatService
        every { chatService.completions() } returns chatCompletionService
        every { chatCompletionService.create(any<ChatCompletionCreateParams>()) } answers {
            requests += invocation.args[0] as ChatCompletionCreateParams
            response
        }
        injectClient(client)

        val reply = service.sendMessage("持续调用工具")

        assertEquals("Error: 工具调用轮次超过上限（10 轮）。", reply)
        assertEquals(11, requests.size)
        assertEquals(31, requests.last().messages().size)
        assertEquals(31, service.createChatCompletionParams(emptyList()).messages().size)
    }

    @Test
    fun testGpt56RequestSetsReasoningEffortNone() {
        val params = service.createChatCompletionParams(emptyList())

        assertEquals(ReasoningEffort.NONE, params.reasoningEffort().get())
    }

    @Test
    fun testLegacyModelRequestDoesNotSetReasoningEffort() {
        service.switchModel(ChatModel.GPT_4O.toString())

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

}
