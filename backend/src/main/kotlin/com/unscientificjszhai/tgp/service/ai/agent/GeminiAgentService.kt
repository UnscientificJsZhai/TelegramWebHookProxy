package com.unscientificjszhai.tgp.service.ai.agent

import com.google.genai.Chat
import com.google.genai.Client
import com.google.genai.types.*
import com.unscientificjszhai.tgp.di.AgentScope
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Provider
import kotlin.jvm.optionals.getOrNull
import com.google.genai.types.ProxyType as GeminiProxyType

@AgentScope
class GeminiAgentService @Inject constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : AgentService() {
    private companion object {
        const val DEFAULT_MODEL = "models/gemini-3.5-flash-lite"
        const val PREVIOUS_DEFAULT_MODEL = "models/gemini-3.1-flash-lite"
        const val LEGACY_MODEL = "models/gemini-2.5-flash"
    }

    private val logger = LoggerFactory.getLogger(GeminiAgentService::class.java)
    private val serviceJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + serviceJob)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleLock = Any()
    @Volatile
    private var closed = false
    private var closeJob: Job? = null

    private val localFunctionProviders = listOf(
        HttpCallingFunctionProvider(),
        McpFunctionProvider(mcpClientService),
        ScheduleTaskFunctionProvider(taskSchedulerServiceProvider, settingsRepository),
        SkillFunctionProvider(skillRepository),
    )
    /** 串行化对话、重置和关闭，防止 Chat 状态在服务关闭后被旧任务改写。 */
    private val sessionMutex = Mutex()
    @Volatile
    private var client: Client? = null
    private var chat: Chat? = null

    private var savedHistory: List<Content>? = null

    @Volatile
    private var resetSessionJob: Job? = null

    private val modelUpdateMutex = Mutex()
    private val modelStateLock = Any()
    private var modelSelectionVersion = 0L

    /**
     * 当前会话使用的模型。
     */
    override var currentModel: String = DEFAULT_MODEL
        private set

    /**
     * 可选的模型列表。
     */
    override var availableModels = listOf(
        DEFAULT_MODEL,
        PREVIOUS_DEFAULT_MODEL,
        LEGACY_MODEL,
    )

    override fun isAiFeatureEnabled(aiSettings: AISettings) =
        aiSettings.agentEnabled && aiSettings.geminiApiKey.isNotBlank()

    init {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai
        val proxySettings = settings.proxy

        if (aiSettings != null && isAiFeatureEnabled(aiSettings)) {
            try {
                val clientOptionsBuilder = ClientOptions.builder()
                if (proxySettings != null) {
                    val geminiProxyType = when (proxySettings.type) {
                        ProxyType.HTTP -> GeminiProxyType(GeminiProxyType.Known.HTTP)
                        ProxyType.SOCKS -> GeminiProxyType(GeminiProxyType.Known.SOCKS)
                    }
                    clientOptionsBuilder.proxyOptions(
                        ProxyOptions.builder().type(geminiProxyType).host(proxySettings.host).port(proxySettings.port)
                            .apply {
                                proxySettings.username?.let { username(it) }
                                proxySettings.password?.let { password(it) }
                            }.build(),
                    )
                }

                client =
                    Client.builder().apiKey(aiSettings.geminiApiKey).clientOptions(clientOptionsBuilder.build()).build()

                this.resetSessionJob = resetSession()
                logger.info("Gemini client initialized.")
            } catch (e: Exception) {
                logger.error("Failed to initialize Gemini client", e)
                client = null
                chat = null
            }
        }
    }

    /**
     * 保存当前会话的历史记录。
     */
    private fun captureHistoryLocked() {
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
    override fun switchModel(modelName: String): Job? {
        val modelChanged = synchronized(modelStateLock) {
            if (closed) {
                return null
            }
            val normalizedModel = when {
                modelName in availableModels -> modelName
                "models/$modelName" in availableModels -> "models/$modelName"
                else -> modelName
            }
            if (normalizedModel !in availableModels) {
                throw IllegalArgumentException("Unsupported model: $modelName")
            }
            if (currentModel == normalizedModel) {
                false
            } else {
                currentModel = normalizedModel
                modelSelectionVersion++
                true
            }
        }
        if (modelChanged) {
            return resetSession(captureHistory = true)
        }
        return null
    }

    override suspend fun updateModel(): ModelSnapshot? = modelUpdateMutex.withLock {
        try {
            if (closed) {
                return@withLock null
            }
            val selectionVersion = synchronized(modelStateLock) { modelSelectionVersion }
            val models = client?.models ?: return@withLock null
            val refreshedModels = withContext(Dispatchers.IO) {
                models.list(ListModelsConfig.builder().build()).mapNotNull { it.name().getOrNull() }
            }
            synchronized(modelStateLock) {
                if (modelSelectionVersion != selectionVersion) {
                    return@withLock null
                }
                availableModels = refreshedModels
                ModelSnapshot(currentModel, availableModels)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to update Gemini models", e)
            null
        }
    }

    /**
     * 重置当前会话，清空历史记录并重新应用系统提示词。
     */
    override fun resetSession(): Job? = resetSession(captureHistory = false)

    private fun resetSession(captureHistory: Boolean): Job? {
        if (closed) {
            return null
        }
        val currentClient = client
        if (currentClient == null) {
            logger.warn("Cannot reset session: Gemini client is not initialized.")
            return null
        }

        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return null
        val functionDeclarations = mutableListOf<FunctionDeclaration>()
        return scope.launch {
            sessionMutex.withLock {
                if (closed) {
                    return@withLock
                }
                if (captureHistory) {
                    captureHistoryLocked()
                }
                mcpClientService.connect(aiSettings.mcpServers)
                currentCoroutineContext().ensureActive()
                if (closed) {
                    return@withLock
                }
                localFunctionProviders.forEach { provider ->
                    functionDeclarations.addAll(provider.providedFunctions)
                }
                val configBuilder = GenerateContentConfig.builder()
                val skills = skillRepository.getSkillSummaries()
                val skillPrompt = getSkillPrompt(skills)

                val systemInstruction = if (aiSettings.globalContext.isNotBlank()) {
                    Content.fromParts(Part.fromText(skillPrompt + aiSettings.globalContext))
                } else {
                    Content.fromParts(Part.fromText(skillPrompt))
                }
                configBuilder.systemInstruction(systemInstruction)

                if (functionDeclarations.isNotEmpty()) {
                    configBuilder.tools(listOf(Tool.builder().functionDeclarations(functionDeclarations).build()))
                }

                try {
                    val newChat = currentClient.chats.create(currentModel, configBuilder.build())
                    synchronized(lifecycleLock) {
                        if (!closed) {
                            chat = newChat
                            logger.info("Gemini session reset with model: $currentModel")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to create Gemini chat session", e)
                    synchronized(lifecycleLock) {
                        if (!closed) {
                            chat = null
                        }
                    }
                }
            }
        }
    }

    /**
     * 发送文本消息并获取回复。
     *
     * @param text 消息内容。
     * @return Gemini 的回复文本。
     */
    override suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

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
        val initialChat = sessionMutex.withLock {
            check(!closed) { "Gemini client is closed." }
            chat
        }
        val currentChat = initialChat ?: resetSessionJob?.run {
            join()
            sessionMutex.withLock {
                check(!closed) { "Gemini client is closed." }
                chat
            }
        } ?: throw IllegalStateException("Gemini chat session is not initialized.")
        return sessionMutex.withLock {
            check(!closed) { "Gemini client is closed." }
            try {
                val chatForMessage = chat ?: currentChat
                val parts = mutableListOf<Part>()
                text?.let { parts.add(Part.fromText(it)) }
                parts.addAll(audioParts)

                val userContent = Content.builder().role("user").parts(parts).build()

                val history = savedHistory
                val response = if (history != null && chatForMessage.getHistory(false).isEmpty()) {
                    savedHistory = null
                    chatForMessage.sendMessage(history + userContent)
                } else {
                    chatForMessage.sendMessage(listOf(userContent))
                }

                // Check for tool calls
                handleResponse(response, chatForMessage)
            } catch (e: ToolCallLimitExceededException) {
                logger.error("Tool call limit reached for Gemini session", e)
                savedHistory = null
                chat = null
                resetSessionJob = resetSession()
                throw e
            } catch (e: Exception) {
                logger.error("Error while sending message to Gemini", e)
                throw e
            }
        }
    }

    private suspend fun handleResponse(
        response: GenerateContentResponse,
        currentChat: Chat,
        toolCallRounds: Int = 0,
    ): String {
        val functionCalls = response.functionCalls()
        if (!functionCalls.isNullOrEmpty()) {
            ensureToolCallRoundIsAllowed(toolCallRounds)
            val functionResponses = mutableListOf<Part>()

            for (functionCall in functionCalls) {
                val fullName = functionCall.name().getOrNull()
                val argsMap = functionCall.args().getOrNull() ?: emptyMap()
                val result = try {
                    val localProvider = fullName?.let { name -> localFunctionProviders.find { it.canHandle(name) } }
                    when {
                        fullName == null -> buildJsonObject {
                            put("error", "Function call name is missing")
                        }

                        localProvider == null -> buildJsonObject {
                            put("error", "Function $fullName not found")
                        }

                        else -> localProvider.execute(fullName, argsMap)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Failed to execute Gemini function call: $fullName", e)
                    buildJsonObject {
                        put("error", e.message ?: "Function $fullName failed")
                    }
                }
                functionResponses.add(createFunctionResponsePart(functionCall, result))
            }

            // Send function results back to the model
            val content = Content.builder().role("user").parts(functionResponses).build()
            val finalResponse = currentChat.sendMessage(content)
            return handleResponse(finalResponse, currentChat, toolCallRounds + 1)
        } else {
            return response.text() ?: ""
        }
    }

    /**
     * 将工具调用结果转换为与原调用一一对应的 Gemini 响应。
     */
    internal fun createFunctionResponsePart(functionCall: FunctionCall, result: JsonObject): Part {
        val responseBuilder = FunctionResponse.builder().response(result.toMap())
        functionCall.id().getOrNull()?.let { responseBuilder.id(it) }
        functionCall.name().getOrNull()?.let { responseBuilder.name(it) }
        return Part.builder().functionResponse(responseBuilder.build()).build()
    }

    override fun close(): Job? = synchronized(lifecycleLock) {
        closeJob ?: run {
            closed = true
            resetSessionJob?.cancel()
            serviceJob.cancel()
            closingScope.launch {
                val currentClient = sessionMutex.withLock {
                    val clientToClose = client
                    client = null
                    chat = null
                    savedHistory = null
                    clientToClose
                }
                serviceJob.join()
                currentClient?.close()
                val disconnectJob = mcpClientService.disconnectAll()
                disconnectJob.join()
            }.also { closeJob = it }
        }
    }
}
