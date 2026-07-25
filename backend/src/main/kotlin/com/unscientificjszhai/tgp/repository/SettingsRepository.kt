package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository private constructor(
    private val configFile: File,
    private val modelSwitchBarrier: ModelSwitchBarrier,
) {
    @Inject
    constructor(modelSwitchBarrier: ModelSwitchBarrier) : this(File("config/settings.json"), modelSwitchBarrier)

    companion object {
        internal fun forTesting(
            configFile: File,
            modelSwitchBarrier: ModelSwitchBarrier = ModelSwitchBarrier(),
        ): SettingsRepository = SettingsRepository(configFile, modelSwitchBarrier)
    }

    private val logger = LoggerFactory.getLogger(SettingsRepository::class.java)

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    /**
     * 带有单调递增版本号及其覆盖的最高屏障代次的设置。代理生命周期代码使用
     * 此流而非 [settingsFlow]，使最终快照能够释放因 StateFlow 合并而丢失的
     * 代次。
     */
    private val _settingsUpdateFlow = MutableStateFlow(
        SettingsUpdate(settings = _settingsFlow.value, version = 0, switchGeneration = null),
    )
    internal val settingsUpdateFlow: StateFlow<SettingsUpdate> = _settingsUpdateFlow.asStateFlow()
    private var settingsVersion = 0L

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    private fun loadSettings(): AppSettings =
        if (configFile.exists()) {
            val content = configFile.readText()
            try {
                ConfigJson.decodeFromString(content)
            } catch (e: Exception) {
                fixMissingProxyType(content) ?: run {
                    logger.error("Error while loading config file", e)
                    AppSettings()
                }
            }
        } else {
            AppSettings()
        }

    @Synchronized
    fun saveSettings(settings: AppSettings) {
        val previousSettings = _settingsFlow.value
        val switchGeneration = if (settings.requiresAgentLifecycleBarrier(previousSettings)) {
            modelSwitchBarrier.beginSwitch()
        } else {
            null
        }

        try {
            val content = ConfigJson.encodeToString(settings)
            configFile.writeText(content)
            _settingsFlow.value = settings
            if (settings != previousSettings) {
                _settingsUpdateFlow.value = SettingsUpdate(
                    settings = settings,
                    version = ++settingsVersion,
                    // 无关的保存操作可能会在 StateFlow 中抢在模型切换之前反映。
                    // 将最高待处理代次向后传递，使最新快照覆盖此前所有待处理的切换。
                    switchGeneration = switchGeneration ?: modelSwitchBarrier.latestPendingGeneration(),
                )
            }
        } catch (e: Exception) {
            modelSwitchBarrier.cancel(switchGeneration)
            throw e
        }
    }

    private fun fixMissingProxyType(content: String): AppSettings? {
        val rawElement = ConfigJson.parseToJsonElement(content)
        val settings = rawElement as? JsonObject ?: return null
        val proxy = settings["proxy"] as? JsonObject ?: return null
        if (proxy.containsKey("host") && proxy.containsKey("port") && !proxy.containsKey("type")) {
            val newProxy =
                buildJsonObject {
                    proxy.forEach { (key, value) -> put(key, value) }
                    put("type", "HTTP")
                }
            val newSettings =
                buildJsonObject {
                    settings.forEach { (key, value) ->
                        if (key == "proxy") {
                            put(key, newProxy)
                        } else {
                            put(key, value)
                        }
                    }
                }

            return ConfigJson.decodeFromJsonElement<AppSettings>(newSettings).also {
                configFile.writeText(ConfigJson.encodeToString(it))
            }
        } else {
            return null
        }
    }
}

/**
 * 代理生命周期流观察到的设置快照。
 *
 * [switchGeneration] 是该快照覆盖的最高待处理生命周期屏障代次。因此，完成该
 * 快照时必须释放截至并包含此值的所有待处理代次。
 */
internal data class SettingsUpdate(
    val settings: AppSettings,
    val version: Long,
    val switchGeneration: Long?,
)

private fun AppSettings.requiresAgentLifecycleBarrier(previous: AppSettings): Boolean {
    val previousAi = previous.ai
    val aiSettings = ai

    val providerChanged = previousAi?.provider != aiSettings?.provider
    val selectedModelChanged = (previousAi?.selectedModel ?: "") != (aiSettings?.selectedModel ?: "")
    val effectiveApiKeyChanged = previous.effectiveApiKey() != effectiveApiKey()
    val openAiBaseUrlChanged = previousAi?.openAiBaseUrl != aiSettings?.openAiBaseUrl
    val agentEnabledChanged = (previousAi?.agentEnabled ?: false) != (aiSettings?.agentEnabled ?: false)

    return providerChanged ||
            selectedModelChanged ||
            effectiveApiKeyChanged ||
            openAiBaseUrlChanged ||
            previous.proxy != proxy ||
            agentEnabledChanged
}

private fun AppSettings.effectiveApiKey(): String? = ai?.let { aiSettings ->
    when (aiSettings.provider) {
        AIProvider.GEMINI -> aiSettings.geminiApiKey
        AIProvider.OPENAI -> aiSettings.openAiApiKey
    }
}
