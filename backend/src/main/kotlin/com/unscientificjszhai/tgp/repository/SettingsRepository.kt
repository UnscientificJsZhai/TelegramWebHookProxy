package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

class SettingsRepository {

    private val logger = LoggerFactory.getLogger(SettingsRepository::class.java)

    private val configFile = File("config/settings.json")
    private val json = Json { prettyPrint = true }

    private val _settingsFlow = MutableStateFlow(loadSettings())
    val settingsFlow: StateFlow<AppSettings> = _settingsFlow.asStateFlow()

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    private fun loadSettings(): AppSettings {
        return if (configFile.exists()) {
            val content = configFile.readText()
            try {
                json.decodeFromString(content)
            } catch (e: Exception) {
                logger.error("Error while loading config file", e)
                AppSettings()
            }
        } else {
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) {
        val content = json.encodeToString(settings)
        configFile.writeText(content)
        _settingsFlow.value = settings
    }
}
