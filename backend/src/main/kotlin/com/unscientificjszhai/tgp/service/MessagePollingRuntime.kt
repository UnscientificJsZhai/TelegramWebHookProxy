package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.repository.AgentTurnJournalEntry
import com.unscientificjszhai.tgp.repository.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 单个 Telegram token 代次拥有的全部协程和串行工作状态。
 *
 * @property token 当前代次使用的 Telegram Bot token。
 * @property botId 从 [token] 解析出的 Bot 标识。
 * @property generation 设置快照中与 [token] 绑定的代次。
 * @property scope 当前会话全部协程共享的作用域。
 * @property updateChannel 串行传递待处理更新的有界通道。
 * @property consumerResume 本地重试结束后恢复队列消费者的汇合信号。
 * @property outboxSignal 唤醒回复 outbox worker 的汇合信号。
 * @property pollJob 当前会话的 Telegram 轮询任务。
 * @property consumerJob 当前会话的更新消费任务。
 * @property consumerRestartedAfterError 是否已经用完一次消费者错误恢复机会。
 * @property outboxJob 当前会话的回复投递任务。
 * @property lastAiReplyAtMillis 最近一次成功提交 AI 回复的 epoch 毫秒时间。
 * @property consecutivePollingFailures 连续 Telegram 轮询失败次数。
 * @property initialOffsetResolved 是否已经完成首次 offset 探测。
 */
internal class PollingSession(
    val token: String,
    val botId: String,
    val generation: Long,
    val scope: CoroutineScope,
    val updateChannel: Channel<QueuedWork>,
    val consumerResume: Channel<Unit>,
    val outboxSignal: Channel<Unit>,
    var pollJob: Job? = null,
    var consumerJob: Job? = null,
    var consumerRestartedAfterError: Boolean = false,
    var outboxJob: Job? = null,
    var lastAiReplyAtMillis: Long? = null,
    var consecutivePollingFailures: Int = 0,
    var initialOffsetResolved: Boolean = false,
)

/**
 * 已授权更新绑定的不可变设置租约。
 *
 * @property agentChatId 获得授权时的 Agent 私聊标识。
 * @property generation 获得授权时的设置代次。
 */
internal data class AdmissionTicket(
    val agentChatId: String,
    val generation: Long,
)

/**
 * 保留 Telegram 原消息中的授权事实，以便每个副作用在屏障内复核身份。
 *
 * @property chatId 原消息所属聊天标识。
 * @property chatType 原消息所属聊天类型。
 * @property fromId 原消息发送者标识；Telegram 未提供发送者时为 `null`。
 * @property messageId 原消息在聊天中的标识。
 */
internal data class AuthorizedMessageContext(
    val chatId: String,
    val chatType: String,
    val fromId: String?,
    val messageId: Long,
) {
    /**
     * 判断当前 AI 设置是否仍授权原消息触发副作用。
     *
     * @param aiSettings 待复核的最新 AI 设置。
     * @return 原消息仍来自已配置 Agent 私聊时返回 `true`。
     */
    fun matches(aiSettings: AISettings): Boolean =
        chatType == "private" &&
                chatId == aiSettings.agentChatId &&
                fromId == aiSettings.agentChatId
}

/** 当前轮询会话待结算的一项队列工作。 */
internal sealed interface QueuedWork {
    /** 待处理的 Telegram 更新。 */
    val update: Update

    /** 工作进入队列的 epoch 毫秒时间。 */
    val entryTime: Long

    /** 消费者写入最终处理要求的完成信号。 */
    val completion: CompletableDeferred<UpdateCompletion>

    /** 入队时必须匹配的持久化重试检查点目标。 */
    val expectedRetryCheckpointTarget: Long?

    /**
     * 尚未取得 durable Agent journal 的已授权更新。
     *
     * @property update 待处理的 Telegram 更新。
     * @property entryTime 工作进入队列的 epoch 毫秒时间。
     * @property completion 消费者写入最终处理要求的完成信号。
     * @property expectedRetryCheckpointTarget 入队时必须匹配的持久化重试检查点目标。
     * @property ticket 更新通过准入时捕获的设置票据。
     */
    data class Authorized(
        override val update: Update,
        override val entryTime: Long,
        override val completion: CompletableDeferred<UpdateCompletion>,
        override val expectedRetryCheckpointTarget: Long?,
        val ticket: AdmissionTicket,
    ) : QueuedWork

    /**
     * 已持久化为终态、只需提交 outbox 与 offset 的 Agent 回合。
     *
     * @property update 对应的 Telegram 更新。
     * @property entryTime 工作进入队列的 epoch 毫秒时间。
     * @property completion 消费者写入最终处理要求的完成信号。
     * @property expectedRetryCheckpointTarget 入队时必须匹配的持久化重试检查点目标。
     * @property entry 已持久化的 Agent 回合终态。
     */
    data class DurableFinal(
        override val update: Update,
        override val entryTime: Long,
        override val completion: CompletableDeferred<UpdateCompletion>,
        override val expectedRetryCheckpointTarget: Long?,
        val entry: AgentTurnJournalEntry,
    ) : QueuedWork

    /**
     * 已持久化为处理中、只可静默确认而不可重放的 Agent 回合。
     *
     * @property update 对应的 Telegram 更新。
     * @property entryTime 工作进入队列的 epoch 毫秒时间。
     * @property completion 消费者写入最终处理要求的完成信号。
     * @property expectedRetryCheckpointTarget 入队时必须匹配的持久化重试检查点目标。
     * @property entry 已持久化的 Agent 回合处理中状态。
     */
    data class DurableInProgress(
        override val update: Update,
        override val entryTime: Long,
        override val completion: CompletableDeferred<UpdateCompletion>,
        override val expectedRetryCheckpointTarget: Long?,
        val entry: AgentTurnJournalEntry,
    ) : QueuedWork
}

/**
 * 同一进程内唯一标识一个已经由消费者占有的 Agent 回合。
 *
 * @property botId 拥有该回合的 Telegram Bot 标识。
 * @property updateId 触发该回合的 Telegram 更新标识。
 */
internal data class AgentTurnKey(
    val botId: String,
    val updateId: Long,
)

/** 消费者完成一项排队更新后对轮询偏移量的处理要求。 */
internal sealed interface UpdateCompletion {
    data object Persisted : UpdateCompletion
    data object Confirmed : UpdateCompletion
    data object Retry : UpdateCompletion
}

/** 票据受当前设置、当前会话和共享模型屏障共同保护的执行结果。 */
internal sealed interface AuthorizedEffect<out T> {
    data object Confirmed : AuthorizedEffect<Nothing>

    /**
     * 已在有效授权内执行的副作用结果。
     *
     * @property value 副作用返回的值。
     */
    data class Executed<T>(val value: T) : AuthorizedEffect<T>
}

/** 在 token 生命周期与会话锁内提交队列的结果。 */
internal enum class QueueOfferResult {
    ENQUEUED,
    FULL,
    NOT_CURRENT,
}

/**
 * MessagePoller 的唯一共享并发运行时。
 *
 * 此类只拥有根任务、当前会话状态和需要线性化的门控；业务编排均位于协作者。所有需要同时持有两把锁的
 * 路径都固定先取得 SettingsRepository 的 token 生命周期锁，再取得这里唯一的 session lock。
 *
 * @param parentScope 根任务继承的应用协程作用域。
 * @param settingsRepository 提供 token 生命周期锁和当前 token 代次的设置仓储。
 */
internal class MessagePollingRuntime(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
) {
    /** 监督所有轮询会话任务的根任务。 */
    val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])

    /** 所有轮询协作者共享的根协程作用域。 */
    val scope = CoroutineScope(parentScope.coroutineContext + Dispatchers.IO + scopeJob)

    private val sessionLock = ReentrantLock()

    /** 服务是否已永久关闭新工作准入。 */
    @Volatile
    var closed: Boolean = false

    /** 当前已安装的 token 会话；没有活动会话时为 `null`。 */
    var currentSession: PollingSession? = null

    /**
     * 在唯一会话锁内执行同步操作。
     *
     * @param action 需要与会话状态线性化的操作。
     * @return [action] 返回的值。
     */
    fun <T> withSessionLock(action: () -> T): T = sessionLock.withLock(action)

    /**
     * 判断会话是否仍是当前有效 token 代次。
     *
     * @param session 待复核的轮询会话。
     * @return 服务未关闭且会话及 token 代次均仍当前时返回 `true`。
     */
    fun isCurrent(session: PollingSession): Boolean = sessionLock.withLock {
        !closed && currentSession === session && isTokenGenerationCurrent(session)
    }

    /**
     * 要求会话仍为当前会话。
     *
     * @param session 待复核的轮询会话。
     * @throws kotlinx.coroutines.CancellationException 会话已经失效时抛出。
     */
    fun ensureCurrent(session: PollingSession) {
        if (!isCurrent(session)) {
            throw kotlinx.coroutines.CancellationException("Polling session is no longer current.")
        }
    }

    /**
     * 在 token 生命周期锁和会话锁内为当前会话执行无返回值持久化操作。
     *
     * @param session 必须仍为当前代次的会话。
     * @param save 需要在线性化临界区内执行的持久化操作。
     * @return 操作已执行时返回 `true`；会话已失效时返回 `false`。
     */
    fun saveForCurrent(session: PollingSession, save: () -> Unit): Boolean =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession !== session || !isTokenGenerationCurrent(session)) {
                    false
                } else {
                    save()
                    true
                }
            }
        }

    /**
     * 在 token 生命周期锁和会话锁内为当前会话执行持久化写入。
     *
     * @param session 必须仍为当前代次的会话。
     * @param write 需要在线性化临界区内执行的写入。
     * @return 写入结果；会话已失效时返回 `null`。
     */
    fun <T> writeForCurrent(session: PollingSession, write: () -> T): T? =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession !== session || !isTokenGenerationCurrent(session)) {
                    null
                } else {
                    write()
                }
            }
        }

    /**
     * 在 token 生命周期锁和会话锁内为当前会话读取持久化状态。
     *
     * @param session 必须仍为当前代次的会话。
     * @param read 需要在线性化临界区内执行的读取。
     * @return 读取结果；会话已失效时返回 `null`。
     */
    fun <T> readForCurrent(session: PollingSession, read: () -> T): T? =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession === session && isTokenGenerationCurrent(session)) read() else null
            }
        }

    /**
     * 在 token 生命周期锁和会话锁内尝试向当前会话队列投递工作。
     *
     * @param session 目标轮询会话。
     * @param queuedWork 待投递的工作。
     * @return 入队、队满或会话失效的精确结果。
     */
    fun offerUpdateForCurrent(session: PollingSession, queuedWork: QueuedWork): QueueOfferResult =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (closed || currentSession !== session || !isTokenGenerationCurrent(session)) {
                    QueueOfferResult.NOT_CURRENT
                } else {
                    val result = session.updateChannel.trySend(queuedWork)
                    when {
                        result.isSuccess -> QueueOfferResult.ENQUEUED
                        result.isClosed -> QueueOfferResult.NOT_CURRENT
                        else -> QueueOfferResult.FULL
                    }
                }
            }
        }

    /**
     * 唤醒指定 Bot 当前会话的 outbox worker。
     *
     * @param botId 需要继续投递回复的 Bot 标识。
     */
    fun signalOutboxForBot(botId: String) {
        sessionLock.withLock {
            currentSession
                ?.takeIf { !closed && it.botId == botId }
                ?.outboxSignal
                ?.trySend(Unit)
        }
    }

    /**
     * 判断会话携带的 token 与代次是否仍匹配设置仓储。
     *
     * @param session 待复核的轮询会话。
     * @return token 和代次仍当前时返回 `true`。
     */
    fun isTokenGenerationCurrent(session: PollingSession): Boolean =
        isTokenGenerationCurrent(session.token, session.generation)

    /**
     * 判断给定 token 与代次是否仍匹配设置仓储。
     *
     * @param token 待复核的 Telegram Bot token。
     * @param generation 待复核的设置代次。
     * @return token 和代次均与最新设置一致时返回 `true`。
     */
    fun isTokenGenerationCurrent(token: String, generation: Long): Boolean =
        settingsRepository.telegramTokenUpdateFlow.value.let { tokenUpdate ->
            tokenUpdate.token == token && tokenUpdate.generation == generation
        }
}
