package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.di.AgentComponent
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
    private val settingsRepository: SettingsRepository,
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

    init {
        combine(
            settingsRepository.settingsFlow.onStart { emit(settingsRepository.settingsFlow.value) },
            skillRepository.skillsUpdateEvent.onStart { emit(Unit) }
        ) { settings, _ -> settings }.onEach { settings ->
            val aiSettings = settings.ai
            val proxySettings = settings.proxy

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
                    _currentService?.close()
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
                    _currentService?.resetSession()?.join()
                }
            } else {
                if (_currentService != null) {
                    _currentService?.close()
                    _currentService = null
                    currentAgentComponent = null
                    currentProvider = null
                    currentApiKey = null
                    currentBaseUrl = null
                    currentProxy = null
                    logger.info("Agent service disabled.")
                }
            }
        }.launchIn(parentScope)
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

    override fun close() {
        _currentService?.close()
        _currentService = null
        currentAgentComponent = null
    }
}
