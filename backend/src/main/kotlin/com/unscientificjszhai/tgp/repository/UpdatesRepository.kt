package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class UpdatesData(
    val chats: List<ChatInfo> = emptyList(),
    val lastUpdateId: Long = 0
)

class UpdatesRepository {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val configFile = File("config/updates.json")
    private val json = Json { prettyPrint = true }

    private val _dataFlow = MutableStateFlow(loadData())
    val chatsFlow: StateFlow<List<ChatInfo>> = _dataFlow.asStateFlow().let { flow ->
        val stateFlow = MutableStateFlow(flow.value.chats)
        flow.onEach { stateFlow.value = it.chats }.launchIn(CoroutineScope(Dispatchers.IO))
        stateFlow
    }

    val lastUpdateId: Long
        get() = _dataFlow.value.lastUpdateId

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    private fun loadData(): UpdatesData {
        if (!configFile.exists()) {
            return UpdatesData()
        }

        val content = configFile.readText()
        if (content.isBlank()) {
            return UpdatesData()
        }

        return try {
            json.decodeFromString<UpdatesData>(content)
        } catch (e: Exception) {
            try {
                val chats = json.decodeFromString<List<ChatInfo>>(content)
                val migratedData = UpdatesData(chats = chats, lastUpdateId = 0)
                configFile.writeText(json.encodeToString(migratedData))
                logger.info("Successfully migrated updates data from old format")
                migratedData
            } catch (e2: Exception) {
                logger.error("Error while loading updates data", e)
                UpdatesData()
            }
        }
    }

    private fun saveData(data: UpdatesData) {
        val content = json.encodeToString(data)
        configFile.writeText(content)
        _dataFlow.value = data
    }

    fun saveChats(chats: List<ChatInfo>) {
        saveData(_dataFlow.value.copy(chats = chats))
    }

    fun saveLastUpdateId(lastUpdateId: Long) {
        saveData(_dataFlow.value.copy(lastUpdateId = lastUpdateId))
    }

    fun deleteChat(chatId: String) {
        val currentChats = _dataFlow.value.chats.toMutableList()
        currentChats.removeIf { it.id == chatId }
        saveChats(currentChats)
    }
}
