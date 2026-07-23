package com.unscientificjszhai.tgp.service

import com.google.genai.Chat
import com.google.genai.Chats
import com.google.genai.Client
import com.google.genai.types.Candidate
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.google.genai.types.GenerateContentConfig
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        skillRepository = com.unscientificjszhai.tgp.repository.SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        service =
            GeminiAgentService(testScope, settingsRepository, skillRepository, MCPClientService(testScope)) { mockk() }
    }

    @AfterTest
    fun teardown() {
        tempDirectory.deleteRecursively()
    }

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

    @Test
    fun testSwitchingPrefixedModelRetainsItsName() {
        service.switchModel("models/gemini-3.1-flash-lite")

        assertEquals("models/gemini-3.1-flash-lite", service.currentModel)
    }

    @Test
    fun testSwitchingUnprefixedDynamicallyAvailableModelAddsPrefix() {
        GeminiAgentService::class.java.getDeclaredField("availableModels").apply {
            isAccessible = true
            set(service, listOf("models/custom-model"))
        }

        service.switchModel("custom-model")

        assertEquals("models/custom-model", service.currentModel)
    }

    @Test
    fun testSwitchingUnsupportedModelFails() {
        assertFailsWith<IllegalArgumentException> {
            service.switchModel("unsupported-model")
        }
    }

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

        val closeJob = assertNotNull(service.close())
        assertFalse(closeJob.isCompleted)

        releaseRequest.countDown()
        assertEquals("完成", withTimeout(5_000) { inFlightMessage.await() })
        withTimeout(5_000) { closeJob.join() }
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

    private fun injectClient(chats: Chats) {
        val client = Client.builder().apiKey("test").build()
        Client::class.java.getDeclaredField("chats").apply { isAccessible = true }.set(client, chats)
        GeminiAgentService::class.java.getDeclaredField("client").apply { isAccessible = true }.set(service, client)
    }

}
