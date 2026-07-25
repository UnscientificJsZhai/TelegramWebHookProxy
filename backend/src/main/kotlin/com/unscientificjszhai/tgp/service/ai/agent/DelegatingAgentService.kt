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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * 设置或技能发生变化时，此服务会重建或重置底层代理；发送消息会等待模型切换屏障放行，
 * 以避免请求使用已被替换的服务。
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
     * 为不使用依赖注入的调用方创建服务。
     *
     * 此构造函数会创建独立的 [ModelSwitchBarrier]。
     *
     * @param agentComponentFactory 用于创建提供商专属代理组件的工厂。
     * @param settingsRepository 提供 AI 与代理设置变更的仓库。
     * @param skillRepository 提供技能变更事件的仓库。
     * @param parentScope 用于收集设置与技能变更的协程作用域。
     */
    @Suppress("unused")
    constructor(
        agentComponentFactory: AgentComponent.Factory,
        settingsRepository: SettingsRepository,
        skillRepository: SkillRepository,
        parentScope: CoroutineScope,
    ) : this(
        agentComponentFactory,
        settingsRepository,
        skillRepository,
        ModelSwitchBarrier(),
        parentScope,
    )

    private val logger = LoggerFactory.getLogger(DelegatingAgentService::class.java)

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
        combine(
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
                if (settingsUpdate.switchGeneration != null) {
                    modelSwitchBarrier.awaitInFlightRequests()
                }
                if (aiSettings != null && aiSettings.agentEnabled) {
                    val apiKey = when (aiSettings.provider) {
                        AIProvider.OPENAI -> aiSettings.openAiApiKey
                        else -> aiSettings.geminiApiKey
                    }
                    val baseUrl = aiSettings.openAiBaseUrl

                    val needsRecreate = _currentService == null ||
                            currentProvider != aiSettings.provider ||
                            currentApiKey != apiKey ||
                            currentBaseUrl != baseUrl ||
                            currentProxy != proxySettings

                    if (needsRecreate) {
                        recreateAgent(aiSettings, apiKey, baseUrl, proxySettings)
                    } else {
                        applySettingsChange(aiSettings, selectedModelChanged, onlySelectedModelChanged)
                    }
                } else {
                    if (_currentService != null) {
                        _currentService?.close()?.join()
                        _currentService = null
                        currentAgentComponent = null
                        currentProvider = null
                        currentApiKey = null
                        currentBaseUrl = null
                        currentProxy = null
                        logger.info("Agent service disabled.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Failed to apply AI settings; keeping the current agent when available.", e)
            } finally {
                lastHandledSettings = settings
                modelSwitchBarrier.completeThrough(settingsUpdate.switchGeneration)
            }
        }.launchIn(parentScope)
    }

    /**
     * 在销毁活跃组件前先创建替代组件。这样即使创建本身失败，仍可保留可用的
     * 代理。
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

        _currentService?.close()?.join()
        currentAgentComponent = newComponent
        _currentService = newService
        currentProvider = aiSettings.provider
        currentApiKey = apiKey
        currentBaseUrl = baseUrl
        currentProxy = proxySettings
        logger.info("Agent component recreated for provider: ${aiSettings.provider}")
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
            _currentService?.resetSession()?.join()
            return
        }

        if (aiSettings.selectedModel.isBlank()) {
            if (!onlySelectedModelChanged) {
                _currentService?.resetSession()?.join()
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
        } else if (!onlySelectedModelChanged) {
            _currentService?.resetSession()?.join()
        }
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
     * @return 当前代理存在且其提供商所需设置完整时返回 `true`，否则返回 `false`。
     */
    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean =
        _currentService?.isAiFeatureEnabled(aiSettings) ?: false

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
     * @return 已开始重置时返回异步任务；服务不可用或无需重置时返回 `null`。
     * @throws IllegalStateException 当服务尚未初始化或已禁用时抛出。
     */
    override fun resetSession(): Job? {
        return currentService.resetSession()
    }

    /**
     * 等待模型切换完成后，将消息转发给当前底层代理。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空。
     * @return 底层代理生成的回复文本；未生成可返回内容时返回空字符串。
     * @throws IllegalStateException 当服务尚未初始化、已禁用或已关闭时抛出。
     */
    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
        return modelSwitchBarrier.runWhenReady {
            currentService.sendMessage(text, mediaData)
        }
    }

    /**
     * 关闭当前底层代理并清除服务引用。
     *
     * @return 底层代理有异步清理工作时返回对应任务；不存在代理或无需清理时返回 `null`。
     */
    override fun close(): Job? {
        val closeJob = _currentService?.close()
        _currentService = null
        currentAgentComponent = null
        return closeJob
    }
}
