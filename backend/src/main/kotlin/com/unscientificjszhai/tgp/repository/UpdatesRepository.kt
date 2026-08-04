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

/** 回退 Telegram 回复在进程崩溃语义下允许持久化登记的最大投递次数。 */
internal const val MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS = 3

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
 * @property agentTurnJournal 按更新标识隔离的 Agent 回合账本；旧文件缺少该字段时默认为空列表。
 */
@Serializable
data class UpdatesData(
    val chats: List<ChatInfo> = emptyList(),
    val lastUpdateId: Long = 0,
    val pendingTelegramReplies: List<PendingTelegramReply> = emptyList(),
    val agentTurnJournal: List<AgentTurnJournalEntry> = emptyList(),
)

/**
 * 一项由轮询器持久化保护的 Agent 回合。
 *
 * 回合在任何模型、工具或外部调用前先写入 `IN_PROGRESS`。仅当 Agent 返回结果（包括空回复）后才会转为
 * `FINAL`；进程重启后遇到没有本地 owner 的进行中回合，调用方必须降级为固定失败回复而不能重放 Agent。
 * 该记录保留回复目标，保证最终状态即使跨重启也能原子写入 outbox 与更新偏移量。
 *
 * @property updateId 该回合所属的 Telegram 更新标识；必须为非负数，且同一机器人内唯一。
 * @property chatId 最终回复目标聊天标识；不能为空。
 * @property replyParameters 回复原消息的可选参数；可以为 `null`。
 * @property status 回合状态；只能从 [AgentTurnJournalStatus.IN_PROGRESS] 迁移到 [AgentTurnJournalStatus.FINAL]。
 * @property reply 最终 Agent 回复；仅在 [AgentTurnJournalStatus.FINAL] 时有意义，`null` 表示成功但无需回复。
 */
@Serializable
data class AgentTurnJournalEntry(
    val updateId: Long,
    val chatId: String,
    val replyParameters: ReplyParameters? = null,
    val status: AgentTurnJournalStatus,
    val reply: String? = null,
)

/** Agent 回合持久化账本的状态。 */
@Serializable
enum class AgentTurnJournalStatus {
    /** 已安全占有回合，但尚未允许重放 Agent 或其工具调用。 */
    IN_PROGRESS,

    /** 回合结果已经持久化，可仅重试 outbox 与偏移量提交。 */
    FINAL,
}

/** 一次持久化 Agent 回合占有的结果。 */
internal sealed interface AgentTurnClaim {
    /** 当前调用方已持久化占有一个新回合，可以紧邻地调用一次 Agent。 */
    data object CLAIMED : AgentTurnClaim

    /** 回合已有最终结果，调用方只能重试提交该结果，不能再进入 Agent。 */
    data class FINAL(val entry: AgentTurnJournalEntry) : AgentTurnClaim

    /** 记录来自失联进程或已结束 owner；调用方必须先降级为失败结果。 */
    data class InProgress(val entry: AgentTurnJournalEntry) : AgentTurnClaim

    /** 更新偏移量已确认，调用方不再执行或提交该回合。 */
    data object AlreadyConfirmed : AgentTurnClaim
}

/**
 * 已由 Agent 生成、等待 Telegram 接受的回复。
 *
 * 每个机器人内同一 [updateId] 最多保留一项。回复以至少一次语义投递：网络结果不确定或 Telegram
 * 拒绝时会保留该记录，因而调用方不得把多次投递当作恰好一次。每次网络投递前都会先持久化
 * [deliveryAttempts]，因此进程在请求中断后可能少于该次数实际发送，但绝不会突破回退消息的投递上限。
 *
 * @property updateId 生成该回复的 Telegram 更新标识；必须为非负数，且在同一机器人 outbox 中唯一。
 * @property chatId 回复目标聊天标识；不能为空。
 * @property text 要投递的非空回复文本。
 * @property replyParameters 可选的原消息回复参数；为 `null` 时发送独立消息。
 * @property deliveryStage 当前投递阶段；旧文件缺少该字段时默认为 [TelegramReplyDeliveryStage.ORIGINAL]。
 * @property deliveryAttempts 当前 [deliveryStage] 已持久化的投递次数；必须为非负数，切换阶段时归零。
 * @property permanentRejectionCount 原文阶段连续出现的永久 `4xx` 拒绝次数；仅
 * [TelegramReplyDeliveryStage.ORIGINAL] 使用且取值只能为 `0` 或 `1`。任一可重试失败都会将其清零，
 * 旧文件缺少该字段时默认为 `0`。
 */
@Serializable
data class PendingTelegramReply(
    val updateId: Long,
    val chatId: String,
    val text: String,
    val replyParameters: ReplyParameters? = null,
    val deliveryStage: TelegramReplyDeliveryStage = TelegramReplyDeliveryStage.ORIGINAL,
    val deliveryAttempts: Int = 0,
    val permanentRejectionCount: Int = 0,
)

/**
 * 等待投递的 Telegram 回复所处的阶段。
 *
 * 原文收到两次连续的永久 `4xx` 拒绝后会切换到 [FALLBACK]；可重试失败会清除原文的连续拒绝计数。回退消息
 * 始终作为不引用原消息的独立消息发送。
 */
@Serializable
enum class TelegramReplyDeliveryStage {
    /** 投递 Agent 生成的原始回复；连续永久拒绝计数最多为 `1`。 */
    ORIGINAL,

    /** 投递说明原始回复未能发送的固定回退消息。 */
    FALLBACK,
}

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
 * 同一机器人标识的读取、变更和写入由同一实例锁串行化，因而聊天发现、聊天删除和偏移量
 * 更新不会互相覆盖。旧的单机器人文件在第一个有效 bot 标识访问状态时迁移给该机器人。
 *
 * @constructor 创建更新状态仓储并从配置文件加载初始状态。
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

    private constructor(
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取，因旧格式迁移不能安全提交时抛出。
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    fun completeAgentUpdate(
        botId: String,
        updateId: Long,
        reply: PendingTelegramReply? = null,
    ): UpdatesData {
        require(updateId >= 0) { "updateId must not be negative." }
        reply?.let {
            validatePendingTelegramReply(it, updateId)
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
     * 只读获取一项 Agent 回合账本记录。
     *
     * 该方法不会迁移旧格式或清理记录，便于轮询器在 AI 可用性、授权等接纳判断之前优先调和已持久化的
     * FINAL 或 IN_PROGRESS，避免后续偏移量确认覆盖尚未提交的回合。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要查询的 Telegram 更新标识；必须为非负数。
     * @return 对应账本记录的不可变快照；不存在或 bot 无效时返回 `null`。
     * @throws IllegalArgumentException 当 [updateId] 为负数时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出。
     */
    @Synchronized
    internal fun findAgentTurn(botId: String, updateId: Long): AgentTurnJournalEntry? {
        require(updateId >= 0) { "updateId must not be negative." }
        if (!botId.isValidBotId()) {
            return null
        }
        ensureStorageValidatedBeforeMutation()
        val entry = state.bots[botId]?.agentTurnJournal?.singleOrNull { it.updateId == updateId }
        entry?.let(::validateAgentTurnJournalEntry)
        return entry
    }

    /**
     * 在调用 Agent 前持久化占有一项回合，或返回其已有的不可重放状态。
     *
     * 该方法是同一仓储实例内的原子读改写操作。它绝不会把已有的 [AgentTurnJournalStatus.IN_PROGRESS]
     * 直接交给 Agent 重放；调用方必须结合本地 owner 判定该回合是否仍在运行，并将失联回合降级为失败。
     * 已确认更新的残留 FINAL 账本会在本次调用中安全回收。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要占有的 Telegram 更新标识；必须为非负数。
     * @param chatId 最终回复的聊天标识；不能为空。
     * @param replyParameters 可选的原消息回复参数；可以为 `null`。
     * @return 新占有的 [AgentTurnClaim.CLAIMED]、已有 FINAL 或 IN_PROGRESS 状态；偏移量已确认时返回
     * `AgentTurnClaim.AlreadyConfirmed`。
     * @throws IllegalArgumentException 当输入不满足账本约束时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出。
     * @throws Exception 当状态无法原子写入时抛出；调用方不得进入 Agent。
     */
    @Synchronized
    internal fun claimAgentTurn(
        botId: String,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters?,
    ): AgentTurnClaim {
        require(updateId >= 0) { "updateId must not be negative." }
        require(chatId.isNotBlank()) { "chatId must not be blank." }
        if (!botId.isValidBotId()) {
            return AgentTurnClaim.AlreadyConfirmed
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: UpdatesData()
        val existing = current.agentTurnJournal.singleOrNull { it.updateId == updateId }
        if (existing != null) {
            validateAgentTurnJournalEntry(existing, updateId)
            if (existing.status == AgentTurnJournalStatus.FINAL && updateId <= current.lastUpdateId) {
                saveState(
                    state.copy(
                        bots = state.bots + (
                                botId to current.copy(
                                    agentTurnJournal = current.agentTurnJournal.filterNot { it.updateId == updateId },
                                )
                                ),
                    ),
                )
                return AgentTurnClaim.AlreadyConfirmed
            }
            return when (existing.status) {
                AgentTurnJournalStatus.IN_PROGRESS -> AgentTurnClaim.InProgress(existing)
                AgentTurnJournalStatus.FINAL -> AgentTurnClaim.FINAL(existing)
            }
        }
        if (updateId <= current.lastUpdateId) {
            return AgentTurnClaim.AlreadyConfirmed
        }
        val claimed = AgentTurnJournalEntry(
            updateId = updateId,
            chatId = chatId,
            replyParameters = replyParameters,
            status = AgentTurnJournalStatus.IN_PROGRESS,
        )
        saveState(
            state.copy(
                bots = state.bots + (botId to current.copy(agentTurnJournal = current.agentTurnJournal + claimed)),
            ),
        )
        return AgentTurnClaim.CLAIMED
    }

    /**
     * 将已占有的 Agent 回合原子转为最终结果。
     *
     * 只有完全匹配的进行中记录才能转为 FINAL，防止迟到 owner 覆盖已降级或已完成的结果。成功返回后，
     * 调用方可以重试 [completeAgentUpdate]，而绝不能再次调用 Agent。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要完成的 Telegram 更新标识；必须为非负数。
     * @param reply 最终 Agent 回复；`null` 表示无需发送回复。
     * @return 写入后的 FINAL 记录；记录不存在、已完成或 bot 无效时返回 `null`。
     * @throws IllegalArgumentException 当更新标识或回复不满足账本约束时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出。
     * @throws Exception 当状态无法原子写入时抛出。
     */
    @Synchronized
    internal fun finalizeAgentTurn(
        botId: String,
        updateId: Long,
        reply: String?,
    ): AgentTurnJournalEntry? {
        require(updateId >= 0) { "updateId must not be negative." }
        validateAgentTurnReply(reply)
        if (!botId.isValidBotId()) {
            return null
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: return null
        val entryIndex = current.agentTurnJournal.indexOfFirst { it.updateId == updateId }
        if (entryIndex < 0) {
            return null
        }
        val existing = current.agentTurnJournal[entryIndex]
        validateAgentTurnJournalEntry(existing, updateId)
        if (existing.status != AgentTurnJournalStatus.IN_PROGRESS) {
            return null
        }
        val finalized = existing.copy(status = AgentTurnJournalStatus.FINAL, reply = reply)
        val journal = current.agentTurnJournal.toMutableList().also { it[entryIndex] = finalized }
        saveState(state.copy(bots = state.bots + (botId to current.copy(agentTurnJournal = journal))))
        return finalized
    }

    /**
     * 将失联的进行中 Agent 回合原子降级为固定失败回复。
     *
     * 调用方只可在不存在本地 owner 时调用；该规则使崩溃、Agent 异常和 FINAL 写入失败都 fail-closed，
     * 不会自动重放任何模型或工具副作用。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要降级的 Telegram 更新标识；必须为非负数。
     * @param failureReply 用于最终 outbox 的固定非空失败回复。
     * @return 已持久化的 FINAL 记录；记录不存在、已不是进行中状态或 bot 无效时返回 `null`。
     * @throws IllegalArgumentException 当输入不满足账本约束时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出。
     * @throws Exception 当状态无法原子写入时抛出。
     */
    @Synchronized
    internal fun failInProgressAgentTurn(
        botId: String,
        updateId: Long,
        failureReply: String,
    ): AgentTurnJournalEntry? {
        require(failureReply.isNotBlank()) { "failureReply must not be blank." }
        return finalizeAgentTurn(botId, updateId, failureReply)
    }

    /**
     * 删除已由偏移量确认的 FINAL Agent 回合残留。
     *
     * 删除失败不改变已确认更新或 outbox；后续会话可再次调用本方法安全回收。进行中记录永远不会被此方法删除。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @return 本次删除的记录数量；bot 无效或没有可回收记录时返回 `0`。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出。
     * @throws Exception 当状态无法原子写入时抛出。
     */
    @Synchronized
    internal fun cleanupConfirmedAgentTurns(botId: String): Int {
        if (!botId.isValidBotId()) {
            return 0
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: return 0
        val remaining = current.agentTurnJournal.filterNot { entry ->
            validateAgentTurnJournalEntry(entry)
            entry.status == AgentTurnJournalStatus.FINAL && entry.updateId <= current.lastUpdateId
        }
        val removed = current.agentTurnJournal.size - remaining.size
        if (removed > 0) {
            saveState(state.copy(bots = state.bots + (botId to current.copy(agentTurnJournal = remaining))))
        }
        return removed
    }

    /**
     * 读取指定机器人的待投递 Telegram 回复快照。
     *
     * 只返回其源更新已经被持久化确认的记录，结果始终按更新标识升序，便于单消费者按顺序投递。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @return 可投递回复的升序快照；[botId] 无效或没有待投递回复时返回空列表。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取，因旧格式迁移不能安全提交时抛出。
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
     * 为一项待投递回复持久化登记下一次网络投递。
     *
     * 登记与读取、修改和文件提交在同一仓储锁内完成；文件提交失败时不会返回可发送记录。处于回退阶段且
     * 已登记三次投递的记录会在本次调用中删除，并返回 `null`，使调用方可以继续处理下一项回复。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要登记投递的回复所属更新标识；必须为非负数。
     * @return 已把 [PendingTelegramReply.deliveryAttempts] 加一并持久化的回复；不存在记录、bot 无效或回退次数
     * 耗尽并已删除时返回 `null`。
     * @throws IllegalArgumentException 当 [updateId] 为负数，或存储中的目标回复违反投递阶段约束时抛出。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    internal fun preparePendingTelegramReplyDelivery(botId: String, updateId: Long): PendingTelegramReply? {
        require(updateId >= 0) { "updateId must not be negative." }
        if (!botId.isValidBotId()) {
            return null
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)

        val current = state.bots[botId] ?: return null
        val replyIndex = current.pendingTelegramReplies.indexOfFirst { it.updateId == updateId }
        if (replyIndex < 0) {
            return null
        }
        val reply = current.pendingTelegramReplies[replyIndex]
        validatePendingTelegramReply(reply, updateId)
        if (reply.deliveryStage == TelegramReplyDeliveryStage.FALLBACK &&
            reply.deliveryAttempts >= MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS
        ) {
            saveState(
                state.copy(
                    bots = state.bots + (
                            botId to current.copy(
                                pendingTelegramReplies = current.pendingTelegramReplies.filterNot { it.updateId == updateId },
                            )
                            ),
                ),
            )
            return null
        }
        require(reply.deliveryAttempts < Int.MAX_VALUE) { "reply deliveryAttempts must be below Int.MAX_VALUE." }
        val prepared = reply.copy(deliveryAttempts = reply.deliveryAttempts + 1)
        val replies = current.pendingTelegramReplies.toMutableList().also { it[replyIndex] = prepared }
        saveState(state.copy(bots = state.bots + (botId to current.copy(pendingTelegramReplies = replies))))
        return prepared
    }

    /**
     * 以匹配的现有快照原子替换一项待投递回复。
     *
     * 只有当前记录与 [expected] 完全相等时才提交 [replacement]，从而不会以迟到网络响应覆盖已被其他会话
     * 更新的投递阶段或次数。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param expected 网络请求前已持久化的回复快照；其更新标识必须非负数。
     * @param replacement 要替换为的回复；其更新标识、聊天标识和阶段计数约束必须与 [expected] 一致。
     * @return 仅当匹配并已持久化替换时为 `true`；bot 无效或记录已变化、不存在时为 `false`。
     * @throws IllegalArgumentException 当 [expected] 或 [replacement] 违反回复约束，或更新标识不一致时抛出。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    internal fun replacePendingTelegramReply(
        botId: String,
        expected: PendingTelegramReply,
        replacement: PendingTelegramReply,
    ): Boolean {
        validatePendingTelegramReply(expected)
        validatePendingTelegramReply(replacement, expected.updateId)
        require(replacement.chatId == expected.chatId) { "replacement chatId must match expected reply." }
        if (!botId.isValidBotId()) {
            return false
        }
        ensureStorageValidatedBeforeMutation()
        migrateLegacyDataIfNeeded(botId)

        val current = state.bots[botId] ?: return false
        val replyIndex = current.pendingTelegramReplies.indexOfFirst { it == expected }
        if (replyIndex < 0) {
            return false
        }
        val replies = current.pendingTelegramReplies.toMutableList().also { it[replyIndex] = replacement }
        saveState(state.copy(bots = state.bots + (botId to current.copy(pendingTelegramReplies = replies))))
        return true
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
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
     * @throws IllegalStateException 配置文件已损坏或暂不可读取，因旧格式迁移不能安全提交时抛出。
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

    private fun validatePendingTelegramReply(reply: PendingTelegramReply, expectedUpdateId: Long? = null) {
        expectedUpdateId?.let { require(reply.updateId == it) { "reply updateId must match updateId." } }
        require(reply.updateId >= 0) { "reply updateId must not be negative." }
        require(reply.chatId.isNotBlank()) { "reply chatId must not be blank." }
        require(reply.text.isNotBlank()) { "reply text must not be blank." }
        require(reply.deliveryAttempts >= 0) { "reply deliveryAttempts must not be negative." }
        require(reply.permanentRejectionCount >= 0) { "reply permanentRejectionCount must not be negative." }
        when (reply.deliveryStage) {
            TelegramReplyDeliveryStage.ORIGINAL -> {
                require(reply.permanentRejectionCount <= 1) {
                    "original reply permanentRejectionCount must not exceed one."
                }
            }

            TelegramReplyDeliveryStage.FALLBACK -> {
                require(reply.replyParameters == null) { "fallback reply must not use replyParameters." }
                require(reply.permanentRejectionCount == 0) {
                    "fallback reply permanentRejectionCount must be zero."
                }
            }
        }
    }

    private fun validateAgentTurnJournalEntry(entry: AgentTurnJournalEntry, expectedUpdateId: Long? = null) {
        expectedUpdateId?.let { require(entry.updateId == it) { "agent turn updateId must match updateId." } }
        require(entry.updateId >= 0) { "agent turn updateId must not be negative." }
        require(entry.chatId.isNotBlank()) { "agent turn chatId must not be blank." }
        validateAgentTurnReply(entry.reply)
        when (entry.status) {
            AgentTurnJournalStatus.IN_PROGRESS -> {
                require(entry.reply == null) { "in-progress agent turn must not contain a reply." }
            }

            AgentTurnJournalStatus.FINAL -> Unit
        }
    }

    private data class LoadedUpdates(
        val state: BotUpdatesData,
        val legacyData: UpdatesData?,
        val requiresStorageValidationBeforeWrite: Boolean = false,
    )

    private fun ensureStorageValidatedBeforeMutation() {
        if (!requiresStorageValidationBeforeWrite) {
            return
        }
        val validated = when (val read = storage.readValidated(::decodeLoadedUpdates)) {
            AtomicJsonRead.Missing -> LoadedUpdates(BotUpdatesData(), null)
            is AtomicJsonRead.Valid -> read.value
            is AtomicJsonRead.Corrupt -> throw IllegalStateException("更新状态文件已损坏，拒绝覆盖现场。", read.cause)
            is AtomicJsonRead.IoFailure -> throw IllegalStateException(
                "更新状态文件尚不可读取，拒绝覆盖现场。",
                read.cause
            )
        }
        state = validated.state
        legacyData = validated.legacyData
        requiresStorageValidationBeforeWrite = false
    }

    private companion object {
        fun load(configFile: File, fileOperations: AtomicJsonFileOperations): LoadedUpdates {
            val storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.UPDATES_BYTES, fileOperations)
            return when (val read = storage.readValidated(::decodeLoadedUpdates)) {
                AtomicJsonRead.Missing -> LoadedUpdates(BotUpdatesData(), null)
                is AtomicJsonRead.Valid -> read.value
                is AtomicJsonRead.Corrupt -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Updates file is semantically invalid; preserving it",
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

            }
        }

        fun decodeLoadedUpdates(bytes: ByteArray): LoadedUpdates {
            val content = bytes.toString(StandardCharsets.UTF_8)
            if (content.isBlank()) {
                throw IllegalArgumentException("Updates data must not be blank")
            }
            val decoded = when (val root = ConfigJson.parseToJsonElement(content)) {
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
            decoded.state.bots.forEach { (_, updates) -> validateAgentTurnJournal(updates.agentTurnJournal) }
            decoded.legacyData?.let { legacy -> validateAgentTurnJournal(legacy.agentTurnJournal) }
            return decoded
        }
    }
}

private fun validateAgentTurnReply(reply: String?) {
    require(reply == null || reply.isNotBlank()) { "agent turn reply must not be blank when present." }
}

private fun validateAgentTurnJournal(entries: List<AgentTurnJournalEntry>) {
    require(entries.map { it.updateId }.distinct().size == entries.size) {
        "agent turn journal update IDs must be unique."
    }
    entries.forEach { entry ->
        require(entry.updateId >= 0) { "agent turn updateId must not be negative." }
        require(entry.chatId.isNotBlank()) { "agent turn chatId must not be blank." }
        validateAgentTurnReply(entry.reply)
        if (entry.status == AgentTurnJournalStatus.IN_PROGRESS) {
            require(entry.reply == null) { "in-progress agent turn must not contain a reply." }
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
