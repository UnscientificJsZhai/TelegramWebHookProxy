package com.unscientificjszhai.tgp.service

import com.google.genai.Chat
import com.google.genai.Client
import com.google.genai.types.*
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.jvm.optionals.getOrNull
import com.google.genai.types.ProxyType as GeminiProxyType

/**
 * Gemini Agent 服务，负责与 Google Gemini API 交互。
 */
class GeminiAgentService(
    private val settingsRepository: SettingsRepository, private val mcpClientService: MCPClientService
) {
    private val logger = LoggerFactory.getLogger(GeminiAgentService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var client: Client? = null
    internal var chat: Chat? = null

    private var currentApiKey: String? = null
    private var currentProxy: ProxySettings? = null
    private var currentGlobalContext: String? = null
    private var currentMcpServers: List<MCPServerConfig>? = null
    private var savedHistory: List<Content>? = null

    /**
     * 当前会话使用的模型。
     */
    var currentModel: String = "models/gemini-3.1-flash-lite-preview"
        private set

    /**
     * 可选的模型列表。
     */
    var availableModels = listOf(
        "models/gemini-3.1-flash-lite-preview", "models/gemini-2.5-flash"
    )

    fun isAiFeatureEnabled(aiSettings: AISettings) = aiSettings.agentEnabled && aiSettings.geminiApiKey.isNotBlank()

    init {
        settingsRepository.settingsFlow.onStart { emit(settingsRepository.settingsFlow.value) }.onEach { settings ->
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
                                ProxyOptions.builder().type(geminiProxyType)
                                    .host(proxySettings.host)
                                    .port(proxySettings.port)
                                    .apply { proxySettings.username?.let { username(it) } }
                                    .apply { proxySettings.password?.let { password(it) } }
                                    .build()
                            )
                        }

                        client =
                            Client.builder().apiKey(aiSettings.geminiApiKey).clientOptions(clientOptionsBuilder.build())
                                .build()

                        currentApiKey = aiSettings.geminiApiKey
                        currentProxy = proxySettings

                        mcpClientService.connect(aiSettings.mcpServers)
                        currentMcpServers = aiSettings.mcpServers

                        resetSession()
                        currentGlobalContext = aiSettings.globalContext
                        logger.info("Gemini client initialized.")
                    } catch (e: Exception) {
                        logger.error("Failed to initialize Gemini client", e)
                        client = null
                        chat = null
                    }
                } else {
                    // Check if only session needs reset
                    val needsSessionReset =
                        currentGlobalContext != aiSettings.globalContext || currentMcpServers != aiSettings.mcpServers || chat == null

                    if (needsSessionReset) {
                        captureHistory()
                        try {
                            if (currentMcpServers != aiSettings.mcpServers) {
                                mcpClientService.connect(aiSettings.mcpServers)
                                currentMcpServers = aiSettings.mcpServers
                            }
                            resetSession()
                            currentGlobalContext = aiSettings.globalContext
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
    fun switchModel(modelName: String) {
        if (modelName !in availableModels && modelName != "gemini-2.5-flash") {
            throw IllegalArgumentException("Unsupported model: $modelName")
        }
        if (currentModel != modelName) {
            captureHistory()
            currentModel = modelName
            resetSession()
        }
    }

    fun updateModel() {
        client?.models?.let { models ->
            this@GeminiAgentService.availableModels =
                models.list(ListModelsConfig.builder().build()).mapNotNull { it.name().getOrNull() }
        }
    }

    /**
     * 重置当前会话，清空历史记录并重新应用系统提示词。
     */
    fun resetSession() {
        val currentClient = client
        if (currentClient == null) {
            logger.warn("Cannot reset session: Gemini client is not initialized.")
            return
        }

        val aiSettings = settingsRepository.settingsFlow.value.ai

        val configBuilder = GenerateContentConfig.builder()
        if (aiSettings != null && aiSettings.globalContext.isNotBlank()) {
            val systemInstruction = Content.fromParts(Part.fromText(aiSettings.globalContext))
            configBuilder.systemInstruction(systemInstruction)
        }

        // Add MCP Tools
        val mcpTools = mcpClientService.getAllTools()
        if (mcpTools.isNotEmpty()) {
            val functionDeclarations = mcpTools.map { (serverName, mcpTool) ->
                val schemaJson = buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", (mcpTool.inputSchema?.properties ?: JsonObject(emptyMap())).toGeminiSchemaJson())
                    val required = mcpTool.inputSchema?.required ?: emptyList()
                    if (required.isNotEmpty()) {
                        put("required", buildJsonArray {
                            required.forEach { add(it) }
                        })
                    }
                }.toString()

                val schema = Schema.fromJson(schemaJson) ?: Schema.builder().type(Type(Type.Known.OBJECT)).build()

                FunctionDeclaration.builder().name("${serverName}_${mcpTool.name}")
                    .description(mcpTool.description ?: "").parameters(schema).build()
            }
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

    private fun JsonElement.toGeminiSchemaJson(): JsonElement = when (this) {
        is JsonObject -> buildJsonObject {
            forEach { (key, value) ->
                if (key == "type" && value is JsonPrimitive && value.isString) {
                    put(key, value.content.uppercase())
                } else {
                    put(key, value.toGeminiSchemaJson())
                }
            }
        }

        is JsonArray -> buildJsonArray {
            forEach { add(it.toGeminiSchemaJson()) }
        }

        else -> this
    }

    /**
     * 发送消息并获取回复。
     *
     * @param text 消息内容。
     * @return Gemini 的回复文本。
     */
    suspend fun sendMessage(text: String): String {
        val currentChat = chat ?: throw IllegalStateException("Gemini chat session is not initialized.")
        try {
            val history = savedHistory
            val response = if (history != null && currentChat.getHistory(false).isEmpty()) {
                savedHistory = null
                val userContent = Content.builder().role("user").parts(listOf(Part.fromText(text))).build()
                currentChat.sendMessage(history + userContent)
            } else {
                currentChat.sendMessage(text)
            }

            // Check for tool calls
            return handleResponse(response, currentChat)
        } catch (e: Exception) {
            logger.error("Error while sending message to Gemini", e)
            throw e
        }
    }

    private suspend fun handleResponse(response: GenerateContentResponse, currentChat: Chat): String {
        val functionCalls = response.functionCalls()
        if (!functionCalls.isNullOrEmpty()) {
            val functionResponses = mutableListOf<Part>()

            for (functionCall in functionCalls) {
                val fullNameOpt = functionCall.name()
                if (!fullNameOpt.isPresent) continue
                val fullName = fullNameOpt.get()
                val serverName = fullName.substringBefore('_')
                val toolName = fullName.substringAfter('_')

                val argsMapOpt = functionCall.args()
                val argsMap = if (argsMapOpt.isPresent) argsMapOpt.get() else emptyMap()

                try {
                    val result = mcpClientService.callTool(serverName, toolName, argsMap)
                    functionResponses.add(
                        Part.fromFunctionResponse(
                            fullName, mapOf("result" to result)
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Error executing MCP tool $fullName", e)
                    functionResponses.add(
                        Part.fromFunctionResponse(
                            fullName, mapOf("error" to (e.message ?: "Unknown error"))
                        )
                    )
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
