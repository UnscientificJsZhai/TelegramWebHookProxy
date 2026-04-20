package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class UpdatesData(
    val chats: List<ChatInfo> = emptyList(),
    val lastUpdateId: Long = 0,
)

@Singleton
class UpdatesRepository
@Inject
constructor(
    parentScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])

    private val configFile = File("config/updates.json")
    private val json = Json { prettyPrint = true }

    private val _dataFlow = MutableStateFlow(loadData())
    val chatsFlow: StateFlow<List<ChatInfo>> =
        _dataFlow
            .map { it.chats }
            .stateIn(scope, SharingStarted.Eagerly, _dataFlow.value.chats)

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
