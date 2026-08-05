package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.JsonElementMigration
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.SchemaValidatedJsonStorage
import com.unscientificjszhai.tgp.utils.TelegramTextChunks
import com.unscientificjszhai.tgp.utils.requireDurable
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/** 回退 Telegram 回复在进程崩溃语义下允许持久化登记的最大投递次数。 */
internal const val MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS = 3

/** 单个聊天发现记录允许占用的最大 UTF-8 JSON 字节数。 */
internal const val MAX_DISCOVERED_CHAT_UTF8_BYTES = 64 * 1024

/** 所有已发现聊天（不含其他更新状态）的最大 UTF-8 JSON 字节预算。 */
internal const val MAX_DISCOVERED_CHATS_UTF8_BYTES = 512 * 1024

/** 单个 Telegram bot 最多保留的已发现聊天数。 */
internal const val MAX_DISCOVERED_CHATS_PER_BOT = 64

/** 所有 Telegram bot 合计最多保留的已发现聊天数。 */
internal const val MAX_DISCOVERED_CHATS = 256

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
 * @property retryCheckpoint 尚未完成的轮询重试检查点；为 `null` 时下一次轮询从 [lastUpdateId] 后开始，旧文件缺少
 * 该字段时默认为 `null`。
 */
@Serializable
data class UpdatesData(
    val chats: List<ChatInfo> = emptyList(),
    val lastUpdateId: Long = 0,
    val pendingTelegramReplies: List<PendingTelegramReply> = emptyList(),
    val agentTurnJournal: List<AgentTurnJournalEntry> = emptyList(),
    val retryCheckpoint: RetryCheckpoint? = null,
)

/**
 * 一项持久化的 Telegram 更新重试检查点。
 *
 * 轮询器在无法安全确认一项更新时先原子写入该记录；只要记录存在，后续请求必须从 [targetUpdateId] 开始，
 * 直到同一次提交确认或明确跳过该目标。计数使用饱和递增，因而不会在长时间故障后回绕。
 *
 * @property targetUpdateId 必须重新取得或明确跳过的 Telegram 更新标识；必须为非负数。
 * @property firstRetryAtMillis 首次记录该目标重试的 Unix 时间戳，单位为毫秒；必须为非负数。
 * @property retryCount 已持久化记录该目标重试的次数；必须为正数，到 [Long.MAX_VALUE] 后保持饱和。
 */
@Serializable
data class RetryCheckpoint(
    val targetUpdateId: Long,
    val firstRetryAtMillis: Long,
    val retryCount: Long,
)

/** 轮询器写入重试检查点时的条件更新结果。 */
internal sealed interface RetryCheckpointRecordResult {
    /** 检查点已原子写入；[checkpoint] 是提交后的完整值。 */
    data class Recorded(val checkpoint: RetryCheckpoint) : RetryCheckpointRecordResult

    /** 当前检查点已被其他工作改变；本次调用没有改写任何状态。 */
    data object Stale : RetryCheckpointRecordResult
}

/** 确认、跳过或持久化完成一个检查点目标时的条件更新结果。 */
internal sealed interface RetryCheckpointCommitResult {
    /** 目标偏移量和检查点已在同一次文件提交中更新。 */
    data object Committed : RetryCheckpointCommitResult

    /** 当前检查点与调用方快照不符；本次调用没有改写任何状态。 */
    data object Stale : RetryCheckpointCommitResult
}

/** 因 Telegram 已不再提供目标更新而跳过检查点时的条件更新结果。 */
internal sealed interface RetryCheckpointGapResult {
    /** 已跳过目标；[checkpoint] 为用于审计的原始持久化检查点。 */
    data class Skipped(val checkpoint: RetryCheckpoint) : RetryCheckpointGapResult

    /** 当前检查点与调用方快照不符；本次调用没有改写任何状态。 */
    data object Stale : RetryCheckpointGapResult
}

/**
 * 一项由轮询器持久化保护的 Agent 回合。
 *
 * 回合在任何模型、工具或外部调用前先写入 [AgentTurnJournalStatus.IN_PROGRESS]。仅当 Agent 返回结果（包括空回复）后才会转为
 * [AgentTurnJournalStatus.FINAL]；进程重启后遇到没有本地 owner 的进行中回合，调用方必须静默确认其偏移量并删除该记录，不能
 * 重放 Agent、创建 FINAL 或创建 outbox。该记录保留回复目标，保证已成为最终状态的记录即使跨重启也能
 * 原子写入 outbox 与更新偏移量。
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

    /** 已有不可重放的进行中记录；有本地 owner 时等待重试，无 owner 时静默确认偏移量。 */
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
 * @property text 要投递的非空原始回复文本；投递回退消息不会覆盖该值。
 * @property replyParameters 可选的原消息回复参数；仅原文的首个片段使用，为 `null` 时发送独立消息。
 * @property nextChunkStart 下一待投递片段在 [text] 中的 UTF-16 起点；旧文件缺少该字段时默认为 `0`。
 * @property deliveryStage 当前片段投递阶段；旧文件缺少该字段时默认为 [TelegramReplyDeliveryStage.ORIGINAL]。
 * @property deliveryAttempts 当前片段的 [deliveryStage] 已持久化投递次数；必须为非负数，切换片段或阶段时归零。
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
    val nextChunkStart: Int = 0,
    val deliveryStage: TelegramReplyDeliveryStage = TelegramReplyDeliveryStage.ORIGINAL,
    val deliveryAttempts: Int = 0,
    val permanentRejectionCount: Int = 0,
)

/**
 * 等待投递的 Telegram 回复所处的阶段。
 *
 * 原文当前片段收到两次连续的永久 `4xx` 拒绝后会切换到 [FALLBACK]；可重试失败会清除原文的连续拒绝计数。
 * 回退消息始终作为不引用原消息的独立消息发送，且不会改写原文。
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
 * @property chatRecency 每个 bot 内已保留聊天的最近发现序号；只包含 [bots] 中仍存在的聊天。
 * @property chatRecencyClock 聊天发现时钟的当前序号；正常发现递增，达到最大值时会按既有最近使用顺序稳定
 * 重编号，因此不承诺跨重编号的绝对单调值。
 */
@Serializable
data class BotUpdatesData(
    val bots: Map<String, UpdatesData> = emptyMap(),
    val chatRecency: Map<String, Map<String, Long>> = emptyMap(),
    val chatRecencyClock: Long = 0,
)

/**
 * 持久化按 Telegram bot 标识隔离的聊天信息和更新处理进度。
 *
 * 同一 `botId` 的读取、变更和写入由同一实例锁串行化，因而聊天发现、聊天删除和偏移量
 * 更新不会互相覆盖。旧的单机器人文件在第一个有效 bot 标识访问状态时迁移给该机器人。
 * 构造仓储时会完整读取、迁移并校验主文件；JSON 结构、必填字段或业务状态严重损坏，或主文件
 * 无法读取时会抛出异常并中止初始化，不会以空状态继续运行。
 *
 * @constructor 创建更新状态仓储并从配置文件加载初始状态。
 * @throws IllegalStateException `config/updates.json` 严重损坏或无法读取时抛出。
 */
@Singleton
class UpdatesRepository private constructor(
    private var state: BotUpdatesData,
    private var legacyData: UpdatesData?,
    private val beforeSaveForTesting: (BotUpdatesData) -> Unit,
    private val storage: SchemaValidatedJsonStorage<BotUpdatesData>,
) {
    private val logger = LoggerFactory.getLogger(UpdatesRepository::class.java)

    /**
     * 创建使用默认配置文件的更新状态仓储。
     *
     * @constructor 创建使用 `config/updates.json` 的仓储；父作用域仅用于保持与现有注入 API 的生命周期一致。
     * @param parentScope 应用父协程作用域；当前仓储不创建后台协程。
     * @throws IllegalStateException `config/updates.json` 严重损坏或无法读取时抛出。
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
    ) : this(load(configFile, fileOperations), beforeSaveForTesting)

    private constructor(
        loaded: LoadedUpdates,
        beforeSaveForTesting: (BotUpdatesData) -> Unit = {},
    ) : this(
        loaded.state,
        loaded.legacyData,
        beforeSaveForTesting,
        loaded.storage,
    )

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
        migrateLegacyDataIfNeeded(botId)
        return state.bots[botId] ?: UpdatesData()
    }

    /**
     * 对指定机器人的状态执行原子的内存读改写并同步持久化结果。
     *
     * [transform] 在仓储锁内执行，不能调用回本仓储或执行长时间阻塞操作。传入无效 bot 标识
     * 时不会写文件，也不会调用 [transform]。变换结果必须保持所有持久化不变量；尤其是存在
     * [RetryCheckpoint] 时，其目标必须严格大于 `lastUpdateId`，否则会在写入前被拒绝。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param transform 基于当前完整状态生成新状态的纯变换函数。
     * @return 实际写入后的状态；聊天字段可能因全局发现缓存的数量或 UTF-8 字节预算而被裁剪，
     * [botId] 无效时返回空状态。
     * @throws IllegalArgumentException 当 [transform] 结果违反持久化状态不变量时抛出；内存状态和文件不变。
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
        migrateLegacyDataIfNeeded(botId)
        val base = normalizeState(state)
        val current = base.bots[botId] ?: UpdatesData()
        val updated = transform(current)
        val candidate = base.copy(bots = base.bots + (botId to updated))
        val withRecency = reconcileChatRecency(base, candidate, botId, current, updated)
        return saveState(withRecency).bots[botId] ?: UpdatesData()
    }

    /**
     * 合并轮询中发现的聊天信息。
     *
     * 每个聊天标识只保留 [chats] 中最后一个值；未出现的已有聊天保持不变。聊天发现使用独立的 LRU
     * 元数据，并受单 bot、全局及 UTF-8 字节预算限制；超出单项预算的聊天会在写入前被拒绝，其他裁剪只会
     * 逐出最久未发现项，不影响更新偏移量、outbox 或 Agent 账本。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param chats 本次轮询发现的聊天信息；空集合不会删除已有聊天。
     * @return 实际合并并裁剪后的完整机器人状态；[botId] 无效时返回空状态。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    fun mergeChats(botId: String, chats: Collection<ChatInfo>): UpdatesData {
        if (!botId.isValidBotId()) {
            return UpdatesData()
        }
        migrateLegacyDataIfNeeded(botId)
        if (chats.isEmpty()) {
            return state.bots[botId] ?: UpdatesData()
        }
        val accepted = chats.filter(::isIndividuallyStorableChat)
        if (accepted.isEmpty()) {
            return state.bots[botId] ?: UpdatesData()
        }
        val base = normalizeState(state)
        val current = base.bots[botId] ?: UpdatesData()
        // LinkedHashMap preserves the existing display order when a known chat is refreshed. The LRU order is kept
        // separately in chatRecency, so a refresh never makes getChats() flicker.
        val merged = current.chats.associateByTo(LinkedHashMap()) { it.id }
        accepted.forEach { chat -> merged[chat.id] = chat }
        val touchIds = accepted.asSequence().map { it.id }.toList()
        val withChats = base.copy(bots = base.bots + (botId to current.copy(chats = merged.values.toList())))
        val touched = touchChats(renumberBeforeTouches(base, withChats, touchIds.size), botId, touchIds)
        return saveState(touched).bots[botId] ?: UpdatesData()
    }

    /**
     * 保存指定机器人的最后已处理更新标识，同时保留其聊天列表。
     *
     * 小于当前值的标识不会倒退偏移量，以免并发完成顺序扰乱后续轮询。存在重试检查点时，此旧版无条件
     * 确认 API 会 fail-closed 并返回原快照，不会写入或跨过检查点；调用方必须改用带期望检查点的条件确认
     * API 完成该目标。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param lastUpdateId 要确认的更新标识；必须为非负数，`0` 表示未初始化。
     * @return 成功写入后的完整机器人状态；[botId] 无效时返回空状态。存在重试检查点时返回未写入的原快照。
     * @throws IllegalArgumentException 当 [lastUpdateId] 小于 `0` 时抛出。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    fun saveLastUpdateId(botId: String, lastUpdateId: Long): UpdatesData {
        require(lastUpdateId >= 0) { "lastUpdateId must not be negative." }
        if (!botId.isValidBotId()) {
            return UpdatesData()
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: UpdatesData()
        if (current.retryCheckpoint != null) {
            return current
        }
        return updateData(botId) { current ->
            current.copy(lastUpdateId = maxOf(current.lastUpdateId, lastUpdateId))
        }
    }

    /**
     * 条件记录一项轮询重试检查点。
     *
     * 调用方必须把读取快照中的检查点目标作为 [expectedTargetUpdateId] 传回；目标不同或检查点已被其他
     * 调用清除时不会写入任何数据。相同目标仅增加饱和计数并保留第一次重试时间，首次失败则创建新记录。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param targetUpdateId 本次无法安全确认的更新标识；必须为非负数。
     * @param expectedTargetUpdateId 读取快照中的检查点目标；为 `null` 表示调用方要求当前没有检查点。
     * @param nowMillis 记录本次失败的 Unix 时间戳，单位为毫秒；必须为非负数。
     * @return 成功提交时返回 [RetryCheckpointRecordResult.Recorded]；快照已失效或 bot 无效时返回
     * [RetryCheckpointRecordResult.Stale]，且不会写文件。
     * @throws IllegalArgumentException 当标识、时间戳或期望目标不满足约束时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 当状态无法原子写入时抛出；内存状态不变。
     */
    @Synchronized
    internal fun recordRetryCheckpoint(
        botId: String,
        targetUpdateId: Long,
        expectedTargetUpdateId: Long?,
        nowMillis: Long,
    ): RetryCheckpointRecordResult {
        require(targetUpdateId >= 0) { "targetUpdateId must not be negative." }
        require(expectedTargetUpdateId == null || expectedTargetUpdateId >= 0) {
            "expectedTargetUpdateId must not be negative."
        }
        require(expectedTargetUpdateId == null || expectedTargetUpdateId == targetUpdateId) {
            "targetUpdateId must match expectedTargetUpdateId when a checkpoint exists."
        }
        require(nowMillis >= 0) { "nowMillis must not be negative." }
        if (!botId.isValidBotId()) {
            return RetryCheckpointRecordResult.Stale
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: UpdatesData()
        val existing = current.retryCheckpoint
        if (existing?.targetUpdateId != expectedTargetUpdateId) {
            return RetryCheckpointRecordResult.Stale
        }
        if (targetUpdateId <= current.lastUpdateId) {
            return RetryCheckpointRecordResult.Stale
        }
        val checkpoint = existing?.copy(retryCount = existing.retryCount.saturatingIncrement())
            ?: RetryCheckpoint(targetUpdateId, nowMillis, retryCount = 1)
        saveState(state.copy(bots = state.bots + (botId to current.copy(retryCheckpoint = checkpoint))))
        return RetryCheckpointRecordResult.Recorded(checkpoint)
    }

    /**
     * 条件确认一项普通更新，并在同一次文件提交中清除匹配的重试检查点。
     *
     * [expectedRetryTarget] 为 `null` 时要求当前没有检查点；非空时必须等于 [updateId] 并与当前检查点
     * 完全一致。因而迟到轮询绝不能绕过一个较早的重试目标推进偏移量。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 已成功完成的 Telegram 更新标识；必须为非负数。
     * @param expectedRetryTarget 读取快照中的重试目标；为 `null` 表示调用方要求当前没有检查点。
     * @return 成功提交时返回 [RetryCheckpointCommitResult.Committed]；快照已失效或 bot 无效时返回
     * [RetryCheckpointCommitResult.Stale]，且不会写文件。
     * @throws IllegalArgumentException 当更新标识或期望目标不满足约束时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 当状态无法原子写入时抛出；内存状态不变。
     */
    @Synchronized
    internal fun confirmProcessedUpdate(
        botId: String,
        updateId: Long,
        expectedRetryTarget: Long?,
    ): RetryCheckpointCommitResult {
        require(updateId >= 0) { "updateId must not be negative." }
        require(expectedRetryTarget == null || expectedRetryTarget == updateId) {
            "expectedRetryTarget must match updateId when present."
        }
        return updateExpectedRetryCheckpoint(botId, expectedRetryTarget) { current ->
            current.copy(lastUpdateId = maxOf(current.lastUpdateId, updateId), retryCheckpoint = null)
        }
    }

    /**
     * 条件跳过 Telegram 已不再提供的重试目标，并保留可供审计的原检查点。
     *
     * 仅当 [observedFirstUpdateId] 严格大于 [expectedTargetUpdateId] 时允许跳过；空响应和较早更新绝不会
     * 触发此操作。偏移量只推进到检查点目标而非观测到的更高标识，随后轮询会从下一个标识重新请求。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param expectedTargetUpdateId 读取快照中的检查点目标；必须为非负数。
     * @param observedFirstUpdateId 本次成功响应中最小的更新标识；必须严格大于期望目标。
     * @return 成功跳过时返回 [RetryCheckpointGapResult.Skipped] 及原检查点；检查点已改变或 bot 无效时返回
     * [RetryCheckpointGapResult.Stale]，且不会写文件。
     * @throws IllegalArgumentException 当标识不满足严格大于关系时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 当状态无法原子写入时抛出；内存状态不变。
     */
    @Synchronized
    internal fun skipRetryCheckpointGap(
        botId: String,
        expectedTargetUpdateId: Long,
        observedFirstUpdateId: Long,
    ): RetryCheckpointGapResult {
        require(expectedTargetUpdateId >= 0) { "expectedTargetUpdateId must not be negative." }
        require(observedFirstUpdateId > expectedTargetUpdateId) {
            "observedFirstUpdateId must be greater than expectedTargetUpdateId."
        }
        if (!botId.isValidBotId()) {
            return RetryCheckpointGapResult.Stale
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: return RetryCheckpointGapResult.Stale
        val checkpoint = current.retryCheckpoint
        if (checkpoint?.targetUpdateId != expectedTargetUpdateId) {
            return RetryCheckpointGapResult.Stale
        }
        saveState(
            state.copy(
                bots = state.bots + (
                        botId to current.copy(
                            lastUpdateId = maxOf(current.lastUpdateId, expectedTargetUpdateId),
                            retryCheckpoint = null,
                        )
                        ),
            ),
        )
        return RetryCheckpointGapResult.Skipped(checkpoint)
    }

    /**
     * 原子记录一轮已成功完成的 Agent 处理，并推进该机器人的更新偏移量。
     *
     * 非空 [reply] 会和 [updateId] 的偏移量在同一次文件提交中写入 outbox，先后重试不会覆盖已有
     * 同标识回复。空回复仍会在同一次提交中确认偏移量，表示该 Agent 回合已经完成且无需投递。文件
     * 提交失败时内存状态保持不变，调用方必须保留该更新并停止继续确认后续更新。存在重试检查点时，该旧版
     * 无条件确认 API 会 fail-closed 并返回原快照，不会创建 outbox、推进偏移量或跨过检查点；调用方必须改用
     * [completeAgentUpdateAtRetryCheckpoint] 进行条件确认。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 已成功完成的 Telegram 更新标识；必须为非负数。
     * @param reply 要投递的 Agent 回复；为 `null` 表示成功但无回复。
     * @return 成功写入后的完整机器人状态；[botId] 无效时返回空状态。存在重试检查点时返回未写入的原快照。
     * @throws IllegalArgumentException 当 [updateId] 为负数，或 [reply] 与 [updateId]、文本约束不一致时抛出。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    fun completeAgentUpdate(
        botId: String,
        updateId: Long,
        reply: PendingTelegramReply? = null,
    ): UpdatesData {
        require(updateId >= 0) { "updateId must not be negative." }
        reply?.let {
            validatePendingTelegramReply(it, updateId)
        }
        if (!botId.isValidBotId()) {
            return UpdatesData()
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: UpdatesData()
        if (current.retryCheckpoint != null) {
            return current
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
     * 条件记录一轮 Agent 完成，并在同一提交中调和轮询重试检查点。
     *
     * 与 [completeAgentUpdate] 的 outbox 语义相同，但 [expectedRetryTarget] 必须与读取快照一致；为 `null`
     * 时要求没有检查点，非空时必须等于 [updateId]。这样 FINAL 回放不会跨越较早的失败更新，且成功提交会
     * 同时写入 outbox、偏移量并清除该检查点。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 已成功完成的 Telegram 更新标识；必须为非负数。
     * @param reply 要投递的 Agent 回复；为 `null` 表示成功但无回复。
     * @param expectedRetryTarget 读取快照中的重试目标；为 `null` 表示调用方要求当前没有检查点。
     * @return 成功提交时返回 [RetryCheckpointCommitResult.Committed]；检查点已改变或 bot 无效时返回
     * [RetryCheckpointCommitResult.Stale]，且不会写文件。
     * @throws IllegalArgumentException 当标识、回复或期望目标不满足约束时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 当状态无法原子写入时抛出；内存状态不变。
     */
    @Synchronized
    internal fun completeAgentUpdateAtRetryCheckpoint(
        botId: String,
        updateId: Long,
        reply: PendingTelegramReply?,
        expectedRetryTarget: Long?,
    ): RetryCheckpointCommitResult {
        require(updateId >= 0) { "updateId must not be negative." }
        require(expectedRetryTarget == null || expectedRetryTarget == updateId) {
            "expectedRetryTarget must match updateId when present."
        }
        reply?.let { validatePendingTelegramReply(it, updateId) }
        return updateExpectedRetryCheckpoint(botId, expectedRetryTarget) { current ->
            val replies = when {
                reply == null -> current.pendingTelegramReplies
                current.pendingTelegramReplies.any { it.updateId == updateId } -> current.pendingTelegramReplies
                else -> (current.pendingTelegramReplies + reply).sortedBy { it.updateId }
            }
            current.copy(
                lastUpdateId = maxOf(current.lastUpdateId, updateId),
                pendingTelegramReplies = replies,
                retryCheckpoint = null,
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
        val entry = state.bots[botId]?.agentTurnJournal?.singleOrNull { it.updateId == updateId }
        entry?.let(::validateAgentTurnJournalEntry)
        return entry
    }

    /**
     * 在调用 Agent 前持久化占有一项回合，或返回其已有的不可重放状态。
     *
     * 该方法是同一仓储实例内的原子读改写操作。它绝不会把已有的 [AgentTurnJournalStatus.IN_PROGRESS]
     * 直接交给 Agent 重放；调用方必须结合本地 owner 判定该回合是否仍在运行，并对失联回合静默确认偏移量。
     * 已确认更新的残留 FINAL 账本会在本次调用中安全回收。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要占有的 Telegram 更新标识；必须为非负数。
     * @param chatId 最终回复的聊天标识；不能为空。
     * @param replyParameters 可选的原消息回复参数；可以为 `null`。
     * @return 新占有的 [AgentTurnClaim.CLAIMED]、已有 FINAL 或 IN_PROGRESS 状态；偏移量已确认时返回
     * [AgentTurnClaim.AlreadyConfirmed]。
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
     * 将当前本地 owner 已明确失败的进行中 Agent 回合原子降级为固定失败回复。
     *
     * 调用方只可在仍持有本地 owner 且已捕获 Agent 异常时调用；失联 owner 则应使用
     * [confirmInProgressAgentTurnWithoutReply] 静默确认。两条路径都不会自动重放模型或工具副作用。
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
     * 静默确认一项没有本地 owner 的进行中 Agent 回合。
     *
     * 仅当指定账本记录仍为 [AgentTurnJournalStatus.IN_PROGRESS] 时，才会在同一次文件提交中删除该记录并将
     * [UpdatesData.lastUpdateId] 至少推进到 [updateId]。该路径绝不创建回复、outbox 或 FINAL 记录，供调用方在
     * 授权租约失效或进程失联后安全跳过不可重放的回合。记录不存在、状态已改变或 bot 无效时不写入文件。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要静默确认的 Telegram 更新标识；必须为非负数。
     * @param expectedRetryTarget 读取快照中的重试目标；为 `null` 时要求当前没有检查点，非空时必须等于
     * [updateId]。不匹配时不会写入文件。
     * @return 仅当进行中记录被删除且偏移量已在同一次提交中确认时为 `true`；其他情况为 `false`。
     * @throws IllegalArgumentException 当 [updateId] 为负数时抛出。
     * @throws IllegalStateException 当更新状态文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 当状态无法原子写入时抛出；内存状态不变。
     */
    @Synchronized
    internal fun confirmInProgressAgentTurnWithoutReply(
        botId: String,
        updateId: Long,
        expectedRetryTarget: Long? = null,
    ): Boolean {
        require(updateId >= 0) { "updateId must not be negative." }
        require(expectedRetryTarget == null || expectedRetryTarget == updateId) {
            "expectedRetryTarget must match updateId when present."
        }
        if (!botId.isValidBotId()) {
            return false
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: return false
        val entry = current.agentTurnJournal.singleOrNull { it.updateId == updateId } ?: return false
        validateAgentTurnJournalEntry(entry, updateId)
        if (entry.status != AgentTurnJournalStatus.IN_PROGRESS) {
            return false
        }
        if (current.retryCheckpoint?.targetUpdateId != expectedRetryTarget) {
            return false
        }
        val updated = current.copy(
            lastUpdateId = maxOf(current.lastUpdateId, updateId),
            agentTurnJournal = current.agentTurnJournal.filterNot { it.updateId == updateId },
            retryCheckpoint = null,
        )
        saveState(state.copy(bots = state.bots + (botId to updated)))
        return true
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
     * 已登记三次投递的片段会在本次调用中仅跳过该片段；若原文还有后续片段，它们会恢复为原文阶段并在下一
     * 次调用中继续投递。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param updateId 要登记投递的回复所属更新标识；必须为非负数。
     * @return 已把 [PendingTelegramReply.deliveryAttempts] 加一并持久化的当前片段快照；不存在记录、bot 无效或
     * 回退片段耗尽并已跳过时返回 `null`。
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
            saveState(state.copy(bots = state.bots + (botId to advancePendingReply(current, replyIndex, reply))))
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
        require(replacement.text == expected.text) { "replacement must preserve the source reply text." }
        if (!botId.isValidBotId()) {
            return false
        }
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
     * 条件确认当前已发送片段，并原子推进到下一片段或删除末片段记录。
     *
     * 只有当前 outbox 记录仍与 [expected] 完全一致时才推进，避免旧 token 的迟到成功响应确认新会话已改变
     * 的投递状态。推进下一片段时恢复原文阶段并清零该片段的尝试和永久拒绝计数。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param expected 网络请求前已持久化登记的当前片段快照。
     * @return 已持久化推进或删除时为 `true`；bot 无效、记录不存在或快照已变化时为 `false`。
     * @throws IllegalArgumentException 当 [expected] 不满足 outbox 不变量时抛出。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    internal fun advancePendingTelegramReplyDelivery(botId: String, expected: PendingTelegramReply): Boolean {
        validatePendingTelegramReply(expected)
        if (!botId.isValidBotId()) {
            return false
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: return false
        val replyIndex = current.pendingTelegramReplies.indexOfFirst { it == expected }
        if (replyIndex < 0) {
            return false
        }
        saveState(state.copy(bots = state.bots + (botId to advancePendingReply(current, replyIndex, expected))))
        return true
    }

    /**
     * 条件放弃已经耗尽投递次数的回退片段，并继续同一原文的后续片段。
     *
     * 此方法绝不会改写 [PendingTelegramReply.text]；若当前片段是末片段才删除整个记录。调用方必须先持久化
     * 登记到回退投递上限，避免网络中断时错误跳过尚可重试的片段。
     *
     * @param botId token 冒号前的非空机器人标识。
     * @param expected 当前已耗尽的回退片段快照。
     * @return 已持久化跳过时为 `true`；bot 无效、快照已变化或不是耗尽回退片段时为 `false`。
     * @throws IllegalArgumentException 当 [expected] 不满足 outbox 不变量时抛出。
     * @throws IllegalStateException 配置文件已损坏或暂不可读取时抛出；内存状态不变。
     * @throws Exception 配置文件无法编码或原子提交时抛出；内存状态不变。
     */
    @Synchronized
    internal fun discardExhaustedPendingTelegramReplyFallback(botId: String, expected: PendingTelegramReply): Boolean {
        validatePendingTelegramReply(expected)
        if (
            expected.deliveryStage != TelegramReplyDeliveryStage.FALLBACK ||
            expected.deliveryAttempts < MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS ||
            !botId.isValidBotId()
        ) {
            return false
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: return false
        val replyIndex = current.pendingTelegramReplies.indexOfFirst { it == expected }
        if (replyIndex < 0) {
            return false
        }
        saveState(state.copy(bots = state.bots + (botId to advancePendingReply(current, replyIndex, expected))))
        return true
    }

    /** 在当前片段处理完成或耗尽后构造同一更新的新 outbox 状态。 */
    private fun advancePendingReply(
        current: UpdatesData,
        replyIndex: Int,
        reply: PendingTelegramReply,
    ): UpdatesData {
        val nextChunkStart = TelegramTextChunks.nextStartAfter(reply.text, reply.nextChunkStart)
        if (nextChunkStart == reply.text.length) {
            return current.copy(pendingTelegramReplies = current.pendingTelegramReplies.filterNot { it.updateId == reply.updateId })
        }
        val nextReply = reply.copy(
            nextChunkStart = nextChunkStart,
            deliveryStage = TelegramReplyDeliveryStage.ORIGINAL,
            deliveryAttempts = 0,
            permanentRejectionCount = 0,
        )
        val replies = current.pendingTelegramReplies.toMutableList().also { it[replyIndex] = nextReply }
        return current.copy(pendingTelegramReplies = replies)
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
     * 列表按首次保留时的展示顺序返回；刷新已知聊天只更新其信息和 LRU 元数据，不会重排此列表。
     * 只有新增、删除或预算裁剪会改变可见条目及其相对位置。
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

    /**
     * 在每次耐久提交前规范化聊天发现快照。
     *
     * 规范化集中在唯一持久化入口，使偏移量、outbox 或 Agent 账本变更也能修复缺少聊天元数据的旧文件。只有
     * 原子替换与父目录同步都确认耐久后，才会把候选状态安装到内存。
     */
    private fun saveState(newState: BotUpdatesData): BotUpdatesData {
        val normalized = normalizeState(newState)
        validatePersistedUpdatesState(normalized)
        beforeSaveForTesting(normalized)
        storage.commit(normalized).requireDurable()
        state = normalized
        return normalized
    }

    /** 在仓储锁内对与调用方快照匹配的检查点执行一次原子更新。 */
    private fun updateExpectedRetryCheckpoint(
        botId: String,
        expectedRetryTarget: Long?,
        transform: (UpdatesData) -> UpdatesData,
    ): RetryCheckpointCommitResult {
        if (!botId.isValidBotId()) {
            return RetryCheckpointCommitResult.Stale
        }
        migrateLegacyDataIfNeeded(botId)
        val current = state.bots[botId] ?: UpdatesData()
        if (current.retryCheckpoint?.targetUpdateId != expectedRetryTarget) {
            return RetryCheckpointCommitResult.Stale
        }
        saveState(state.copy(bots = state.bots + (botId to transform(current))))
        return RetryCheckpointCommitResult.Committed
    }

    /** Returns true only when the single discovery item can never consume the complete discovery budget. */
    private fun isIndividuallyStorableChat(chat: ChatInfo): Boolean =
        chatFootprint(chat) <= MAX_DISCOVERED_CHAT_UTF8_BYTES

    /**
     * Reconciles generic updateData chat edits with the metadata map.
     *
     * mergeChats supplies explicit touches even for a value-identical refresh. A generic transform has no such
     * admission signal, so only an added or materially changed record becomes recent here.
     */
    private fun reconcileChatRecency(
        before: BotUpdatesData,
        after: BotUpdatesData,
        botId: String,
        oldData: UpdatesData,
        newData: UpdatesData,
    ): BotUpdatesData {
        val oldById = oldData.chats.associateBy { it.id }
        val touched = newData.chats.asSequence()
            .filter(::isIndividuallyStorableChat)
            .filter { chat -> oldById[chat.id] != chat }
            .map { it.id }
            .toList()
        val boundedChats = newData.chats.filter(::isIndividuallyStorableChat)
        val boundedAfter = if (boundedChats == newData.chats) {
            after
        } else {
            after.copy(bots = after.bots + (botId to newData.copy(chats = boundedChats)))
        }
        return if (touched.isEmpty()) boundedAfter else {
            touchChats(renumberBeforeTouches(before, boundedAfter, touched.size), botId, touched)
        }
    }

    /** Renumbers only pre-existing records before a batch would exhaust the global clock. */
    private fun renumberBeforeTouches(
        before: BotUpdatesData,
        candidate: BotUpdatesData,
        touchCount: Int,
    ): BotUpdatesData {
        if (touchCount <= 0 || before.chatRecencyClock <= Long.MAX_VALUE - touchCount.toLong()) {
            return candidate
        }
        val renumbered = renumberRecency(before)
        return candidate.copy(
            chatRecency = renumbered.chatRecency,
            chatRecencyClock = renumbered.chatRecencyClock,
        )
    }

    /** Assigns strictly increasing global recency values in the caller's input order. */
    private fun touchChats(snapshot: BotUpdatesData, botId: String, chatIds: List<String>): BotUpdatesData {
        var working = snapshot
        chatIds.forEach { chatId ->
            val data = working.bots[botId] ?: return@forEach
            if (data.chats.none { it.id == chatId }) {
                return@forEach
            }
            if (working.chatRecencyClock == Long.MAX_VALUE) {
                working = renumberRecency(working)
            }
            val next = working.chatRecencyClock + 1
            val perBot = (working.chatRecency[botId].orEmpty() + (chatId to next))
            working = working.copy(
                chatRecency = working.chatRecency + (botId to perBot),
                chatRecencyClock = next,
            )
        }
        return working
    }

    /**
     * Rebuilds a saturated recency clock in a deterministic oldest-to-newest order.
     *
     * A tie is broken by bot and chat identifier so a corrupt or historical duplicate timestamp cannot make a
     * Long.MAX_VALUE rollover depend on map iteration order.
     */
    private fun renumberRecency(snapshot: BotUpdatesData): BotUpdatesData {
        val ordered = storedChats(snapshot)
            .sortedWith(
                compareBy<StoredChat> { if (it.hasValidRecency) it.recency else Long.MAX_VALUE }
                    .thenBy { it.botId }
                    .thenBy { it.chat.id },
            )
        var next = 0L
        val recency = LinkedHashMap<String, MutableMap<String, Long>>()
        ordered.forEach { stored ->
            next++
            recency.getOrPut(stored.botId) { LinkedHashMap() }[stored.chat.id] = next
        }
        return snapshot.copy(chatRecency = recency, chatRecencyClock = next)
    }

    /** Canonicalizes legacy metadata, applies count/byte bounds and leaves the visible chat order untouched. */
    private fun normalizeState(candidate: BotUpdatesData): BotUpdatesData {
        val canonicalBots = LinkedHashMap<String, UpdatesData>()
        candidate.bots.toSortedMap().forEach { (botId, data) ->
            if (!botId.isValidBotId()) {
                return@forEach
            }
            val chats = LinkedHashMap<String, ChatInfo>()
            data.chats.filter(::isIndividuallyStorableChat).forEach { chat -> chats[chat.id] = chat }
            canonicalBots[botId] = data.copy(chats = chats.values.toList())
        }

        val provisional = candidate.copy(bots = canonicalBots)
        val rebuilt = rebuildRecency(provisional)
        var bounded = trimPerBot(rebuilt)
        bounded = trimGlobalCount(bounded)
        bounded = trimChatByteBudget(bounded)
        bounded = trimWholeStateByteBudget(bounded)
        return bounded
    }

    /** Repairs absent, stale or duplicated metadata without treating a read refresh as a change to display order. */
    private fun rebuildRecency(snapshot: BotUpdatesData): BotUpdatesData {
        val chats = snapshot.bots.flatMap { (botId, data) ->
            data.chats.map { chat ->
                val value = snapshot.chatRecency[botId]?.get(chat.id)
                StoredChat(botId, chat, value ?: Long.MAX_VALUE, value != null && value > 0)
            }
        }
        val supplied = chats.map { it.recency }
        val suppliedValid = chats.all { it.hasValidRecency } && supplied.distinct().size == supplied.size
        val highestSupplied = supplied.maxOrNull() ?: 0L
        if (suppliedValid && snapshot.chatRecencyClock >= highestSupplied && snapshot.chatRecencyClock >= 0L) {
            val metadata = chats.groupBy { it.botId }.mapValues { (_, entries) ->
                entries.associate { it.chat.id to it.recency }
            }
            return snapshot.copy(chatRecency = metadata, chatRecencyClock = snapshot.chatRecencyClock)
        }
        val ordered = chats.sortedWith(
            compareBy<StoredChat> { if (it.hasValidRecency) it.recency else Long.MAX_VALUE }
                .thenBy { it.botId }
                .thenBy { it.chat.id },
        )
        var clock = 0L
        val metadata = LinkedHashMap<String, MutableMap<String, Long>>()
        ordered.forEach { entry ->
            clock++
            metadata.getOrPut(entry.botId) { LinkedHashMap() }[entry.chat.id] = clock
        }
        return snapshot.copy(chatRecency = metadata, chatRecencyClock = clock)
    }

    private fun trimPerBot(snapshot: BotUpdatesData): BotUpdatesData {
        var working = snapshot
        working.bots.keys.forEach { botId ->
            while ((working.bots[botId]?.chats?.size ?: 0) > MAX_DISCOVERED_CHATS_PER_BOT) {
                working = removeOldestChat(working, botId)
            }
        }
        return working
    }

    private fun trimGlobalCount(snapshot: BotUpdatesData): BotUpdatesData {
        var working = snapshot
        while (storedChats(working).size > MAX_DISCOVERED_CHATS) {
            working = removeOldestChat(working)
        }
        return working
    }

    private fun trimChatByteBudget(snapshot: BotUpdatesData): BotUpdatesData {
        var working = snapshot
        while (
            storedChats(working).isNotEmpty() &&
            chatDiscoveryFootprint(working) > MAX_DISCOVERED_CHATS_UTF8_BYTES
        ) {
            working = removeOldestChat(working)
        }
        return working
    }

    /** Ensures a large historical discovery section cannot crowd out offsets, journals or outbox entries. */
    private fun trimWholeStateByteBudget(snapshot: BotUpdatesData): BotUpdatesData {
        var working = snapshot
        while (serializedSize(working) > ResourceLimits.UPDATES_BYTES && storedChats(working).isNotEmpty()) {
            working = removeOldestChat(working)
        }
        return working
    }

    private fun removeOldestChat(snapshot: BotUpdatesData, onlyBotId: String? = null): BotUpdatesData {
        val oldest = storedChats(snapshot)
            .asSequence()
            .filter { onlyBotId == null || it.botId == onlyBotId }
            .minWithOrNull(
                compareBy<StoredChat> { if (it.hasValidRecency) it.recency else Long.MAX_VALUE }
                    .thenBy { it.botId }
                    .thenBy { it.chat.id },
            )
            ?: return snapshot
        val data = snapshot.bots.getValue(oldest.botId)
        val recency = snapshot.chatRecency.toMutableMap()
        val perBot = recency[oldest.botId].orEmpty().toMutableMap().also { it.remove(oldest.chat.id) }
        if (perBot.isEmpty()) recency.remove(oldest.botId) else recency[oldest.botId] = perBot
        return snapshot.copy(
            bots = snapshot.bots + (oldest.botId to data.copy(chats = data.chats.filterNot { it.id == oldest.chat.id })),
            chatRecency = recency,
        )
    }

    private fun storedChats(snapshot: BotUpdatesData): List<StoredChat> = snapshot.bots.flatMap { (botId, data) ->
        data.chats.map { chat ->
            val recency = snapshot.chatRecency[botId]?.get(chat.id)
            StoredChat(botId, chat, recency ?: Long.MAX_VALUE, recency != null && recency > 0)
        }
    }

    private fun chatFootprint(chat: ChatInfo): Int =
        ConfigJson.encodeToString(chat).toByteArray(StandardCharsets.UTF_8).size

    /**
     * Returns the UTF-8 size of the persisted discovery section, including only chat-bearing bot entries and LRU
     * metadata.
     *
     * Offsets, outboxes, journals and empty bots are deliberately excluded: they are protected by the independent
     * whole-state budget below, while this budget specifically prevents chat discovery and its recency map from
     * consuming unbounded space.
     */
    private fun chatDiscoveryFootprint(snapshot: BotUpdatesData): Int {
        val chatBearingBots = snapshot.bots
            .asSequence()
            .filter { (_, data) -> data.chats.isNotEmpty() }
            .associateTo(LinkedHashMap()) { (botId, data) -> botId to ChatDiscoveryBot(data.chats) }
        val chatIdsByBot = chatBearingBots.mapValues { (_, bot) -> bot.chats.asSequence().map { it.id }.toSet() }
        val recency = chatBearingBots.keys.associateWithTo(LinkedHashMap()) { botId ->
            snapshot.chatRecency[botId].orEmpty().filterKeys { it in chatIdsByBot.getValue(botId) }
        }.filterValues { it.isNotEmpty() }
        return ConfigJson.encodeToString(
            ChatDiscoveryBudgetData(
                bots = chatBearingBots,
                chatRecency = recency,
                chatRecencyClock = snapshot.chatRecencyClock,
            ),
        ).toByteArray(StandardCharsets.UTF_8).size
    }

    private fun serializedSize(snapshot: BotUpdatesData): Int =
        ConfigJson.encodeToString(snapshot).toByteArray(StandardCharsets.UTF_8).size

    private data class StoredChat(
        val botId: String,
        val chat: ChatInfo,
        val recency: Long,
        val hasValidRecency: Boolean = true,
    )

    @Serializable
    private data class ChatDiscoveryBudgetData(
        val bots: Map<String, ChatDiscoveryBot>,
        val chatRecency: Map<String, Map<String, Long>>,
        val chatRecencyClock: Long,
    )

    /** The persisted bot sub-object stripped to discovery-only fields for byte-budget accounting. */
    @Serializable
    private data class ChatDiscoveryBot(
        val chats: List<ChatInfo>,
    )

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
        val storage: SchemaValidatedJsonStorage<BotUpdatesData>,
        val state: BotUpdatesData,
        val legacyData: UpdatesData?,
    )

    private companion object {
        const val LEGACY_BOT_ID = ""

        /** 节点上限不低于文件字节上限，避免原有合法 updates 快照被通用的 4096 节点预算拒绝。 */
        val UPDATES_JSON_STRUCTURE_BUDGET = JsonStructureLimits.Budget(
            maxNodes = ResourceLimits.UPDATES_BYTES,
        )
        val CURRENT_UPDATES_ROOT_FIELDS = setOf("bots", "chatRecency", "chatRecencyClock")

        val LEGACY_ROOT_MIGRATION = JsonElementMigration("updates-legacy-root-to-bots") { root ->
            when (root) {
                is JsonObject -> if (root.keys.any(CURRENT_UPDATES_ROOT_FIELDS::contains)) {
                    root
                } else {
                    // 旧 UpdatesData 的全部字段均有默认值；没有当前根格式标志的对象（包括 `{}`）都属于旧格式。
                    wrapLegacyUpdates(root)
                }

                is JsonArray -> wrapLegacyUpdates(
                    buildJsonObject { put("chats", root) },
                )

                else -> root
            }
        }

        fun load(configFile: File, fileOperations: AtomicJsonFileOperations): LoadedUpdates {
            val storage = SchemaValidatedJsonStorage(
                storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.UPDATES_BYTES, fileOperations),
                serializer = BotUpdatesData.serializer(),
                migrations = listOf(LEGACY_ROOT_MIGRATION),
                validator = ::validatePersistedUpdatesState,
                structureBudget = UPDATES_JSON_STRUCTURE_BUDGET,
                logger = LoggerFactory.getLogger(UpdatesRepository::class.java),
            )
            return when (val read = storage.read()) {
                AtomicJsonRead.Missing -> LoadedUpdates(storage, BotUpdatesData(), null)
                is AtomicJsonRead.Valid -> {
                    val legacyData = read.value.bots[LEGACY_BOT_ID]
                    val state = read.value.copy(
                        bots = read.value.bots - LEGACY_BOT_ID,
                        chatRecency = read.value.chatRecency - LEGACY_BOT_ID,
                    )
                    LoadedUpdates(storage, state, legacyData)
                }

                is AtomicJsonRead.Corrupt -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Updates file is severely damaged; application startup is aborted; category={}",
                        SafeLogging.failureCategory(read.cause).wireName,
                    )
                    throw IllegalStateException("更新状态文件严重损坏，应用无法安全启动。", read.cause)
                }

                is AtomicJsonRead.IoFailure -> {
                    LoggerFactory.getLogger(UpdatesRepository::class.java).error(
                        "Unable to read updates data; application startup is aborted; category={}",
                        SafeLogging.failureCategory(read.cause).wireName,
                    )
                    throw IllegalStateException("更新状态文件无法读取，应用无法安全启动。", read.cause)
                }

            }
        }

        fun wrapLegacyUpdates(legacy: JsonElement): JsonObject = buildJsonObject {
            put("bots", buildJsonObject { put(LEGACY_BOT_ID, legacy) })
        }
    }
}

private fun validateAgentTurnReply(reply: String?) {
    require(reply == null || reply.isNotBlank()) { "agent turn reply must not be blank when present." }
}

/** 验证一项机器人更新状态可被持久化，不允许任意读改写绕过重试检查点与偏移量的不变量。 */
private fun validatePersistedUpdatesData(updates: UpdatesData) {
    validatePendingTelegramReplies(updates.pendingTelegramReplies, updates.lastUpdateId)
    validateAgentTurnJournal(updates.agentTurnJournal)
    validateRetryCheckpoint(updates.retryCheckpoint, updates.lastUpdateId)
}

/** 验证完整多机器人更新状态中每项可持久化的业务不变量。 */
private fun validatePersistedUpdatesState(state: BotUpdatesData) {
    state.bots.values.forEach(::validatePersistedUpdatesData)
}

/** 验证持久化 outbox 的唯一性、已确认偏移量及当前片段状态，避免损坏 cursor 让 worker 永久重试。 */
private fun validatePendingTelegramReplies(replies: List<PendingTelegramReply>, lastUpdateId: Long) {
    require(replies.map { it.updateId }.distinct().size == replies.size) {
        "pending Telegram reply update IDs must be unique."
    }
    replies.forEach { reply ->
        validatePendingTelegramReply(reply)
        require(reply.updateId <= lastUpdateId) {
            "pending Telegram reply updateId must not exceed lastUpdateId."
        }
    }
}

/** 验证一项 outbox 回复的源文本、稳定 cursor 与当前片段投递状态。 */
private fun validatePendingTelegramReply(reply: PendingTelegramReply, expectedUpdateId: Long? = null) {
    expectedUpdateId?.let { require(reply.updateId == it) { "reply updateId must match updateId." } }
    require(reply.updateId >= 0) { "reply updateId must not be negative." }
    require(reply.chatId.isNotBlank()) { "reply chatId must not be blank." }
    require(reply.text.isNotBlank()) { "reply text must not be blank." }
    require(TelegramTextChunks.isChunkStart(reply.text, reply.nextChunkStart)) {
        "reply nextChunkStart must identify a pending Telegram text chunk."
    }
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

private fun validateRetryCheckpoint(checkpoint: RetryCheckpoint?, lastUpdateId: Long) {
    checkpoint ?: return
    require(checkpoint.targetUpdateId >= 0) { "retry checkpoint targetUpdateId must not be negative." }
    require(checkpoint.targetUpdateId > lastUpdateId) {
        "retry checkpoint targetUpdateId must be ahead of lastUpdateId."
    }
    require(checkpoint.firstRetryAtMillis >= 0) { "retry checkpoint firstRetryAtMillis must not be negative." }
    require(checkpoint.retryCount > 0) { "retry checkpoint retryCount must be positive." }
}

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) Long.MAX_VALUE else this + 1

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
