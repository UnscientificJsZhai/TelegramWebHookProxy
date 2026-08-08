package com.unscientificjszhai.tgp.service

import com.google.common.collect.ImmutableList
import com.google.genai.*
import com.google.genai.types.*
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.*
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionRouter
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
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


    /**
     * 验证刷新前恢复持久化模型选择的设计。
     *
     * 验证服务创建时会先采用已保存的有效模型。
     */
    @Test
    fun testServiceRestoresPersistedSelectedModelBeforeRefreshing() {
        settingsRepository.replaceSettingsForTest(
            AppSettings(ai = AISettings(selectedModel = "models/gemini-custom")),
        )

        val restoredService = newService()

        assertEquals("models/gemini-custom", restoredService.currentModel)
    }

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
        settingsRepository.replaceSettingsForTest(AppSettings(ai = AISettings()))
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
