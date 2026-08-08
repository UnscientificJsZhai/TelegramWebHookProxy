package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SettingsUpdate
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 根据应用设置创建并代理当前 AI 提供商的服务实例。
 *
 * 设置或技能发生变化时，此服务会重建或重置底层代理；构造时会登记一次性初始就绪代次，直到首次
 * 设置处理及候选代理初始化完成才放行。发送消息会等待模型切换屏障放行，以避免请求使用已被替换的服务。
 * 该屏障仅协调此委派服务的消息和组件重建，不表示应用内所有 AI 相关操作都被全局串行化。
 *
 * @param agentComponentFactory 用于创建提供商专属代理组件的工厂。
 * @param settingsRepository 提供 AI 与代理设置变更的仓库。
 * @param skillRepository 提供技能变更事件的仓库。
 * @param modelSwitchBarrier 协调设置切换和进行中请求的屏障。
 * @param parentScope 用于收集设置与技能变更的协程作用域。
 * @param deadlines 限制候选代理初始化的总体时限。
 */
@Singleton
class DelegatingAgentService @Inject internal constructor(
    private val agentComponentFactory: AgentComponent.Factory,
    private val settingsRepository: SettingsRepository,
    skillRepository: SkillRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    parentScope: CoroutineScope,
    private val deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
) : AgentService() {
    /**
     * 为未接入依赖注入的既有调用方创建服务。
     *
     * 此兼容构造器复用 [settingsRepository] 持有的屏障，不会创建独立屏障。新代码应通过依赖注入构造服务，
     * 以显式共享应用级依赖。
     *
     * @param agentComponentFactory 用于创建提供商专属代理组件的工厂。
     * @param settingsRepository 提供 AI 与代理设置变更的仓库，且必须持有应用共享的屏障。
     * @param skillRepository 提供技能变更事件的仓库。
     * @param parentScope 用于收集设置与技能变更的协程作用域。
     */
    @Deprecated("请通过依赖注入构造 DelegatingAgentService，以显式共享 ModelSwitchBarrier。")
    constructor(
        agentComponentFactory: AgentComponent.Factory,
        settingsRepository: SettingsRepository,
        skillRepository: SkillRepository,
        parentScope: CoroutineScope,
    ) : this(
        agentComponentFactory,
        settingsRepository,
        skillRepository,
        settingsRepository.modelSwitchBarrier,
        parentScope,
    )

    private val logger = LoggerFactory.getLogger(DelegatingAgentService::class.java)
    private val lifecycleLock = Any()
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cleanupLock = Any()
    private val backgroundCleanupJobs = mutableSetOf<Job>()

    // 初始 SettingsUpdate 没有 switchGeneration，必须用独立代次保护 Poller 的启动。
    private val initialReadinessGeneration = modelSwitchBarrier.beginSwitch()
    private var initialReadinessPending = true

    @Volatile
    private var closed = false

    private var closeJob: Job? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var settingsJob: Job? = null

    /** 关闭开始时从生命周期锁中摘除、并在锁外完成的资源。 */
    private data class ClosingResources(
        val completion: CompletableDeferred<Unit>,
        val settingsJob: Job?,
        val currentService: AgentService?,
    )

    private var currentAgentComponent: AgentComponent? = null

    @Volatile
    private var _currentService: AgentService? = null

    private val currentService: AgentService
        get() = _currentService ?: throw IllegalStateException("Agent service is not initialized or disabled.")

    private var currentProvider: AIProvider? = null
    private var currentApiKey: String? = null
    private var currentBaseUrl: String? = null
    private var currentProxy: ProxySettings? = null

    /** 仅在当前服务完整应用设置后更新；失败操作不能覆盖此快照。 */
    private var appliedSettings: AppSettings? = null

    /**
     * 一个已发布或正在处理的代理配置快照。
     *
     * 设置版本是配置身份的一部分：即使两次保存恰好包含相同字段，也必须完成各自的生命周期操作后才能
     * 重新接受请求。
     */
    private data class AgentConfiguration(
        val settingsVersion: Long,
        val provider: AIProvider,
        val apiKey: String,
        val baseUrl: String,
        val proxySettings: ProxySettings?,
    )

    /** 单次设置或技能生命周期操作的身份，用于拒绝迟到完成。 */
    private data class LifecycleOperation(
        val settingsUpdate: SettingsUpdate,
        val epoch: Long,
        val configuration: AgentConfiguration?,
    )

    private var desiredSettingsVersion: Long = -1L
    private var desiredConfiguration: AgentConfiguration? = null
    private var readyConfiguration: AgentConfiguration? = null
    private var lifecycleOperationEpoch: Long = 0L

    init {
        settingsJob = combine(
            settingsRepository.settingsUpdateFlow,
            skillRepository.skillsUpdateEvent.onStart { emit(Unit) }
        ) { settingsUpdate, _ -> settingsUpdate }.onEach(::applyLifecycleUpdate).launchIn(parentScope).also { job ->
            job.invokeOnCompletion { completeInitialReadiness() }
        }
    }

    /**
     * 处理一次设置快照或技能事件。
     *
     * 每个事件先在 [lifecycleLock] 中废止先前就绪状态，再在锁外等待已准入请求、初始化候选或重置会话。
     * 因此迟到完成无法把旧设置重新标为就绪。
     */
    private suspend fun applyLifecycleUpdate(settingsUpdate: SettingsUpdate) {
        val operation = beginLifecycleOperation(settingsUpdate) ?: return
        val settings = settingsUpdate.settings
        val previousSettings = synchronized(lifecycleLock) { appliedSettings }
        val previousAiSettings = previousSettings?.ai
        val aiSettings = settings.ai
        val selectedModelChanged = previousAiSettings != null &&
                previousAiSettings.selectedModel != aiSettings?.selectedModel

        try {
            if (settingsUpdate.switchGeneration != null) {
                modelSwitchBarrier.awaitInFlightRequests()
            }
            if (!isOperationCurrent(operation)) {
                return
            }

            val configuration = operation.configuration
            if (
                aiSettings?.provider == AIProvider.OPENAI &&
                aiSettings.agentEnabled &&
                settingsRepository.hasHistoricalInvalidOpenAiBaseUrl
            ) {
                logger.warn("OpenAI agent remains disabled until the historical invalid base URL is explicitly replaced.")
                disableAgent(operation)
            } else if (configuration != null && aiSettings != null) {
                val needsRecreate = synchronized(lifecycleLock) {
                    !currentServiceMatchesConfigurationLocked(configuration) ||
                            (selectedModelChanged && aiSettings.selectedModel.isBlank())
                }
                if (needsRecreate) {
                    recreateAgent(operation, aiSettings, configuration)
                } else if (applySettingsChange(operation, aiSettings, selectedModelChanged)) {
                    markReady(operation, configuration)
                }
            } else {
                disableAgent(operation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to apply AI settings; current configuration remains unavailable; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
        } finally {
            modelSwitchBarrier.completeSettingsThrough(settingsUpdate.switchGeneration)
            completeInitialReadiness()
        }
    }

    /**
     * 在锁内登记新的期望配置，并立即废止此前的就绪标记。
     */
    private fun beginLifecycleOperation(settingsUpdate: SettingsUpdate): LifecycleOperation? =
        synchronized(lifecycleLock) {
            if (closed) {
                return@synchronized null
            }
            val configuration = settingsUpdate.toAgentConfigurationOrNull()
            if (settingsUpdate.version >= desiredSettingsVersion) {
                desiredSettingsVersion = settingsUpdate.version
                desiredConfiguration = configuration
            }
            readyConfiguration = null
            LifecycleOperation(settingsUpdate, ++lifecycleOperationEpoch, configuration)
        }

    /**
     * 释放仅覆盖首次设置处理的一次性启动代次。
     *
     * 设置收集器在首次处理完成、取消或关闭时都会调用本方法；后续设置更新不会重复影响该代次。
     */
    private fun completeInitialReadiness() {
        val shouldComplete = synchronized(lifecycleLock) {
            initialReadinessPending.also { initialReadinessPending = false }
        }
        if (shouldComplete) {
            modelSwitchBarrier.complete(initialReadinessGeneration)
        }
    }

    /**
     * 返回当前 AI 提供商启动所需的 API 密钥。
     */
    private fun AISettings.requiredApiKey(): String = when (provider) {
        AIProvider.OPENAI -> openAiApiKey
        AIProvider.GEMINI -> geminiApiKey
    }

    /** 返回可创建代理时的完整配置身份；禁用或缺少凭据时返回 `null`。 */
    private fun SettingsUpdate.toAgentConfigurationOrNull(): AgentConfiguration? {
        val aiSettings = settings.ai ?: return null
        val apiKey = aiSettings.requiredApiKey()
        if (!aiSettings.agentEnabled || apiKey.isBlank()) {
            return null
        }
        return AgentConfiguration(
            settingsVersion = version,
            provider = aiSettings.provider,
            apiKey = apiKey,
            baseUrl = aiSettings.openAiBaseUrl,
            proxySettings = settings.proxy,
        )
    }

    /**
     * 在销毁活跃组件前先创建替代组件。候选失败时旧组件仅保留到后续替换或关闭，不能代表新的期望配置。
     * 替代组件完成就绪后会原子发布；旧代理关闭转入后台追踪，不能占用模型切换屏障。不同组件的 MCP
     * 资源相互隔离，因此旧代理的慢速关闭不会影响已发布候选。
     */
    private suspend fun recreateAgent(
        operation: LifecycleOperation,
        aiSettings: AISettings,
        configuration: AgentConfiguration,
    ) {
        val newComponent = agentComponentFactory.create()
        val newService = when (aiSettings.provider) {
            AIProvider.OPENAI -> newComponent.openAIAgentService
            AIProvider.GEMINI -> newComponent.geminiAgentService
        }

        var published = false
        try {
            val ready = withTimeoutOrNull(deadlines.candidateInitialization) {
                newService.awaitReady()
            } ?: false
            if (!ready) {
                logger.warn("Replacement agent did not complete initialization before its deadline; current configuration remains unavailable.")
                return
            }
            val previousService = synchronized(lifecycleLock) {
                if (isOperationCurrentLocked(operation) &&
                    operation.configuration == configuration &&
                    isSettingsVersionCurrent(operation.settingsUpdate.version)
                ) {
                    val previous = _currentService
                    currentAgentComponent = newComponent
                    _currentService = newService
                    currentProvider = configuration.provider
                    currentApiKey = configuration.apiKey
                    currentBaseUrl = configuration.baseUrl
                    currentProxy = configuration.proxySettings
                    readyConfiguration = configuration
                    appliedSettings = operation.settingsUpdate.settings
                    published = true
                    previous
                } else {
                    null
                }
            }
            if (published) {
                // 所有旧请求已经在切换屏障外排空；旧资源关闭可能忽略取消，不能继续占用设置切换路径。
                scheduleBackgroundCleanup(previousService?.close())
                logger.info("Agent component recreated for provider: ${configuration.provider}")
            }
        } finally {
            if (!published) {
                // 取消、失败或超时后的候选清理可能卡住；初始 Poller 不应因此永久等待启动屏障。
                completeInitialReadiness()
                scheduleBackgroundCleanup(newService.close())
            }
        }
    }

    /**
     * 跟踪代理资源的后台清理，但绝不在设置切换屏障的关键路径等待它。
     *
     * 传入任务代表已经触发的关闭；本方法仅观察其结束，用于保留生命周期可诊断性。候选和旧组件已从
     * 可见服务引用中摘除，因此其延迟关闭不能重新发布或阻塞后续切换。
     */
    private fun scheduleBackgroundCleanup(cleanupJob: Job?) {
        cleanupJob ?: return
        synchronized(cleanupLock) { backgroundCleanupJobs.add(cleanupJob) }
        closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                cleanupJob.join()
            } catch (e: CancellationException) {
                // closingScope 不会主动取消此任务；若应用进程终止则无需把取消记录为代理错误。
            } finally {
                synchronized(cleanupLock) { backgroundCleanupJobs.remove(cleanupJob) }
            }
        }
    }

    /**
     * 等待所有已登记的后台清理完成。
     *
     * 关闭流程先等待设置收集协程结束，因此本方法开始后不会再有生命周期操作登记新的清理任务。每轮
     * 都在等待外清理已结束任务，并在等待当前快照后再次清理，避免完成观察者与关闭流程交错时留下过期条目。
     */
    private suspend fun drainBackgroundCleanup() {
        while (true) {
            val cleanupJobs = synchronized(cleanupLock) {
                backgroundCleanupJobs.removeAll { it.isCompleted }
                backgroundCleanupJobs.toList()
            }
            if (cleanupJobs.isEmpty()) {
                return
            }

            cleanupJobs.joinAll()
            synchronized(cleanupLock) {
                backgroundCleanupJobs.removeAll { it.isCompleted }
            }
        }
    }

    /**
     * 关闭当前已发布的代理并清除其配置记录。
     */
    private fun disableAgent(operation: LifecycleOperation) {
        val disabled = synchronized(lifecycleLock) {
            if (!isOperationCurrentLocked(operation) || !isSettingsVersionCurrent(operation.settingsUpdate.version)) {
                return@synchronized null
            }
            val serviceToClose = _currentService
            clearCurrentAgentLocked()
            serviceToClose
        }
        scheduleBackgroundCleanup(disabled?.close())
        if (disabled != null) {
            logger.info("Agent service disabled.")
        }
    }

    /**
     * 调用方必须持有 [lifecycleLock]，以清除当前已发布代理的引用和配置记录。
     */
    private fun clearCurrentAgentLocked() {
        _currentService = null
        currentAgentComponent = null
        currentProvider = null
        currentApiKey = null
        currentBaseUrl = null
        currentProxy = null
        readyConfiguration = null
        appliedSettings = null
    }

    /**
     * 在同一处应用设置变更。模型选择会在 [AgentService.switchModel] 中进行专门
     * 重置，因此之后不能再执行通用重置。
     */
    private suspend fun applySettingsChange(
        operation: LifecycleOperation,
        aiSettings: AISettings,
        selectedModelChanged: Boolean,
    ): Boolean {
        val service = synchronized(lifecycleLock) {
            _currentService.takeIf { isOperationCurrentLocked(operation) }
        } ?: return false
        val completed = if (selectedModelChanged && aiSettings.selectedModel.isNotBlank()) {
            try {
                awaitSuccessfulReset(service.switchModel(aiSettings.selectedModel))
            } catch (e: IllegalArgumentException) {
                logger.warn(
                    "Unsupported model from settings; current configuration remains unavailable; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
                false
            }
        } else {
            // 清除模型选择和任意非模型设置都必须重新建立会话；`null` 任务同样是失败，不能重新放行旧会话。
            awaitSuccessfulReset(service.resetSession())
        }
        if (!completed) {
            val action =
                if (selectedModelChanged && aiSettings.selectedModel.isNotBlank()) "switch agent model" else "reset agent session"
            logger.warn("Failed to {} for the current settings.", action)
            return false
        }
        return isOperationCurrent(operation)
    }

    /**
     * 等待会话重置任务，并以任务状态而非 [Job.join] 的返回行为判定成功。
     */
    private suspend fun awaitSuccessfulReset(resetJob: Job?): Boolean {
        resetJob ?: return false
        resetJob.join()
        return !resetJob.isCancelled
    }

    /** 标记已完成本次生命周期操作的当前配置可用。 */
    private fun markReady(operation: LifecycleOperation, configuration: AgentConfiguration) {
        synchronized(lifecycleLock) {
            if (isOperationCurrentLocked(operation) &&
                currentServiceMatchesConfigurationLocked(configuration) &&
                isSettingsVersionCurrent(operation.settingsUpdate.version)
            ) {
                readyConfiguration = configuration
                appliedSettings = operation.settingsUpdate.settings
            }
        }
    }

    /** 在不持有 [lifecycleLock] 时检查迟到操作是否仍代表最新设置。 */
    private fun isOperationCurrent(operation: LifecycleOperation): Boolean = synchronized(lifecycleLock) {
        isOperationCurrentLocked(operation) && isSettingsVersionCurrent(operation.settingsUpdate.version)
    }

    /** 调用方必须持有 [lifecycleLock]。 */
    private fun isOperationCurrentLocked(operation: LifecycleOperation): Boolean =
        !closed &&
                lifecycleOperationEpoch == operation.epoch &&
                desiredSettingsVersion == operation.settingsUpdate.version &&
                desiredConfiguration == operation.configuration

    /** 当前 [SettingsRepository] 已发布相同版本时返回 `true`。 */
    private fun isSettingsVersionCurrent(settingsVersion: Long): Boolean =
        settingsRepository.settingsUpdateFlow.value.version == settingsVersion

    /** 调用方必须持有 [lifecycleLock]。 */
    private fun currentServiceMatchesConfigurationLocked(configuration: AgentConfiguration): Boolean =
        _currentService != null &&
                currentProvider == configuration.provider &&
                currentApiKey == configuration.apiKey &&
                currentBaseUrl == configuration.baseUrl &&
                currentProxy == configuration.proxySettings

    /**
     * 获取当前底层代理实际使用的模型名称。
     *
     * 服务尚未初始化或已禁用时访问会失败。
     *
     * @throws IllegalStateException 当不存在可用的底层代理时抛出。
     */
    override val currentModel: String
        get() = currentService.currentModel

    /**
     * 获取当前底层代理可供选择的模型名称列表。
     *
     * 服务尚未初始化或已禁用时访问会失败。
     *
     * @throws IllegalStateException 当不存在可用的底层代理时抛出。
     */
    override val availableModels: List<String>
        get() = currentService.availableModels

    /**
     * 判断当前底层代理能否根据给定设置启用。
     *
     * 输入必须与仓储最新设置中的 AI 配置完全一致；委派服务仅在该版本已由当前代理完整应用且未关闭时
     * 返回可用。候选初始化、会话重置或模型切换尚未完成时，即使旧代理仍在内存中也返回 `false`。
     *
     * @param aiSettings 要检查的 AI 设置；必须等于仓储最新设置中的非空 AI 配置。
     * @return 服务未关闭、当前代理完整应用了最新配置且底层代理可用时返回 `true`，否则返回 `false`。
     */
    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean {
        val settingsUpdate = settingsRepository.settingsUpdateFlow.value
        val configuration = settingsUpdate.toAgentConfigurationOrNull() ?: return false
        if (settingsUpdate.settings.ai != aiSettings) {
            return false
        }
        val service = synchronized(lifecycleLock) {
            _currentService.takeIf {
                !closed &&
                        desiredSettingsVersion == configuration.settingsVersion &&
                        desiredConfiguration == configuration &&
                        readyConfiguration == configuration &&
                        currentServiceMatchesConfigurationLocked(configuration)
            }
        } ?: return false
        return service.isAiFeatureEnabled(aiSettings) && isReadyForCurrentSettings(configuration, service)
    }

    /**
     * 将模型切换请求转发给当前底层代理。
     *
     * @param modelName 要切换到的模型名称，必须被当前代理支持。
     * @return 已开始切换时返回异步重置会话的任务；模型未变化或服务不可用时返回 `null`。
     * @throws IllegalArgumentException 当 [modelName] 不被当前代理支持时抛出。
     * @throws IllegalStateException 当服务尚未初始化或已禁用时抛出。
     */
    override fun switchModel(modelName: String): Job? {
        return currentService.switchModel(modelName)
    }

    /**
     * 刷新当前底层代理的可用模型列表。
     *
     * @return 刷新成功且请求期间底层代理未被替换时返回模型快照；否则返回 `null`。
     * @throws IllegalStateException 当服务尚未初始化或已禁用时抛出。
     */
    override suspend fun updateModel(): ModelSnapshot? {
        val service = currentService
        val snapshot = service.updateModel() ?: return null
        return snapshot.takeIf { _currentService === service }
    }

    /**
     * 转发当前底层代理的会话重置请求。
     *
     * 本方法原样返回当前底层提供商的任务；任务的正常完成、取消和失败语义完全遵从该提供商的
     * [AgentService.resetSession] 实现文档。委托层不将 Gemini 的候选会话原子提交语义泛化给其他提供商。
     *
     * @return 已开始重置时返回异步任务；服务不可用或无需重置时返回 `null`。
     * @throws IllegalStateException 当服务尚未初始化或已禁用时抛出。
     */
    override fun resetSession(): Job? {
        return currentService.resetSession()
    }

    /**
     * 在唯一一次模型切换屏障准入中，将当前底层服务交给调用方完成完整操作。
     *
     * [block] 只能在本次同步作用域内使用传入服务，不得将服务逸出到后台任务、返回值或字段，也不得再次
     * 对该服务调用 [withReadyService]。这样切换会一直等待 [block] 完成，且不会因嵌套屏障而死锁。该次
     * 准入只读取一次设置快照，并将同一版本的工具配置安装到协程上下文；配置暂时未就绪时不会回退到
     * 已退休服务，而是拒绝本次操作。
     *
     * @param T [block] 的返回类型。
     * @param block 在屏障已准入时使用当前底层服务的挂起代码块。
     * @return [block] 的返回值。
     * @throws AgentConfigurationNotReadyException 当前设置尚未由已完成初始化或会话操作的代理完整应用时抛出。
     */
    override suspend fun <T> withReadyService(block: suspend (AgentService) -> T): T {
        return modelSwitchBarrier.runWhenReady {
            val settingsUpdate = settingsRepository.settingsUpdateFlow.value
            val configuration = settingsUpdate.toAgentConfigurationOrNull()
                ?: throw AgentConfigurationNotReadyException()
            val service = synchronized(lifecycleLock) {
                val currentService = _currentService
                currentService?.takeIf { isReadyForCapturedSettingsLocked(configuration, it) }
            } ?: throw AgentConfigurationNotReadyException()
            withContext(AgentToolExecutionContext.from(settingsUpdate)) {
                block(service)
            }
        }
    }

    /**
     * 在不持有 [lifecycleLock] 时确认服务仍属于当前已完整应用的设置。
     */
    private fun isReadyForCurrentSettings(configuration: AgentConfiguration, service: AgentService): Boolean =
        settingsRepository.settingsUpdateFlow.value.let { current ->
            current.version == configuration.settingsVersion &&
                    synchronized(lifecycleLock) {
                        isReadyForCurrentSettingsLocked(configuration, service)
                    }
        }

    /** 调用方必须持有 [lifecycleLock]。 */
    private fun isReadyForCurrentSettingsLocked(
        configuration: AgentConfiguration,
        service: AgentService,
    ): Boolean = isReadyForCapturedSettingsLocked(configuration, service) &&
            isSettingsVersionCurrent(configuration.settingsVersion)

    /** 调用方必须持有 [lifecycleLock]，且调用方已捕获并固定设置快照。 */
    private fun isReadyForCapturedSettingsLocked(
        configuration: AgentConfiguration,
        service: AgentService,
    ): Boolean =
        !closed &&
                desiredSettingsVersion == configuration.settingsVersion &&
                desiredConfiguration == configuration &&
                readyConfiguration == configuration &&
                _currentService === service &&
                currentServiceMatchesConfigurationLocked(configuration)

    /**
     * 等待模型切换完成后，将消息转发给当前底层代理。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空。
     * @return 底层代理生成的回复文本；未生成可返回内容时返回空字符串。
     * @throws AudioTranscriptionTooLargeException 当当前 OpenAI 代理收到超过
     * [MAX_AUDIO_TRANSCRIPTION_BYTES] 的 OGG 语音时，在上传前抛出。
     * @throws AudioTranscriptionFailedException 当当前 OpenAI 代理的 OGG 语音转写失败或返回空文本时抛出。
     * @throws AgentTurnFailedException 当当前 OpenAI 代理未完成回合且未提交其会话历史时抛出。
     * @throws AgentConfigurationNotReadyException 当最新配置正在初始化、重置或切换，或其先前操作失败时抛出；
     * 不会回退到旧代理。
     * @throws IllegalStateException 当服务尚未初始化、已禁用或已关闭时抛出。
     * @throws Exception 当当前非 OpenAI 代理以其原有语义报告失败时抛出。
     * @throws CancellationException 当模型切换屏障、当前代理或调用协程被取消时原样抛出。
     */
    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
        return withReadyService { readyService -> readyService.sendMessage(text, mediaData) }
    }

    /**
     * 终态关闭底层代理并清除服务引用。
     *
     * 首次调用会释放初始就绪屏障并停止设置订阅。返回任务完成前，当前已发布代理、已退休代理以及未发布候选
     * 代理的资源清理均已结束；替换流程本身仍会异步清理退休代理，不会等待其完成才发布新代理。重复调用复用
     * 同一关闭过程；取消某次返回的等待任务不会取消真实清理，后续调用会返回可继续等待的任务。关闭后不再发布
     * 新的代理。
     *
     * @return 幂等的异步清理任务；等待其完成后当前、已退休和未发布候选 Agent 组件持有的资源均已释放。
     */
    override fun close(): Job {
        val (completion, resourcesToClose) = synchronized(lifecycleLock) {
            val existingCompletion = closeCompletion
            if (existingCompletion != null) {
                existingCompletion to null
            } else {
                val newCompletion = CompletableDeferred<Unit>()
                closed = true
                completeInitialReadiness()
                val settingsJobToStop = settingsJob
                settingsJob = null
                settingsJobToStop?.cancel()
                val serviceToClose = _currentService
                clearCurrentAgentLocked()
                closeCompletion = newCompletion
                newCompletion to ClosingResources(newCompletion, settingsJobToStop, serviceToClose)
            }
        }

        resourcesToClose?.let { resources ->
            closingScope.launch {
                withContext(NonCancellable) {
                    try {
                        try {
                            resources.settingsJob?.join()
                        } catch (e: Exception) {
                            logger.error(
                                "Failed to stop delegating agent settings subscription; category={}",
                                SafeLogging.failureCategory(e).wireName,
                            )
                        }
                        try {
                            resources.currentService?.close()?.join()
                        } catch (e: Exception) {
                            logger.error(
                                "Failed to close current delegating agent resources; category={}",
                                SafeLogging.failureCategory(e).wireName,
                            )
                        }
                    } finally {
                        try {
                            drainBackgroundCleanup()
                        } catch (e: Exception) {
                            logger.error(
                                "Failed to drain retired delegating agent resources; category={}",
                                SafeLogging.failureCategory(e).wireName,
                            )
                        } finally {
                            resources.completion.complete(Unit)
                        }
                    }
                }
            }
        }

        return synchronized(lifecycleLock) {
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
}
