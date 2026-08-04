package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ResourceLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单个 Telegram 机器人的已持久化更新处理状态。
 *
 * 聊天列表与最后已处理更新标识来自同一机器人快照；`lastUpdateId` 为 `0` 表示尚未
 * 初始化该机器人的轮询偏移量。
 *
 * @property chats 已发现的聊天信息列表；没有已保存聊天时为空列表。
 * @property lastUpdateId 最后已处理的更新标识；`0` 表示尚未初始化。
 * @property pendingTelegramReplies 已持久化但尚未被 Telegram 确认接受的回复，按 [PendingTelegramReply.updateId]
 * 升序且在同一更新标识下唯一；旧文件缺少该字段时默认为空列表。
 */
@Serializable
data class UpdatesData(
    val chats: List<ChatInfo> = emptyList(),
    val lastUpdateId: Long = 0,
    val pendingTelegramReplies: List<PendingTelegramReply> = emptyList(),
)

/**
 * 已由 Agent 生成、等待 Telegram 接受的回复。
 *
 * 每个机器人内同一 [updateId] 最多保留一项。回复以至少一次语义投递：网络结果不确定或 Telegram
 * 拒绝时会保留该记录，因而调用方不得把多次投递当作恰好一次。
 *
 * @property updateId 生成该回复的 Telegram 更新标识；必须为非负数，且在同一机器人 outbox 中唯一。
 * @property chatId 回复目标聊天标识；不能为空。
 * @property text 要投递的非空回复文本。
 * @property replyParameters 可选的原消息回复参数；为 `null` 时发送独立消息。
 */
@Serializable
data class PendingTelegramReply(
    val updateId: Long,
    val chatId: String,
    val text: String,
    val replyParameters: ReplyParameters? = null,
)

/**
 * 所有机器人的更新处理状态。
 *
 * 键为 Telegram token 中冒号前的非空 bot 标识。空键不会被写入，以免未配置令牌时
 * 不同机器人意外共享状态。
 *
 * @property bots 以 bot 标识为键的更新处理状态；没有已访问机器人时为空映射。
 */
@Serializable
data class BotUpdatesData(
    val bots: Map<String, UpdatesData> = emptyMap(),
)

/**
 * 持久化按 Telegram bot 标识隔离的聊天信息和更新处理进度。
 *
 * 同一 bot 标识的读取、变更和写入由同一实例锁串行化，因而聊天发现、聊天删除和偏移量
 * 更新不会互相覆盖。旧的单机器人文件在第一个有效 bot 标识访问状态时迁移给该机器人。
 *
 * 创建时从配置文件加载初始状态。应用父协程作用域只用于保持与现有注入 API 的生命周期一致，
 * 当前仓储不会创建后台协程。
 */
@Singleton
class UpdatesRepository private constructor(
    configFile: File,
    private var state: BotUpdatesData,
    private var legacyData: UpdatesData?,
    private val beforeSaveForTesting: (BotUpdatesData) -> Unit,
    fileOperations: AtomicJsonFileOperations,
    private var requiresStorageValidationBeforeWrite: Boolean,
) {
    private val logger = LoggerFactory.getLogger(UpdatesRepository::class.java)
    private val storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.UPDATES_BYTES, fileOperations)

    /**
     * 创建使用默认配置文件的更新状态仓储。
     *
     * @constructor 创建使用 `config/updates.json` 的仓储；父作用域仅用于保持与现有注入 API 的生命周期一致。
     * @param parentScope 应用父协程作用域；当前仓储不创建后台协程。
     */
    @Inject
    constructor(@Suppress("unused") parentScope: CoroutineScope) : this(File("config/updates.json"))

    /**
     * 使用指定文件创建仅供测试使用的仓储。
     *
     * @param configFile 临时配置文件；其父目录必须允许创建和写入。
     * @return 使用 [configFile] 的新仓储。
     */
    internal constructor(configFile: File) : this(configFile, DefaultAtomicJsonFileOperations)

    /**
     * 使用保存前钩子创建仅供并发测试使用的仓储。
     *
     * @param configFile 临时配置文件；其父目录必须允许创建和写入。
     * @param beforeSaveForTesting 每次内存状态即将写入文件前调用；不得调用本仓储。
     */
    internal constructor(
        configFile: File,
        beforeSaveForTesting: (BotUpdatesData) -> Unit,
    ) : this(configFile, DefaultAtomicJsonFileOperations, beforeSaveForTesting)

    /** 为文件系统故障测试创建仓储。 */
    internal constructor(
        configFile: File,
        fileOperations: AtomicJsonFileOperations,
        beforeSaveForTesting: (BotUpdatesData) -> Unit = {},
    ) : this(configFile, load(configFile, fileOperations), beforeSaveForTesting, fileOperations)

    internal constructor(
        configFile: File,
        loaded: LoadedUpdates,
        beforeSaveForTesting: (BotUpdatesData) -> Unit = {},
        fileOperations: AtomicJsonFileOperations,
    ) : this(
        configFile,
        loaded.state,
        loaded.legacyData,
        beforeSaveForTesting,
        fileOperations,
        loaded.requiresStorageValidationBeforeWrite,
    ) {
        configFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
    }

    /**
     * 读取指定机器人的当前状态快照。
     *
     * 有效 bot 首次读取时会接收旧格式文件中的单机器人状态，并立即迁移为新格式。
     *
     * @param botId token 冒号前的非空机器人标识；空白值不会读取或迁移任何共享状态。
     * @return 该机器人的状态；[botId] 无效或不存在时返回空状态。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取，因旧格式迁移不能安全提交时抛出。
     * @throws Exception 旧格式迁移的编码或原子提交失败时抛出；内存状态不变。
     */
    @Synchronized
    fun getData(botId: String): UpdatesData {
        if (!botId.isValidBotId()) {
            return UpdatesData()
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)
        return state.bots[botId] ?: UpdatesData()
    }

    /**
     * 对指定机器人的状态执行原子的内存读改写并同步持久化结果。
     *
     * [transform] 在仓储锁内执行，不能调用回本仓储或执行长时间阻塞操作。传入无效 bot 标识
     * 时不会写文件，也不会调用 [transform]。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param transform 基于当前完整状态生成新状态的纯变换函数。
     * @return 写入后的状态；[botId] 无效时返回空状态。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    fun updateData(
        botId: String,
        transform: (UpdatesData) -> UpdatesData,
    ): UpdatesData {
        if (!botId.isValidBotId()) {
            return UpdatesData()
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)
        val updated = transform(state.bots[botId] ?: UpdatesData())
        saveState(state.copy(bots = state.bots + (botId to updated)))
        return updated
    }

    /**
     * 合并轮询中发现的聊天信息。
     *
     * 每个聊天标识只保留 [chats] 中最后一个值；未出现的已有聊天保持不变。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param chats 本次轮询发现的聊天信息；空集合不会删除已有聊天。
     * @return 合并后的完整机器人状态；[botId] 无效时返回空状态。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    fun mergeChats(botId: String, chats: Collection<ChatInfo>): UpdatesData = updateData(botId) { current ->
        if (chats.isEmpty()) {
            current
        } else {
            val merged = current.chats.associateByTo(LinkedHashMap()) { it.id }
            chats.forEach { chat -> merged[chat.id] = chat }
            current.copy(chats = merged.values.toList())
        }
    }

    /**
     * 保存指定机器人的最后已处理更新标识，同时保留其聊天列表。
     *
     * 小于当前值的标识不会倒退偏移量，以免并发完成顺序扰乱后续轮询。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param lastUpdateId 要确认的更新标识；必须为非负数，`0` 表示未初始化。
     * @return 写入后的完整机器人状态；[botId] 无效时返回空状态。
     * @throws IllegalArgumentException 当 [lastUpdateId] 小于 `0` 时抛出。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    fun saveLastUpdateId(botId: String, lastUpdateId: Long): UpdatesData {
        require(lastUpdateId >= 0) { "lastUpdateId must not be negative." }
        return updateData(botId) { current ->
            current.copy(lastUpdateId = maxOf(current.lastUpdateId, lastUpdateId))
        }
    }

    /**
     * 原子记录一轮已成功完成的 Agent 处理，并推进该机器人的更新偏移量。
     *
     * 非空 [reply] 会和 [updateId] 的偏移量在同一次文件提交中写入 outbox，先后重试不会覆盖已有
     * 同标识回复。空回复仍会在同一次提交中确认偏移量，表示该 Agent 回合已经完成且无需投递。文件
     * 提交失败时内存状态保持不变，调用方必须保留该更新并停止继续确认后续更新。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 已成功完成的 Telegram 更新标识；必须为非负数。
     * @param reply 要投递的 Agent 回复；为 `null` 表示成功但无回复。
     * @return 写入后的完整机器人状态；[botId] 无效时返回空状态。
     * @throws IllegalArgumentException 当 [updateId] 为负数，或 [reply] 与 [updateId]、文本约束不一致时抛出。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    fun completeAgentUpdate(
        botId: String,
        updateId: Long,
        reply: PendingTelegramReply? = null,
    ): UpdatesData {
        require(updateId >= 0) { "updateId must not be negative." }
        reply?.let {
            require(it.updateId == updateId) { "reply updateId must match updateId." }
            require(it.chatId.isNotBlank()) { "reply chatId must not be blank." }
            require(it.text.isNotBlank()) { "reply text must not be blank." }
        }
        return updateData(botId) { current ->
            val replies = when {
                reply == null -> current.pendingTelegramReplies
                current.pendingTelegramReplies.any { it.updateId == updateId } -> current.pendingTelegramReplies
                else -> (current.pendingTelegramReplies + reply).sortedBy { it.updateId }
            }
            current.copy(
                lastUpdateId = maxOf(current.lastUpdateId, updateId),
                pendingTelegramReplies = replies,
            )
        }
    }

    /**
     * 读取指定机器人的待投递 Telegram 回复快照。
     *
     * 只返回其源更新已经被持久化确认的记录，结果始终按更新标识升序，便于单消费者按顺序投递。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @return 可投递回复的升序快照；[botId] 无效或没有待投递回复时返回空列表。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取，因旧格式迁移不能安全提交时抛出。
     * @throws Exception 旧格式迁移的编码或原子提交失败时抛出；内存状态不变。
     */
    fun getPendingTelegramReplies(botId: String): List<PendingTelegramReply> {
        val data = getData(botId)
        return data.pendingTelegramReplies
            .asSequence()
            .filter { it.updateId <= data.lastUpdateId }
            .sortedBy { it.updateId }
            .toList()
    }

    /**
     * 删除指定机器人 outbox 中源自一项更新的回复。
     *
     * 调用方只能在 Telegram 同时返回 HTTP `2xx` 与 API `ok: true` 后调用。不存在匹配项时仍会安全保留
     * 其他回复和聊天、偏移量。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要删除的回复所属更新标识；必须为非负数。
     * @return 写入后的完整机器人状态；[botId] 无效时返回空状态。
     * @throws IllegalArgumentException 当 [updateId] 为负数时抛出。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    fun deletePendingTelegramReply(botId: String, updateId: Long): UpdatesData {
        require(updateId >= 0) { "updateId must not be negative." }
        return updateData(botId) { current ->
            current.copy(pendingTelegramReplies = current.pendingTelegramReplies.filterNot { it.updateId == updateId })
        }
    }

    /**
     * 删除指定机器人保存的一个聊天。
     *
     * 删除与聊天发现共用同一读改写事务，因此不会覆盖轮询同时发现的其他聊天。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param chatId 要删除的聊天标识；按完全相等的字符串匹配。
     * @return 删除后的完整机器人状态；[botId] 无效时返回空状态。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    fun deleteChat(botId: String, chatId: String): UpdatesData = updateData(botId) { current ->
        current.copy(chats = current.chats.filterNot { it.id == chatId })
    }

    /**
     * 读取指定机器人已保存聊天的快照。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @return 聊天信息列表；[botId] 无效或没有聊天时为空列表。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取，因旧格式迁移不能安全提交时抛出。
     * @throws Exception 旧格式迁移的编码或原子提交失败时抛出；内存状态不变。
     */
    fun getChats(botId: String): List<ChatInfo> = getData(botId).chats

    private fun migrateLegacyDataIfNeeded(botId: String) {
        val legacy = legacyData ?: return
        val migratedState = state.copy(bots = state.bots + (botId to legacy))
        saveState(migratedState)
        legacyData = null
        logger.info("Successfully migrated legacy updates data to bot {}", botId)
    }

    private fun saveState(newState: BotUpdatesData) {
        ensureStorageValidatedBeforeMutation()
        beforeSaveForTesting(newState)
        storage.commit(ConfigJson.encodeToString(newState).toByteArray(StandardCharsets.UTF_8))
        state = newState
    }

    internal data class LoadedUpdates(
        val state: BotUpdatesData,
        val legacyData: UpdatesData?,
        val requiresStorageValidationBeforeWrite: Boolean = false,
    )

    private fun ensureStorageValidatedBeforeMutation() {
        if (!requiresStorageValidationBeforeWrite) {
            return
        }
        val validated = when (val read = storage.readValidatedAndRecover(::decodeLoadedUpdates)) {
            AtomicJsonRead.Missing -> LoadedUpdates(BotUpdatesData(), null)
            is AtomicJsonRead.Valid -> read.value
            is AtomicJsonRead.Corrupt -> throw IllegalStateException(
                "更新状态文件及备份均已损坏，拒绝覆盖现场。",
                read.cause
            )

            is AtomicJsonRead.IoFailure -> throw IllegalStateException(
                "更新状态文件尚不可读取，拒绝覆盖现场。",
                read.cause
            )

            is AtomicJsonRead.RecoveryFailed ->
                throw IllegalStateException("有效更新备份无法恢复主文件，拒绝覆盖现场。", read.cause)

            is AtomicJsonRead.RecoverabilityPending ->
                throw IllegalStateException("更新状态备份尚不可读取或验证，拒绝覆盖现场。", read.cause)
        }
        state = validated.state
        legacyData = validated.legacyData
        requiresStorageValidationBeforeWrite = false
    }

    private companion object {
        fun load(configFile: File, fileOperations: AtomicJsonFileOperations): LoadedUpdates {
            val storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.UPDATES_BYTES, fileOperations)
            return when (val read = storage.readValidatedAndRecover(::decodeLoadedUpdates)) {
                AtomicJsonRead.Missing -> LoadedUpdates(BotUpdatesData(), null)
                is AtomicJsonRead.Valid -> read.value
                is AtomicJsonRead.Corrupt -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Updates file and its backup are semantically invalid; preserving both files",
                        read.cause,
                    )
                    LoadedUpdates(BotUpdatesData(), null, requiresStorageValidationBeforeWrite = true)
                }

                is AtomicJsonRead.IoFailure -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Unable to read updates data; delaying writes until it can be revalidated",
                        read.cause,
                    )
                    LoadedUpdates(BotUpdatesData(), null, requiresStorageValidationBeforeWrite = true)
                }

                is AtomicJsonRead.RecoveryFailed -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Validated updates backup could not be restored; preserving files and disabling writes",
                        read.cause,
                    )
                    LoadedUpdates(BotUpdatesData(), null, requiresStorageValidationBeforeWrite = true)
                }

                is AtomicJsonRead.RecoverabilityPending -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Updates recovery is blocked by I/O; delaying writes until revalidation",
                        read.cause,
                    )
                    LoadedUpdates(BotUpdatesData(), null, requiresStorageValidationBeforeWrite = true)
                }
            }
        }

        fun decodeLoadedUpdates(bytes: ByteArray): LoadedUpdates {
            val content = bytes.toString(StandardCharsets.UTF_8)
            if (content.isBlank()) {
                throw IllegalArgumentException("Updates data must not be blank")
            }
            return when (val root = ConfigJson.parseToJsonElement(content)) {
                is JsonArray -> LoadedUpdates(
                    BotUpdatesData(),
                    UpdatesData(chats = ConfigJson.decodeFromString<List<ChatInfo>>(content)),
                )

                is JsonObject -> {
                    // 先检查格式标志。因为 ConfigJson 忽略未知字段，直接尝试旧格式会把
                    // {"bots": ...} 静默解码成空的 UpdatesData，导致状态丢失。
                    if (root.containsKey("bots")) {
                        LoadedUpdates(ConfigJson.decodeFromString(content), null)
                    } else {
                        LoadedUpdates(BotUpdatesData(), ConfigJson.decodeFromString(content))
                    }
                }

                else -> throw IllegalArgumentException("Unsupported updates data format")
            }
        }
    }
}

/**
 * 从 Telegram token 提取用于持久化隔离的 bot 标识。
 *
 * @receiver Telegram Bot token；冒号前的 bot 标识和冒号后的密钥部分均须非空白。
 * @return 规范化后的 bot 标识；token 缺少有效标识或密钥部分时返回 `null`。
 */
internal fun String.botIdFromTelegramToken(): String? {
    val separator = indexOf(':')
    if (separator <= 0 || separator == lastIndex) {
        return null
    }
    val botId = substring(0, separator).trim()
    val secret = substring(separator + 1).trim()
    return botId.takeIf { it.isNotEmpty() && secret.isNotEmpty() }
}

private fun String.isValidBotId(): Boolean = isNotBlank()
