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
 * 代理分发服务，通过 AgentComponent 管理具体的 AgentService 生命周期。
 */
@Singleton
class DelegatingAgentService @Inject constructor(
    private val agentComponentFactory: AgentComponent.Factory,
    settingsRepository: SettingsRepository,
    skillRepository: SkillRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    parentScope: CoroutineScope,
) : AgentService() {
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

    override val currentModel: String
        get() = currentService.currentModel

    override val availableModels: List<String>
        get() = currentService.availableModels

    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean =
        _currentService?.isAiFeatureEnabled(aiSettings) ?: false

    override fun switchModel(modelName: String): Job? {
        return currentService.switchModel(modelName)
    }

    override suspend fun updateModel(): ModelSnapshot? {
        val service = currentService
        val snapshot = service.updateModel() ?: return null
        return snapshot.takeIf { _currentService === service }
    }

    override fun resetSession(): Job? {
        return currentService.resetSession()
    }

    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
        return modelSwitchBarrier.runWhenReady {
            currentService.sendMessage(text, mediaData)
        }
    }

    override fun close(): Job? {
        val closeJob = _currentService?.close()
        _currentService = null
        currentAgentComponent = null
        return closeJob
    }
}
