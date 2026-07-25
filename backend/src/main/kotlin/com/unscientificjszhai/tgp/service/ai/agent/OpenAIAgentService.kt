package com.unscientificjszhai.tgp.service.ai.agent

import com.openai.client.OpenAIClient
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.models.ChatModel
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.*
import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.*
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.*
import javax.inject.Inject
import javax.inject.Provider
import kotlin.jvm.optionals.getOrNull

/**
 * 基于 OpenAI API 维护对话会话并执行模型工具调用的 AI 代理服务。
 *
 * 服务在创建时根据当前设置初始化 OpenAI 客户端；会话重置会同步 MCP 工具和技能提示词。
 * 调用 [close] 返回的任务完成后，服务持有的客户端与 MCP 连接均已释放。
 *
 * @param parentScope 服务任务所属的父协程作用域。
 * @param settingsRepository 提供 OpenAI、MCP 和代理设置的仓库。
 * @param skillRepository 提供会话系统提示词所需技能摘要的仓库。
 * @param mcpClientService 管理会话可调用的 MCP 工具连接。
 * @param taskSchedulerServiceProvider 延迟提供定时任务调度服务，以避免初始化循环依赖。
 */
@AgentScope
class OpenAIAgentService @Inject constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : AgentService() {
    private companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        val FALLBACK_MODELS = listOf(DEFAULT_MODEL, ChatModel.GPT_4O.toString())
    }

    private val logger = LoggerFactory.getLogger(OpenAIAgentService::class.java)
    private val serviceJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + serviceJob)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleLock = Any()

    @Volatile
    private var closed = false
    private var closeJob: Job? = null

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

    @Volatile
    private var client: OpenAIClient? = null
    private val history = mutableListOf<ChatCompletionMessageParam>()

    /** 串行化完整对话与会话重置，避免历史记录交错。 */
    private val sessionMutex = Mutex()

    /** 串行化 MCP 连接，确保新连接不会与旧连接并行执行。 */
    private val mcpConnectionMutex = Mutex()
    private val mcpConnectionStateLock = Any()
    private var mcpConnectionGeneration = 0L
    private var currentMcpConnectionJob: Job? = null
    private val modelUpdateMutex = Mutex()
    private val modelStateLock = Any()

    /** 等待会话锁的最新模型选择；只有对应版本的任务可以提交它。 */
    private var desiredModel = DEFAULT_MODEL
    private var modelSelectionVersion = 0L
    private var initialModelUpdateJob: Job? = null
    private var configuredApiKey: String? = null
    private var configuredBaseUrl: String? = null
    private var configuredProxy: ProxySettings? = null

    /**
     * 获取当前会话实际使用的 OpenAI 模型名称。
     */
    @Volatile
    override var currentModel: String = DEFAULT_MODEL
        private set

    /**
     * 获取当前可供选择的 OpenAI 模型名称列表。
     *
     * 列表由 [updateModel] 刷新，初始值包含内置回退模型。
     */
    @Volatile
    override var availableModels: List<String> = listOf(
        DEFAULT_MODEL,
        ChatModel.GPT_4O.toString(),
        ChatModel.GPT_4O_MINI.toString()
    )
        private set

    /**
     * 判断给定设置是否启用了 OpenAI 代理。
     *
     * @param aiSettings 要检查的 AI 设置。
     * @return 已启用代理且 OpenAI API 密钥非空时返回 `true`，否则返回 `false`。
     */
    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean =
        aiSettings.agentEnabled && aiSettings.openAiApiKey.isNotBlank()

    init {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai
        val proxySettings = settings.proxy

        if (aiSettings?.provider == AIProvider.OPENAI) {
            restoreSelectedModel(aiSettings.selectedModel)
        }

        if (aiSettings != null && aiSettings.provider == AIProvider.OPENAI && isAiFeatureEnabled(aiSettings)) {
            try {
                configuredApiKey = aiSettings.openAiApiKey
                configuredBaseUrl = aiSettings.openAiBaseUrl
                configuredProxy = proxySettings
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
                initialModelUpdateJob = scope.launch { updateModel() }
                logger.info("OpenAI client initialized.")
            } catch (e: Exception) {
                logger.error("Failed to initialize OpenAI client", e)
            }
        }
    }

    /**
     * 切换当前会话使用的 OpenAI 模型。
     *
     * 模型切换会异步重置会话；只有仍代表最新模型选择的任务可以提交切换结果。
     *
     * @param modelName 要切换到的模型名称，必须存在于 [availableModels]。
     * @return 已开始切换时返回重置会话的任务；模型未改变或服务已关闭时返回 `null`。
     * @throws IllegalArgumentException 当 [modelName] 不在 [availableModels] 中时抛出。
     */
    override fun switchModel(modelName: String): Job? {
        val selectionVersion = synchronized(modelStateLock) {
            if (closed) {
                return null
            }
            if (modelName !in availableModels) {
                throw IllegalArgumentException("Unsupported model: $modelName")
            }
            if (desiredModel == modelName) {
                return null
            }
            desiredModel = modelName
            ++modelSelectionVersion
        }
        return launchSessionJob {
            val canReset = synchronized(modelStateLock) {
                if (!closed && modelSelectionVersion == selectionVersion && currentModel != desiredModel) {
                    currentModel = desiredModel
                    true
                } else {
                    false
                }
            }
            if (canReset) resetSessionLocked() else null
        }
    }

    /**
     * 从 OpenAI API 刷新可用模型列表。
     *
     * 若当前选择的模型不再可用，会选择内置回退模型并重置会话；刷新失败不会修改当前模型列表。
     *
     * @return 刷新成功后的模型快照；客户端不可用、服务已关闭或刷新结果过期时返回 `null`。
     */
    override suspend fun updateModel(): ModelSnapshot? = modelUpdateMutex.withLock {
        try {
            if (closed) {
                return@withLock null
            }
            val selectionVersion = synchronized(modelStateLock) { modelSelectionVersion }
            val currentClient = client ?: return@withLock null
            val models = withContext(Dispatchers.IO) {
                currentClient.models().list().data()
            }
            val refreshedModels = models.map { it.id() }
            val refreshResult = sessionMutex.withLock {
                val (snapshot, fallbackModelChanged, invalidModel) = synchronized(modelStateLock) {
                    if (closed || modelSelectionVersion != selectionVersion) {
                        return@withLock null
                    }
                    availableModels = refreshedModels
                    val invalidDesiredModel = desiredModel.takeUnless { it in availableModels }
                    var modelChanged = false
                    val fallbackModel = invalidDesiredModel?.let { preferredModel(availableModels) }
                    fallbackModel?.let { preferredModel ->
                        desiredModel = preferredModel
                        modelSelectionVersion++
                        if (currentModel !in availableModels) {
                            currentModel = preferredModel
                            modelChanged = true
                        }
                    }
                    Triple(ModelSnapshot(currentModel, availableModels), modelChanged, invalidDesiredModel)
                }
                Triple(snapshot, if (fallbackModelChanged) resetSessionLocked() else null, invalidModel)
            } ?: return@withLock null
            awaitMcpConnectionJob(refreshResult.second)
            refreshResult.third?.let(::clearPersistedSelectedModel)
            refreshResult.first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to update OpenAI models", e)
            null
        }
    }

    /**
     * 异步重置当前会话并重新应用系统提示词、技能与 MCP 工具。
     *
     * @return 已开始重置时返回对应任务；服务已关闭时返回 `null`。
     */
    override fun resetSession(): Job? = if (closed) null else launchSessionJob(::resetSessionLocked)

    /**
     * 在会话锁内完成状态更新，并在任务取消时清理同级的 MCP 连接任务。
     */
    private fun launchSessionJob(action: () -> Job?): Job? {
        if (closed) {
            return null
        }
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var mcpConnectionJob: Job? = null
            try {
                mcpConnectionJob = sessionMutex.withLock {
                    if (closed) null else action()
                }
            } finally {
                awaitMcpConnectionJob(mcpConnectionJob)
            }
        }
    }

    /**
     * 等待 MCP 连接结束；调用方被取消时同时取消并等待该连接任务。
     */
    private suspend fun awaitMcpConnectionJob(mcpConnectionJob: Job?) {
        try {
            mcpConnectionJob?.join()
        } finally {
            withContext(NonCancellable) {
                mcpConnectionJob?.takeIf { !it.isCompleted }?.cancelAndJoin()
            }
        }
    }

    /**
     * 重置当前会话的历史记录。调用方必须已持有 [sessionMutex]。
     */
    private fun resetSessionLocked(): Job? {
        if (closed) {
            return null
        }
        history.clear()
        val aiSettings = settingsRepository.settingsFlow.value.ai
        val mcpConnectionJob = aiSettings?.let { startMcpConnection(it.mcpServers) }
            ?: run {
                cancelCurrentMcpConnection()
                return null
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
        return mcpConnectionJob
    }

    /**
     * 创建最新的 MCP 连接任务。调用方无需等待该任务，任务会自行取消并等待前一连接。
     */
    private fun startMcpConnection(configs: List<MCPServerConfig>): Job {
        var generation = 0L
        var previousConnectionJob: Job? = null
        lateinit var connectionJob: Job
        connectionJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previousConnectionJob?.cancelAndJoin()
                mcpConnectionMutex.withLock {
                    if (closed || !isCurrentMcpConnection(generation)) {
                        return@withLock
                    }
                    currentCoroutineContext().ensureActive()
                    mcpClientService.connect(configs)
                }
            } finally {
                synchronized(mcpConnectionStateLock) {
                    if (mcpConnectionGeneration == generation && currentMcpConnectionJob === connectionJob) {
                        currentMcpConnectionJob = null
                    }
                }
            }
        }
        synchronized(mcpConnectionStateLock) {
            generation = ++mcpConnectionGeneration
            previousConnectionJob = currentMcpConnectionJob
            currentMcpConnectionJob = connectionJob
            previousConnectionJob?.cancel()
        }
        connectionJob.start()
        return connectionJob
    }

    private fun isCurrentMcpConnection(generation: Long): Boolean = synchronized(mcpConnectionStateLock) {
        mcpConnectionGeneration == generation
    }

    private fun cancelCurrentMcpConnection() {
        synchronized(mcpConnectionStateLock) {
            mcpConnectionGeneration++
            currentMcpConnectionJob?.cancel()
            currentMcpConnectionJob = null
        }
    }

    /**
     * 向当前会话发送文本和媒体数据，并返回模型最终回复。
     *
     * 图像会以内联数据发送；仅模型名称包含 `audio` 或以 `o1`、`o3` 开头时会发送音频。
     * 不支持的媒体或空消息会被忽略。
     * 此挂起函数会与会话重置串行执行，取消时会取消正在进行的模型调用。
     *
     * @param text 可选的配文或指令内容；为 `null` 或空白时不创建文本内容片段。
     * @param mediaData 要发送的媒体数据列表；可为空，元素的 MIME 类型决定其处理方式。
     * @return 模型最终回复文本；没有可发送内容或模型未返回文本时返回空字符串。工具调用或其他
     * 可恢复错误会返回以 `Error:` 开头的文本。
     * @throws IllegalStateException 当服务已关闭或 OpenAI 客户端尚未初始化时抛出。
     */
    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String = sessionMutex.withLock {
        check(!closed) { "OpenAI client is closed." }
        val currentClient = client ?: throw IllegalStateException("OpenAI client is not initialized.")

        val contentParts = mutableListOf<ChatCompletionContentPart>()

        if (!text.isNullOrBlank()) {
            contentParts.add(
                ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(text).build()
                )
            )
        }

        for ((data, mimeType) in mediaData) {
            if (mimeType.startsWith("image/")) {
                val base64Data = Base64.getEncoder().encodeToString(data)
                contentParts.add(
                    ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                            .imageUrl(
                                ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url("data:$mimeType;base64,$base64Data")
                                    .build()
                            )
                            .build()
                    )
                )
            } else if (mimeType.startsWith("audio/")) {
                if (currentModel.contains("audio") || currentModel.startsWith("o1") || currentModel.startsWith("o3")) {
                    contentParts.add(
                        ChatCompletionContentPart.ofInputAudio(
                            ChatCompletionContentPartInputAudio.builder()
                                .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                        .data(Base64.getEncoder().encodeToString(data))
                                        .format(
                                            when {
                                                mimeType.contains("wav") -> ChatCompletionContentPartInputAudio.InputAudio.Format.WAV
                                                mimeType.contains("mp3") -> ChatCompletionContentPartInputAudio.InputAudio.Format.MP3
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
            return@withLock ""
        }

        try {
            performChatLocked(currentClient)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ToolCallLimitExceededException) {
            logger.error("Tool call limit reached for OpenAI session", e)
            resetSessionLocked()
            "Error: ${e.message}"
        } catch (e: Exception) {
            logger.error("Error while sending message to OpenAI", e)
            "Error: ${e.message}"
        }
    }

    /**
     * 执行完整的模型与工具调用流程。调用方必须已持有 [sessionMutex]。
     */
    private suspend fun performChatLocked(
        client: OpenAIClient,
        toolCallRounds: Int = 0,
    ): String {
        val tools = localFunctionProviders.flatMap { provider ->
            provider.providedOpenAIFunctions.map { func ->
                ChatCompletionTool.ofFunction(
                    ChatCompletionFunctionTool.builder()
                        .function(func)
                        .build()
                )
            }
        }

        val paramsBuilder = createChatCompletionParams(tools, history.toList())

        // OpenAI 阻塞客户端会同步执行网络请求，避免占用调用方的默认调度器线程。
        val response = withContext(Dispatchers.IO) {
            client.chat().completions().create(paramsBuilder)
        }
        val choice = response.choices().firstOrNull() ?: return ""
        val message = choice.message()

        val toolCalls = message.toolCalls()
        if (toolCalls.isPresent) {
            ensureToolCallRoundIsAllowed(toolCallRounds)
        }

        history.add(ChatCompletionMessageParam.ofAssistant(message.toParam()))

        if (toolCalls.isPresent) {
            val functionToolCalls = toolCalls.get()
            val toolMessages = mutableListOf<ChatCompletionMessageParam>()

            for (toolCall in functionToolCalls) {
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
                        emptyMap()
                    }

                    val provider = localFunctionProviders.find { it.canHandle(name) }
                    val result = provider?.execute(name, argsMap)
                        ?: buildJsonObject {
                            put("error", "Function $name not found")
                        }
                    currentCoroutineContext().ensureActive()

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
            return performChatLocked(client, toolCallRounds + 1)
        }

        return message.content().getOrNull() ?: ""
    }

    /**
     * 根据当前模型构建 Chat Completions 请求参数。
     */
    internal suspend fun createChatCompletionParams(tools: List<ChatCompletionTool>): ChatCompletionCreateParams =
        sessionMutex.withLock {
            createChatCompletionParams(tools, history.toList())
        }

    /**
     * 根据给定的历史快照构建 Chat Completions 请求参数。
     */
    private fun createChatCompletionParams(
        tools: List<ChatCompletionTool>,
        historySnapshot: List<ChatCompletionMessageParam>,
    ): ChatCompletionCreateParams {
        val paramsBuilder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(currentModel))
            .messages(historySnapshot)

        if (tools.isNotEmpty()) {
            paramsBuilder.tools(tools)
        }

        if (currentModel.startsWith("gpt-5.6")) {
            paramsBuilder.reasoningEffort(ReasoningEffort.NONE)
        }

        return paramsBuilder.build()
    }

    /**
     * 返回模型列表刷新后应优先使用的模型。
     */
    internal fun preferredModel(models: List<String>): String? =
        FALLBACK_MODELS.firstOrNull { it in models } ?: models.firstOrNull()

    private fun restoreSelectedModel(selectedModel: String) {
        if (selectedModel.isNotBlank()) {
            desiredModel = selectedModel
            currentModel = selectedModel
        }
    }

    /**
     * 仅成功且仍为当前实例的客户端可清除已持久化的模型选择，避免旧刷新结果覆盖新设置。
     */
    private fun clearPersistedSelectedModel(invalidModel: String) {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai
        if (
            aiSettings?.provider == AIProvider.OPENAI &&
            aiSettings.openAiApiKey == configuredApiKey &&
            aiSettings.openAiBaseUrl == configuredBaseUrl &&
            settings.proxy == configuredProxy &&
            aiSettings.selectedModel == invalidModel
        ) {
            settingsRepository.saveSettings(settings.copy(ai = aiSettings.copy(selectedModel = "")))
        }
    }

    /**
     * 关闭 OpenAI 会话任务与 MCP 连接。
     *
     * 重复调用会返回同一个清理任务。
     *
     * @return 异步关闭任务；等待该任务完成后不再保留会话状态和 MCP 连接。
     */
    override fun close(): Job = synchronized(lifecycleLock) {
        closeJob ?: run {
            closed = true
            initialModelUpdateJob?.cancel()
            initialModelUpdateJob = null
            serviceJob.cancel()
            closingScope.launch {
                sessionMutex.withLock {
                    client = null
                    history.clear()
                    cancelCurrentMcpConnection()
                }
                serviceJob.join()
                val disconnectJob = mcpClientService.disconnectAll()
                disconnectJob.join()
            }.also { closeJob = it }
        }
    }
}
