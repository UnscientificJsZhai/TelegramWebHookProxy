package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * 消息轮询 facade 使用的可替换时限与退避策略。
 *
 * @property processingTimeout 单项队列工作的最大处理时间。
 * @property retryDelay 执行轮询退避等待的函数。
 * @property retryJitter 为本地退避生成附加抖动的函数。
 */
private data class MessagePollingTuning(
    val processingTimeout: Duration,
    val retryDelay: suspend (Duration) -> Unit,
    val retryJitter: (Duration) -> Duration,
) {
    init {
        require(processingTimeout.isPositive()) { "processingTimeout must be positive." }
    }
}

/**
 * 后台轮询 Telegram 更新的稳定入口。
 *
 * facade 只组装同一进程内的协作者并委托生命周期；唯一根任务、唯一会话锁和当前会话状态均由
 * MessagePollingRuntime 持有。[start] 只启动监听；[requestStop] 关闭准入并取消任务，[awaitStopped]
 * 等待所有子任务静止；[close] 保留为不等待的 AutoCloseable 入口。
 *
 * @param parentScope 轮询根任务继承的应用协程作用域。
 * @param telegramService 执行 Telegram API 请求的服务。
 * @param agentService 执行消息回合和上下文重置的 Agent 服务。
 * @param settingsChangeCoordinator 提供设置快照、token 代次和生命周期锁的协调器。
 * @param updatesRepository 持久化 offset、重试检查点、Agent journal 与回复 outbox 的仓储。
 * @param modelSwitchBarrier 协调模型切换与 Agent 请求准入的共享屏障。
 * @param tuning 单项处理时限与轮询退避策略。
 */
@Singleton
class MessagePoller private constructor(
    parentScope: CoroutineScope,
    telegramService: TelegramService,
    agentService: AgentService,
    settingsChangeCoordinator: SettingsChangeCoordinator,
    updatesRepository: UpdatesRepository,
    modelSwitchBarrier: ModelSwitchBarrier,
    tuning: MessagePollingTuning,
) : AutoCloseable {
    /**
     * 创建使用生产时限与退避策略的依赖注入入口，并复用应用共享的 [ModelSwitchBarrier]。
     *
     * @constructor 创建尚未启动的消息轮询器。
     * @param parentScope 轮询根任务继承的应用协程作用域。
     * @param telegramService 执行 Telegram API 请求的服务。
     * @param agentService 执行消息回合和上下文重置的 Agent 服务。
     * @param settingsChangeCoordinator 提供设置快照、token 代次和生命周期锁的协调器。
     * @param updatesRepository 持久化轮询与回复状态的仓储。
     * @param modelSwitchBarrier 协调模型切换与 Agent 请求准入的共享屏障。
     */
    @Inject
    constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsChangeCoordinator: SettingsChangeCoordinator,
        updatesRepository: UpdatesRepository,
        modelSwitchBarrier: ModelSwitchBarrier,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsChangeCoordinator,
        updatesRepository,
        modelSwitchBarrier,
        MessagePollingTuning(
            processingTimeout = 10.minutes,
            retryDelay = { delay(it) },
            retryJitter = { backoff ->
                if (backoff <= Duration.ZERO) {
                    Duration.ZERO
                } else {
                    Random.nextLong((backoff.inWholeMilliseconds / 5) + 1).milliseconds
                }
            },
        ),
    )

    /**
     * 创建使用确定性超时和退避策略的测试入口。
     *
     * @param parentScope 轮询根任务继承的测试协程作用域。
     * @param telegramService 执行 Telegram API 请求的服务。
     * @param agentService 执行消息回合和上下文重置的 Agent 服务。
     * @param settingsChangeCoordinator 提供设置快照和 token 生命周期锁的协调器。
     * @param updatesRepository 持久化轮询与回复状态的仓储。
     * @param modelSwitchBarrier 协调模型切换与 Agent 请求准入的共享屏障。
     * @param processingTimeout 单项队列工作的最大处理时间。
     * @param retryDelay 执行轮询退避等待的函数。
     * @param retryJitter 为本地退避生成附加抖动的函数。
     */
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsChangeCoordinator: SettingsChangeCoordinator,
        updatesRepository: UpdatesRepository,
        modelSwitchBarrier: ModelSwitchBarrier,
        processingTimeout: Duration,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsChangeCoordinator,
        updatesRepository,
        modelSwitchBarrier,
        MessagePollingTuning(processingTimeout, retryDelay, retryJitter),
    )

    private val logger = LoggerFactory.getLogger(MessagePoller::class.java)
    private val runtime = MessagePollingRuntime(parentScope, settingsChangeCoordinator)
    private val admissionPolicy = UpdateAdmissionPolicy(
        runtime,
        telegramService,
        agentService,
        settingsChangeCoordinator,
        updatesRepository,
        modelSwitchBarrier,
        logger,
    )
    private val cleanupCoordinator = ContextCleanupCoordinator(
        runtime,
        admissionPolicy,
        agentService,
        logger,
    )
    private val outboxWorker = TelegramReplyOutboxWorker(
        runtime,
        telegramService,
        updatesRepository,
        admissionPolicy,
        logger,
    )
    private val commandHandler = BotCommandHandler(
        admissionPolicy,
        cleanupCoordinator,
        outboxWorker,
        agentService,
        settingsChangeCoordinator,
        logger,
    )
    private val processor = AgentTurnProcessor(
        runtime,
        telegramService,
        agentService,
        updatesRepository,
        admissionPolicy,
        cleanupCoordinator,
        outboxWorker,
        commandHandler,
        logger,
        tuning.processingTimeout,
    )
    private val supervisor = PollingSessionSupervisor(
        runtime,
        telegramService,
        agentService,
        settingsChangeCoordinator,
        updatesRepository,
        modelSwitchBarrier,
        admissionPolicy,
        cleanupCoordinator,
        outboxWorker,
        processor,
        logger,
    ).also {
        it.retryDelay = tuning.retryDelay
        it.retryJitter = tuning.retryJitter
    }

    /** 仅供 /model 代次 CAS 竞争回归测试。 */
    internal var beforeModelSelectionPersistForTesting: (() -> Unit)?
        get() = commandHandler.beforeModelSelectionPersistForTesting
        set(value) {
            commandHandler.beforeModelSelectionPersistForTesting = value
        }

    /** 仅供 /model ready-agent 准入竞争回归测试。 */
    internal var beforeModelRefreshForTesting: (() -> Unit)?
        get() = commandHandler.beforeModelRefreshForTesting
        set(value) {
            commandHandler.beforeModelRefreshForTesting = value
        }

    /** 启动唯一的 token 设置监听器；重复调用不会创建额外监听器。 */
    fun start() = supervisor.start()

    /** 同步关闭新会话和队列准入并取消当前任务，但不等待不可取消收尾。 */
    internal fun requestStop() = supervisor.requestStop()

    /** 等待先前停止请求覆盖的根任务和所有会话子任务完全结束。 */
    internal suspend fun awaitStopped() = supervisor.awaitStopped()

    /** 请求停止并等待本服务拥有的全部协程静止。 */
    internal suspend fun closeAndJoin() = supervisor.closeAndJoin()

    /** AutoCloseable 兼容入口；只请求停止，不阻塞调用线程。 */
    override fun close() = supervisor.requestStop()
}
