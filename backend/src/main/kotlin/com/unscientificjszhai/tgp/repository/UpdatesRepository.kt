package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.service.ChatInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class UpdatesRepository {
    private val configFile = File("config/updates.json")
    private val json = Json { prettyPrint = true }

    private val _chatsFlow = MutableStateFlow(loadChats())
    val chatsFlow: StateFlow<List<ChatInfo>> = _chatsFlow.asStateFlow()

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    private fun loadChats(): List<ChatInfo> {
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
        _chatsFlow.value = chats
    }
}
