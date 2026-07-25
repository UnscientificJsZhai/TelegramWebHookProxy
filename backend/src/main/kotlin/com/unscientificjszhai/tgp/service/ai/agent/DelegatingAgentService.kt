package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
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
            settingsRepository.settingsFlow,
            skillRepository.skillsUpdateEvent.onStart { emit(Unit) }
        ) { settings, _ -> settings }.onEach { settings ->
            val previousAiSettings = lastHandledSettings?.ai
            val aiSettings = settings.ai
            val proxySettings = settings.proxy
            val selectedModelChanged = previousAiSettings != null &&
                    previousAiSettings.selectedModel != aiSettings?.selectedModel
            val onlySelectedModelChanged = selectedModelChanged &&
                    previousAiSettings.copy(selectedModel = "") == aiSettings?.copy(selectedModel = "")

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
                    _currentService?.close()?.join()
                    val newComponent = agentComponentFactory.create()
                    currentAgentComponent = newComponent
                    _currentService = when (aiSettings.provider) {
                        AIProvider.OPENAI -> newComponent.openAIAgentService
                        else -> newComponent.geminiAgentService
                    }
                    currentProvider = aiSettings.provider
                    currentApiKey = apiKey
                    currentBaseUrl = baseUrl
                    currentProxy = proxySettings
                    logger.info("Agent component recreated for provider: ${aiSettings.provider}")
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
            lastHandledSettings = settings
        }.launchIn(parentScope)
    }

    /**
     * Apply settings changes in one place. A model selection has its own reset in
     * [AgentService.switchModel], so it must not be followed by a generic reset.
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
        return currentService.sendMessage(text, mediaData)
    }

    override fun close(): Job? {
        val closeJob = _currentService?.close()
        _currentService = null
        currentAgentComponent = null
        return closeJob
    }
}
