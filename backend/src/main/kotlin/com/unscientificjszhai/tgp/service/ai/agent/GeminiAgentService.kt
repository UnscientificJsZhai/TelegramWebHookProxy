package com.unscientificjszhai.tgp.service.ai.agent

import com.google.genai.Chat
import com.google.genai.Client
import com.google.genai.types.*
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.function.HttpCallingFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.McpFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.ScheduleTaskFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.SkillFunctionProvider
import javax.inject.Provider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.plus
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.jvm.optionals.getOrNull
import com.google.genai.types.ProxyType as GeminiProxyType

@Singleton
class GeminiAgentService @Inject constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : AgentService() {
    private val logger = LoggerFactory.getLogger(GeminiAgentService::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])

    private val localFunctionProviders = listOf(
        HttpCallingFunctionProvider(),
        McpFunctionProvider(mcpClientService),
        ScheduleTaskFunctionProvider(taskSchedulerServiceProvider, settingsRepository),
        SkillFunctionProvider(skillRepository),
    )
    private var client: Client? = null
    var chat: Chat? = null

    private var currentApiKey: String? = null
    private var currentProxy: ProxySettings? = null
    private var currentGlobalContext: String? = null
    private var currentMcpServers: List<MCPServerConfig>? = null
    private var currentSkills: List<SkillBrief>? = null
    private var savedHistory: List<Content>? = null

    /**
     * 当前会话使用的模型。
     */
    override var currentModel: String = "models/gemini-3.1-flash-lite-preview"
        private set

    /**
     * 可选的模型列表。
     */
    override var availableModels = listOf(
        "models/gemini-3.1-flash-lite-preview",
        "models/gemini-2.5-flash",
    )

    override fun isAiFeatureEnabled(aiSettings: AISettings) = aiSettings.agentEnabled && aiSettings.geminiApiKey.isNotBlank()

    init {
        combine(
            settingsRepository.settingsFlow.onStart { emit(settingsRepository.settingsFlow.value) },
            skillRepository.skillsUpdateEvent.onStart { emit(Unit) }
        ) { settings, _ -> settings to skillRepository.getSkillSummaries() }.onEach { (settings, skills) ->
            val aiSettings = settings.ai
            val proxySettings = settings.proxy

            if (aiSettings != null && isAiFeatureEnabled(aiSettings)) {
                val needsClientRestart =
                    client == null || currentApiKey != aiSettings.geminiApiKey || currentProxy != proxySettings

                if (needsClientRestart) {
                    captureHistory()
                    client?.close()
                    try {
                        val clientOptionsBuilder = ClientOptions.builder()
                        if (proxySettings != null) {
                            val geminiProxyType = when (proxySettings.type) {
                                ProxyType.HTTP -> GeminiProxyType(GeminiProxyType.Known.HTTP)
                                ProxyType.SOCKS -> GeminiProxyType(GeminiProxyType.Known.SOCKS)
                            }
                            clientOptionsBuilder.proxyOptions(
                                ProxyOptions.builder().type(geminiProxyType).host(proxySettings.host)
                                    .port(proxySettings.port).apply { proxySettings.username?.let { username(it) } }
                                    .apply { proxySettings.password?.let { password(it) } }.build(),
                            )
                        }

                        client = Client.builder().apiKey(aiSettings.geminiApiKey)
                            .clientOptions(clientOptionsBuilder.build()).build()

                        currentApiKey = aiSettings.geminiApiKey
                        currentProxy = proxySettings

                        mcpClientService.connect(aiSettings.mcpServers)
                        currentMcpServers = aiSettings.mcpServers

                        resetSession()
                        currentGlobalContext = aiSettings.globalContext
                        currentSkills = skills
                        logger.info("Gemini client initialized.")
                    } catch (e: Exception) {
                        logger.error("Failed to initialize Gemini client", e)
                        client = null
                        chat = null
                    }
                } else {
                    // Check if only session needs reset
                    val needsSessionReset =
                        currentGlobalContext != aiSettings.globalContext || currentMcpServers != aiSettings.mcpServers || currentSkills != skills || chat == null

                    if (needsSessionReset) {
                        captureHistory()
                        try {
                            if (currentMcpServers != aiSettings.mcpServers) {
                                mcpClientService.connect(aiSettings.mcpServers)
                                currentMcpServers = aiSettings.mcpServers
                            }
                            resetSession()
                            currentGlobalContext = aiSettings.globalContext
                            currentSkills = skills
                        } catch (e: Exception) {
                            logger.error("Failed to reset session", e)
                        }
                    }
                }
            } else {
                if (client != null) {
                    captureHistory()
                    client?.close()
                    client = null
                    chat = null
                    currentApiKey = null
                    currentProxy = null
                    currentGlobalContext = null
                    currentMcpServers = null
                    mcpClientService.disconnectAll()
                    logger.info("Gemini client closed.")
                }
                if (aiSettings?.agentEnabled == false) {
                    savedHistory = null
                }
            }
        }.launchIn(scope)
    }

    /**
     * 保存当前会话的历史记录。
     */
    private fun captureHistory() {
        try {
            chat?.getHistory(true)?.let { history ->
                if (history.isNotEmpty()) {
                    savedHistory = history
                    logger.debug("Captured history: ${history.size} items.")
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to capture history", e)
        }
    }

    /**
     * 切换当前会话使用的模型。
     *
     * @param modelName 模型名称。
     */
    override fun switchModel(modelName: String) {
        if (modelName !in availableModels && modelName != "gemini-2.5-flash") {
            throw IllegalArgumentException("Unsupported model: $modelName")
        }
        if (currentModel != modelName) {
            captureHistory()
            currentModel = modelName
            resetSession()
        }
    }

    override fun updateModel() {
        client?.models?.let { models ->
            this@GeminiAgentService.availableModels =
                models.list(ListModelsConfig.builder().build()).mapNotNull { it.name().getOrNull() }
        }
    }

    /**
     * 重置当前会话，清空历史记录并重新应用系统提示词。
     */
    override fun resetSession() {
        val currentClient = client
        if (currentClient == null) {
            logger.warn("Cannot reset session: Gemini client is not initialized.")
            return
        }

        val aiSettings = settingsRepository.settingsFlow.value.ai

        val configBuilder = GenerateContentConfig.builder()
        val skills = skillRepository.getSkillSummaries()
        val skillPrompt = if (skills.isNotEmpty()) {
            "Before doing anything, first try calling the read_skill tool to confirm the correct process. Available Skills:\n" + skills.joinToString("\n") { "- ID: ${it.id}, Description: ${it.description}" } + "\n\n"
        } else {
            ""
        }

        val systemInstruction = if(aiSettings != null && aiSettings.globalContext.isNotBlank()) {
            Content.fromParts(Part.fromText(skillPrompt + aiSettings.globalContext))
        } else {
            Content.fromParts(Part.fromText(skillPrompt))
        }
        configBuilder.systemInstruction(systemInstruction)

        val functionDeclarations = mutableListOf<FunctionDeclaration>()

        // Add Local Functions
        localFunctionProviders.forEach { provider ->
            functionDeclarations.addAll(provider.providedFunctions)
        }

        if (functionDeclarations.isNotEmpty()) {
            configBuilder.tools(listOf(Tool.builder().functionDeclarations(functionDeclarations).build()))
        }

        try {
            chat = currentClient.chats.create(currentModel, configBuilder.build())
            logger.info("Gemini session reset with model: $currentModel")
        } catch (e: Exception) {
            logger.error("Failed to create Gemini chat session", e)
            chat = null
        }
    }

    /**
     * 发送文本消息并获取回复。
     *
     * @param text 消息内容。
     * @return Gemini 的回复文本。
     */
    override suspend fun sendMessage(text: String): String = sendMessage(text, emptyList<MediaData>())

    /**
     * 发送包含语音数据的消息并获取回复。
     *
     * @param text 配文或指令内容（可选）。
     * @param mediaData 包含媒体数据的列表。
     * @return Gemini 的回复文本。
     */
    override suspend fun sendMessage(
        text: String?,
        mediaData: List<MediaData>,
    ): String {
        val audioParts = mediaData.map {
            Part.builder().inlineData(
                Blob.builder().mimeType(it.mimeType).data(it.data).build()
            ).build()
        }
        return sendMessageWithParts(text, audioParts)
    }

    /**
     * 发送包含 Gemini Part 的消息。
     */
    private suspend fun sendMessageWithParts(
        text: String?,
        audioParts: List<Part>,
    ): String {
        val currentChat = chat ?: throw IllegalStateException("Gemini chat session is not initialized.")
        try {
            val parts = mutableListOf<Part>()
            text?.let { parts.add(Part.fromText(it)) }
            parts.addAll(audioParts)

            val userContent = Content.builder().role("user").parts(parts).build()

            val history = savedHistory
            val response = if (history != null && currentChat.getHistory(false).isEmpty()) {
                savedHistory = null
                currentChat.sendMessage(history + userContent)
            } else {
                currentChat.sendMessage(listOf(userContent))
            }

            // Check for tool calls
            return handleResponse(response, currentChat)
        } catch (e: Exception) {
            logger.error("Error while sending message to Gemini", e)
            throw e
        }
    }

    private suspend fun handleResponse(
        response: GenerateContentResponse,
        currentChat: Chat,
    ): String {
        val functionCalls = response.functionCalls()
        if (!functionCalls.isNullOrEmpty()) {
            val functionResponses = mutableListOf<Part>()

            for (functionCall in functionCalls) {
                val fullNameOpt = functionCall.name()
                if (!fullNameOpt.isPresent) continue
                val fullName = fullNameOpt.get()

                val argsMapOpt = functionCall.args()
                val argsMap = if (argsMapOpt.isPresent) argsMapOpt.get() else emptyMap()

                val localProvider = localFunctionProviders.find { it.canHandle(fullName) }
                if (localProvider != null) {
                    val result = localProvider.execute(fullName, argsMap)
                    functionResponses.add(Part.fromFunctionResponse(fullName, result))
                    continue
                }
            }

            // Send function results back to the model
            val content = Content.builder().role("user").parts(functionResponses).build()
            val finalResponse = currentChat.sendMessage(content)
            return handleResponse(finalResponse, currentChat)
        } else {
            return response.text() ?: ""
        }
    }
}
