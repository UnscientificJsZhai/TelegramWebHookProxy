package com.unscientificjszhai.tgp.service.ai.agent

import com.google.genai.Chat
import com.google.genai.Client
import com.google.genai.JsonSerializable
import com.google.genai.types.*
import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.configureHttpProxyBasicAuthentication
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.MAX_MCP_TOOL_ARGUMENT_BYTES
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.service.ai.function.HttpCallingFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionProvider.Companion.toMap
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionRouteSnapshot
import com.unscientificjszhai.tgp.service.ai.function.LocalFunctionRouter
import com.unscientificjszhai.tgp.service.ai.function.McpFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.ScheduleTaskFunctionProvider
import com.unscientificjszhai.tgp.service.ai.function.SkillFunctionProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.charset.StandardCharsets
import java.time.Duration
import javax.inject.Inject
import javax.inject.Provider
import kotlin.jvm.optionals.getOrNull

/**
 * 基于 Gemini API 维护对话会话并执行模型工具调用的 AI 代理服务。
 *
 * 服务在创建时根据当前设置初始化 Gemini 原生可取消 HTTP 传输，并在会话重置时同步 MCP 工具和技能提示词。
 * 调用 [close] 返回的任务完成后，服务持有的 HTTP 传输与 MCP 连接均已释放。
 *
 * @param parentScope 服务任务所属的父协程作用域。
 * @param settingsRepository 提供 Gemini、MCP 和代理设置的仓库。
 * @param skillRepository 提供会话系统提示词所需技能摘要的仓库。
 * @param mcpClientService 管理会话可调用的 MCP 工具连接。
 * @param taskSchedulerServiceProvider 延迟提供定时任务调度服务，以避免初始化循环依赖。
 * @param deadlines 限制候选初始化与其 MCP 批次的总体执行时间。
 */
@AgentScope
class GeminiAgentService @Inject internal constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    private val deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : AgentService() {
    private companion object {
        const val DEFAULT_MODEL = "models/gemini-3.5-flash-lite"
        const val PREVIOUS_DEFAULT_MODEL = "models/gemini-3.1-flash-lite"
        const val LEGACY_MODEL = "models/gemini-2.5-flash"
    }

    private val logger = LoggerFactory.getLogger(GeminiAgentService::class.java)
    private val wireJson = Json { ignoreUnknownKeys = true }
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

    private val httpCallingFunctionProvider = HttpCallingFunctionProvider(settingsRepository)
    private val localFunctionProviders = listOf(
        httpCallingFunctionProvider,
        McpFunctionProvider(mcpClientService),
        ScheduleTaskFunctionProvider(taskSchedulerServiceProvider, settingsRepository),
        SkillFunctionProvider(skillRepository),
    )
    private val localFunctionRouter = LocalFunctionRouter(localFunctionProviders)

    /** 串行化对话、重置和关闭，防止 Chat 状态在服务关闭后被旧任务改写。 */
    private val sessionMutex = Mutex()

    @Volatile
    private var client: Client? = null

    /** 生产请求使用的原生可取消 HTTP 传输；SDK 客户端仅保留给旧会话兼容路径。 */
    @Volatile
    private var rawTransport: CancellableOkHttpTransport? = null
    private var rawBaseUrl: String? = null
    private var rawApiKey: String? = null

    @Volatile
    private var chat: Chat? = null

    @Volatile
    private var chatFunctionRouteSnapshot: LocalFunctionRouteSnapshot? = null
    private var sdkSessionConfig: GenerateContentConfig? = null

    private var savedHistory: List<Content>? = null
    private var rawSession: RawGeminiSession? = null

    @Volatile
    private var resetSessionJob: Job? = null

    @Volatile
    private var initialReadinessJob: Job? = null

    /**
     * 工具调用超限后创建的恢复身份。
     *
     * 该身份只在 [sessionMutex] 保护下发布和清除。即使候选重置无法启动，仍会发布 `job == null` 的
     * 身份，使后续发送拒绝使用旧会话而不是降级放行。
     */
    private data class ToolLimitRecovery(val job: Job?)

    /** 仅在 [sessionMutex] 保护下访问；非空时后续发送必须等待或拒绝。 */
    private var pendingToolLimitRecovery: ToolLimitRecovery? = null

    private val modelUpdateMutex = Mutex()
    private val modelStateLock = Any()
    private var modelSelectionVersion = 0L

    /** 仅在 [modelStateLock] 保护下访问；非空时表示尚未提交的最新模型选择。 */
    private var pendingModel: String? = null

    /** 仅在 [modelStateLock] 保护下访问；表示 [pendingModel] 对应的候选会话任务。 */
    private var pendingModelSwitchJob: Job? = null
    private var initialModelUpdateJob: Job? = null
    private var configuredApiKey: String? = null
    private var configuredProxy: ProxySettings? = null

    /** 由本服务维护、仅在成功回合后提交的 Gemini 会话快照。 */
    private data class RawGeminiSession(
        val model: String,
        val config: JsonObject,
        val functionRouteSnapshot: LocalFunctionRouteSnapshot,
        val history: List<JsonObject>,
    )

    /**
     * 获取当前会话实际使用的 Gemini 模型名称。
     */
    @Volatile
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
                rawApiKey = aiSettings.geminiApiKey
                rawBaseUrl = geminiBaseUrl()
                rawTransport = CancellableOkHttpTransport(createGeminiHttpClient(proxySettings))

                this.resetSessionJob = resetSession()
                initialModelUpdateJob = createInitialModelUpdateJob(resetSessionJob)
                initialReadinessJob = createInitialReadinessJob(resetSessionJob, checkNotNull(initialModelUpdateJob))
                logger.info("Gemini client initialized.")
            } catch (e: Exception) {
                logger.error("Failed to initialize Gemini client; category={}", SafeLogging.failureCategory(e).wireName)
                client = null
                rawTransport?.close()
                rawTransport = null
                chat = null
                rawSession = null
                chatFunctionRouteSnapshot = null
                initialReadinessJob = failedInitializationJob()
            }
        }
    }

    /**
     * 获取创建时首轮 Gemini 会话、MCP 连接与模型发现的组合就绪任务。
     *
     * 任务正常完成表示服务可发布；此时首轮会话、工具快照和模型发现均已完成，模型快照非空、模型列表
     * 非空且包含当前模型。构造、会话创建、模型发现或其回退会话重置失败时任务会取消。
     * [MCPClientService] 对单个服务器连接错误采取降级处理，因此该错误不会使本任务取消。未启用 Gemini
     * 时返回 `null`。
     *
     * @return 初始会话就绪任务；无需初始化时返回 `null`。
     */
    override fun initializationJob(): Job? = initialReadinessJob

    private fun createInitialModelUpdateJob(initialResetJob: Job?): Job = initialResetJob?.let { resetJob ->
        scope.launch(CoroutineExceptionHandler { _, error ->
            logger.error(
                "Gemini model discovery did not become ready; category={}",
                SafeLogging.failureCategory(error).wireName,
            )
        }) {
            resetJob.join()
            if (resetJob.isCancelled) {
                throw CancellationException("Gemini initial session reset did not complete")
            }
            val snapshot = updateModel()
                ?: throw IllegalStateException("Gemini initial model discovery failed")
            check(snapshot.availableModels.isNotEmpty()) { "Gemini initial model list is empty" }
            check(snapshot.currentModel in snapshot.availableModels) {
                "Gemini initial current model is not present in the discovered model list"
            }
        }
    } ?: failedInitializationJob()

    /**
     * 合并首轮会话与模型发现，并以统一时限约束完整候选初始化。
     *
     * 时限到期时会取消两个同级任务，并在独立作用域追踪其退出，禁止其在候选已经放弃后继续提交会话或
     * 模型状态，也避免不响应取消的 I/O 延长候选 deadline。
     */
    private fun createInitialReadinessJob(resetJob: Job?, initialModelJob: Job): Job =
        resetJob?.let { initialResetJob ->
            scope.launch(CoroutineExceptionHandler { _, error ->
                logger.error(
                    "Gemini agent initialization did not become ready; category={}",
                    SafeLogging.failureCategory(error).wireName,
                )
            }) {
                try {
                    withTimeout(deadlines.candidateInitialization) {
                        initialResetJob.join()
                        initialModelJob.join()
                        if (
                            initialResetJob.isCancelled ||
                            initialModelJob.isCancelled ||
                            (chat == null && rawSession == null)
                        ) {
                            throw IllegalStateException("Gemini agent initialization failed")
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.warn("Gemini candidate initialization timed out; cancelling unfinished initialization work.")
                    scheduleInitializationSiblingCleanup(initialResetJob, initialModelJob)
                    throw e
                }
            }
        } ?: failedInitializationJob()

    /**
     * 取消并在独立作用域中等待超时初始化的同级任务。
     *
     * 取消请求必须立即发出；实际 `join` 可能被不响应取消的网络实现延迟，故不得继续占用候选就绪任务或
     * 模型切换屏障。
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

    private fun failedInitializationJob(): Job = scope.launch(
        CoroutineExceptionHandler { _, error ->
            logger.error(
                "Gemini agent initialization failed; category={}",
                SafeLogging.failureCategory(error).wireName,
            )
        },
        start = CoroutineStart.UNDISPATCHED,
    ) {
        throw IllegalStateException("Gemini agent initialization failed")
    }

    /**
     * 读取候选会话应继承的历史记录。
     *
     * 调用方必须持有 [sessionMutex]。读取失败时保留当前待恢复历史，避免候选创建失败或历史读取失败
     * 提前破坏既有会话状态。
     */
    private fun captureHistoryLocked(): List<Content>? {
        try {
            val history = chat?.getHistory(true)?.takeIf { it.isNotEmpty() } ?: savedHistory
            if (history != null) {
                logger.debug("Captured history: ${history.size} items.")
            }
            return history
        } catch (e: Exception) {
            logger.warn("Failed to capture history; category={}", SafeLogging.failureCategory(e).wireName)
            return savedHistory
        }
    }

    /** 创建 Gemini 原生传输客户端，并支持 HTTP Basic 代理认证与 SOCKS 代理路由。 */
    private fun createGeminiHttpClient(proxySettings: ProxySettings?): OkHttpClient {
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
     * 返回 Gemini REST 根地址。
     *
     * `GOOGLE_GEMINI_BASE_URL` 可以指向服务根地址或已经包含 `v1beta` 的测试端点；其余情况统一使用
     * Gemini 开发者 API 的 `v1beta` 路径。
     */
    private fun geminiBaseUrl(): String {
        val configured = System.getenv("GOOGLE_GEMINI_BASE_URL")
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf(String::isNotEmpty)
            ?: "https://generativelanguage.googleapis.com"
        return if (configured.endsWith("/v1beta")) configured else "$configured/v1beta"
    }

    private data class ModelSwitchRequest(
        val model: String,
        val version: Long,
    )

    /**
     * 切换当前会话使用的模型。
     *
     * 模型名称可以省略 `models/` 前缀。实际切换时会保存当前历史记录并异步重建会话；只有候选会话
     * 创建成功且该请求仍代表最新选择时，才会同时提交模型与会话。并发切换按最新选择线性化，较旧任务会
     * 以取消状态结束而不会覆盖新选择。
     *
     * @param modelName 要切换到的模型名称，必须存在于 [availableModels]，或在补上 `models/`
     * 前缀后存在于该列表。
     * @return 已开始切换时返回重置会话的任务；模型未改变或服务已关闭时返回 `null`。调用方等待任务后
     * 必须检查 [Job.isCancelled]；仅正常完成表示模型和会话都已提交。
     * @throws IllegalArgumentException 当 [modelName] 不在 [availableModels] 中时抛出。
     */
    override fun switchModel(modelName: String): Job? {
        return synchronized(modelStateLock) {
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
            startModelSwitchLocked(normalizedModel)
        }
    }

    /**
     * 从 Gemini API 刷新可用模型列表。
     *
     * 若当前模型不再可用，会选择内置回退模型并重置会话；刷新失败不会修改当前模型列表。
     *
     * @return 刷新成功后的模型快照；HTTP 传输不可用、服务已关闭或刷新结果过期时返回 `null`。
     */
    override suspend fun updateModel(): ModelSnapshot? = modelUpdateMutex.withLock {
        try {
            if (closed) {
                return@withLock null
            }
            val selectionVersion = synchronized(modelStateLock) { modelSelectionVersion }
            val currentTransport = rawTransport
            val refreshedModels = when {
                currentTransport != null -> listRawModels(currentTransport)
                else -> {
                    val models = client?.models ?: return@withLock null
                    withContext(Dispatchers.IO) {
                        models.list(ListModelsConfig.builder().build()).mapNotNull { it.name().getOrNull() }
                    }
                }
            }
            val refreshResult = synchronized(modelStateLock) {
                if (modelSelectionVersion != selectionVersion) {
                    return@withLock null
                }
                availableModels = refreshedModels
                val invalidModel = currentModel.takeUnless { it in availableModels }
                val fallbackModel = invalidModel?.let { preferredModel(availableModels) }
                val fallbackJob = fallbackModel?.let(::startModelSwitchLocked)
                Triple(fallbackJob, invalidModel, ModelSnapshot(currentModel, availableModels))
            }
            val fallbackJob = refreshResult.first
            if (fallbackJob != null) {
                fallbackJob.join()
                if (fallbackJob.isCancelled) {
                    return@withLock null
                }
                refreshResult.second?.let { invalidModel ->
                    if (settingsRepository.hasHistoricalInvalidOpenAiBaseUrl) {
                        logger.info("Keeping the persisted Gemini model selection while a historical OpenAI base URL is protected.")
                    } else {
                        clearPersistedSelectedModel(invalidModel)
                    }
                }
            } else if (refreshResult.second != null) {
                return@withLock null
            }
            synchronized(modelStateLock) {
                ModelSnapshot(currentModel, availableModels)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to update Gemini models; category={}", SafeLogging.failureCategory(e).wireName)
            null
        }
    }

    /**
     * 异步重置当前会话并重新应用系统提示词、技能与 MCP 工具。
     *
     * 工具调用达到上限时，服务会使用同一候选重置机制建立发送恢复屏障；恢复任务未正常完成、被取消或
     * 无法启动时，后续 [sendMessage] 会拒绝发送，不会复用此前会话。
     *
     * @return 已开始重置时返回对应任务；服务已关闭或 Gemini HTTP 传输不可用时返回 `null`。调用方必须在
     * [Job.join] 后检查 [Job.isCancelled]：只有任务正常完成时，新会话才已原子替换旧会话；候选创建
     * 失败或任务取消都会保留旧会话状态。
     */
    override fun resetSession(): Job? = resetSession(captureHistory = false)

    private fun resetSession(
        captureHistory: Boolean,
        switchRequest: ModelSwitchRequest? = null,
    ): Job? {
        if (closed) {
            return null
        }
        if (rawTransport != null) {
            return resetRawSession(captureHistory, switchRequest)
        }
        val currentClient = client
        if (currentClient == null) {
            logger.warn("Cannot reset session: Gemini client is not initialized.")
            return null
        }

        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return null
        return scope.launch(CoroutineExceptionHandler { _, error ->
            logger.error("Gemini session reset failed; category={}", SafeLogging.failureCategory(error).wireName)
        }) {
            try {
                sessionMutex.withLock {
                    ensureResetCanCommit(switchRequest)
                    val candidateHistory = if (captureHistory) {
                        captureHistoryLocked()?.let(::prepareSdkGeminiHistory)
                    } else {
                        null
                    }
                    mcpClientService.connect(aiSettings.mcpServers)
                    currentCoroutineContext().ensureActive()
                    ensureResetCanCommit(switchRequest)
                    val functionRouteSnapshot = localFunctionRouter.refresh()
                    val candidateModel = switchRequest?.model ?: currentModel
                    val candidateConfig = createSdkSessionConfig(aiSettings, functionRouteSnapshot)
                    val newChat = try {
                        currentClient.chats.create(candidateModel, candidateConfig)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to create Gemini chat session; category={}",
                            SafeLogging.failureCategory(e).wireName,
                        )
                        throw e
                    }
                    currentCoroutineContext().ensureActive()
                    ensureResetCanCommit(switchRequest)

                    // 会话锁和模型锁将会话、路由、待恢复历史和模型的发布线性化；在此之前旧状态始终可用。
                    commitCandidateSession(
                        newChat,
                        functionRouteSnapshot,
                        candidateHistory,
                        candidateConfig,
                        switchRequest
                    )
                    logger.info("Gemini session reset with model: $candidateModel")
                }
            } catch (e: Throwable) {
                switchRequest?.let(::abandonModelSwitch)
                throw e
            }
        }
    }

    /**
     * 建立候选的原生 Gemini 会话并在成功时一次性发布。
     *
     * REST API 没有服务端 Chat 资源，因此候选会话只包含不可变配置、工具路由和已经成功提交的本地历史。
     * 构建或 MCP 连接被取消时，该候选不会写入 [rawSession]。
     */
    private fun resetRawSession(
        captureHistory: Boolean,
        switchRequest: ModelSwitchRequest?,
    ): Job? {
        val currentTransport = rawTransport ?: return null
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return null
        return scope.launch(CoroutineExceptionHandler { _, error ->
            logger.error("Gemini raw session reset failed; category={}", SafeLogging.failureCategory(error).wireName)
        }) {
            try {
                sessionMutex.withLock {
                    ensureResetCanCommit(switchRequest)
                    val candidateHistory = if (captureHistory) {
                        prepareRawGeminiCandidate(rawSession?.history.orEmpty())
                    } else {
                        emptyList()
                    }
                    mcpClientService.connect(aiSettings.mcpServers)
                    currentCoroutineContext().ensureActive()
                    ensureResetCanCommit(switchRequest)
                    check(rawTransport === currentTransport) { "Gemini HTTP transport was replaced" }
                    val routeSnapshot = localFunctionRouter.refresh()
                    val model = switchRequest?.model ?: currentModel
                    val candidate = RawGeminiSession(
                        model = model,
                        config = createGeminiWireConfig(aiSettings, routeSnapshot),
                        functionRouteSnapshot = routeSnapshot,
                        history = candidateHistory,
                    )
                    commitRawCandidateSession(candidate, switchRequest)
                    logger.info("Gemini raw session reset with model: {}", model)
                }
            } catch (e: Throwable) {
                switchRequest?.let(::abandonModelSwitch)
                throw e
            }
        }
    }

    /** 在模型状态锁内提交已完整建立的原生会话候选。 */
    private fun commitRawCandidateSession(candidate: RawGeminiSession, switchRequest: ModelSwitchRequest?) {
        synchronized(modelStateLock) {
            if (switchRequest != null) {
                check(modelSelectionVersion == switchRequest.version && pendingModel == switchRequest.model) {
                    "Gemini model switch was superseded before commit"
                }
            }
            rawSession = candidate
            if (switchRequest != null) {
                currentModel = switchRequest.model
                pendingModel = null
                pendingModelSwitchJob = null
            }
        }
    }

    private suspend fun ensureResetCanCommit(switchRequest: ModelSwitchRequest?) {
        currentCoroutineContext().ensureActive()
        if (closed) {
            throw CancellationException("Gemini agent is closed")
        }
        if (switchRequest != null && !isLatestModelSwitch(switchRequest)) {
            throw CancellationException("Gemini model switch was superseded")
        }
    }

    private fun isLatestModelSwitch(request: ModelSwitchRequest): Boolean = synchronized(modelStateLock) {
        modelSelectionVersion == request.version && pendingModel == request.model
    }

    /**
     * 在 [modelStateLock] 内启动或复用目标模型的候选会话任务。
     */
    private fun startModelSwitchLocked(model: String): Job? {
        if (pendingModel == model) {
            return pendingModelSwitchJob
        }
        if (pendingModel == null && currentModel == model) {
            return null
        }

        val request = ModelSwitchRequest(model, ++modelSelectionVersion)
        if (currentModel == model) {
            // 取消尚未提交的其他选择；当前已发布会话正好就是最新模型。
            pendingModel = null
            pendingModelSwitchJob = null
            return Job().apply { complete() }
        }

        pendingModel = model
        val switchJob = resetSession(captureHistory = true, switchRequest = request) ?: run {
            pendingModel = null
            return null
        }
        pendingModelSwitchJob = switchJob
        switchJob.invokeOnCompletion {
            synchronized(modelStateLock) {
                if (pendingModelSwitchJob === switchJob) {
                    pendingModelSwitchJob = null
                    if (pendingModel == request.model && modelSelectionVersion == request.version) {
                        pendingModel = null
                    }
                }
            }
        }
        return switchJob
    }

    private fun commitCandidateSession(
        candidateChat: Chat,
        candidateRouteSnapshot: LocalFunctionRouteSnapshot,
        candidateHistory: List<Content>?,
        candidateConfig: GenerateContentConfig,
        switchRequest: ModelSwitchRequest?,
    ) {
        synchronized(modelStateLock) {
            if (switchRequest != null) {
                check(modelSelectionVersion == switchRequest.version && pendingModel == switchRequest.model) {
                    "Gemini model switch was superseded before commit"
                }
            }
            chat = candidateChat
            chatFunctionRouteSnapshot = candidateRouteSnapshot
            savedHistory = candidateHistory
            sdkSessionConfig = candidateConfig
            if (switchRequest != null) {
                currentModel = switchRequest.model
                pendingModel = null
                pendingModelSwitchJob = null
            }
        }
    }

    private fun abandonModelSwitch(request: ModelSwitchRequest) {
        synchronized(modelStateLock) {
            if (modelSelectionVersion == request.version && pendingModel == request.model) {
                pendingModel = null
                pendingModelSwitchJob = null
            }
        }
    }

    /**
     * 发送文本消息并获取回复。
     *
     * 此挂起函数会与会话重置串行执行，取消时会取消正在进行的模型调用。若前一回合因工具调用超限而正在
     * 恢复，本调用会在不持有会话锁的情况下等待；只有恢复正常提交新会话后才发送。恢复失败、取消或无法
     * 启动时会拒绝发送；取消本调用不会取消恢复任务。
     *
     * @param text 要发送的文本消息；空字符串会作为空文本部分发送。
     * @return Gemini 的回复文本；模型未提供文本时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或无法建立 Gemini 会话时抛出。
     * @throws ToolCallLimitExceededException 当连续工具调用达到上限时抛出。
     * @throws AgentTurnFailedException 当单个回合或本地历史超过资源上限且本轮未提交时抛出。
     */
    override suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * 此挂起函数会与会话重置串行执行，取消时会取消正在进行的模型调用。若前一回合因工具调用超限而正在
     * 恢复，本调用会在不持有会话锁的情况下等待；只有恢复正常提交新会话后才发送。恢复失败、取消或无法
     * 启动时会拒绝发送；取消本调用不会取消恢复任务。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空，元素会作为 Gemini 内联数据发送。
     * @return Gemini 的回复文本；模型未提供文本时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或无法建立 Gemini 会话时抛出。
     * @throws ToolCallLimitExceededException 当连续工具调用达到上限时抛出。
     * @throws AgentTurnFailedException 当单个回合或本地历史超过资源上限且本轮未提交时抛出。
     */
    override suspend fun sendMessage(
        text: String?,
        mediaData: List<MediaData>,
    ): String {
        validateGeminiInput(text, mediaData)
        if (rawTransport != null) {
            return sendRawMessage(text, mediaData)
        }
        val audioParts = mediaData.map {
            Part.builder().inlineData(
                Blob.builder().mimeType(it.mimeType).data(it.data).build()
            ).build()
        }
        return sendMessageWithParts(text, audioParts)
    }

    /** 发送准入结果；恢复等待必须发生在 [sessionMutex] 之外。 */
    private sealed interface SendAdmission {
        data object Ready : SendAdmission

        data object Unavailable : SendAdmission

        data class AwaitReset(val job: Job) : SendAdmission

        data class AwaitRecovery(val recovery: ToolLimitRecovery) : SendAdmission
    }

    /**
     * 等待工具超限恢复或首轮会话创建，并在返回前确认当前路径存在可用会话。
     *
     * 每次等待结束后都会重新取得 [sessionMutex] 并按恢复身份复核，避免旧恢复任务清除或替代更新的恢复。
     */
    private suspend fun awaitSendAdmission(sessionAvailable: () -> Boolean) {
        while (true) {
            when (val admission = sessionMutex.withLock {
                check(!closed) { "Gemini client is closed." }
                pendingToolLimitRecovery?.let(SendAdmission::AwaitRecovery)
                    ?: if (sessionAvailable()) {
                        SendAdmission.Ready
                    } else {
                        resetSessionJob?.let(SendAdmission::AwaitReset) ?: SendAdmission.Unavailable
                    }
            }) {
                SendAdmission.Ready -> return
                SendAdmission.Unavailable -> throw IllegalStateException("Gemini chat session is not initialized.")
                is SendAdmission.AwaitReset -> {
                    admission.job.join()
                    if (admission.job.isCancelled) {
                        throw IllegalStateException("Gemini chat session is not initialized.")
                    }
                    val sessionEstablished = sessionMutex.withLock {
                        check(!closed) { "Gemini client is closed." }
                        sessionAvailable()
                    }
                    if (!sessionEstablished) {
                        throw IllegalStateException("Gemini chat session is not initialized.")
                    }
                }

                is SendAdmission.AwaitRecovery -> awaitToolLimitRecovery(admission.recovery)
            }
        }
    }

    /**
     * 在不持有 [sessionMutex] 的情况下等待指定恢复身份，并只清除仍为当前身份的成功恢复。
     */
    private suspend fun awaitToolLimitRecovery(recovery: ToolLimitRecovery) {
        val recoveryJob = recovery.job
            ?: throw IllegalStateException("Gemini tool-limit recovery could not be started.")
        recoveryJob.join()
        sessionMutex.withLock {
            check(!closed) { "Gemini client is closed." }
            if (pendingToolLimitRecovery !== recovery) {
                return
            }
            if (recoveryJob.isCancelled) {
                throw IllegalStateException("Gemini tool-limit recovery did not complete.")
            }
            pendingToolLimitRecovery = null
        }
    }

    /**
     * 在会话锁内发布工具调用超限的恢复身份。
     *
     * 候选重置会在锁释放后取得 [sessionMutex]；这里绝不等待它，避免恢复路径与发送路径互锁。
     */
    private fun startToolLimitRecoveryLocked() {
        val recovery = ToolLimitRecovery(resetSession())
        pendingToolLimitRecovery = recovery
        resetSessionJob = recovery.job
    }

    /** 使用原生 REST 传输完成一个 Gemini 回合，并只在最终回答成功时提交历史。 */
    private suspend fun sendRawMessage(text: String?, mediaData: List<MediaData>): String {
        while (true) {
            awaitSendAdmission { rawSession != null }
            val result = sessionMutex.withLock {
                check(!closed) { "Gemini client is closed." }
                if (pendingToolLimitRecovery != null || rawSession == null) {
                    null
                } else {
                    val session = checkNotNull(rawSession)
                    val currentTransport = rawTransport
                        ?: throw IllegalStateException("Gemini HTTP transport is not initialized.")
                    val tentativeHistory = prepareRawGeminiCandidate(session.history)
                    tentativeHistory += createGeminiUserContent(text, mediaData)
                    normalizeRawGeminiCandidate(tentativeHistory, currentRawGeminiTurnStart(tentativeHistory))

                    try {
                        var toolCallRounds = 0
                        var toolCallsExecuted = 0
                        var reply: String? = null
                        while (reply == null) {
                            val candidate = requestGeminiContent(currentTransport, session, tentativeHistory)
                            tentativeHistory += candidate
                            normalizeRawGeminiCandidate(tentativeHistory, currentRawGeminiTurnStart(tentativeHistory))
                            val functionCalls = geminiFunctionCalls(candidate)
                            if (functionCalls.isEmpty()) {
                                val completedReply = candidate["parts"]?.jsonArray
                                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                                    ?.joinToString("")
                                    .orEmpty()
                                currentCoroutineContext().ensureActive()
                                check(!closed && rawSession === session) { "Gemini session was replaced before commit" }
                                rawSession = session.copy(history = tentativeHistory)
                                reply = completedReply
                                continue
                            }

                            ensureToolCallRoundIsAllowed(toolCallRounds++)
                            ensureToolCallCountIsAllowed(functionCalls.size, toolCallsExecuted)
                            toolCallsExecuted += functionCalls.size
                            val responses = buildJsonArray {
                                functionCalls.forEach { functionCall ->
                                    add(createGeminiFunctionResponse(functionCall, session.functionRouteSnapshot))
                                }
                            }
                            tentativeHistory += buildJsonObject {
                                put("role", "user")
                                put("parts", responses)
                            }
                            normalizeRawGeminiCandidate(tentativeHistory, currentRawGeminiTurnStart(tentativeHistory))
                        }
                        reply
                    } catch (e: ToolCallLimitExceededException) {
                        logger.error(
                            "Gemini raw session reached the tool call limit; category={}",
                            SafeLogging.failureCategory(e).wireName,
                        )
                        startToolLimitRecoveryLocked()
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(
                            "Gemini raw message processing failed; category={}",
                            SafeLogging.failureCategory(e).wireName
                        )
                        throw e
                    }
                }
            }
            if (result != null) {
                return result
            }
        }
    }

    /** 将文本与内联媒体转换为 Gemini REST 的用户内容。 */
    private fun createGeminiUserContent(text: String?, mediaData: List<MediaData>): JsonObject = buildJsonObject {
        put("role", "user")
        put("parts", buildJsonArray {
            text?.let { add(buildJsonObject { put("text", it) }) }
            mediaData.forEach { media ->
                add(buildJsonObject {
                    put("inlineData", buildJsonObject {
                        put("mimeType", media.mimeType)
                        put("data", java.util.Base64.getEncoder().encodeToString(media.data))
                    })
                })
            }
        })
    }

    private fun validateGeminiInput(text: String?, mediaData: List<MediaData>) {
        require((text ?: "").toByteArray(StandardCharsets.UTF_8).size <= MAX_AGENT_TEXT_BYTES) {
            "消息文本超过本地上下文限制。"
        }
        var mediaBytes = 0
        mediaData.forEach { media ->
            if (media.data.size > MAX_AGENT_INLINE_MEDIA_BYTES || mediaBytes > MAX_AGENT_INLINE_MEDIA_BYTES - media.data.size) {
                throw AgentTurnFailedException("内联媒体超过本地上下文限制。")
            }
            mediaBytes += media.data.size
        }
    }

    /** 复制已提交的 REST 历史，并以完整回合为单位预留下一回合的空间。 */
    private fun prepareRawGeminiCandidate(history: List<JsonObject>): MutableList<JsonObject> {
        val candidate = history.toMutableList()
        normalizeRawGeminiCandidate(
            candidate,
            currentTurnStart = null,
            maxEntries = MAX_AGENT_HISTORY_ENTRIES - 1,
            maxBytes = MAX_AGENT_HISTORY_BYTES - MAX_AGENT_TURN_RESERVATION_BYTES,
        )
        return candidate
    }

    /** 仅 normal user 内容开启回合；携带 functionResponse 的 user 内容属于该回合。 */
    private fun JsonObject.isNormalGeminiUserTurnStart(): Boolean =
        this["role"]?.jsonPrimitive?.contentOrNull == "user" &&
                this["parts"]?.jsonArray?.none { part -> part.jsonObject["functionResponse"] != null } != false

    /** 将最早已完成的 REST 回合整体移除，避免遗留 functionCall/functionResponse 配对的一侧。 */
    private fun normalizeRawGeminiCandidate(
        candidate: MutableList<JsonObject>,
        currentTurnStart: Int?,
        maxEntries: Int = MAX_AGENT_HISTORY_ENTRIES,
        maxBytes: Int = MAX_AGENT_HISTORY_BYTES,
    ) {
        while (candidate.size > maxEntries || rawGeminiHistoryBytes(candidate) > maxBytes) {
            val protectedTurnStart = currentTurnStart?.let { currentRawGeminiTurnStart(candidate) }
            val firstHistoricalTurn = candidate.indexOfFirst { it.isNormalGeminiUserTurnStart() }
            if (firstHistoricalTurn < 0 ||
                (protectedTurnStart != null && firstHistoricalTurn >= protectedTurnStart)
            ) {
                throw AgentTurnFailedException("AI 会话历史超过资源上限。")
            }
            val turnEnd = candidate.subList(firstHistoricalTurn + 1, candidate.size)
                .indexOfFirst { it.isNormalGeminiUserTurnStart() }
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

    /**
     * 根据当前设置和工具路由构造可重建的 SDK 会话配置。
     *
     * 此配置既用于重置候选会话，也用于迁移历史遗留的无配置 Chat；调用方只有在候选回合成功后才能发布它。
     */
    private fun createSdkSessionConfig(
        aiSettings: AISettings,
        functionRouteSnapshot: LocalFunctionRouteSnapshot,
    ): GenerateContentConfig {
        val configBuilder = GenerateContentConfig.builder()
        val skillPrompt = getSkillPrompt(skillRepository.getApprovedSkillSummaries())
        val systemInstruction = if (aiSettings.globalContext.isNotBlank()) {
            Content.fromParts(Part.fromText(skillPrompt + aiSettings.globalContext))
        } else {
            Content.fromParts(Part.fromText(skillPrompt))
        }
        configBuilder.systemInstruction(systemInstruction)
        val functionDeclarations = functionRouteSnapshot.providedFunctions()
        if (functionDeclarations.isNotEmpty()) {
            configBuilder.tools(listOf(Tool.builder().functionDeclarations(functionDeclarations).build()))
        }
        return configBuilder.build()
    }

    private fun currentRawGeminiTurnStart(candidate: List<JsonObject>): Int =
        candidate.indexOfLast { it.isNormalGeminiUserTurnStart() }.takeIf { it >= 0 }
            ?: throw AgentTurnFailedException("AI 会话历史缺少当前用户消息。")

    private fun rawGeminiHistoryBytes(history: List<JsonObject>): Int =
        wireJson.encodeToString(JsonArray(history)).toByteArray(StandardCharsets.UTF_8).size

    /** 复制已提交的 SDK 历史，并在创建候选 Chat 前按完整回合预留空间。 */
    private fun prepareSdkGeminiHistory(history: List<Content>): List<Content> {
        val candidate = history.toMutableList()
        normalizeSdkGeminiCandidate(
            candidate,
            currentTurnStart = null,
            maxEntries = MAX_AGENT_HISTORY_ENTRIES - 1,
            maxBytes = MAX_AGENT_HISTORY_BYTES - MAX_AGENT_TURN_RESERVATION_BYTES,
        )
        return candidate
    }

    /** SDK 中携带 functionResponse 的 user Content 是当前工具回合的一部分，不是下一回合的开头。 */
    private fun Content.isNormalSdkGeminiUserTurnStart(): Boolean =
        role().getOrNull() == "user" && parts().getOrNull()?.none { it.functionResponse().isPresent } != false

    /** 对 SDK 历史整体删除最早完整回合，保证 functionCall 与 functionResponse 始终同在或同删。 */
    private fun normalizeSdkGeminiCandidate(
        candidate: MutableList<Content>,
        currentTurnStart: Int?,
        maxEntries: Int = MAX_AGENT_HISTORY_ENTRIES,
        maxBytes: Int = MAX_AGENT_HISTORY_BYTES,
    ) {
        while (candidate.size > maxEntries || sdkGeminiHistoryBytes(candidate) > maxBytes) {
            val protectedTurnStart = currentTurnStart?.let { currentSdkGeminiTurnStart(candidate) }
            val firstHistoricalTurn = candidate.indexOfFirst { it.isNormalSdkGeminiUserTurnStart() }
            if (firstHistoricalTurn < 0 ||
                (protectedTurnStart != null && firstHistoricalTurn >= protectedTurnStart)
            ) {
                throw AgentTurnFailedException("AI 会话历史超过资源上限。")
            }
            val turnEnd = candidate.subList(firstHistoricalTurn + 1, candidate.size)
                .indexOfFirst { it.isNormalSdkGeminiUserTurnStart() }
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

    private fun currentSdkGeminiTurnStart(candidate: List<Content>): Int =
        candidate.indexOfLast { it.isNormalSdkGeminiUserTurnStart() }.takeIf { it >= 0 }
            ?: throw AgentTurnFailedException("AI 会话历史缺少当前用户消息。")

    private fun sdkGeminiHistoryBytes(history: List<Content>): Int = history.sumOf { content ->
        JsonSerializable.toJsonString(content).toByteArray(StandardCharsets.UTF_8).size
    }

    /** 发起一次 Gemini `generateContent` 调用并返回首个候选内容。 */
    private suspend fun requestGeminiContent(
        transport: CancellableOkHttpTransport,
        session: RawGeminiSession,
        contents: List<JsonObject>,
    ): JsonObject {
        val apiKey = rawApiKey ?: throw IllegalStateException("Gemini API key is not initialized.")
        val baseUrl = rawBaseUrl ?: throw IllegalStateException("Gemini base URL is not initialized.")
        val model = session.model.removePrefix("models/")
        val url = "$baseUrl/models/$model:generateContent".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .build()
        val requestBody = buildJsonObject {
            put("contents", JsonArray(contents))
            session.config.forEach { (key, value) -> put(key, value) }
        }
        val response = transport.execute(
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(wireJson.encodeToString(JsonObject.serializer(), requestBody).toRequestBodyJson())
                .build(),
        )
        requireGeminiSuccess(response)
        val root = wireJson.parseToJsonElement(response.body).jsonObject
        return root["candidates"]?.jsonArray
            ?.firstOrNull()
            ?.jsonObject
            ?.get("content")
            ?.jsonObject
            ?: throw IllegalStateException("Gemini response did not contain a candidate content.")
    }

    /** 抽取候选中的函数调用，保留服务器返回的调用标识。 */
    private fun geminiFunctionCalls(content: JsonObject): List<JsonObject> = content["parts"]?.jsonArray
        ?.mapNotNull { it.jsonObject["functionCall"]?.jsonObject }
        .orEmpty()

    /** 执行一个 Gemini 函数调用，并构造对应的协议函数响应。 */
    private suspend fun createGeminiFunctionResponse(
        functionCall: JsonObject,
        routeSnapshot: LocalFunctionRouteSnapshot,
    ): JsonObject {
        val name = functionCall["name"]?.jsonPrimitive?.contentOrNull
        val args = functionCall["args"] as? JsonObject
        val result = try {
            when {
                name.isNullOrBlank() -> buildJsonObject { put("error", "Function call name is missing") }
                args == null -> buildJsonObject { put("error", "Function $name arguments are invalid") }
                args.toString().toByteArray(StandardCharsets.UTF_8).size > MAX_MCP_TOOL_ARGUMENT_BYTES ->
                    buildJsonObject { put("error", "tool_arguments_too_large") }

                !routeSnapshot.canHandle(name) -> buildJsonObject { put("error", "Function $name not found") }
                else -> routeSnapshot.execute(name, args.toMap())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Gemini function call failed with a safe local error category.")
            buildJsonObject { put("error", "tool_execution_failed") }
        }
        return buildJsonObject {
            put("functionResponse", buildJsonObject {
                put("name", name.orEmpty())
                functionCall["id"]?.let { put("id", it) }
                put("response", result)
            })
        }
    }

    /** 构建 Gemini REST 请求中允许出现的顶层配置字段。 */
    private fun createGeminiWireConfig(
        aiSettings: AISettings,
        routeSnapshot: LocalFunctionRouteSnapshot,
    ): JsonObject = buildJsonObject {
        val skillPrompt = getSkillPrompt(skillRepository.getApprovedSkillSummaries())
        val instruction = skillPrompt + aiSettings.globalContext
        if (instruction.isNotBlank()) {
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", instruction) }) })
            })
        }
        val declarations = routeSnapshot.providedFunctions()
        if (declarations.isNotEmpty()) {
            put("tools", buildJsonArray {
                add(buildJsonObject {
                    put("functionDeclarations", JsonArray(declarations.map(::geminiFunctionDeclarationJson)))
                })
            })
        }
    }

    /** 显式转换一个函数声明，避免把 SDK 的完整配置对象直接序列化为 REST 请求。 */
    private fun geminiFunctionDeclarationJson(declaration: FunctionDeclaration): JsonObject = buildJsonObject {
        declaration.name().getOrNull()?.let { put("name", it) }
        declaration.description().getOrNull()?.let { put("description", it) }
        declaration.parameters().getOrNull()?.let { put("parameters", geminiSchemaJson(it)) }
        declaration.response().getOrNull()?.let { put("response", geminiSchemaJson(it)) }
    }

    /** 显式转换 Gemini 工具 JSON Schema，完整保留 SDK 已公开的约束、组合和展示字段。 */
    private fun geminiSchemaJson(schema: Schema): JsonObject = buildJsonObject {
        schema.anyOf().getOrNull()?.let { variants -> put("anyOf", JsonArray(variants.map(::geminiSchemaJson))) }
        schema.default_().getOrNull()?.let { put("default", geminiSchemaValueJson(it)) }
        schema.type().getOrNull()?.let { put("type", it.toString()) }
        schema.description().getOrNull()?.let { put("description", it) }
        schema.example().getOrNull()?.let { put("example", geminiSchemaValueJson(it)) }
        schema.format().getOrNull()?.let { put("format", it) }
        schema.maxItems().getOrNull()?.let { put("maxItems", it) }
        schema.maxLength().getOrNull()?.let { put("maxLength", it) }
        schema.maxProperties().getOrNull()?.let { put("maxProperties", it) }
        schema.maximum().getOrNull()?.let { put("maximum", it) }
        schema.minItems().getOrNull()?.let { put("minItems", it) }
        schema.minLength().getOrNull()?.let { put("minLength", it) }
        schema.minProperties().getOrNull()?.let { put("minProperties", it) }
        schema.minimum().getOrNull()?.let { put("minimum", it) }
        schema.nullable().getOrNull()?.let { put("nullable", it) }
        schema.pattern().getOrNull()?.let { put("pattern", it) }
        schema.propertyOrdering().getOrNull()?.let { ordering ->
            put("propertyOrdering", JsonArray(ordering.map(::JsonPrimitive)))
        }
        schema.required().getOrNull()?.let { required -> put("required", JsonArray(required.map(::JsonPrimitive))) }
        schema.enum_().getOrNull()?.let { values -> put("enum", JsonArray(values.map(::JsonPrimitive))) }
        schema.items().getOrNull()?.let { put("items", geminiSchemaJson(it)) }
        schema.properties().getOrNull()?.let { properties ->
            put("properties", buildJsonObject {
                properties.forEach { (name, property) -> put(name, geminiSchemaJson(property)) }
            })
        }
        schema.title().getOrNull()?.let { put("title", it) }
    }

    /**
     * 将 Schema 的自由默认值或示例值转换为 JSON，并在值不能被安全编码时明确失败而不是静默丢弃约束。
     */
    private fun geminiSchemaValueJson(value: Any): JsonElement =
        when (value) {
            is JsonElement -> value
            else -> try {
                wireJson.parseToJsonElement(JsonSerializable.toJsonString(value))
            } catch (e: Exception) {
                throw IllegalArgumentException("Gemini Schema value cannot be represented as JSON.", e)
            }
        }

    /** 刷新 Gemini 模型列表并只接受服务声明的名称。 */
    private suspend fun listRawModels(transport: CancellableOkHttpTransport): List<String> {
        val apiKey = rawApiKey ?: throw IllegalStateException("Gemini API key is not initialized.")
        val baseUrl = rawBaseUrl ?: throw IllegalStateException("Gemini base URL is not initialized.")
        val response = transport.execute(
            Request.Builder()
                .url("$baseUrl/models".toHttpUrl().newBuilder().addQueryParameter("key", apiKey).build())
                .get()
                .build(),
        )
        requireGeminiSuccess(response)
        return wireJson.parseToJsonElement(response.body).jsonObject["models"]?.jsonArray
            ?.mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    }

    /** 将 HTTP 失败转换为携带有限响应正文的异常，避免把失败结果当作模型协议解析。 */
    private fun requireGeminiSuccess(response: HttpResult) {
        if (response.statusCode !in 200..299) {
            throw IllegalStateException("Gemini API returned HTTP ${response.statusCode}: ${response.body.take(1024)}")
        }
    }

    private fun String.toRequestBodyJson() = toRequestBody("application/json; charset=utf-8".toMediaType())

    /**
     * 发送包含 Gemini Part 的消息。
     */
    private suspend fun sendMessageWithParts(
        text: String?,
        audioParts: List<Part>,
    ): String {
        while (true) {
            awaitSendAdmission { chat != null }
            val result = sessionMutex.withLock {
                check(!closed) { "Gemini client is closed." }
                if (pendingToolLimitRecovery != null || chat == null) {
                    null
                } else {
                    try {
                        val activeChat = checkNotNull(chat) to checkNotNull(chatFunctionRouteSnapshot)
                        val parts = mutableListOf<Part>()
                        text?.let { parts.add(Part.fromText(it)) }
                        parts.addAll(audioParts)

                        val userContent = Content.builder().role("user").parts(parts).build()
                        val config = sdkSessionConfig ?: run {
                            val aiSettings = settingsRepository.settingsFlow.value.ai
                                ?: throw AgentTurnFailedException("Gemini SDK 会话缺少可重建的配置。")
                            createSdkSessionConfig(aiSettings, activeChat.second)
                        }
                        val currentClient = checkNotNull(client) { "Gemini client is not initialized." }
                        val persistedHistory = savedHistory ?: activeChat.first.getHistory(true)
                        val candidate = completeSdkCandidateTurn(
                            currentClient,
                            config,
                            prepareSdkGeminiHistory(persistedHistory),
                            userContent,
                            activeChat.second,
                        )
                        check(chat === activeChat.first) { "Gemini session was replaced before commit" }
                        chat = candidate.chat
                        savedHistory = candidate.history
                        sdkSessionConfig = config
                        candidate.reply
                    } catch (e: ToolCallLimitExceededException) {
                        logger.error(
                            "Gemini SDK session reached the tool call limit; category={}",
                            SafeLogging.failureCategory(e).wireName,
                        )
                        startToolLimitRecoveryLocked()
                        throw e
                    } catch (e: Exception) {
                        logger.error(
                            "Gemini SDK message processing failed; category={}",
                            SafeLogging.failureCategory(e).wireName
                        )
                        throw e
                    }
                }
            }
            if (result != null) {
                return result
            }
        }
    }

    /** 候选 SDK Chat 的最终回复与已按预算规范化的完整本地历史。 */
    private data class SdkCandidateTurnResult(
        val reply: String,
        val chat: Chat,
        val history: List<Content>,
    )

    /**
     * 在独立候选 Chat 中完成一个 SDK 回合。
     *
     * 每次模型或工具结果使候选超出硬上限时，下次请求都会以裁剪后的旧完整回合与当前部分回合重建 Chat，
     * 而不会继续使用 SDK 无法删除历史的旧 Chat。
     */
    private suspend fun completeSdkCandidateTurn(
        currentClient: Client,
        config: GenerateContentConfig,
        persistedHistory: List<Content>,
        userContent: Content,
        functionRouteSnapshot: LocalFunctionRouteSnapshot,
    ): SdkCandidateTurnResult {
        var historical = persistedHistory.toMutableList()
        var currentTurn = mutableListOf(userContent)
        var toolCallRounds = 0
        var toolCallsExecuted = 0

        while (true) {
            val requestHistory = (historical + currentTurn).toMutableList()
            normalizeSdkGeminiCandidate(requestHistory, currentSdkGeminiTurnStart(requestHistory))
            val currentTurnStart = currentSdkGeminiTurnStart(requestHistory)
            historical = requestHistory.subList(0, currentTurnStart).toMutableList()
            currentTurn = requestHistory.subList(currentTurnStart, requestHistory.size).toMutableList()

            val candidateChat = currentClient.chats.create(currentModel, config)
            val response = withContext(Dispatchers.IO) {
                candidateChat.sendMessage(requestHistory)
            }
            val modelContent = response.candidates().getOrNull()
                ?.firstOrNull()
                ?.content()
                ?.getOrNull()
                ?: throw AgentTurnFailedException("Gemini 响应未包含模型内容。")
            currentTurn += modelContent

            val completed = (historical + currentTurn).toMutableList()
            normalizeSdkGeminiCandidate(completed, currentSdkGeminiTurnStart(completed))
            val normalizedCurrentTurnStart = currentSdkGeminiTurnStart(completed)
            historical = completed.subList(0, normalizedCurrentTurnStart).toMutableList()
            currentTurn = completed.subList(normalizedCurrentTurnStart, completed.size).toMutableList()

            val functionCalls = response.functionCalls()
            if (functionCalls.isNullOrEmpty()) {
                return SdkCandidateTurnResult(response.text() ?: "", candidateChat, completed)
            }

            ensureToolCallRoundIsAllowed(toolCallRounds++)
            ensureToolCallCountIsAllowed(functionCalls.size, toolCallsExecuted)
            toolCallsExecuted += functionCalls.size
            val functionResponses = functionCalls.map { functionCall ->
                createSdkFunctionResponse(functionCall, functionRouteSnapshot)
            }
            currentTurn += Content.builder().role("user").parts(functionResponses).build()

            val afterToolResults = (historical + currentTurn).toMutableList()
            normalizeSdkGeminiCandidate(afterToolResults, currentSdkGeminiTurnStart(afterToolResults))
            val afterToolCurrentStart = currentSdkGeminiTurnStart(afterToolResults)
            historical = afterToolResults.subList(0, afterToolCurrentStart).toMutableList()
            currentTurn = afterToolResults.subList(afterToolCurrentStart, afterToolResults.size).toMutableList()
        }
    }

    /** 执行一个 SDK 函数调用，并将可恢复错误转换为安全的函数响应。 */
    private suspend fun createSdkFunctionResponse(
        functionCall: FunctionCall,
        functionRouteSnapshot: LocalFunctionRouteSnapshot,
    ): Part {
        val fullName = functionCall.name().getOrNull()
        val argsMap = functionCall.args().getOrNull() ?: emptyMap()
        val result = try {
            when {
                fullName == null -> buildJsonObject {
                    put("error", "Function call name is missing")
                }

                !functionRouteSnapshot.canHandle(fullName) -> buildJsonObject {
                    put("error", "Function $fullName not found")
                }

                else -> functionRouteSnapshot.execute(fullName, argsMap)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Gemini SDK candidate function call failed with a safe local error category.")
            buildJsonObject { put("error", "tool_execution_failed") }
        }
        return createFunctionResponsePart(functionCall, result)
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
        settingsRepository.updateSettings { settings ->
            val aiSettings = settings.ai
            if (
                aiSettings?.provider == AIProvider.GEMINI &&
                aiSettings.geminiApiKey == configuredApiKey &&
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
     * 关闭 Gemini 原生 HTTP 传输、会话任务与 MCP 连接。
     *
     * 重复调用会返回同一个等待任务；若调用方取消此前返回的等待任务，后续调用会提供新的等待任务，
     * 而不会取消已启动的资源清理。
     *
     * @return 异步关闭任务；等待该任务完成后不再保留 Gemini HTTP 传输和 MCP 连接。
     */
    override fun close(): Job = synchronized(lifecycleLock) {
        val completion = closeCompletion ?: CompletableDeferred<Unit>().also { newCompletion ->
            closed = true
            // 先取消实际 HTTP Call；不要等待 sessionMutex，否则超时请求会阻塞关闭路径。
            rawTransport?.close()
            initialModelUpdateJob?.cancel()
            initialModelUpdateJob = null
            resetSessionJob?.cancel()
            serviceJob.cancel()
            closeCompletion = newCompletion
            closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    try {
                        try {
                            val currentClient = sessionMutex.withLock {
                                val clientToClose = client
                                client = null
                                rawTransport = null
                                rawSession = null
                                chat = null
                                savedHistory = null
                                clientToClose
                            }
                            serviceJob.join()
                            currentClient?.close()
                            httpCallingFunctionProvider.close()
                        } finally {
                            mcpClientService.close().join()
                        }
                    } catch (e: Exception) {
                        logger.error(
                            "Failed to close Gemini agent resources; category={}",
                            SafeLogging.failureCategory(e).wireName,
                        )
                    } finally {
                        newCompletion.complete(Unit)
                    }
                }
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
}
