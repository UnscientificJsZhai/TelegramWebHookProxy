package com.unscientificjszhai.tgp.service

import com.google.genai.Chat
import com.google.genai.types.Candidate
import com.google.genai.types.Content
import com.google.genai.types.FunctionCall
import com.google.genai.types.GenerateContentResponse
import com.google.genai.types.Part
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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
    fun testSwitchingUnprefixedDefaultModelRetainsSdkPrefix() {
        service.switchModel("models/gemini-3.1-flash-lite")

        service.switchModel("gemini-3.5-flash-lite")

        assertEquals("models/gemini-3.5-flash-lite", service.currentModel)
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

    private fun responseWithParts(vararg parts: Part): GenerateContentResponse =
        GenerateContentResponse.builder().candidates(
            Candidate.builder().content(
                Content.builder().role("model").parts(parts.toList()).build(),
            ).build(),
        ).build()

    private fun injectChat(chat: Chat) {
        GeminiAgentService::class.java.getDeclaredField("chat").apply { isAccessible = true }.set(service, chat)
    }

}
