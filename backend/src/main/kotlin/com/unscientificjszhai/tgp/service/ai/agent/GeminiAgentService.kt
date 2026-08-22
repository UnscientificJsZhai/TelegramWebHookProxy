package com.unscientificjszhai.tgp.service.ai.agent

import com.google.genai.Chat
import com.google.genai.Client
import com.google.genai.JsonSerializable
import com.google.genai.Models
import com.google.genai.types.*
import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.models.ProxyType
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
 * 基于 Gemini API 维护对话会话并执行模型工具调用的 AI 代理服务。
 *
 * 构造器只恢复本地模型选择；[initializeForPublication] 才根据当前设置创建 Gemini 原生可取消 HTTP
 * 传输、同步 MCP 工具和技能提示词并发现模型。调用 [close] 返回的任务完成后，服务持有的 HTTP 传输与
 * MCP 连接均已释放。
 *
 * @param parentScope 服务任务所属的父协程作用域。
 * @param settingsRepository 提供 Gemini、MCP 和代理设置的仓库。
 * @param skillRepository 提供会话系统提示词所需技能摘要的仓库。
 * @param mcpClientService 管理会话可调用的 MCP 工具连接。
 * @param taskSchedulerServiceProvider 延迟提供定时任务调度服务，以避免初始化循环依赖。
 * @param deadlines 限制候选初始化、模型发现及其 MCP 批次的总体执行时间。
 */
@AgentScope
class GeminiAgentService @Inject internal constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val skillRepository: SkillRepository,
    private val mcpClientService: MCPClientService,
    private val deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
) : ProviderAgentService() {
    /** 仅供模拟 HTTP 服务测试覆盖固定 Gemini 根地址；生产构造器始终保持为 `null`。 */
    private var baseUrlOverrideForTesting: String? = null

    internal constructor(
        parentScope: CoroutineScope,
        settingsRepository: SettingsRepository,
        skillRepository: SkillRepository,
        mcpClientService: MCPClientService,
        deadlines: AgentExecutionDeadlines,
        taskSchedulerServiceProvider: Provider<TaskSchedulerService>,
        baseUrlOverrideForTesting: String,
    ) : this(
        parentScope,
        settingsRepository,
        skillRepository,
        mcpClientService,
        deadlines,
        taskSchedulerServiceProvider,
    ) {
        this.baseUrlOverrideForTesting = baseUrlOverrideForTesting
    }

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

    /**
     * 一次会话候选重置的全序身份。
     *
     * 代次在启动协程前分配；较早候选即使较晚取得 [sessionMutex]，也不能提交并覆盖较新的重置请求。
     */
    private data class ResetAttempt(val generation: Long)

    /**
     * 只保护 [nextResetGeneration] 与 [latestRequestedResetGeneration] 的短临界区，不与会话 I/O 共用锁。
     */
    private val resetAttemptLock = Any()
    private var nextResetGeneration = 0L

    @Volatile
    private var latestRequestedResetGeneration = 0L

    /** 仅在 [sessionMutex] 保护下访问；记录最后成功提交会话的重置代次。 */
    private var lastCommittedResetGeneration = 0L

    /**
     * 工具调用超限后创建的恢复身份。
     *
     * 该身份只在 [sessionMutex] 保护下发布和清除。即使候选重置无法启动，仍会发布 `job == null` 的
     * 身份，使后续发送拒绝使用旧会话而不是降级放行。
     */
    private data class ToolLimitRecovery(
        val attempt: ResetAttempt,
        val job: Job?,
    )

    /** 仅在 [sessionMutex] 保护下访问；非空时后续发送必须等待或拒绝。 */
    private var pendingToolLimitRecovery: ToolLimitRecovery? = null

    private val modelUpdateMutex = Mutex()
    private val modelStateLock = Any()
    private var modelSelectionVersion = 0L

    /** 仅在 [modelStateLock] 保护下访问；非空时表示尚未提交的最新模型选择。 */
    private var pendingModel: String? = null

    /** 仅在 [modelStateLock] 保护下访问；表示 [pendingModel] 对应的候选会话任务。 */
    private var pendingModelSwitchJob: Job? = null
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
        val aiSettings = settingsRepository.settingsFlow.value.ai
        if (aiSettings?.provider == AIProvider.GEMINI) {
            restoreSelectedModel(aiSettings.selectedModel)
        }
    }

    /**
     * 显式建立首轮 Gemini 会话、MCP 工具快照和模型列表。
     *
     * 构造器不执行网络操作。委派恢复控制器每次创建全新实例并仅调用本方法一次；失败原因由
     * [ProviderAgentService] 转为脱敏的 [AgentInitializationResult.Failed]。
     */
    override suspend fun performPublicationInitialization() {
        withTimeout(deadlines.candidateInitialization) {
            val settings = settingsRepository.settingsFlow.value
            val aiSettings = settings.ai
                ?.takeIf { it.provider == AIProvider.GEMINI && isAiFeatureEnabled(it) }
                ?: throw IllegalArgumentException("Gemini agent configuration is incomplete.")
            check(!closed) { "Gemini agent is closed." }

            configuredApiKey = aiSettings.geminiApiKey
            configuredProxy = settings.proxy
            rawApiKey = aiSettings.geminiApiKey
            rawBaseUrl = geminiBaseUrl()
            rawTransport = CancellableOkHttpTransport(createGeminiHttpClient(settings.proxy))

            val initialResetJob = resetSession()
            resetSessionJob = initialResetJob
            awaitPublicationJob(initialResetJob)
            val snapshot = updateModelOrThrow()
                ?: throw AgentInvalidResponseException()
            if (snapshot.availableModels.isEmpty()) throw AgentEmptyModelListException()
            if (snapshot.currentModel !in snapshot.availableModels || (chat == null && rawSession == null)) {
                throw AgentInvalidResponseException()
            }
            logger.debug("Gemini candidate completed publication initialization.")
        }
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
        val configured = (baseUrlOverrideForTesting ?: System.getenv("GOOGLE_GEMINI_BASE_URL"))
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
    override suspend fun updateModel(): ModelSnapshot? = try {
        updateModelOrThrow()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.debug("Gemini model update failed; category={}", SafeLogging.failureCategory(e).wireName)
        null
    }

    /** 与公开刷新共享实现，但保留初始化恢复分类所需的真实异常。 */
    private suspend fun updateModelOrThrow(): ModelSnapshot? = modelUpdateMutex.withLock update@{
        if (closed) {
            return@update null
        }
        val selectionVersion = synchronized(modelStateLock) { modelSelectionVersion }
        val currentTransport = rawTransport
        val refreshedModels = try {
            withTimeout(deadlines.geminiModelDiscovery) {
                when {
                    currentTransport != null -> listRawModels(currentTransport)
                    else -> {
                        client?.models?.let { models ->
                            withContext(Dispatchers.IO) { listSdkModels(models) }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentUpstreamHttpException) {
            throw e
        } catch (e: UpstreamResponseTooLargeException) {
            throw e
        } catch (e: java.io.IOException) {
            throw e
        } catch (e: Exception) {
            throw AgentInvalidResponseException(e)
        } ?: return@update null
        if (refreshedModels.isEmpty()) throw AgentEmptyModelListException()
        val refreshResult = synchronized(modelStateLock) {
            if (closed || modelSelectionVersion != selectionVersion) {
                return@update null
            }
            availableModels = refreshedModels
            val invalidModel = currentModel.takeUnless { it in availableModels }
            val fallbackModel = invalidModel?.let { preferredModel(availableModels) }
            val fallbackJob = fallbackModel?.let(::startModelSwitchLocked)
            Triple(fallbackJob, invalidModel, ModelSnapshot(currentModel, availableModels))
        }
        val fallbackJob = refreshResult.first
        if (fallbackJob != null) {
            awaitPublicationJob(fallbackJob)
            refreshResult.second?.let { invalidModel ->
                if (settingsRepository.hasHistoricalInvalidOpenAiBaseUrl) {
                    logger.info("Keeping the persisted Gemini model selection while a historical OpenAI base URL is protected.")
                } else {
                    clearPersistedSelectedModel(invalidModel)
                }
            }
        } else if (refreshResult.second != null) {
            return@update null
        }
        synchronized(modelStateLock) {
            ModelSnapshot(currentModel, availableModels)
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
     * 失败或任务取消都会保留旧会话状态。成功提交的新会话会原子取代任何较早的工具超限恢复屏障；无法
     * 提交的重置不会解除该屏障。
     */
    override fun resetSession(): Job? {
        val attempt = allocateResetAttempt()
        return startResetSession(captureHistory = false, attempt = attempt)
    }

    /**
     * 在启动候选协程前为调用方已经线性化的重置请求保留唯一代次。
     */
    private fun allocateResetAttempt(): ResetAttempt = synchronized(resetAttemptLock) {
        ResetAttempt(++nextResetGeneration).also { attempt ->
            latestRequestedResetGeneration = attempt.generation
        }
    }

    /**
     * 启动已分配 [attempt] 的候选重置。
     *
     * 所有调用方都必须先调用 [allocateResetAttempt]；此处绝不在 [scope] 的协程体内补分配代次，保证调用
     * 次序就是候选提交次序的上界。
     */
    private fun startResetSession(
        captureHistory: Boolean,
        switchRequest: ModelSwitchRequest? = null,
        attempt: ResetAttempt,
    ): Job? {
        if (closed) {
            return null
        }
        if (rawTransport != null) {
            return resetRawSession(captureHistory, switchRequest, attempt)
        }
        val currentClient = client
        if (currentClient == null) {
            logger.warn("Cannot reset session: Gemini client is not initialized.")
            return null
        }

        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return null
        return scope.launch(CoroutineExceptionHandler { _, error ->
            logger.debug("Gemini session reset failed; category={}", SafeLogging.failureCategory(error).wireName)
        }) {
            try {
                sessionMutex.withLock {
                    ensureResetCanCommit(switchRequest, attempt)
                    val candidateHistory = if (captureHistory) {
                        captureHistoryLocked()?.let(::prepareSdkGeminiHistory)
                    } else {
                        null
                    }
                    mcpClientService.connect(aiSettings.mcpServers)
                    currentCoroutineContext().ensureActive()
                    ensureResetCanCommit(switchRequest, attempt)
                    val functionRouteSnapshot = localFunctionRouter.refresh()
                    val candidateModel = switchRequest?.model ?: currentModel
                    val candidateConfig = createSdkSessionConfig(aiSettings, functionRouteSnapshot)
                    val newChat = try {
                        currentClient.chats.create(candidateModel, candidateConfig)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.debug(
                            "Failed to create Gemini chat session; category={}",
                            SafeLogging.failureCategory(e).wireName,
                        )
                        throw e
                    }
                    currentCoroutineContext().ensureActive()
                    ensureResetCanCommit(switchRequest, attempt)

                    // 会话锁和模型锁将会话、路由、待恢复历史和模型的发布线性化；在此之前旧状态始终可用。
                    commitCandidateSession(
                        newChat,
                        functionRouteSnapshot,
                        candidateHistory,
                        candidateConfig,
                        switchRequest,
                        attempt,
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
        attempt: ResetAttempt,
    ): Job? {
        val currentTransport = rawTransport ?: return null
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return null
        return scope.launch(CoroutineExceptionHandler { _, error ->
            logger.debug("Gemini raw session reset failed; category={}", SafeLogging.failureCategory(error).wireName)
        }) {
            try {
                sessionMutex.withLock {
                    ensureResetCanCommit(switchRequest, attempt)
                    val candidateHistory = if (captureHistory) {
                        prepareRawGeminiCandidate(rawSession?.history.orEmpty())
                    } else {
                        emptyList()
                    }
                    mcpClientService.connect(aiSettings.mcpServers)
                    currentCoroutineContext().ensureActive()
                    ensureResetCanCommit(switchRequest, attempt)
                    check(rawTransport === currentTransport) { "Gemini HTTP transport was replaced" }
                    val routeSnapshot = localFunctionRouter.refresh()
                    val model = switchRequest?.model ?: currentModel
                    val candidate = RawGeminiSession(
                        model = model,
                        config = createGeminiWireConfig(aiSettings, routeSnapshot),
                        functionRouteSnapshot = routeSnapshot,
                        history = candidateHistory,
                    )
                    commitRawCandidateSession(candidate, switchRequest, attempt)
                    logger.info("Gemini raw session reset with model: {}", model)
                }
            } catch (e: Throwable) {
                switchRequest?.let(::abandonModelSwitch)
                throw e
            }
        }
    }

    /** 在模型状态锁内提交已完整建立的原生会话候选。 */
    private fun commitRawCandidateSession(
        candidate: RawGeminiSession,
        switchRequest: ModelSwitchRequest?,
        attempt: ResetAttempt,
    ) {
        ensureResetAttemptCanCommitLocked(attempt)
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
        recordCommittedResetAttemptLocked(attempt)
    }

    private suspend fun ensureResetCanCommit(switchRequest: ModelSwitchRequest?, attempt: ResetAttempt) {
        currentCoroutineContext().ensureActive()
        if (closed) {
            throw CancellationException("Gemini agent is closed")
        }
        ensureResetAttemptCanCommitLocked(attempt)
        if (switchRequest != null && !isLatestModelSwitch(switchRequest)) {
            throw CancellationException("Gemini model switch was superseded")
        }
    }

    /**
     * 确认当前持有 [sessionMutex] 的候选仍是最新请求，且不会回退已提交的会话代次。
     */
    private fun ensureResetAttemptCanCommitLocked(attempt: ResetAttempt) {
        check(attempt.generation == latestRequestedResetGeneration) {
            "Gemini session reset was superseded"
        }
        check(attempt.generation > lastCommittedResetGeneration) {
            "Gemini session reset is older than the committed session"
        }
    }

    /**
     * 记录成功发布的候选，并且只移除不晚于它的工具超限恢复屏障。
     *
     * 调用方必须持有 [sessionMutex]。失败或取消候选绝不会经过此处，所以恢复屏障保持 fail-closed。
     */
    private fun recordCommittedResetAttemptLocked(attempt: ResetAttempt) {
        lastCommittedResetGeneration = attempt.generation
        pendingToolLimitRecovery
            ?.takeIf { recovery -> recovery.attempt.generation <= attempt.generation }
            ?.let { pendingToolLimitRecovery = null }
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
        val attempt = allocateResetAttempt()
        val switchJob = startResetSession(captureHistory = true, switchRequest = request, attempt = attempt) ?: run {
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
        attempt: ResetAttempt,
    ) {
        ensureResetAttemptCanCommitLocked(attempt)
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
        recordCommittedResetAttemptLocked(attempt)
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
     * 启动时会拒绝发送；取消本调用不会取消恢复任务。调用 [resetSession] 的新候选只有成功提交后才会取代
     * 较早恢复标记并恢复发送。
     *
     * @param text 要发送的文本消息；空字符串会作为空文本部分发送。
     * @return Gemini 的回复文本；模型未提供文本时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或无法建立 Gemini 会话时抛出。
     * @throws ToolCallLimitExceededException 当连续工具调用达到上限时抛出。
     * @throws AgentTurnFailedException 当单个回合或本地历史超过资源上限、Gemini 未以正常 `STOP` 状态完成，
     * 或本轮未提交时抛出。
     */
    override suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * 此挂起函数会与会话重置串行执行，取消时会取消正在进行的模型调用。若前一回合因工具调用超限而正在
     * 恢复，本调用会在不持有会话锁的情况下等待；只有恢复正常提交新会话后才发送。恢复失败、取消或无法
     * 启动时会拒绝发送；取消本调用不会取消恢复任务。调用 [resetSession] 的新候选只有成功提交后才会取代
     * 较早恢复标记并恢复发送。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空，元素会作为 Gemini 内联数据发送。
     * @return Gemini 的回复文本；模型未提供文本时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或无法建立 Gemini 会话时抛出。
     * @throws ToolCallLimitExceededException 当连续工具调用达到上限时抛出。
     * @throws AgentTurnFailedException 当单个回合或本地历史超过资源上限、Gemini 未以正常 `STOP` 状态完成，
     * 或本轮未提交时抛出。
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
     * 在不持有 [sessionMutex] 的情况下等待指定恢复身份。
     *
     * 恢复标记只能由 [recordCommittedResetAttemptLocked] 随成功候选一起移除；因此任务正常结束而标记仍在
     * 时也保持 fail-closed，防止未提交或过期任务错误放开旧会话。
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
            throw IllegalStateException("Gemini tool-limit recovery did not commit a current session.")
        }
    }

    /**
     * 在工具调用超限后创建恢复屏障并启动对应候选。
     *
     * 发现上限的发送任务在仍持有 [sessionMutex] 时用独立短锁分配代次并发布 `job == null` 的身份；本方法
     * 只在锁释放后启动候选并补入其任务，使任何后续发送都不会抢在恢复屏障前使用旧会话。这里绝不等待候选，
     * 避免恢复路径与发送路径互锁。
     */
    private fun publishToolLimitRecoveryLocked(): ResetAttempt {
        val attempt = allocateResetAttempt()
        pendingToolLimitRecovery = ToolLimitRecovery(attempt, job = null)
        resetSessionJob = null
        return attempt
    }

    /** 在已发布的工具超限标记之后启动候选，并且只回填仍代表该代次的标记。 */
    private suspend fun startPublishedToolLimitRecovery(attempt: ResetAttempt) {
        val recoveryJob = startResetSession(captureHistory = false, attempt = attempt)
        sessionMutex.withLock {
            if (pendingToolLimitRecovery?.attempt == attempt) {
                pendingToolLimitRecovery = ToolLimitRecovery(attempt, recoveryJob)
                resetSessionJob = recoveryJob
            } else if (lastCommittedResetGeneration >= attempt.generation) {
                // 候选已在回填前成功提交并移除了标记；保留任务引用供初始化/诊断调用方观察其完成状态。
                resetSessionJob = recoveryJob
            } else {
                // 较新的人工重置已成功提交；不让已失去身份的恢复继续浪费 MCP/会话资源。
                recoveryJob?.cancel()
            }
        }
    }

    /** 使用原生 REST 传输完成一个 Gemini 回合，并只在最终回答成功时提交历史。 */
    private suspend fun sendRawMessage(text: String?, mediaData: List<MediaData>): String {
        while (true) {
            awaitSendAdmission { rawSession != null }
            var recoveryAttempt: ResetAttempt? = null
            val result: String? = try {
                sessionMutex.withLock {
                    try {
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

                            var toolCallRounds = 0
                            var toolCallsExecuted = 0
                            var reply: String? = null
                            while (reply == null) {
                                val candidate = requestGeminiContent(currentTransport, session, tentativeHistory)
                                tentativeHistory += candidate.content
                                normalizeRawGeminiCandidate(
                                    tentativeHistory,
                                    currentRawGeminiTurnStart(tentativeHistory)
                                )
                                val functionCalls = geminiFunctionCalls(candidate.content)
                                if (functionCalls.isEmpty()) {
                                    val completedReply = candidate.content["parts"]?.jsonArray
                                        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                                        ?.joinToString("")
                                        .orEmpty()
                                    currentCoroutineContext().ensureActive()
                                    check(!closed && rawSession === session) { "Gemini session was replaced before commit" }
                                    rawSession = session.copy(history = tentativeHistory)
                                    reply = completedReply
                                } else {
                                    ensureToolCallRoundIsAllowed(toolCallRounds++)
                                    ensureToolCallCountIsAllowed(functionCalls.size, toolCallsExecuted)
                                    toolCallsExecuted += functionCalls.size
                                    val responses = buildJsonArray {
                                        functionCalls.forEach { functionCall ->
                                            add(
                                                createGeminiFunctionResponse(
                                                    functionCall,
                                                    session.functionRouteSnapshot
                                                )
                                            )
                                        }
                                    }
                                    tentativeHistory += buildJsonObject {
                                        put("role", "user")
                                        put("parts", responses)
                                    }
                                    normalizeRawGeminiCandidate(
                                        tentativeHistory,
                                        currentRawGeminiTurnStart(tentativeHistory)
                                    )
                                }
                            }
                            reply
                        }
                    } catch (e: ToolCallLimitExceededException) {
                        recoveryAttempt = publishToolLimitRecoveryLocked()
                        throw e
                    }
                }
            } catch (e: ToolCallLimitExceededException) {
                logger.error(
                    "Gemini raw session reached tool call limit; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
                startPublishedToolLimitRecovery(checkNotNull(recoveryAttempt))
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
                        put("data", Base64.getEncoder().encodeToString(media.data))
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

    private fun rawGeminiHistoryBytes(history: List<JsonObject>): Int {
        val array = JsonArray(history)
        JsonStructureLimits.validateElement(array)
        return wireJson.encodeToString(array).also(JsonStructureLimits::validateJsonString)
            .toByteArray(StandardCharsets.UTF_8).size
    }

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

    /** 已通过候选终态校验的原生 Gemini 首个候选。 */
    private data class RawGeminiCandidate(
        val content: JsonObject,
        val finishReason: String,
    )

    /** 发起一次 Gemini `generateContent` 调用并返回终态为 `STOP` 的首个候选。 */
    private suspend fun requestGeminiContent(
        transport: CancellableOkHttpTransport,
        session: RawGeminiSession,
        contents: List<JsonObject>,
    ): RawGeminiCandidate {
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
        JsonStructureLimits.validateElement(requestBody)
        val encodedBody = wireJson.encodeToString(JsonObject.serializer(), requestBody)
        JsonStructureLimits.validateJsonString(encodedBody)
        val response = transport.execute(
            Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(encodedBody.toRequestBodyJson())
                .build(),
        )
        requireGeminiSuccess(response)
        val root = JsonStructureLimits.parseToJsonElement(wireJson, response.body).jsonObject
        return requireStoppedRawGeminiCandidate(root)
    }

    /**
     * 只接受首个候选以字符串 `STOP` 终止的原生 Gemini 响应。
     *
     * 该校验在候选内容写入暂存历史、解析函数调用或执行本地工具之前进行；未知、缺失和非字符串终态一律
     * 视为未完成回合，避免部分内容被误提交。
     */
    private fun requireStoppedRawGeminiCandidate(response: JsonObject): RawGeminiCandidate {
        val candidate = (response["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: throw AgentTurnFailedException("Gemini 响应未包含候选。")
        val finishReason = candidate["finishReason"] as? JsonPrimitive
        if (finishReason?.isString != true || finishReason.content != "STOP") {
            throw AgentTurnFailedException("Gemini 响应未以正常 STOP 状态完成。")
        }
        val content = candidate["content"] as? JsonObject
            ?: throw AgentTurnFailedException("Gemini 响应未包含模型内容。")
        return RawGeminiCandidate(content = content, finishReason = finishReason.content)
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

    /** 将 Gemini schema 显式转换为 JSON 时使用的非递归后序工作项。 */
    private sealed interface GeminiSchemaWork {
        data class Visit(
            val schema: Schema,
            val depth: Int,
            val sink: (JsonObject) -> Unit,
        ) : GeminiSchemaWork

        class Finish(
            val fields: LinkedHashMap<String, JsonElement>,
            val anyOf: List<JsonObject?>,
            val items: Array<JsonObject?>,
            val properties: LinkedHashMap<String, JsonObject?>,
            val sink: (JsonObject) -> Unit,
        ) : GeminiSchemaWork
    }

    /** 显式转换 Gemini 工具 JSON Schema，完整保留 SDK 已公开的约束、组合和展示字段。 */
    private fun geminiSchemaJson(schema: Schema): JsonObject {
        var result: JsonObject? = null
        val work = ArrayDeque<GeminiSchemaWork>()
        work.addLast(GeminiSchemaWork.Visit(schema, 1) { result = it })
        var nodes = 0
        while (work.isNotEmpty()) {
            when (val item = work.removeLast()) {
                is GeminiSchemaWork.Visit -> {
                    if (++nodes > JsonStructureLimits.MAX_NODES || item.depth > JsonStructureLimits.MAX_DEPTH) {
                        throw IllegalArgumentException("Gemini 函数 schema 超出 JSON 结构限制。")
                    }
                    val fields = LinkedHashMap<String, JsonElement>()
                    item.schema.default_().getOrNull()?.let { fields["default"] = geminiSchemaValueJson(it) }
                    item.schema.type().getOrNull()?.let { fields["type"] = JsonPrimitive(it.toString()) }
                    item.schema.description().getOrNull()?.let { fields["description"] = JsonPrimitive(it) }
                    item.schema.example().getOrNull()?.let { fields["example"] = geminiSchemaValueJson(it) }
                    item.schema.format().getOrNull()?.let { fields["format"] = JsonPrimitive(it) }
                    item.schema.maxItems().getOrNull()?.let { fields["maxItems"] = JsonPrimitive(it) }
                    item.schema.maxLength().getOrNull()?.let { fields["maxLength"] = JsonPrimitive(it) }
                    item.schema.maxProperties().getOrNull()?.let { fields["maxProperties"] = JsonPrimitive(it) }
                    item.schema.maximum().getOrNull()?.let { fields["maximum"] = JsonPrimitive(it) }
                    item.schema.minItems().getOrNull()?.let { fields["minItems"] = JsonPrimitive(it) }
                    item.schema.minLength().getOrNull()?.let { fields["minLength"] = JsonPrimitive(it) }
                    item.schema.minProperties().getOrNull()?.let { fields["minProperties"] = JsonPrimitive(it) }
                    item.schema.minimum().getOrNull()?.let { fields["minimum"] = JsonPrimitive(it) }
                    item.schema.nullable().getOrNull()?.let { fields["nullable"] = JsonPrimitive(it) }
                    item.schema.pattern().getOrNull()?.let { fields["pattern"] = JsonPrimitive(it) }
                    item.schema.propertyOrdering().getOrNull()?.let { ordering ->
                        ensureSchemaCollectionFitsBudget(ordering.size, nodes)
                        fields["propertyOrdering"] = JsonArray(ordering.map(::JsonPrimitive))
                    }
                    item.schema.required().getOrNull()?.let { required ->
                        ensureSchemaCollectionFitsBudget(required.size, nodes)
                        fields["required"] = JsonArray(required.map(::JsonPrimitive))
                    }
                    item.schema.enum_().getOrNull()?.let { values ->
                        ensureSchemaCollectionFitsBudget(values.size, nodes)
                        fields["enum"] = JsonArray(values.map(::JsonPrimitive))
                    }
                    item.schema.title().getOrNull()?.let { fields["title"] = JsonPrimitive(it) }

                    val variants = item.schema.anyOf().getOrNull().orEmpty()
                    ensureSchemaCollectionFitsBudget(variants.size, nodes)
                    val anyOf = MutableList<JsonObject?>(variants.size) { null }
                    val itemSchema = arrayOfNulls<JsonObject>(1)
                    val properties = LinkedHashMap<String, JsonObject?>()
                    val itemChild = item.schema.items().getOrNull()
                    val propertyChildren = item.schema.properties().getOrNull().orEmpty()
                    ensureSchemaCollectionFitsBudget(propertyChildren.size, nodes)
                    work.addLast(GeminiSchemaWork.Finish(fields, anyOf, itemSchema, properties, item.sink))
                    if (itemChild != null) {
                        work.addLast(GeminiSchemaWork.Visit(itemChild, item.depth + 1) { itemSchema[0] = it })
                    }
                    propertyChildren.forEach { (name, child) ->
                        work.addLast(GeminiSchemaWork.Visit(child, item.depth + 1) { properties[name] = it })
                    }
                    variants.indices.forEach { index ->
                        work.addLast(GeminiSchemaWork.Visit(variants[index], item.depth + 1) { anyOf[index] = it })
                    }
                }

                is GeminiSchemaWork.Finish -> {
                    if (item.anyOf.isNotEmpty()) item.fields["anyOf"] =
                        JsonArray(item.anyOf.map { it ?: error("Missing schema variant") })
                    item.items[0]?.let { item.fields["items"] = it }
                    if (item.properties.isNotEmpty()) {
                        item.fields["properties"] = JsonObject(
                            LinkedHashMap<String, JsonElement>().apply {
                                item.properties.forEach { (name, child) ->
                                    put(
                                        name,
                                        child ?: error("Missing schema property")
                                    )
                                }
                            },
                        )
                    }
                    item.sink(JsonObject(item.fields))
                }
            }
        }
        return requireNotNull(result).also(JsonStructureLimits::validateElement)
    }

    /** 在复制 SDK schema 集合为 JSON 容器前限制其大小，避免超大浅层 schema 先耗尽堆。 */
    private fun ensureSchemaCollectionFitsBudget(size: Int, nodesUsed: Int) {
        if (size < 0 || size > JsonStructureLimits.MAX_NODES - nodesUsed) {
            throw IllegalArgumentException("Gemini 函数 schema 超出 JSON 结构限制。")
        }
    }

    /**
     * 将 Schema 的自由默认值或示例值转换为 JSON，并在值不能被安全编码时明确失败而不是静默丢弃约束。
     */
    private fun geminiSchemaValueJson(value: Any): JsonElement =
        when (value) {
            is JsonElement -> value
            else -> try {
                JsonStructureLimits.parseToJsonElement(wireJson, JsonSerializable.toJsonString(value))
            } catch (e: Exception) {
                throw IllegalArgumentException("Gemini Schema value cannot be represented as JSON.", e)
            }
        }

    /**
     * 刷新 Gemini 模型列表并只接受声明支持 `generateContent` 的非空名称。
     *
     * 原生 API 分页令牌、页数、条目数和重复模型名称均受固定预算限制；任一页失败时不返回部分列表。
     */
    private suspend fun listRawModels(transport: CancellableOkHttpTransport): List<String> {
        val apiKey = rawApiKey ?: throw IllegalStateException("Gemini API key is not initialized.")
        val baseUrl = rawBaseUrl ?: throw IllegalStateException("Gemini base URL is not initialized.")
        val discoveredModels = mutableListOf<String>()
        val discoveredNames = mutableSetOf<String>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null
        var pages = 0
        var entries = 0
        var tokenBytes = 0
        var duplicateNames = 0
        do {
            currentCoroutineContext().ensureActive()
            check(++pages <= MAX_GEMINI_MODEL_DISCOVERY_PAGES) {
                "Gemini 模型发现页数超过限制。"
            }
            val url = "$baseUrl/models".toHttpUrl().newBuilder()
                .addQueryParameter("key", apiKey)
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val response = transport.execute(Request.Builder().url(url).get().build())
            requireGeminiSuccess(response)
            val root = JsonStructureLimits.parseToJsonElement(wireJson, response.body).jsonObject
            val pageModels = root["models"] as? JsonArray
                ?: throw IllegalArgumentException("Gemini 模型列表响应缺少 models 数组。")
            pageModels.forEach { entry ->
                check(++entries <= MAX_GEMINI_MODEL_DISCOVERY_ENTRIES) {
                    "Gemini 模型发现条目超过限制。"
                }
                val model = entry as? JsonObject ?: return@forEach
                val name = (model["name"] as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                    ?.takeIf(String::isNotBlank)
                    ?: return@forEach
                val supportedMethods = model["supportedGenerationMethods"] as? JsonArray
                if (supportedMethods?.any { method ->
                        (method as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content == "generateContent"
                    } != true
                ) {
                    return@forEach
                }
                if (discoveredNames.add(name)) {
                    discoveredModels += name
                } else {
                    check(++duplicateNames <= MAX_GEMINI_MODEL_DISCOVERY_DUPLICATES) {
                        "Gemini 模型发现重复名称超过限制。"
                    }
                }
            }
            pageToken = root["nextPageToken"]?.let { token ->
                val value = (token as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)
                    ?.content
                    ?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("Gemini 模型分页令牌不合法。")
                tokenBytes += value.toByteArray(StandardCharsets.UTF_8).size
                check(tokenBytes <= MAX_GEMINI_MODEL_DISCOVERY_TOKEN_BYTES) {
                    "Gemini 模型分页令牌超过限制。"
                }
                check(seenPageTokens.add(value)) { "Gemini 模型分页令牌重复。" }
                value
            }
        } while (pageToken != null)
        return discoveredModels
    }

    /** 使用 SDK 的显式分页 API 读取支持 `generateContent` 的模型，绝不使用自动迭代器。 */
    private suspend fun listSdkModels(models: Models): List<String> {
        val pager = models.list(ListModelsConfig.builder().build())
        val discoveredModels = mutableListOf<String>()
        val discoveredNames = mutableSetOf<String>()
        var pages = 0
        var entries = 0
        var duplicateNames = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            check(++pages <= MAX_GEMINI_MODEL_DISCOVERY_PAGES) {
                "Gemini 模型发现页数超过限制。"
            }
            pager.page().forEach { model ->
                check(++entries <= MAX_GEMINI_MODEL_DISCOVERY_ENTRIES) {
                    "Gemini 模型发现条目超过限制。"
                }
                val name = model.name().getOrNull()?.takeIf(String::isNotBlank) ?: return@forEach
                if (model.supportedActions().getOrNull()?.any { it == "generateContent" } != true) {
                    return@forEach
                }
                if (discoveredNames.add(name)) {
                    discoveredModels += name
                } else {
                    check(++duplicateNames <= MAX_GEMINI_MODEL_DISCOVERY_DUPLICATES) {
                        "Gemini 模型发现重复名称超过限制。"
                    }
                }
            }
            try {
                pager.nextPage()
            } catch (_: IndexOutOfBoundsException) {
                return discoveredModels
            }
        }
    }

    /** 将 HTTP 失败转换为不保存响应正文或请求信息的公共上游异常。 */
    private fun requireGeminiSuccess(response: HttpResult) {
        if (response.statusCode !in 200..299) {
            throw AgentUpstreamHttpException.fromResponse(response.statusCode, response.headers)
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
            var recoveryAttempt: ResetAttempt? = null
            val result = try {
                sessionMutex.withLock {
                    try {
                        check(!closed) { "Gemini client is closed." }
                        if (pendingToolLimitRecovery != null || chat == null) {
                            null
                        } else {
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
                        }
                    } catch (e: ToolCallLimitExceededException) {
                        recoveryAttempt = publishToolLimitRecoveryLocked()
                        throw e
                    }
                }
            } catch (e: ToolCallLimitExceededException) {
                logger.error(
                    "Gemini SDK session reached tool call limit; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
                startPublishedToolLimitRecovery(checkNotNull(recoveryAttempt))
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(
                    "Gemini SDK message processing failed; category={}",
                    SafeLogging.failureCategory(e).wireName
                )
                throw e
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

    /** 已通过候选终态校验的 SDK 首个候选内容与其原始内容片段。 */
    private data class StoppedSdkGeminiCandidate(
        val content: Content,
        val parts: List<Part>,
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
            val candidate = requireStoppedSdkGeminiCandidate(response)
            currentTurn += candidate.content

            val completed = (historical + currentTurn).toMutableList()
            normalizeSdkGeminiCandidate(completed, currentSdkGeminiTurnStart(completed))
            val normalizedCurrentTurnStart = currentSdkGeminiTurnStart(completed)
            historical = completed.subList(0, normalizedCurrentTurnStart).toMutableList()
            currentTurn = completed.subList(normalizedCurrentTurnStart, completed.size).toMutableList()

            val functionCalls = candidate.parts.mapNotNull { part -> part.functionCall().getOrNull() }
            if (functionCalls.isEmpty()) {
                val reply = candidate.parts.mapNotNull { part -> part.text().getOrNull() }.joinToString("")
                return SdkCandidateTurnResult(reply, candidateChat, completed)
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

    /**
     * 从 SDK 原始首个候选读取已完成的模型内容。
     *
     * `GenerateContentResponse` 的聚合访问器会放宽或转换终态，故此处只读取候选自身的终态、内容和内容
     * 片段。验证必须发生在修改当前回合、归一化历史和执行任意工具之前。
     */
    private fun requireStoppedSdkGeminiCandidate(response: GenerateContentResponse): StoppedSdkGeminiCandidate {
        val candidate = response.candidates().getOrNull()?.firstOrNull()
            ?: throw AgentTurnFailedException("Gemini 响应未包含候选。")
        if (candidate.finishReason().getOrNull()?.knownEnum() != FinishReason.Known.STOP) {
            throw AgentTurnFailedException("Gemini 响应未以正常 STOP 状态完成。")
        }
        val content = candidate.content().getOrNull()
            ?: throw AgentTurnFailedException("Gemini 响应未包含模型内容。")
        return StoppedSdkGeminiCandidate(
            content = content,
            parts = content.parts().getOrNull().orEmpty(),
        )
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
            logger.warn("Gemini SDK function call failed with a safe local error category.")
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
                            // Public model refreshes run in their caller's scope rather than serviceJob. Closing the
                            // transport makes them exit; waiting for the mutex fences all model-state publication
                            // before close completion is reported.
                            modelUpdateMutex.withLock { }
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

internal const val MAX_GEMINI_MODEL_DISCOVERY_PAGES = 16
internal const val MAX_GEMINI_MODEL_DISCOVERY_ENTRIES = 256
internal const val MAX_GEMINI_MODEL_DISCOVERY_TOKEN_BYTES = 8 * 1024
internal const val MAX_GEMINI_MODEL_DISCOVERY_DUPLICATES = 32
