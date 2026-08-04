package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
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
 */
@Singleton
class DelegatingAgentService @Inject constructor(
    private val agentComponentFactory: AgentComponent.Factory,
    settingsRepository: SettingsRepository,
    skillRepository: SkillRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    parentScope: CoroutineScope,
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

    // 初始 SettingsUpdate 没有 switchGeneration，必须用独立代次保护 Poller 的启动。
    private val initialReadinessGeneration = modelSwitchBarrier.beginSwitch()
    private var initialReadinessPending = true

    @Volatile
    private var closed = false

    private var closeJob: Job? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var settingsJob: Job? = null

    private var currentAgentComponent: AgentComponent? = null

    @Volatile
    private var _currentService: AgentService? = null

    private val currentService: AgentService
        get() = _currentService ?: throw IllegalStateException("Agent service is not initialized or disabled.")

    private var currentProvider: AIProvider? = null
    private var currentApiKey: String? = null
    private var currentBaseUrl: String? = null
    private var currentProxy: ProxySettings? = null
    private var lastHandledSettings: AppSettings? = null

    init {
        settingsJob = combine(
            settingsRepository.settingsUpdateFlow,
            skillRepository.skillsUpdateEvent.onStart { emit(Unit) }
        ) { settingsUpdate, _ -> settingsUpdate }.onEach { settingsUpdate ->
            val settings = settingsUpdate.settings
            val previousAiSettings = lastHandledSettings?.ai
            val aiSettings = settings.ai
            val proxySettings = settings.proxy
            val selectedModelChanged = previousAiSettings != null &&
                    previousAiSettings.selectedModel != aiSettings?.selectedModel
            val onlySelectedModelChanged = selectedModelChanged &&
                    previousAiSettings.copy(selectedModel = "") == aiSettings?.copy(selectedModel = "")

            try {
                if (closed) {
                    return@onEach
                }
                if (settingsUpdate.switchGeneration != null) {
                    modelSwitchBarrier.awaitInFlightRequests()
                }
                val apiKey = aiSettings?.requiredApiKey()
                if (
                    aiSettings?.provider == AIProvider.OPENAI &&
                    aiSettings.agentEnabled &&
                    settingsRepository.hasHistoricalInvalidOpenAiBaseUrl
                ) {
                    logger.warn("OpenAI agent remains disabled until the historical invalid base URL is explicitly replaced.")
                    disableAgent()
                } else if (aiSettings?.agentEnabled == true && !apiKey.isNullOrBlank()) {
                    val baseUrl = aiSettings.openAiBaseUrl

                    val needsRecreate = synchronized(lifecycleLock) {
                        _currentService == null ||
                                currentProvider != aiSettings.provider ||
                                currentApiKey != apiKey ||
                                currentBaseUrl != baseUrl ||
                                currentProxy != proxySettings
                    }

                    if (needsRecreate) {
                        recreateAgent(aiSettings, apiKey, baseUrl, proxySettings)
                    } else {
                        applySettingsChange(aiSettings, selectedModelChanged, onlySelectedModelChanged)
                    }
                } else {
                    disableAgent()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to apply AI settings; keeping the current agent when available.", e)
            } finally {
                lastHandledSettings = settings
                modelSwitchBarrier.completeSettingsThrough(settingsUpdate.switchGeneration)
                completeInitialReadiness()
            }
        }.launchIn(parentScope).also { job ->
            job.invokeOnCompletion { completeInitialReadiness() }
        }
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

    /**
     * 在销毁活跃组件前先创建替代组件。这样即使创建本身失败，仍可保留可用的代理。替代组件创建后，
     * 必须先等待旧代理关闭，才可发布新代理；不同组件的 MCP 资源相互隔离，因此旧代理关闭不会影响
     * 已开始连接的新代理。
     */
    private suspend fun recreateAgent(
        aiSettings: AISettings,
        apiKey: String,
        baseUrl: String,
        proxySettings: ProxySettings?,
    ) {
        val newComponent = agentComponentFactory.create()
        val newService = when (aiSettings.provider) {
            AIProvider.OPENAI -> newComponent.openAIAgentService
            AIProvider.GEMINI -> newComponent.geminiAgentService
        }

        var published = false
        try {
            if (!newService.awaitReady()) {
                logger.warn("Replacement agent did not complete initialization; retaining the current agent.")
                return
            }
            val previousService = synchronized(lifecycleLock) {
                if (closed) null else _currentService
            }
            if (closed) {
                return
            }

            previousService?.close()?.join()
            published = synchronized(lifecycleLock) {
                if (closed || _currentService !== previousService) {
                    false
                } else {
                    currentAgentComponent = newComponent
                    _currentService = newService
                    currentProvider = aiSettings.provider
                    currentApiKey = apiKey
                    currentBaseUrl = baseUrl
                    currentProxy = proxySettings
                    true
                }
            }
            if (published) {
                logger.info("Agent component recreated for provider: ${aiSettings.provider}")
            }
        } finally {
            if (!published) {
                // 取消或失败后的候选清理可能卡住；初始 Poller 不应因此永久等待启动屏障。
                completeInitialReadiness()
                withContext(NonCancellable) {
                    newService.close()?.join()
                }
            }
        }
    }

    /**
     * 关闭当前已发布的代理并清除其配置记录。
     */
    private suspend fun disableAgent() {
        val serviceToClose = synchronized(lifecycleLock) { _currentService }
        serviceToClose?.close()?.join()
        val disabled = synchronized(lifecycleLock) {
            if (_currentService !== serviceToClose) {
                false
            } else {
                clearCurrentAgentLocked()
                true
            }
        }
        if (disabled && serviceToClose != null) {
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
    }

    /**
     * 在同一处应用设置变更。模型选择会在 [AgentService.switchModel] 中进行专门
     * 重置，因此之后不能再执行通用重置。
     */
    private suspend fun applySettingsChange(
        aiSettings: AISettings,
        selectedModelChanged: Boolean,
        onlySelectedModelChanged: Boolean,
    ) {
        if (!selectedModelChanged) {
            if (!awaitSuccessfulReset(_currentService?.resetSession())) {
                logger.warn("Failed to reset agent session after settings changed.")
            }
            return
        }

        if (aiSettings.selectedModel.isBlank()) {
            if (!onlySelectedModelChanged) {
                if (!awaitSuccessfulReset(_currentService?.resetSession())) {
                    logger.warn("Failed to reset agent session after clearing model selection.")
                }
            }
            return
        }

        val modelSwitchJob = try {
            _currentService?.switchModel(aiSettings.selectedModel)
        } catch (e: IllegalArgumentException) {
            logger.warn("Ignoring unsupported model from settings: ${aiSettings.selectedModel}", e)
            null
        }

        if (modelSwitchJob != null) {
            modelSwitchJob.join()
            if (modelSwitchJob.isCancelled) {
                logger.warn("Failed to switch agent model to {}.", aiSettings.selectedModel)
            }
        } else if (!onlySelectedModelChanged) {
            if (!awaitSuccessfulReset(_currentService?.resetSession())) {
                logger.warn("Failed to reset agent session after unsupported model selection.")
            }
        }
    }

    /**
     * 等待会话重置任务，并以任务状态而非 [Job.join] 的返回行为判定成功。
     */
    private suspend fun awaitSuccessfulReset(resetJob: Job?): Boolean {
        resetJob ?: return false
        resetJob.join()
        return !resetJob.isCancelled
    }

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
     * @param aiSettings 要检查的 AI 设置。
     * @return 服务未关闭、当前代理与设置指定的提供商及凭据一致且底层代理可用时返回 `true`，否则返回
     * `false`。
     */
    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean {
        val apiKey = aiSettings.requiredApiKey()
        return synchronized(lifecycleLock) {
            val service = _currentService
            !closed &&
                    aiSettings.agentEnabled &&
                    apiKey.isNotBlank() &&
                    service != null &&
                    currentProvider == aiSettings.provider &&
                    currentApiKey == apiKey &&
                    currentBaseUrl == aiSettings.openAiBaseUrl &&
                    service.isAiFeatureEnabled(aiSettings)
        }
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
     * 对该服务调用 [withReadyService]。这样切换会一直等待 [block] 完成，且不会因嵌套屏障而死锁。
     *
     * @param T [block] 的返回类型。
     * @param block 在屏障已准入时使用当前底层服务的挂起代码块。
     * @return [block] 的返回值。
     */
    override suspend fun <T> withReadyService(block: suspend (AgentService) -> T): T {
        return modelSwitchBarrier.runWhenReady { block(currentService) }
    }

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
     * @throws IllegalStateException 当服务尚未初始化、已禁用或已关闭时抛出。
     * @throws Exception 当当前非 OpenAI 代理以其原有语义报告失败时抛出。
     * @throws CancellationException 当模型切换屏障、当前代理或调用协程被取消时原样抛出。
     */
    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
        return withReadyService { readyService -> readyService.sendMessage(text, mediaData) }
    }

    /**
     * 终态关闭当前底层代理并清除服务引用。
     *
     * 首次调用会同步释放初始就绪屏障、停止设置订阅，并等待当前代理及任何正在进行的重建安全完成清理；
     * 候选代理清理耗时不会阻塞已等待启动屏障的调用方。重复调用返回同一个等待任务。调用方取消等待任务
     * 不会取消清理，后续调用会提供新的等待任务。关闭后服务不再发布新的代理。
     *
     * @return 幂等的异步清理任务；等待其完成后当前 Agent 组件持有的资源均已释放。
     */
    override fun close(): Job = synchronized(lifecycleLock) {
        val completion = closeCompletion ?: CompletableDeferred<Unit>().also { newCompletion ->
            closed = true
            completeInitialReadiness()
            val settingsJobToStop = settingsJob
            settingsJob = null
            settingsJobToStop?.cancel()
            val serviceToClose = _currentService
            clearCurrentAgentLocked()
            closeCompletion = newCompletion
            closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    try {
                        settingsJobToStop?.join()
                        serviceToClose?.close()?.join()
                    } catch (e: Exception) {
                        logger.error("Failed to close delegating agent resources", e)
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
