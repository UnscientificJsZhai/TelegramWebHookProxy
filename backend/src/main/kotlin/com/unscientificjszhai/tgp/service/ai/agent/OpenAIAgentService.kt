package com.unscientificjszhai.tgp.service.ai.agent

import com.openai.client.OpenAIClient
import com.openai.core.jsonMapper
import com.openai.models.ChatModel
import com.openai.models.ReasoningEffort
import com.openai.models.chat.completions.*
import com.openai.models.models.Model
import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MAX_MCP_TOOL_ARGUMENT_BYTES
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.function.*
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider.Companion.toMap
import com.unscientificjszhai.tgp.service.configureHttpProxyBasicAuthentication
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import javax.inject.Inject
import javax.inject.Provider
import kotlin.jvm.optionals.getOrNull

/**
 * 基于 OpenAI API 维护对话会话并执行模型工具调用的 AI 代理服务。
 *
 * 服务在创建时根据当前设置初始化 OpenAI 兼容协议的原生可取消 HTTP 传输；会话重置会同步 MCP 工具和技能提示词。
 * 调用 [close] 返回的任务完成后，服务持有的 HTTP 传输、HTTP 工具客户端与 MCP 连接均已释放。
 *
 * @param parentScope 服务任务所属的父协程作用域。
 * @param settingsRepository 提供 OpenAI、MCP 和代理设置的仓库。
 * @param skillRepository 提供会话系统提示词所需技能摘要的仓库。
 * @param mcpClientService 管理会话可调用的 MCP 工具连接。
 * @param taskSchedulerServiceProvider 延迟提供定时任务调度服务，以避免初始化循环依赖。
 * @param deadlines 限制候选初始化与其 MCP 批次的总体执行时间。
 */
@AgentScope
class OpenAIAgentService @Inject internal constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    private val deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : AgentService() {
    private companion object {
        const val DEFAULT_MODEL = "gpt-5.6-luna"
        const val TRANSCRIPTION_MODEL = "gpt-4o-mini-transcribe"
        const val TELEGRAM_OGG_MIME_TYPE = "audio/ogg"
        val FALLBACK_MODELS = listOf(DEFAULT_MODEL, ChatModel.GPT_4O.toString())
    }

    private val logger = LoggerFactory.getLogger(OpenAIAgentService::class.java)
    private val serviceJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + serviceJob)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycleLock = Any()
    private val initializationCleanupLock = Any()
    private val initializationCleanupJobs = mutableSetOf<Job>()

    @Volatile
    private var closed = false
    private var closeJob: Job? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val httpCallingFunctionProvider = HttpCallingFunctionProvider(settingsRepository)
    private var localFunctionProviders = listOf(
        httpCallingFunctionProvider,
        McpFunctionProvider(mcpClientService),
        ScheduleTaskFunctionProvider(taskSchedulerServiceProvider, settingsRepository),
        SkillFunctionProvider(skillRepository),
    )
    private var localFunctionRouter = LocalFunctionRouter(localFunctionProviders)

    @Volatile
    private var client: OpenAIClient? = null

    /** 生产 API 请求使用的原生可取消传输；SDK 客户端仅保留给未配置传输的旧会话兼容路径。 */
    @Volatile
    private var rawTransport: CancellableOkHttpTransport? = null
    private var rawBaseUrl: String? = null
    private var rawApiKey: String? = null
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

    @Volatile
    private var initialMcpConnectionJob: Job? = null
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
                rawApiKey = aiSettings.openAiApiKey
                rawBaseUrl = openAiBaseUrlForRequests(aiSettings.openAiBaseUrl)
                rawTransport = CancellableOkHttpTransport(createOpenAIHttpClient(proxySettings))

                val initialResetJob = resetSession() ?: failedInitializationJob()
                initialModelUpdateJob = createInitialModelUpdateJob(initialResetJob)
                initialMcpConnectionJob =
                    createInitialReadinessJob(initialResetJob, checkNotNull(initialModelUpdateJob))
                logger.info("OpenAI client initialized.")
            } catch (e: Exception) {
                logger.error("Failed to initialize OpenAI client; category={}", SafeLogging.failureCategory(e).wireName)
                client = null
                rawTransport?.close()
                rawTransport = null
                initialMcpConnectionJob = failedInitializationJob()
            }
        }
    }

    /** 创建 OpenAI 兼容服务的原生客户端，并支持 HTTP Basic 代理认证与 SOCKS 代理路由。 */
    private fun createOpenAIHttpClient(proxySettings: ProxySettings?): OkHttpClient {
        val builder = OkHttpClient.Builder().callTimeout(Duration.ofMinutes(9))
        if (proxySettings != null) {
            val type = when (proxySettings.type) {
                ProxyType.HTTP -> Proxy.Type.HTTP
                ProxyType.SOCKS -> Proxy.Type.SOCKS
            }
            builder.proxy(Proxy(type, InetSocketAddress(proxySettings.host, proxySettings.port)))
            builder.configureHttpProxyBasicAuthentication(proxySettings)
        }
        return builder.build()
    }

    /**
     * 获取创建时首轮 OpenAI 会话、MCP 连接与模型发现的组合就绪任务。
     *
     * 有效 OpenAI 配置会在构造时启动该任务；任务正常完成表示初始会话、MCP 工具快照与模型发现均已
     * 完成，且模型快照非空、模型列表非空并包含当前模型。任一阶段失败或取消都会取消该任务。
     * [MCPClientService] 会将单个服务器连接错误降级处理，因此此类错误不会单独使任务取消。未启用
     * OpenAI 时返回 `null`。
     *
     * @return 初始组合就绪任务；没有需要连接的初始 OpenAI 实例时返回 `null`。
     */
    override fun initializationJob(): Job? = initialMcpConnectionJob

    /** 创建顺序执行模型发现的任务；首轮会话或模型快照无效时以取消状态结束。 */
    private fun createInitialModelUpdateJob(initialResetJob: Job): Job = scope.launch(
        CoroutineExceptionHandler { _, error ->
            logger.error(
                "OpenAI model discovery did not become ready; category={}",
                SafeLogging.failureCategory(error).wireName,
            )
        },
    ) {
        initialResetJob.join()
        if (initialResetJob.isCancelled) {
            throw CancellationException("OpenAI initial session reset did not complete")
        }
        val snapshot = updateModel()
            ?: throw IllegalStateException("OpenAI initial model discovery failed")
        check(snapshot.availableModels.isNotEmpty()) { "OpenAI initial model list is empty" }
        check(snapshot.currentModel in snapshot.availableModels) {
            "OpenAI initial current model is not present in the discovered model list"
        }
    }

    /**
     * 组合首轮会话和模型发现，使候选仅在两个阶段均成功后才可发布。
     *
     * 总时限到期会取消两个同级任务，并将等待其退出的工作转到独立跟踪清理，保证候选不会在委派层已经
     * 放开切换屏障后继续发布状态，也不会因不响应取消的 I/O 延长该时限。
     */
    private fun createInitialReadinessJob(initialResetJob: Job, initialModelJob: Job): Job = scope.launch(
        CoroutineExceptionHandler { _, error ->
            logger.error(
                "OpenAI agent initialization did not become ready; category={}",
                SafeLogging.failureCategory(error).wireName,
            )
        },
    ) {
        try {
            withTimeout(deadlines.candidateInitialization) {
                initialResetJob.join()
                initialModelJob.join()
                if (initialResetJob.isCancelled || initialModelJob.isCancelled) {
                    throw CancellationException("OpenAI agent initialization failed")
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("OpenAI candidate initialization timed out; cancelling unfinished initialization work.")
            scheduleInitializationSiblingCleanup(initialResetJob, initialModelJob)
            throw e
        }
    }

    /**
     * 取消并在独立作用域中等待超时初始化的同级任务。
     *
     * 等待不能留在候选就绪任务中：底层 I/O 可能忽略取消。这里先同步请求取消，再跟踪后台 `join`，使
     * 委派层能按 deadline 释放屏障，同时仍保留对滞留任务的生命周期观察。
     */
    private fun scheduleInitializationSiblingCleanup(vararg jobs: Job) {
        jobs.forEach(Job::cancel)
        lateinit var cleanupJob: Job
        synchronized(initializationCleanupLock) {
            cleanupJob = closingScope.launch(start = CoroutineStart.LAZY) {
                try {
                    jobs.toList().joinAll()
                } finally {
                    synchronized(initializationCleanupLock) { initializationCleanupJobs.remove(cleanupJob) }
                }
            }
            initializationCleanupJobs.add(cleanupJob)
        }
        cleanupJob.start()
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
     * @return 刷新成功后的模型快照；HTTP 传输不可用、服务已关闭或刷新结果过期时返回 `null`。
     */
    override suspend fun updateModel(): ModelSnapshot? = modelUpdateMutex.withLock {
        try {
            if (closed) {
                return@withLock null
            }
            val selectionVersion = synchronized(modelStateLock) { modelSelectionVersion }
            val models = when (val currentTransport = rawTransport) {
                null -> {
                    val currentClient = client ?: return@withLock null
                    withContext(Dispatchers.IO) {
                        currentClient.models().list().data()
                    }
                }

                else -> listRawOpenAIModels(currentTransport)
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
            if (refreshResult.second?.isCancelled == true) {
                return@withLock null
            }
            refreshResult.third?.let(::clearPersistedSelectedModel)
            refreshResult.first
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to update OpenAI models; category={}", SafeLogging.failureCategory(e).wireName)
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
        return scope.launch(
            CoroutineExceptionHandler { _, error ->
                logger.error("OpenAI session reset failed; category={}", SafeLogging.failureCategory(error).wireName)
            },
            start = CoroutineStart.UNDISPATCHED,
        ) {
            var mcpConnectionJob: Job? = null
            try {
                mcpConnectionJob = sessionMutex.withLock {
                    if (closed) null else action()
                }
                awaitMcpConnectionJob(mcpConnectionJob)
                if (mcpConnectionJob?.isCancelled == true) {
                    throw CancellationException("MCP connection did not complete")
                }
                if (!closed && mcpConnectionJob?.isCancelled == false) {
                    functionRouter().refresh()
                }
            } finally {
                withContext(NonCancellable) {
                    mcpConnectionJob?.takeIf { !it.isCompleted }?.cancelAndJoin()
                }
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

        val skills = skillRepository.getApprovedSkillSummaries()
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
     * 图像会以内联数据发送。Telegram OGG 语音会先通过转写端点转换为文本，再与配文一起进入聊天回合；
     * 仅真实 WAV/MP3 MIME 类型且模型支持音频时才作为直接音频输入。其他媒体或空消息会被忽略。
     * 此挂起函数会与会话重置串行执行。用户消息、模型工具调用和工具结果先在本地暂存，只有收到
     * 不含工具调用的最终 assistant 消息后才会一次性提交；失败或取消不会影响既有成功会话。工具已
     * 产生的外部副作用无法撤销，重试具有至少一次语义。
     *
     * @param text 可选的配文或指令内容；为 `null` 或空白时不创建文本内容片段。
     * @param mediaData 要发送的媒体数据列表；可为空，元素的 MIME 类型决定其处理方式。
     * @return 模型最终回复文本；没有可发送内容或模型未返回文本时返回空字符串。以 `Error:` 开头的
     * 文本是模型的正常回复。
     * @throws AudioTranscriptionTooLargeException OGG 语音超过 [MAX_AUDIO_TRANSCRIPTION_BYTES] 时，在构建
     * multipart 请求前抛出；本次回合历史不会提交。
     * @throws AudioTranscriptionFailedException OGG 转写请求失败、响应格式无效或返回空文本时抛出；本次回合
     * 历史不会提交。
     * @throws AgentTurnFailedException 当 API、协议、工具调用上限或其他非取消错误导致本次回合未完成时抛出；
     * 本次回合历史不会提交。
     * @throws IllegalStateException 当服务已关闭或 OpenAI HTTP 传输尚未初始化时抛出。
     * @throws CancellationException 当调用协程、模型调用或工具调用被取消时抛出。
     */
    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String = sessionMutex.withLock {
        check(!closed) { "OpenAI client is closed." }
        val currentClient = client
        check(currentClient != null || rawTransport != null) { "OpenAI client is not initialized." }

        var messageText = text
        mediaData.filter { (_, mimeType) -> mimeType.normalizedMimeType() == TELEGRAM_OGG_MIME_TYPE }
            .forEach { (data) ->
                messageText = appendTranscription(messageText, transcribeTelegramOggLocked(data))
            }
        require((messageText ?: "").toByteArray(StandardCharsets.UTF_8).size <= MAX_AGENT_TEXT_BYTES) {
            "消息文本超过本地上下文限制。"
        }

        val contentParts = mutableListOf<ChatCompletionContentPart>()

        if (!messageText.isNullOrBlank()) {
            contentParts.add(
                ChatCompletionContentPart.ofText(
                    ChatCompletionContentPartText.builder().text(messageText).build()
                )
            )
        }

        var inlineMediaBytes = 0
        for ((data, mimeType) in mediaData) {
            val normalizedMimeType = mimeType.normalizedMimeType()
            if (normalizedMimeType.startsWith("image/")) {
                inlineMediaBytes = reserveInlineMedia(inlineMediaBytes, data.size)
                val base64Data = Base64.getEncoder().encodeToString(data)
                contentParts.add(
                    ChatCompletionContentPart.ofImageUrl(
                        ChatCompletionContentPartImage.builder()
                            .imageUrl(
                                ChatCompletionContentPartImage.ImageUrl.builder()
                                    .url("data:$normalizedMimeType;base64,$base64Data")
                                    .build()
                            )
                            .build()
                    )
                )
            } else if (normalizedMimeType.directAudioFormat() != null) {
                if (currentModel.contains("audio") || currentModel.startsWith("o1") || currentModel.startsWith("o3")) {
                    inlineMediaBytes = reserveInlineMedia(inlineMediaBytes, data.size)
                    contentParts.add(
                        ChatCompletionContentPart.ofInputAudio(
                            ChatCompletionContentPartInputAudio.builder()
                                .inputAudio(
                                    ChatCompletionContentPartInputAudio.InputAudio.builder()
                                        .data(Base64.getEncoder().encodeToString(data))
                                        .format(normalizedMimeType.directAudioFormat()!!)
                                        .build()
                                )
                                .build()
                        )
                    )
                } else {
                    logger.warn(
                        "Model {} might not support direct WAV/MP3 audio input. Skipping audio part.",
                        currentModel
                    )
                }
            } else if (normalizedMimeType.startsWith("audio/") && normalizedMimeType != TELEGRAM_OGG_MIME_TYPE) {
                logger.warn("Skipping unsupported direct audio MIME type.")
            }
        }

        if (contentParts.isEmpty()) {
            return@withLock ""
        }

        try {
            val userMessage = ChatCompletionMessageParam.ofUser(
                ChatCompletionUserMessageParam.builder()
                    .contentOfArrayOfContentParts(contentParts)
                    .build()
            )
            val tentativeHistory = prepareOpenAiCandidate()
            tentativeHistory.add(userMessage)
            normalizeOpenAiCandidate(tentativeHistory, currentOpenAiTurnStart(tentativeHistory))
            val reply = performChatLocked(currentClient, tentativeHistory)
            history.clear()
            history.addAll(tentativeHistory)
            reply
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioTranscriptionTooLargeException) {
            throw e
        } catch (e: AudioTranscriptionFailedException) {
            throw e
        } catch (e: AgentTurnFailedException) {
            throw e
        } catch (e: Exception) {
            logger.error("OpenAI message processing failed; category={}", SafeLogging.failureCategory(e).wireName)
            throw AgentTurnFailedException("OpenAI 代理回合未完成。", e)
        }
    }

    /**
     * 在内存中提交 Telegram OGG 语音到 OpenAI 转写端点，并返回非空转写文本。
     *
     * 大小上限在创建 multipart 请求体前校验；请求始终复用当前 [CancellableOkHttpTransport]，所以关闭或
     * 协程取消会中断原生 OkHttp Call。此方法不读取或写入临时文件，也不执行音频转码。
     *
     * @param data Telegram 下载得到的原始 OGG 字节；长度不得超过 [MAX_AUDIO_TRANSCRIPTION_BYTES]。
     * @return 去除首尾空白后的非空转写文本。
     * @throws AudioTranscriptionTooLargeException [data] 超过本地大小上限时抛出，不会创建网络请求。
     * @throws AudioTranscriptionFailedException 转写请求失败、响应无 `text` 字段或文本为空时抛出。
     * @throws CancellationException 调用协程或底层 HTTP 请求被取消时原样抛出。
     */
    private suspend fun transcribeTelegramOggLocked(data: ByteArray): String {
        if (data.size > MAX_AUDIO_TRANSCRIPTION_BYTES) {
            throw AudioTranscriptionTooLargeException()
        }
        val transport = rawTransport ?: throw AudioTranscriptionFailedException()
        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", TRANSCRIPTION_MODEL)
                .addFormDataPart(
                    "file",
                    "telegram-voice.ogg",
                    data.toRequestBody(TELEGRAM_OGG_MIME_TYPE.toMediaType()),
                )
                .build()
            val response = transport.execute(
                rawOpenAIRequestBuilder("audio/transcriptions")
                    .post(requestBody)
                    .build(),
            )
            if (response.statusCode !in 200..299) {
                logger.warn("OpenAI transcription request failed with HTTP {}.", response.statusCode)
                throw AudioTranscriptionFailedException()
            }
            val text = (JsonStructureLimits.parseToJsonElement(json, response.body) as? JsonObject)
                ?.get("text")
                ?.let { it as? JsonPrimitive }
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw AudioTranscriptionFailedException()
            text
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioTranscriptionFailedException) {
            throw e
        } catch (e: Exception) {
            throw AudioTranscriptionFailedException(e)
        }
    }

    private fun appendTranscription(text: String?, transcription: String): String =
        listOfNotNull(text?.takeIf(String::isNotBlank), transcription).joinToString("\n\n")

    private fun reserveInlineMedia(current: Int, size: Int): Int {
        if (size !in 0..MAX_AGENT_INLINE_MEDIA_BYTES || current > MAX_AGENT_INLINE_MEDIA_BYTES - size) {
            throw AgentTurnFailedException("内联媒体超过本地上下文限制。")
        }
        return current + size
    }

    /** 在候选副本上保留连续系统前缀，并为新的完整回合预留空间。 */
    private fun prepareOpenAiCandidate(): MutableList<ChatCompletionMessageParam> {
        val candidate = history.toMutableList()
        normalizeOpenAiCandidate(
            candidate,
            currentTurnStart = null,
            maxEntries = MAX_AGENT_HISTORY_ENTRIES - 1,
            maxBytes = MAX_AGENT_HISTORY_BYTES - MAX_AGENT_TURN_RESERVATION_BYTES,
        )
        return candidate
    }

    /** 将超出预算的最早完整回合整体删除，绝不留下工具调用或工具结果的孤立片段。 */
    private fun normalizeOpenAiCandidate(
        candidate: MutableList<ChatCompletionMessageParam>,
        currentTurnStart: Int?,
        maxEntries: Int = MAX_AGENT_HISTORY_ENTRIES,
        maxBytes: Int = MAX_AGENT_HISTORY_BYTES,
    ) {
        while (candidate.size > maxEntries || openAiHistoryBytes(candidate) > maxBytes) {
            val protectedTurnStart = currentTurnStart?.let { currentOpenAiTurnStart(candidate) }
            val firstHistoricalTurn = candidate.indexOfFirst(ChatCompletionMessageParam::isUser)
            if (firstHistoricalTurn < 0 ||
                (protectedTurnStart != null && firstHistoricalTurn >= protectedTurnStart)
            ) {
                throw AgentTurnFailedException("AI 会话历史超过资源上限。")
            }
            val turnEnd = candidate.subList(firstHistoricalTurn + 1, candidate.size)
                .indexOfFirst(ChatCompletionMessageParam::isUser)
                .takeIf { it >= 0 }
                ?.plus(firstHistoricalTurn + 1)
                ?: protectedTurnStart
                ?: candidate.size
            if (turnEnd <= firstHistoricalTurn) {
                throw AgentTurnFailedException("AI 会话历史无法按完整回合裁剪。")
            }
            candidate.subList(firstHistoricalTurn, turnEnd).clear()
        }
    }

    /** 当前回合唯一以普通 user 消息开始；工具结果不是 user 消息。 */
    private fun currentOpenAiTurnStart(candidate: List<ChatCompletionMessageParam>): Int =
        candidate.indexOfLast(ChatCompletionMessageParam::isUser).takeIf { it >= 0 }
            ?: throw AgentTurnFailedException("AI 会话历史缺少当前用户消息。")

    private fun openAiHistoryBytes(candidate: List<ChatCompletionMessageParam>): Int =
        try {
            jsonMapper().writeValueAsBytes(candidate).size
        } catch (error: Exception) {
            throw AgentTurnFailedException("AI 会话历史无法安全编码。", error)
        }

    private fun String.normalizedMimeType(): String = substringBefore(';').trim().lowercase(Locale.ROOT)

    private fun String.directAudioFormat(): ChatCompletionContentPartInputAudio.InputAudio.Format? =
        when (this) {
            "audio/wav", "audio/x-wav" -> ChatCompletionContentPartInputAudio.InputAudio.Format.WAV
            "audio/mpeg", "audio/mp3" -> ChatCompletionContentPartInputAudio.InputAudio.Format.MP3
            else -> null
        }

    private fun functionRouter(): LocalFunctionRouter {
        val currentProviders = localFunctionProviders
        if (!localFunctionRouter.uses(currentProviders)) {
            localFunctionRouter = LocalFunctionRouter(currentProviders)
        }
        return localFunctionRouter
    }

    private fun failedInitializationJob(): Job = Job().also {
        it.cancel(CancellationException("OpenAI agent initialization failed"))
    }

    /**
     * 执行完整的模型与工具调用流程。调用方必须已持有 [sessionMutex]。
     */
    private suspend fun performChatLocked(
        client: OpenAIClient?,
        tentativeHistory: MutableList<ChatCompletionMessageParam>,
        functionRouteSnapshot: LocalFunctionRouteSnapshot = functionRouter().refresh(),
        toolCallRounds: Int = 0,
        toolCallsExecuted: Int = 0,
    ): String {
        val tools = functionRouteSnapshot.providedOpenAIFunctions().map { func ->
            ChatCompletionTool.ofFunction(
                ChatCompletionFunctionTool.builder()
                    .function(func)
                    .build()
            )
        }

        val paramsBuilder = createChatCompletionParams(tools, tentativeHistory)

        val response = when (val currentTransport = rawTransport) {
            null -> {
                val currentClient = client ?: throw IllegalStateException("OpenAI client is not initialized.")
                withContext(Dispatchers.IO) {
                    currentClient.chat().completions().create(paramsBuilder)
                }
            }

            else -> executeRawOpenAIChat(currentTransport, paramsBuilder)
        }
        val choice = response.choices().firstOrNull()
            ?: throw AgentTurnFailedException("OpenAI 响应未包含 assistant 消息。")
        val message = choice.message()
        val toolCalls = message.toolCalls()

        return when (choice.finishReason()) {
            ChatCompletion.Choice.FinishReason.STOP -> {
                if (toolCalls.isPresent) {
                    throw AgentTurnFailedException("OpenAI 最终响应不应包含工具调用。")
                }
                tentativeHistory.add(ChatCompletionMessageParam.ofAssistant(message.toParam()))
                normalizeOpenAiCandidate(tentativeHistory, currentOpenAiTurnStart(tentativeHistory))
                message.content().getOrNull() ?: ""
            }

            ChatCompletion.Choice.FinishReason.TOOL_CALLS -> {
                ensureToolCallRoundIsAllowed(toolCallRounds)
                val validatedToolCalls = validateToolCalls(toolCalls)
                ensureToolCallCountIsAllowed(validatedToolCalls.size, toolCallsExecuted)
                tentativeHistory.add(ChatCompletionMessageParam.ofAssistant(message.toParam()))
                normalizeOpenAiCandidate(tentativeHistory, currentOpenAiTurnStart(tentativeHistory))
                for ((toolCall, toolCallId) in validatedToolCalls) {
                    val result = executeToolCall(toolCall, functionRouteSnapshot)
                    currentCoroutineContext().ensureActive()
                    tentativeHistory.add(createToolMessage(toolCallId, result))
                    normalizeOpenAiCandidate(tentativeHistory, currentOpenAiTurnStart(tentativeHistory))
                }
                performChatLocked(
                    client,
                    tentativeHistory,
                    functionRouteSnapshot,
                    toolCallRounds + 1,
                    toolCallsExecuted + validatedToolCalls.size,
                )
            }

            else -> throw AgentTurnFailedException("OpenAI 响应未完成本次代理回合。")
        }
    }

    /**
     * 验证一批工具调用可被完整、无歧义地回传给模型。
     *
     * 此校验必须先于 assistant 消息暂存和任一工具执行。每个调用都需要唯一的非空标识，并必须能够
     * 构造对应的工具结果；任一调用不满足条件时整批回合失败。
     *
     * @param toolCalls assistant 消息中可选的工具调用列表。
     * @return 与原顺序一致的工具调用及其已验证标识。
     * @throws AgentTurnFailedException 当工具调用缺失、为空、标识无效或无法构造工具结果时抛出。
     */
    private fun validateToolCalls(
        toolCalls: Optional<List<ChatCompletionMessageToolCall>>,
    ): List<Pair<ChatCompletionMessageToolCall, String>> {
        val calls = toolCalls.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: throw AgentTurnFailedException("OpenAI 工具调用缺失或为空。")
        if (calls.size > MAX_TOOL_CALLS_PER_MODEL_RESPONSE) {
            throw ToolCallLimitExceededException()
        }
        val validatedCalls = calls.map { toolCall ->
            val toolCallId = toolCall.validToolCallId()
                ?: throw AgentTurnFailedException("OpenAI 工具调用缺少有效标识。")
            try {
                createToolMessage(toolCallId, toolError("tool_call_validation"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw AgentTurnFailedException("OpenAI 工具调用无法构造结果。", e)
            }
            toolCall to toolCallId
        }
        if (validatedCalls.map { it.second }.toSet().size != validatedCalls.size) {
            throw AgentTurnFailedException("OpenAI 工具调用标识重复。")
        }
        return validatedCalls
    }

    /**
     * 将一个已验证标识和工具结果转换为 OpenAI 工具消息。
     *
     * @param toolCallId 已验证的非空工具调用标识。
     * @param result 要回传给模型的 JSON 对象。
     * @return 与 [toolCallId] 对应的工具消息参数。
     */
    private fun createToolMessage(toolCallId: String, result: JsonObject): ChatCompletionMessageParam {
        JsonStructureLimits.validateElement(result)
        val encodedResult = json.encodeToString(result)
        JsonStructureLimits.validateJsonString(encodedResult)
        return ChatCompletionMessageParam.ofTool(
            ChatCompletionToolMessageParam.builder()
                .toolCallId(toolCallId)
                .content(encodedResult)
                .build()
        )
    }

    /**
     * 为一个 OpenAI 工具调用生成可回传给模型的 JSON 结果。
     *
     * 非函数调用、未知函数、非法参数和提供者失败均转换为稳定的错误对象；取消始终向上传播。
     *
     * @param toolCall 模型返回的工具调用。
     * @return 可作为工具消息内容编码的 JSON 对象。
     * @throws CancellationException 当当前协程或工具提供者被取消时抛出。
     */
    private suspend fun executeToolCall(
        toolCall: ChatCompletionMessageToolCall,
        functionRouteSnapshot: LocalFunctionRouteSnapshot,
    ): JsonObject {
        if (!toolCall.isFunction()) {
            return toolError("unsupported_tool")
        }

        return try {
            val function = toolCall.asFunction().function()
            val name = function.name()
            val args = parseToolArguments(function.arguments()) ?: return toolError("invalid_arguments")
            if (!functionRouteSnapshot.canHandle(name)) return toolError("unknown_tool")
            try {
                functionRouteSnapshot.execute(name, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "OpenAI tool provider failed for {}; category={}",
                    name,
                    SafeLogging.failureCategory(e).wireName,
                )
                toolError("tool_execution_failed")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "OpenAI returned an invalid function tool call; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            toolError("invalid_tool_call")
        }
    }

    /**
     * 解析函数参数，且只接受 JSON 对象，避免向提供者传入不符合函数契约的数据。
     *
     * @param arguments 模型返回的原始 JSON 参数文本。
     * @return 参数对象转换后的映射；文本非法或根元素不是对象时返回 `null`。
     */
    private fun parseToolArguments(arguments: String): Map<String, Any?>? {
        if (arguments.toByteArray(StandardCharsets.UTF_8).size > MAX_MCP_TOOL_ARGUMENT_BYTES) return null
        return try {
            (JsonStructureLimits.parseToJsonElement(json, arguments) as? JsonObject)?.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 创建不包含提供者异常详情的有限错误结果。
     *
     * @param code 供模型判断失败类别的固定错误码。
     * @return 仅包含固定错误码字段的 JSON 对象。
     */
    private fun toolError(code: String): JsonObject = buildJsonObject { put("error", code) }

    /**
     * 读取工具调用中可用于工具响应的标识。
     *
     * @return 非空白标识；调用类型未知、标识缺失或无效时返回 `null`。
     */
    private fun ChatCompletionMessageToolCall.validToolCallId(): String? =
        try {
            when {
                isFunction() -> asFunction().id().takeIf { it.isNotBlank() }
                isCustom() -> asCustom().id().takeIf { it.isNotBlank() }
                else -> null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
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
     * 使用 SDK 的参数 DTO 生成请求体和附加头/查询参数，但由原生 OkHttp Call 执行实际请求。
     *
     * 这避免 SDK 高层 Future 的取消断链，同时继续遵守 SDK 对联合消息、工具和新增字段的序列化规则。
     */
    private suspend fun executeRawOpenAIChat(
        transport: CancellableOkHttpTransport,
        params: ChatCompletionCreateParams,
    ): ChatCompletion {
        val mapper = jsonMapper()
        val request = rawOpenAIRequestBuilder(
            "chat/completions", params._headers().names().associateWith(params._headers()::values),
            params._queryParams().keys().associateWith(params._queryParams()::values)
        )
            .post(
                mapper.writeValueAsString(params._body()).toRequestBody("application/json; charset=utf-8".toMediaType())
            )
            .build()
        val response = transport.execute(request)
        requireOpenAISuccess(response)
        JsonStructureLimits.validateJsonString(response.body)
        return mapper.readValue(response.body, ChatCompletion::class.java)
    }

    /** 列出 OpenAI 兼容服务的模型，并通过 SDK 的 [Model] DTO 校验每个条目。 */
    private suspend fun listRawOpenAIModels(transport: CancellableOkHttpTransport): List<Model> {
        val response = transport.execute(rawOpenAIRequestBuilder("models").get().build())
        requireOpenAISuccess(response)
        val mapper = jsonMapper()
        JsonStructureLimits.validateJsonString(response.body)
        val root = mapper.readTree(response.body)
        val data = root.path("data")
        if (!data.isArray) {
            throw IllegalStateException("OpenAI models response did not contain a data array.")
        }
        return data.map { node -> mapper.treeToValue(node, Model::class.java) }
    }

    /** 构建保留自定义基础路径、Bearer 认证和 SDK 附加参数的原生 OpenAI 请求。 */
    private fun rawOpenAIRequestBuilder(
        path: String,
        additionalHeaders: Map<String, List<String>> = emptyMap(),
        additionalQuery: Map<String, List<String>> = emptyMap(),
    ): Request.Builder {
        val baseUrl = rawBaseUrl ?: throw IllegalStateException("OpenAI base URL is not initialized.")
        val apiKey = rawApiKey ?: throw IllegalStateException("OpenAI API key is not initialized.")
        val urlBuilder = baseUrl.toHttpUrl().newBuilder().addPathSegments(path)
        additionalQuery.forEach { (name, values) -> values.forEach { urlBuilder.addQueryParameter(name, it) } }
        return Request.Builder()
            .url(urlBuilder.build())
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .apply {
                additionalHeaders.forEach { (name, values) -> values.forEach { addHeader(name, it) } }
            }
    }

    /** 将非 2xx OpenAI 结果隔离在协议边界，防止其被误解为成功 DTO。 */
    private fun requireOpenAISuccess(response: HttpResult) {
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("OpenAI API returned HTTP ${response.statusCode}: ${response.body.take(1024)}")
        }
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
        settingsRepository.updateSettings { settings ->
            val aiSettings = settings.ai
            if (
                aiSettings?.provider == AIProvider.OPENAI &&
                aiSettings.openAiApiKey == configuredApiKey &&
                aiSettings.openAiBaseUrl == configuredBaseUrl &&
                settings.proxy == configuredProxy &&
                aiSettings.selectedModel == invalidModel
            ) {
                settings.copy(ai = aiSettings.copy(selectedModel = ""))
            } else {
                settings
            }
        }
    }

    /**
     * 关闭 OpenAI 原生 HTTP 传输、会话任务及其工具连接。
     *
     * 首次调用在返回前会拒绝新的消息、会话重置和模型刷新。重复调用会返回同一个等待任务；若调用方
     * 取消此前返回的等待任务，后续调用会提供新的等待任务，
     * 而不会取消已启动的资源清理。等待任务完成后，正在进行的模型刷新已退出，OpenAI HTTP 传输、
     * HTTP 工具客户端和 MCP 连接均已释放。
     *
     * @return 异步关闭任务；等待该任务完成后服务不再保留会话状态或网络资源。
     */
    override fun close(): Job = synchronized(lifecycleLock) {
        val completion = closeCompletion ?: CompletableDeferred<Unit>().also { newCompletion ->
            closed = true
            // 关闭先中断原生 HTTP Call，绝不等待 sessionMutex 中的网络回合返回。
            rawTransport?.close()
            initialModelUpdateJob?.cancel()
            initialModelUpdateJob = null
            initialMcpConnectionJob?.cancel()
            serviceJob.cancel()
            closeCompletion = newCompletion
            closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                closeResources(newCompletion)
            }
        }
        if (closeJob == null || closeJob?.isCancelled == true) {
            closeJob = closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    completion.await()
                }
            }
        }
        closeJob!!
    }

    /**
     * 在独立且不可取消的作用域中释放本服务拥有的资源。
     *
     * 原生 HTTP 调用已在取得会话锁前取消；本方法再摘取兼容路径的 SDK 客户端、等待已取消服务任务和
     * 模型刷新退出。各资源的关闭通过嵌套的 `finally` 串联，以便前一资源关闭失败时仍继续清理。
     */
    private suspend fun closeResources(completion: CompletableDeferred<Unit>) = withContext(NonCancellable) {
        try {
            val detachedClient = sessionMutex.withLock {
                val currentClient = client
                client = null
                rawTransport = null
                history.clear()
                cancelCurrentMcpConnection()
                currentClient
            }
            serviceJob.join()
            modelUpdateMutex.withLock { }
            try {
                detachedClient?.close()
            } finally {
                try {
                    httpCallingFunctionProvider.close()
                } finally {
                    mcpClientService.close().join()
                }
            }
        } catch (e: Exception) {
            logger.error(
                "Failed to close OpenAI agent resources; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
        } finally {
            completion.complete(Unit)
        }
    }
}
