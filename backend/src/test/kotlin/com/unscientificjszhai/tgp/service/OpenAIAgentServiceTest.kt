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
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.service.ai.agent.MAX_TOOL_CALL_ROUNDS
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * OpenAI 代理服务的模型刷新、会话生命周期、并发与请求参数测试设计。
 */
class OpenAIAgentServiceTest {

    private lateinit var settingsChangeCoordinator: SettingsChangeCoordinator
    private lateinit var skillRepository: SkillRepository
    private lateinit var service: OpenAIAgentService
    private lateinit var tempDirectory: File
    private lateinit var testJob: Job
    private lateinit var testScope: CoroutineScope
    private val services = mutableListOf<OpenAIAgentService>()

    @BeforeTest
    fun setup() {
        tempDirectory = Files.createTempDirectory("openai-agent-service-test").toFile()
        testJob = SupervisorJob()
        testScope = CoroutineScope(testJob)
        services.clear()
        settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(
            File(tempDirectory, "settings.json"),
            ModelSwitchBarrier(),
        )
        skillRepository = SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        service = OpenAIAgentService(
            testScope,
            settingsChangeCoordinator,
            skillRepository,
            MCPClientService(testScope),
            scheduledTaskService = mockk(),
        ).also { services += it }
    }

    @AfterTest
    fun teardown() {
        runBlocking {
            val closeFailures = services.asReversed().mapNotNull { candidate ->
                runCatching { candidate.close().join() }.exceptionOrNull()
            }
            testJob.cancelAndJoin()
            tempDirectory.deleteRecursively()
            closeFailures.firstOrNull()?.let { throw it }
        }
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
        settingsChangeCoordinator.replaceSettingsForTest(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, selectedModel = "gpt-4o")),
        )

        val restoredService = newService()

        assertEquals("gpt-4o", restoredService.currentModel)
    }


    private fun injectClient(client: OpenAIClient) = injectClient(service, client)

    private fun injectClient(target: OpenAIAgentService, client: OpenAIClient) {
        setPrivateField(target, "client", client)
    }

    private fun newService(mcpClientService: MCPClientService? = null): OpenAIAgentService {
        return OpenAIAgentService(
            testScope,
            settingsChangeCoordinator,
            skillRepository,
            mcpClientService ?: MCPClientService(testScope),
            scheduledTaskService = mockk(),
        ).also { services += it }
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

    /** 构造恰好超过统一限制的深 JSON，让回归覆盖 Jackson 之前的 preflight。 */
    private fun deeplyNestedJson(depth: Int): String = buildString {
        repeat(depth) { append("{\"next\":") }
        append("\"leaf\"")
        repeat(depth) { append('}') }
    }

}
