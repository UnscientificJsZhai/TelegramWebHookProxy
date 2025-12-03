package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.service.ChatInfo
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class UpdatesRepository {
    private val configFile = File("config/updates.json")
    private val json = Json { prettyPrint = true }

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    fun loadChats(): List<ChatInfo> {
        return if (configFile.exists()) {
            val content = configFile.readText()
            try {
                json.decodeFromString(content)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun saveChats(chats: List<ChatInfo>) {
        val content = json.encodeToString(chats)
        configFile.writeText(content)
    }
}
