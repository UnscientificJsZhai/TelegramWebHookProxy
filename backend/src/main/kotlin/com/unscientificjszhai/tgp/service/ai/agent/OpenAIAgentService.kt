package com.unscientificjszhai.tgp.service.ai.agent

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.chat.completions.*
import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.function.HttpCallingFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider.Companion.toMap
import com.unscientificjszhai.tgp.service.ai.function.McpFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.ScheduleTaskFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.SkillFunctionProvider
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Base64
import javax.inject.Inject
import javax.inject.Provider
import kotlin.jvm.optionals.getOrNull

@AgentScope
class OpenAIAgentService @Inject constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : AgentService() {
    private val logger = LoggerFactory.getLogger(OpenAIAgentService::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val localFunctionProviders = listOf(
        HttpCallingFunctionProvider(),
        McpFunctionProvider(mcpClientService),
        ScheduleTaskFunctionProvider(taskSchedulerServiceProvider, settingsRepository),
        SkillFunctionProvider(skillRepository),
    )

    private var client: OpenAIClient? = null
    private var history = mutableListOf<ChatCompletionMessageParam>()

    override var currentModel: String = ChatModel.GPT_4O.toString()
        private set

    override var availableModels: List<String> = listOf(
        ChatModel.GPT_4O.toString(),
        ChatModel.GPT_4O_MINI.toString()
    )
        private set

    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean =
        aiSettings.agentEnabled && aiSettings.openAiApiKey.isNotBlank()

    init {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai
        val proxySettings = settings.proxy

        if (aiSettings != null && aiSettings.provider == AIProvider.OPENAI && isAiFeatureEnabled(aiSettings)) {
            try {
                client = OpenAIOkHttpClient.builder()
                    .apiKey(aiSettings.openAiApiKey)
                    .apply {
                        if (aiSettings.openAiBaseUrl.isNotBlank()) {
                            baseUrl(aiSettings.openAiBaseUrl)
                        }
                        if (proxySettings != null) {
                            val type = when (proxySettings.type) {
                                ProxyType.HTTP -> Proxy.Type.HTTP
                                ProxyType.SOCKS -> Proxy.Type.SOCKS
                            }
                            proxy(Proxy(type, InetSocketAddress(proxySettings.host, proxySettings.port)))
                        }
                    }
                    .build()

                resetSession()
                updateModel()
                logger.info("OpenAI client initialized.")
            } catch (e: Exception) {
                logger.error("Failed to initialize OpenAI client", e)
            }
        }
    }

    override fun switchModel(modelName: String): Job? {
        if (modelName !in availableModels) {
            throw IllegalArgumentException("Unsupported model: $modelName")
        }
        currentModel = modelName
        return resetSession()
    }

    override fun updateModel() {
        scope.launch {
            try {
                val models = client?.models()?.list()?.data() ?: emptyList()
                availableModels = models.map { it.id() }
                if (currentModel !in availableModels && availableModels.isNotEmpty()) {
                    currentModel = if (ChatModel.GPT_4O.toString() in availableModels) {
                        ChatModel.GPT_4O.toString()
                    } else {
                        availableModels.first()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to update OpenAI models", e)
            }
        }
    }

    override fun resetSession(): Job? {
        history.clear()
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return null
        scope.launch {
            mcpClientService.connect(aiSettings.mcpServers)
        }

        val skills = skillRepository.getSkillSummaries()
        val skillPrompt = getSkillPrompt(skills)

        val systemPrompt = (aiSettings.globalContext) + "\n\n" + skillPrompt
        if (systemPrompt.isNotBlank()) {
            history.add(
                ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                        .content(systemPrompt.trim())
                        .build()
                )
            )
        }
        logger.info("OpenAI session reset with model: $currentModel")
        return null
    }

    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
        val currentClient = client ?: throw IllegalStateException("OpenAI client is not initialized.")

        val contentParts = mutableListOf<ChatCompletionContentPart>()

        if (!text.isNullOrBlank()) {
            contentParts.add(
                ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(text).build()
                )
            )
        }

        for (media in mediaData) {
            if (media.mimeType.startsWith("image/")) {
                val base64Data = Base64.getEncoder().encodeToString(media.data)
                contentParts.add(
                    ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                            .imageUrl(
                                ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url("data:${media.mimeType};base64,$base64Data")
                                    .build()
                            )
                            .build()
                    )
                )
            } else if (media.mimeType.startsWith("audio/")) {
                if (currentModel.contains("audio") || currentModel.startsWith("o1") || currentModel.startsWith("o3")) {
                    contentParts.add(
                        ChatCompletionContentPart.ofInputAudio(
                            ChatCompletionContentPartInputAudio.builder()
                                .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                        .data(Base64.getEncoder().encodeToString(media.data))
                                        .format(
                                            when {
                                                media.mimeType.contains("wav") -> ChatCompletionContentPartInputAudio.InputAudio.Format.WAV
                                                media.mimeType.contains("mp3") -> ChatCompletionContentPartInputAudio.InputAudio.Format.MP3
                                                else -> ChatCompletionContentPartInputAudio.InputAudio.Format.WAV
                                            }
                                        )
                                        .build()
                                )
                                .build()
                        )
                    )
                } else {
                    logger.warn("Model $currentModel might not support audio input. Skipping audio part.")
                }
            }
        }

        if (contentParts.isNotEmpty()) {
            history.add(
                ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                        .contentOfArrayOfContentParts(contentParts)
                        .build()
                )
            )
        } else {
            return ""
        }

        return try {
            performChat(currentClient)
        } catch (e: Exception) {
            logger.error("Error while sending message to OpenAI", e)
            "Error: ${e.message}"
        }
    }

    private suspend fun performChat(client: OpenAIClient): String {
        val tools = localFunctionProviders.flatMap { provider ->
            provider.providedOpenAIFunctions.map { func ->
                ChatCompletionTool.ofFunction(
                    ChatCompletionFunctionTool.builder()
                        .function(func)
                        .build()
                )
            }
        }

        val paramsBuilder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(currentModel))
            .messages(history)

        if (tools.isNotEmpty()) {
            paramsBuilder.tools(tools)
        }

        val response = client.chat().completions().create(paramsBuilder.build())
        val choice = response.choices().firstOrNull() ?: return ""
        val message = choice.message()

        history.add(ChatCompletionMessageParam.ofAssistant(message.toParam()))

        if (message.toolCalls().isPresent) {
            val toolCalls = message.toolCalls().get()
            val toolMessages = mutableListOf<ChatCompletionMessageParam>()

            for (toolCall in toolCalls) {
                if (toolCall.isFunction()) {
                    val functionToolCall = toolCall.asFunction()
                    val function = functionToolCall.function()
                    val name = function.name()
                    val arguments = function.arguments()

                    val argsMap = try {
                        val jsonObject = json.parseToJsonElement(arguments) as? JsonObject
                        jsonObject?.toMap() ?: throw IllegalArgumentException(arguments)
                    } catch (e: Exception) {
                        logger.error("Failed to parse function arguments: $arguments", e)
                        emptyMap<String, Any?>()
                    }

                    val provider = localFunctionProviders.find { it.canHandle(name) }
                    val result = provider?.execute(name, argsMap)
                        ?: buildJsonObject {
                            put("error", "Function $name not found")
                        }

                    toolMessages.add(
                        ChatCompletionMessageParam.ofTool(
                            ChatCompletionToolMessageParam.builder()
                                .toolCallId(functionToolCall.id())
                                .content(json.encodeToString(result))
                                .build()
                        )
                    )

                }
            }

            history.addAll(toolMessages)
            return performChat(client)
        }

        return message.content().getOrNull() ?: ""
    }

    override fun close() {
        client = null
        mcpClientService.disconnectAll()
        logger.info("OpenAI client closed.")
    }
}
