package com.unscientificjszhai.tgp.service

import com.google.genai.Chat
import com.google.genai.Chats
import com.google.genai.Client
import com.google.genai.Models
import com.google.genai.Pager
import com.google.genai.types.*
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import com.unscientificjszhai.tgp.service.ai.agent.MAX_TOOL_CALL_ROUNDS
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
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
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"))
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
        val fallbackModel = Model.builder().name("models/fallback-model").build()
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(geminiApiKey = "test-key", selectedModel = "models/gemini-3.5-flash-lite")),
        )
        every { models.list(any<ListModelsConfig>()) } returns pager
        every { pager.iterator() } returns mutableListOf(fallbackModel).iterator()
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
    fun testSwitchingUnprefixedModelsRetainsSdkPrefix() {
        listOf(
            "gemini-3.5-flash-lite" to "models/gemini-3.5-flash-lite",
            "gemini-3.1-flash-lite" to "models/gemini-3.1-flash-lite",
            "gemini-2.5-flash" to "models/gemini-2.5-flash",
        ).forEach { (modelName, expectedModel) ->
            service.switchModel(modelName)

            assertEquals(expectedModel, service.currentModel)
        }
    }

    /**
     * 验证切换已有前缀模型的设计。
     *
     * 验证模型名称不会被重复添加或修改前缀。
     */
    @Test
    fun testSwitchingPrefixedModelRetainsItsName() {
        service.switchModel("models/gemini-3.1-flash-lite")

        assertEquals("models/gemini-3.1-flash-lite", service.currentModel)
    }

    /**
     * 验证动态可用模型切换时的名称规范化设计。
     *
     * 验证无前缀的动态模型会以 SDK 兼容名称保存。
     */
    @Test
    fun testSwitchingUnprefixedDynamicallyAvailableModelAddsPrefix() {
        GeminiAgentService::class.java.getDeclaredField("availableModels").apply {
            isAccessible = true
            set(service, listOf("models/custom-model"))
        }

        service.switchModel("custom-model")

        assertEquals("models/custom-model", service.currentModel)
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
        val chat = mockk<Chat>()
        val firstCall = FunctionCall.builder().id("call-1").name("missing_one").args(emptyMap()).build()
        val secondCall = FunctionCall.builder().id("call-2").name("missing_two").args(emptyMap()).build()
        val toolCallResponse = responseWithParts(
            Part.builder().functionCall(firstCall).build(),
            Part.builder().functionCall(secondCall).build(),
        )
        val finalResponse = responseWithParts(Part.fromText("完成"))
        val sentFunctionResults = slot<Content>()
        every { chat.sendMessage(any<List<Content>>()) } returns toolCallResponse
        every { chat.sendMessage(capture(sentFunctionResults)) } returns finalResponse
        injectChat(chat)

        assertEquals("完成", service.sendMessage("执行未知工具"))

        val functionResponses = sentFunctionResults.captured.parts().get().map { it.functionResponse().get() }
        assertEquals(listOf("call-1", "call-2"), functionResponses.map { it.id().get() })
        assertEquals(listOf("missing_one", "missing_two"), functionResponses.map { it.name().get() })
        assertEquals("Function missing_one not found", functionResponses[0].response().get()["error"])
        assertEquals("Function missing_two not found", functionResponses[1].response().get()["error"])
    }

    /**
     * 验证工具调用轮次上限的设计。
     *
     * 验证达到最大轮次后会停止调用并报告异常。
     */
    @Test
    fun testToolCallsStopAfterMaximumRounds() = runTest {
        val chat = mockk<Chat>()
        val newChat = mockk<Chat>()
        val chats = mockk<Chats>()
        val functionCall = FunctionCall.builder().name("missing").args(emptyMap()).build()
        val toolCallResponse = responseWithParts(Part.builder().functionCall(functionCall).build())
        settingsRepository.saveSettings(AppSettings(ai = AISettings()))
        every { chat.sendMessage(any<List<Content>>()) } returns toolCallResponse
        every { chat.sendMessage(any<Content>()) } returns toolCallResponse
        every { chats.create(any<String>(), any<GenerateContentConfig>()) } returns newChat
        every { newChat.sendMessage(any<List<Content>>()) } returns responseWithParts(Part.fromText("新会话"))
        injectClient(chats)
        injectChat(chat)

        val exception = assertFailsWith<IllegalStateException> {
            service.sendMessage("持续调用工具")
        }

        assertEquals("工具调用轮次超过上限（$MAX_TOOL_CALL_ROUNDS 轮）。", exception.message)
        verify(exactly = 1) { chat.sendMessage(any<List<Content>>()) }
        verify(exactly = MAX_TOOL_CALL_ROUNDS) { chat.sendMessage(any<Content>()) }
        assertEquals(newChat, GeminiAgentService::class.java.getDeclaredField("chat").apply {
            isAccessible = true
        }.get(service))
        assertEquals("新会话", service.sendMessage("继续对话"))
        verify(exactly = 1) { chats.create(any<String>(), any<GenerateContentConfig>()) }
        verify(exactly = 1) { newChat.sendMessage(any<List<Content>>()) }
    }

    /**
     * 验证关闭服务时等待在途消息的设计。
     *
     * 验证会话资源仅在已开始的消息处理完成后才释放。
     */
    @Test
    fun test关闭会等待在途消息完成后再释放会话() = runBlocking {
        val chat = mockk<Chat>()
        val requestStarted = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        every { chat.sendMessage(any<List<Content>>()) } answers {
            requestStarted.countDown()
            check(releaseRequest.await(5, TimeUnit.SECONDS))
            responseWithParts(Part.fromText("完成"))
        }
        injectChat(chat)

        val inFlightMessage = async(Dispatchers.Default) { service.sendMessage("第一条消息") }
        assertTrue(requestStarted.await(5, TimeUnit.SECONDS))

        val closeJob = service.close()
        assertFalse(closeJob.isCompleted)

        releaseRequest.countDown()
        assertEquals("完成", withTimeout(5.seconds) { inFlightMessage.await() })
        withTimeout(5.seconds) { closeJob.join() }
        assertTrue(closeJob.isCompleted)
    }

    private fun responseWithParts(vararg parts: Part): GenerateContentResponse =
        GenerateContentResponse.builder().candidates(
            Candidate.builder().content(
                Content.builder().role("model").parts(parts.toList()).build(),
            ).build(),
        ).build()

    private fun injectChat(chat: Chat) {
        GeminiAgentService::class.java.getDeclaredField("chat").apply { isAccessible = true }.set(service, chat)
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

    private fun setPrivateField(name: String, value: Any?) {
        GeminiAgentService::class.java.getDeclaredField(name).apply { isAccessible = true }.set(service, value)
    }

}
