package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import kotlinx.serialization.json.Json
import java.io.File

class SettingsRepository {
    private val configFile = File("config/settings.json")
    private val json = Json { prettyPrint = true }

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    fun loadSettings(): AppSettings {
        return if (configFile.exists()) {
            val content = configFile.readText()
            json.decodeFromString(content)
        } else {
            AppSettings()
        }
    }

    fun saveSettings(settings: AppSettings) {
        val content = json.encodeToString(settings)
        configFile.writeText(content)
    }
}
