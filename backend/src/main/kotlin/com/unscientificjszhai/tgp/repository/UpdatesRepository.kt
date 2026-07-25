package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
/**
 * 已持久化的 Telegram 更新处理状态。
 *
 * 聊天列表与最后已处理更新标识必须来自同一保存快照，以避免在重启后重复处理更新。
 *
 * 聊天信息列表字段保存已发现的聊天；没有已保存聊天时为空列表。
 * 最后已处理更新标识字段为 `0` 时，表示尚未初始化。
 */
data class UpdatesData(
    val chats: List<ChatInfo> = emptyList(),
    val lastUpdateId: Long = 0,
)

@Singleton
/**
 * 持久化 Telegram 聊天信息和更新处理进度。
 *
 * 所有保存操作会覆盖 `config/updates.json` 并更新状态流；实例应在应用关闭时随其父协程作用域取消。
 *
 * @constructor 创建更新状态仓储并从配置文件加载初始状态。
 * @param parentScope 持有仓储后台状态流的父协程作用域；取消该作用域会停止内部协程。
 */
class UpdatesRepository
@Inject
constructor(
    parentScope: CoroutineScope,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])

    private val configFile = File("config/updates.json")

    private val _dataFlow = MutableStateFlow(loadData())

    /**
     * 已保存聊天信息的只读状态流。
     *
     * 新订阅者会立即收到当前列表；保存或删除聊天后会收到完整的新列表。
     */
    val chatsFlow: StateFlow<List<ChatInfo>> =
        _dataFlow
            .map { it.chats }
            .stateIn(scope, SharingStarted.Eagerly, _dataFlow.value.chats)

    /**
     * 当前内存中最后已处理的更新标识。
     *
     * 值为 `0` 表示尚未初始化；该属性不触发磁盘读取。
     */
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
            ConfigJson.decodeFromString<UpdatesData>(content)
        } catch (e: Exception) {
            try {
                val chats = ConfigJson.decodeFromString<List<ChatInfo>>(content)
                val migratedData = UpdatesData(chats = chats, lastUpdateId = 0)
                configFile.writeText(ConfigJson.encodeToString(migratedData))
                logger.info("Successfully migrated updates data from old format")
                migratedData
            } catch (_: Exception) {
                logger.error("Error while loading updates data", e)
                UpdatesData()
            }
        }
    }

    private fun saveData(data: UpdatesData) {
        val content = ConfigJson.encodeToString(data)
        configFile.writeText(content)
        _dataFlow.value = data
    }

    /**
     * 保存完整的聊天信息列表。
     *
     * 此操作会覆盖此前的聊天列表并同步写入配置文件。
     *
     * @param chats 要保存的完整聊天列表，不能为空；允许为空列表以清空所有聊天。
     * @throws Exception 配置文件无法编码或写入时抛出。
     */
    fun saveChats(chats: List<ChatInfo>) {
        saveData(_dataFlow.value.copy(chats = chats))
    }

    /**
     * 保存最后已处理的更新标识。
     *
     * 此操作会保留当前聊天列表并同步写入配置文件。
     *
     * @param lastUpdateId 要保存的更新标识，应为非负数；`0` 表示未初始化。
     * @throws Exception 配置文件无法编码或写入时抛出。
     */
    fun saveLastUpdateId(lastUpdateId: Long) {
        saveData(_dataFlow.value.copy(lastUpdateId = lastUpdateId))
    }

    /**
     * 删除指定标识的聊天信息。
     *
     * 未找到匹配聊天时仍会保存当前列表；删除后会发布新的 [chatsFlow] 值。
     *
     * @param chatId 要删除的聊天标识，不能为空；按完全相等的字符串匹配。
     * @throws Exception 配置文件无法编码或写入时抛出。
     */
    fun deleteChat(chatId: String) {
        val currentChats = _dataFlow.value.chats.toMutableList()
        currentChats.removeIf { it.id == chatId }
        saveChats(currentChats)
    }
}
