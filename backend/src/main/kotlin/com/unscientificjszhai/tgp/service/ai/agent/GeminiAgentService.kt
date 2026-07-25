package com.unscientificjszhai.tgp.service.ai.agent

import com.google.genai.Chat
import com.google.genai.Client
import com.google.genai.types.*
import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.ProxySettings
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

/**
 * 基于 Gemini API 维护对话会话并执行模型工具调用的 AI 代理服务。
 *
 * 服务在创建时根据当前设置初始化 Gemini 客户端，并在会话重置时同步 MCP 工具和技能提示词。
 * 调用 [close] 返回的任务完成后，服务持有的客户端与 MCP 连接均已释放。
 *
 * @param parentScope 服务任务所属的父协程作用域。
 * @param settingsRepository 提供 Gemini、MCP 和代理设置的仓库。
 * @param skillRepository 提供会话系统提示词所需技能摘要的仓库。
 * @param mcpClientService 管理会话可调用的 MCP 工具连接。
 * @param taskSchedulerServiceProvider 延迟提供定时任务调度服务，以避免初始化循环依赖。
 */
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
    private var initialModelUpdateJob: Job? = null
    private var configuredApiKey: String? = null
    private var configuredProxy: ProxySettings? = null

    /**
     * 获取当前会话实际使用的 Gemini 模型名称。
     */
    override var currentModel: String = DEFAULT_MODEL
        private set

    /**
     * 获取或更新当前可供选择的 Gemini 模型名称列表。
     *
     * 列表由 [updateModel] 刷新，初始值包含内置回退模型；调用方可赋值空列表或自定义列表，
     * 后续刷新会覆盖该值。
     */
    override var availableModels = listOf(
        DEFAULT_MODEL,
        PREVIOUS_DEFAULT_MODEL,
        LEGACY_MODEL,
    )

    /**
     * 判断给定设置是否启用了 Gemini 代理。
     *
     * @param aiSettings 要检查的 AI 设置。
     * @return 已启用代理且 Gemini API 密钥非空时返回 `true`，否则返回 `false`。
     */
    override fun isAiFeatureEnabled(aiSettings: AISettings) =
        aiSettings.agentEnabled && aiSettings.geminiApiKey.isNotBlank()

    init {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai
        val proxySettings = settings.proxy

        if (aiSettings?.provider == AIProvider.GEMINI) {
            restoreSelectedModel(aiSettings.selectedModel)
        }

        if (aiSettings != null && aiSettings.provider == AIProvider.GEMINI && isAiFeatureEnabled(aiSettings)) {
            try {
                configuredApiKey = aiSettings.geminiApiKey
                configuredProxy = proxySettings
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
                initialModelUpdateJob = scope.launch { updateModel() }
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
     * 模型名称可以省略 `models/` 前缀。实际切换时会保存当前历史记录并异步重建会话。
     *
     * @param modelName 要切换到的模型名称，必须存在于 [availableModels]，或在补上 `models/`
     * 前缀后存在于该列表。
     * @return 已开始切换时返回重置会话的任务；模型未改变或服务已关闭时返回 `null`。
     * @throws IllegalArgumentException 当 [modelName] 不在 [availableModels] 中时抛出。
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

    /**
     * 从 Gemini API 刷新可用模型列表。
     *
     * 若当前模型不再可用，会选择内置回退模型并重置会话；刷新失败不会修改当前模型列表。
     *
     * @return 刷新成功后的模型快照；客户端不可用、服务已关闭或刷新结果过期时返回 `null`。
     */
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
            val refreshResult = synchronized(modelStateLock) {
                if (modelSelectionVersion != selectionVersion) {
                    return@withLock null
                }
                availableModels = refreshedModels
                val invalidModel = currentModel.takeUnless { it in availableModels }
                val fallbackModel = invalidModel?.let { preferredModel(availableModels) }
                if (fallbackModel != null) {
                    currentModel = fallbackModel
                    modelSelectionVersion++
                }
                Triple(ModelSnapshot(currentModel, availableModels), fallbackModel != null, invalidModel)
            }
            if (refreshResult.second) {
                resetSession()?.join()
            }
            refreshResult.third?.let(::clearPersistedSelectedModel)
            refreshResult.first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to update Gemini models", e)
            null
        }
    }

    /**
     * 异步重置当前会话并重新应用系统提示词、技能与 MCP 工具。
     *
     * @return 已开始重置时返回对应任务；服务已关闭或 Gemini 客户端不可用时返回 `null`。
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
     * 此挂起函数会与会话重置串行执行，取消时会取消正在进行的模型调用。
     *
     * @param text 要发送的文本消息；空字符串会作为空文本部分发送。
     * @return Gemini 的回复文本；模型未提供文本时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或无法建立 Gemini 会话时抛出。
     * @throws ToolCallLimitExceededException 当连续工具调用达到上限时抛出。
     */
    override suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * 此挂起函数会与会话重置串行执行，取消时会取消正在进行的模型调用。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空，元素会作为 Gemini 内联数据发送。
     * @return Gemini 的回复文本；模型未提供文本时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或无法建立 Gemini 会话时抛出。
     * @throws ToolCallLimitExceededException 当连续工具调用达到上限时抛出。
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
                    withContext(Dispatchers.IO) {
                        chatForMessage.sendMessage(history + userContent)
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        chatForMessage.sendMessage(listOf(userContent))
                    }
                }

                // 检查模型是否请求调用工具。
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

            // 将工具调用结果回传给模型。
            val content = Content.builder().role("user").parts(functionResponses).build()
            val finalResponse = withContext(Dispatchers.IO) {
                currentChat.sendMessage(content)
            }
            return handleResponse(finalResponse, currentChat, toolCallRounds + 1)
        } else {
            return response.text() ?: ""
        }
    }

    /**
     * 将工具调用结果转换为与原调用一一对应的 Gemini 响应。
     *
     * @param functionCall 模型发出的原始工具调用；其标识和名称会复制到响应中。
     * @param result 工具执行结果的 JSON 对象。
     * @return 可作为 Gemini 函数响应发送的内容片段。
     */
    internal fun createFunctionResponsePart(functionCall: FunctionCall, result: JsonObject): Part {
        val responseBuilder = FunctionResponse.builder().response(result.toMap())
        functionCall.id().getOrNull()?.let { responseBuilder.id(it) }
        functionCall.name().getOrNull()?.let { responseBuilder.name(it) }
        return Part.builder().functionResponse(responseBuilder.build()).build()
    }

    private fun restoreSelectedModel(selectedModel: String) {
        if (selectedModel.isNotBlank()) {
            currentModel = selectedModel
        }
    }

    private fun preferredModel(models: List<String>): String? =
        listOf(DEFAULT_MODEL, PREVIOUS_DEFAULT_MODEL, LEGACY_MODEL).firstOrNull { it in models } ?: models.firstOrNull()

    /**
     * 仅成功且仍为当前实例的客户端可清除已持久化的模型选择，避免旧刷新结果覆盖新设置。
     */
    private fun clearPersistedSelectedModel(invalidModel: String) {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai
        if (
            aiSettings?.provider == AIProvider.GEMINI &&
            aiSettings.geminiApiKey == configuredApiKey &&
            settings.proxy == configuredProxy &&
            aiSettings.selectedModel == invalidModel
        ) {
            settingsRepository.saveSettings(settings.copy(ai = aiSettings.copy(selectedModel = "")))
        }
    }

    /**
     * 关闭 Gemini 客户端、会话任务与 MCP 连接。
     *
     * 重复调用会返回同一个清理任务。
     *
     * @return 异步关闭任务；等待该任务完成后不再保留 Gemini 客户端和 MCP 连接。
     */
    override fun close(): Job = synchronized(lifecycleLock) {
        closeJob ?: run {
            closed = true
            initialModelUpdateJob?.cancel()
            initialModelUpdateJob = null
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
