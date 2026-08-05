package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.repository.ActiveTelegramBotUnavailableException
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.TelegramBotLease
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.TelegramApiResponse
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AgentConfigurationNotReadyException
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonCommitResult
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.SchemaValidatedJsonStorage
import com.unscientificjszhai.tgp.utils.TelegramTextChunks
import com.unscientificjszhai.tgp.utils.requireDurable
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * 持久化并按时执行面向 AI 代理的定时任务。
 *
 * 服务在创建时从 `config/schedule.json` 恢复任务并启动后台扫描；任务执行结果会发送到
 * 对应的 Telegram 会话。调用 [close] 会停止后续扫描；[start] 与 [close] 应在同一生命周期
 * 控制路径中调用。恢复时按 [ScheduledTask] schema 将损坏的可选字段回退为默认值；必填字段或 JSON 结构
 * 严重损坏会中断创建并保留现场。每次执行会先在状态锁内重新确认任务内容仍等于扫描快照且仍到期，
 * 随后原子持久化删除单次任务或推进循环任务，只有文件替换与目录项同步都确认耐久后才调用代理和
 * Telegram。
 * 替换已可见但目录项耐久性未知时会保留内存任务并隔离副作用，后续扫描重新提交至确认耐久；该预消费确认
 * 耐久后，即使代理、投递、协程取消或进程崩溃发生在副作用完成前，也绝不会恢复或重试该次；这刻意提供
 * at-most-once，而非 exactly-once，代价是提交与副作用之间崩溃或取消可能遗漏一次执行。预提交失败、
 * 任务被取消或替换、授权失效或已经不再到期时不会调用代理。
 * 调度的服务器时间和日历时区分别由 [Clock] 与 [ZoneId] 决定。错过的循环周期只预消费一次并跳到下一次
 * 未来执行，不逐期追赶；小时任务保持 Unix epoch 相位，日/周任务保持服务器时区中的本地日历锚点。DST
 * gap 解析到首个有效本地时间，overlap 使用较早偏移量；日/周任务会持久化创建时的本地时刻锚点，所以 gap
 * 当次的延后时刻不会漂移到之后的日期或周。无法表示未来时刻或存在无效日历锚点时同样预消费删除并记录警告，
 * 避免永久重复。
 * 到期任务会先在 token 生命周期锁内短暂确认 AI 已启用、当前代理会话标识非空并
 * 精确等于任务的 [ScheduledTask.agentChatId]；不满足这些 AI 授权条件的任务会在锁外原子删除，不调用
 * Agent 或 Telegram。无效 token 时任务会保留。通过首次确认的任务会进入
 * [AgentService.withReadyService]，并在预消费前再次确认同一授权条件，以防等待模型就绪期间的设置变更。
 * 最新 AI 配置尚未就绪但授权仍有效时，任务也会保留。准入后的完整预消费、Agent 与投递链路受
 * [AgentExecutionDeadlines.scheduledTurn] 的总体时限约束；超时任务已预消费但不会重试或投递迟到结果。
 * 因此切换已发生时旧任务不会调用旧 Agent 或旧 token；任务已准入时，对应的 Agent 切换最多等待该完整
 * 回合结束或超时，投递始终使用第二次确认中捕获的 token。
 *
 * @param parentScope 后台扫描任务所属的协程作用域；取消该作用域会停止扫描。
 * @param telegramService 用于投递任务执行结果的 Telegram 服务。
 * @param agentService 用于取得执行任务指令的 AI 代理服务的提供者。
 * @param settingsRepository 用于在线性化 token 生命周期内捕获执行租约的仓储。
 * @param clock 提供服务器当前时间的时钟。
 * @param zoneId 日/周循环任务使用的服务器日历时区。
 * @param deadlines 限制已准入定时任务的完整预消费、Agent 和投递链路。
 */
@Singleton
class TaskSchedulerService private constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val agentService: Provider<AgentService>,
    private val settingsRepository: SettingsRepository,
    private val storage: SchemaValidatedJsonStorage<List<ScheduledTask>>,
    startImmediately: Boolean,
    private val clock: Clock,
    private val zoneId: ZoneId,
    private val deadlines: AgentExecutionDeadlines,
) : AutoCloseable {

    /**
     * 创建使用默认配置文件且立即启动扫描的调度服务。
     *
     * @constructor 创建使用 `config/schedule.json` 的服务。
     * @param parentScope 后台扫描任务所属的协程作用域。
     * @param telegramService 用于投递任务执行结果的 Telegram 服务。
     * @param agentService 用于取得 AI 代理的提供者。
     * @param settingsRepository 用于捕获执行租约的设置仓储。
     * @param deadlines 限制完整已准入任务回合的总体时限。
     */
    @Inject
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: Provider<AgentService>,
        settingsRepository: SettingsRepository,
        deadlines: AgentExecutionDeadlines,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsRepository,
        SchemaValidatedJsonStorage(
            AtomicJsonStorage(File("config/schedule.json").toPath(), ResourceLimits.SCHEDULE_BYTES),
            ListSerializer(ScheduledTask.serializer()),
        ),
        startImmediately = true,
        clock = Clock.systemDefaultZone(),
        zoneId = ZoneId.systemDefault(),
        deadlines = deadlines,
    )

    /**
     * 为临时配置文件、故障注入和确定性时钟测试创建调度服务。
     *
     * @param parentScope 后台扫描任务所属的协程作用域。
     * @param telegramService 用于投递任务结果的 Telegram 服务。
     * @param agentService 用于取得 AI 代理的提供者。
     * @param settingsRepository 用于确认 AI 授权并捕获 token 的仓储。
     * @param scheduleFile 测试或本地调度文件。
     * @param fileOperations 原子 JSON 存储使用的文件操作。
     * @param startImmediately 为 `true` 时构造后立即启动每分钟扫描。
     * @param clock 提供服务器当前时间的时钟。
     * @param zoneId 日/周循环任务使用的服务器日历时区。
     * @param deadlines 限制完整已准入任务回合的总体时限。
     */
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: Provider<AgentService>,
        settingsRepository: SettingsRepository,
        scheduleFile: File,
        fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        startImmediately: Boolean = false,
        clock: Clock = Clock.systemDefaultZone(),
        zoneId: ZoneId = ZoneId.systemDefault(),
        deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsRepository,
        SchemaValidatedJsonStorage(
            AtomicJsonStorage(scheduleFile.toPath(), ResourceLimits.SCHEDULE_BYTES, fileOperations),
            ListSerializer(ScheduledTask.serializer()),
        ),
        startImmediately,
        clock,
        zoneId,
        deadlines,
    )

    private val logger = LoggerFactory.getLogger(TaskSchedulerService::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job.Key])
    private val stateLock = ReentrantLock()
    private val tasks = mutableListOf<ScheduledTask>()
    private val executingTaskIds = mutableSetOf<String>()
    private var job: Job? = null

    init {
        loadTasks()
        if (startImmediately) {
            start()
        }
    }

    private fun loadTasks() {
        when (val read = storage.read()) {
            AtomicJsonRead.Missing -> Unit
            is AtomicJsonRead.Valid -> stateLock.withLock {
                tasks.clear()
                tasks.addAll(read.value)
                logger.info("Loaded {} scheduled tasks.", read.value.size)
            }

            is AtomicJsonRead.Corrupt -> {
                logger.error(
                    "Schedule file is severely damaged; application startup is aborted; category={}",
                    SafeLogging.failureCategory(read.cause).wireName,
                )
                throw IllegalStateException("定时任务文件严重损坏，应用无法安全启动。", read.cause)
            }

            is AtomicJsonRead.IoFailure -> {
                logger.error(
                    "Unable to read scheduled tasks; application startup is aborted; category={}",
                    SafeLogging.failureCategory(read.cause).wireName,
                )
                throw IllegalStateException("定时任务文件无法读取，应用无法安全启动。", read.cause)
            }

        }
    }

    private fun persistTasks(candidate: List<ScheduledTask>): AtomicJsonCommitResult {
        return storage.commit(candidate)
    }

    /**
     * 启动每分钟一次的后台任务扫描。
     *
     * 已启动扫描任务时此方法不执行额外操作；扫描任务会持续到 [close] 被调用或其协程
     * 作用域被取消。
     */
    @Synchronized
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    scanAndExecute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Task scan failed; category={}", SafeLogging.failureCategory(e).wireName)
                }
                delay(1.minutes)
            }
        }
        logger.info("Task scheduler started.")
    }

    /**
     * 扫描并依次执行所有执行时间已到的任务。
     *
     * 候选任务会在短临界区内声明执行权；并发扫描不会重复执行同一任务。每个候选会先确认 AI 授权；当前
     * 会话缺失、禁用或与任务会话不一致时，调度器会在任何 Agent 或 Telegram 副作用前原子删除该任务。
     * 无效 token 或最新 Agent 配置尚未就绪时会保留任务。通过 Agent 就绪屏障后会再次
     * 确认授权、扫描快照和到期状态，并原子持久化预消费状态。只有原子替换与目录同步都确认耐久才会放行
     * 副作用；替换可见但耐久性未知时保留内存任务，在后续扫描重新提交并继续隔离。因此耐久预提交后的失败、
     * 取消和进程重启都不会重放该次，提交失败或耐久性未知则不会调用 Agent。代理调用与 Telegram I/O 永远
     * 不在状态锁或 token 生命周期租约内运行。
     *
     * @throws CancellationException 扫描被取消时原样抛出；若任务已经预提交，取消也不会恢复该次。
     */
    suspend fun scanAndExecute() {
        val currentTime = try {
            clock.millis()
        } catch (e: ArithmeticException) {
            logger.warn(
                "Scheduler clock cannot be represented as epoch milliseconds; skipping this scan; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            return
        }
        val tasksToExecute = stateLock.withLock {
            tasks.filter { task -> task.executionTime <= currentTime && executingTaskIds.add(task.id) }
        }

        try {
            for (task in tasksToExecute) {
                executeTask(task)
            }
        } finally {
            stateLock.withLock { tasksToExecute.forEach { task -> executingTaskIds.remove(task.id) } }
        }
    }

    /**
     * 在单次 Agent 就绪屏障准入中预消费并执行一个已声明执行权的任务。
     *
     * 首次租约只用于短暂确认当前 AI 授权；被撤销的任务会在租约释放后使用扫描快照原子删除。授权
     * 有效的任务才进入 Agent 就绪屏障，并在预消费前再次获得租约确认，防止设置在等待期间改变。状态锁只
     * 用于重新验证扫描快照、以 fresh now 预消费并持久化。所有锁和租约都会在副作用前释放。预消费成功后，
     * 代理失败、普通异常、取消以及 Telegram 失败或取消均不会恢复或重试任务；这在崩溃或取消落在提交和
     * 副作用之间时可能遗漏一次执行，但避免重复外部副作用。最新 AI 配置尚未就绪但授权仍有效时任务会保留
     * 给后续已就绪配置的扫描。
     *
     * @param task 已到期且已被当前扫描声明执行权的任务。
     * [AgentExecutionDeadlines.scheduledTurn] 到期只记录稳定任务标识并停止后续投递，不重试已预消费任务；
     * 普通 [CancellationException] 仍会原样抛出，且已预消费状态不回滚。
     *
     * @throws CancellationException 当 Agent、Telegram 调用或当前协程被普通取消时原样抛出；已预消费状态不回滚。
     */
    private suspend fun executeTask(task: ScheduledTask) {
        when (taskAuthorization(task)) {
            TaskAuthorization.Retain -> return
            TaskAuthorization.Revoked -> {
                revokeTask(task)
                return
            }

            is TaskAuthorization.Authorized -> Unit
        }

        try {
            agentService.get().withReadyService { readyAgent ->
                try {
                    withTimeout(deadlines.scheduledTurn) {
                        val authorization = taskAuthorization(task)
                        when (authorization) {
                            TaskAuthorization.Retain -> return@withTimeout
                            TaskAuthorization.Revoked -> {
                                revokeTask(task)
                                return@withTimeout
                            }

                            is TaskAuthorization.Authorized -> Unit
                        }
                        val preparedTask = prepareTaskForExecution(task, authorization.telegramLease)
                            ?: return@withTimeout
                        logger.info("Executing precommitted task {}", preparedTask.task.id)
                        val result = readyAgent.sendMessage(
                            "以下是一个定时任务指令：\n${preparedTask.task.instruction}\n\n请直接执行并返回结果。",
                        )
                        if (result.isNotBlank()) {
                            deliverTaskResult(preparedTask.task, preparedTask.telegramLease.token, result)
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    logger.warn("Precommitted task {} timed out; it will not be retried or delivered", task.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AgentTurnFailedException) {
                    logger.warn(
                        "Precommitted task {} agent turn did not complete; it will not be retried; category={}",
                        task.id,
                        SafeLogging.failureCategory(e).wireName,
                    )
                } catch (e: Exception) {
                    logger.error(
                        "Precommitted task {} agent execution failed; it will not be retried; category={}",
                        task.id,
                        SafeLogging.failureCategory(e).wireName,
                    )
                }
            }
        } catch (_: AgentConfigurationNotReadyException) {
            logger.info("Skipping task {} because the current AI configuration is not ready", task.id)
        }
    }

    /**
     * 在副作用前确认扫描快照，并原子持久化本次预消费状态。
     *
     * @param task 扫描时声明执行权的不可变任务快照。
     * @param telegramLease 第二次 AI 授权确认中捕获的当前 Telegram token 快照。
     * @return 已提交且可安全执行的任务与 token 快照；快照、到期或持久化验证不满足时返回 `null`。
     * @throws CancellationException 当调用协程在存储操作期间被取消时原样抛出。
     */
    private fun prepareTaskForExecution(task: ScheduledTask, telegramLease: TelegramBotLease): PreparedTask? {
        return try {
            stateLock.withLock {
                val index = tasks.indexOfFirst { current ->
                    current.id == task.id && current == task
                }
                if (index == -1) {
                    logger.info("Skipping task {} because it was cancelled or replaced before admission", task.id)
                    return@withLock null
                }
                val currentTask = tasks[index]
                val currentTime = clock.millis()
                if (currentTask.executionTime > currentTime) {
                    logger.info("Skipping task {} because it is no longer due", task.id)
                    return@withLock null
                }

                val calendarAnchorTimeMillis = currentTask.calendarAnchorTimeMillisOrLegacy(zoneId)
                val hasInvalidCalendarAnchor = currentTask.requiresCalendarAnchor() &&
                        currentTask.calendarAnchorTimeMillis != null && calendarAnchorTimeMillis == null
                if (hasInvalidCalendarAnchor) {
                    logger.warn("Removing task {} because its persisted calendar anchor is invalid", task.id)
                    val candidate = tasks.toMutableList().apply { removeAt(index) }
                    persistTasks(candidate).requireDurable()
                    tasks.clear()
                    tasks.addAll(candidate)
                    return@withLock null
                }
                val nextExecutionTime = calculateNextExecutionTime(
                    currentTask.executionTime,
                    currentTask.loopMode,
                    Instant.ofEpochMilli(currentTime),
                    zoneId,
                    calendarAnchorTimeMillis,
                )
                if (currentTask.loopMode != LoopMode.ONCE && nextExecutionTime == null) {
                    logger.warn("Removing task {} because no future execution time can be represented", task.id)
                }
                val candidate = tasks.toMutableList().also { candidateTasks ->
                    if (nextExecutionTime == null) {
                        candidateTasks.removeAt(index)
                    } else {
                        candidateTasks[index] = currentTask.copy(
                            executionTime = nextExecutionTime,
                            calendarAnchorTimeMillis = calendarAnchorTimeMillis,
                        )
                    }
                }
                when (val commitResult = persistTasks(candidate)) {
                    AtomicJsonCommitResult.Durable -> Unit
                    is AtomicJsonCommitResult.ReplacedDurabilityUnknown -> {
                        logger.error(
                            "Task {} precommit is visible but not known durable; " +
                                    "withholding side effects until a durable retry; category={}",
                            task.id,
                            SafeLogging.failureCategory(commitResult.cause).wireName,
                        )
                        return@withLock null
                    }
                }
                tasks.clear()
                tasks.addAll(candidate)
                PreparedTask(currentTask, telegramLease)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to precommit task {}; it will remain eligible for a later scan; category={}",
                task.id,
                SafeLogging.failureCategory(e).wireName,
            )
            null
        }
    }

    /**
     * 在 token 生命周期锁内短暂确认当前 AI 会话授权。
     *
     * 此方法不获取状态锁，也不执行 I/O、挂起或等待；调用方必须在返回后才进行任务删除、持久化或 Agent
     * 调用。token 不可用时保留任务；AI 未启用、会话标识空白或与任务不精确相等时撤销任务。
     */
    private fun taskAuthorization(task: ScheduledTask): TaskAuthorization {
        return try {
            settingsRepository.withActiveTelegramBotSettingsLease { telegramLease, settings ->
                val currentAgentChatId = settings.ai
                    ?.takeIf { it.agentEnabled }
                    ?.agentChatId
                when {
                    currentAgentChatId.isNullOrBlank() -> TaskAuthorization.Revoked
                    currentAgentChatId != task.agentChatId -> TaskAuthorization.Revoked
                    else -> TaskAuthorization.Authorized(telegramLease)
                }
            }
        } catch (_: ActiveTelegramBotUnavailableException) {
            TaskAuthorization.Retain
        }
    }

    /**
     * 删除已确认撤销、且仍与扫描快照完全一致的任务。
     *
     * 此方法只在 token 生命周期租约释放后调用。持久化失败时不会变更内存任务列表，也不会触发 Agent 或
     * Telegram 副作用；任务会保留供后续扫描重试删除。
     */
    private fun revokeTask(task: ScheduledTask) {
        try {
            stateLock.withLock {
                val index = tasks.indexOfFirst { current ->
                    current.id == task.id && current == task
                }
                if (index == -1) {
                    logger.info("Skipping revoked task {} because it was cancelled or replaced before removal", task.id)
                    return@withLock
                }

                val candidate = tasks.toMutableList().apply { removeAt(index) }
                persistTasks(candidate).requireDurable()
                tasks.clear()
                tasks.addAll(candidate)
                logger.info("Removed revoked task {}", task.id)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to remove revoked task {}; it will remain eligible for a later scan; category={}",
                task.id,
                SafeLogging.failureCategory(e).wireName,
            )
        }
    }

    /**
     * 尽力向 Telegram 投递已完成代理回合的结果。
     *
     * 非取消失败和 API 非成功响应只记录日志，避免因投递问题重复执行可能带外部副作用的代理回合。完整结果
     * （含前缀）会按 Telegram 文本上限分块；每块发送前均重新确认 token，任一确认或发送失败
     * 都停止后续片段。该投递仍是既有的 at-most-once 尽力语义，并不持久化为通用 scheduler outbox。
     *
     * @param task 已完成代理回合的任务。
     * @param token 在任务执行租约中捕获的 Telegram Bot token。
     * @param result 要发送给任务会话的非空结果文本。
     * @throws CancellationException 当当前协程或 Telegram 调用被取消时抛出。
     */
    private suspend fun deliverTaskResult(task: ScheduledTask, token: String, result: String) {
        try {
            val fullText = "⏰ 定时任务执行结果：\n\n$result"
            for (chunk in TelegramTextChunks.split(fullText)) {
                if (!isTaskResultTokenCurrent(token)) {
                    logger.info("Stopping task result delivery for {} because its bot token changed", task.id)
                    return
                }
                val response = telegramService.sendMessageForToken(token, task.agentChatId, chunk)
                if (!response.isTelegramOk()) {
                    logger.warn("Telegram did not accept task result chunk for {}", task.id)
                    return
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to send task result for {}; category={}",
                task.id,
                SafeLogging.failureCategory(e).wireName,
            )
        }
    }

    /** 在每个任务结果片段前短暂确认当前设置仍使用本次执行捕获的 token。 */
    private fun isTaskResultTokenCurrent(token: String): Boolean {
        return try {
            settingsRepository.withActiveTelegramBotLease { lease -> lease.token == token }
        } catch (_: ActiveTelegramBotUnavailableException) {
            false
        }
    }

    /**
     * 判断 Telegram HTTP 响应是否同时具有成功状态码和 API `ok: true` 标记。
     *
     * @receiver 已完整读取的 Telegram 响应快照。
     * @return HTTP 状态成功且响应 JSON 的 `ok` 字段为 `true` 时返回 `true`，否则返回 `false`。
     */
    private fun TelegramApiResponse.isTelegramOk(): Boolean =
        status.isSuccess() && try {
            JsonStructureLimits.parseToJsonElement(Json, body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }

    /**
     * 创建并持久化一个定时任务。
     *
     * 仅当完整任务列表完成原子替换且目录项同步确认耐久后才返回新标识；持久化失败或耐久性未知时内存任务
     * 列表保持不变。
     * @param instruction 到期时发送给 AI 代理的指令文本；允许为空字符串，将按原样保存。
     * @param executionTime 首次执行的 Unix 时间戳，单位为毫秒；可为过去时间，此时会在下一次
     * 扫描时执行。
     * @param loopMode 任务到期后的循环方式；[LoopMode.ONCE] 表示仅执行一次。
     * @param agentChatId 接收执行结果的 Telegram 会话标识；不得为空白，非空值按原样保存，不去除首尾空白。
     * @return 已成功持久化的新任务八位标识符。
     * @throws IllegalArgumentException [agentChatId] 或编码后的任务列表不符合资源限制时抛出；不会添加
     * 内存任务。
     * @throws java.io.IOException 原子替换失败或目录项耐久性无法确认时抛出；不会添加内存任务。
     */
    fun createTask(instruction: String, executionTime: Long, loopMode: LoopMode, agentChatId: String): String {
        require(agentChatId.isNotBlank()) { "定时任务必须绑定非空代理会话标识。" }
        val id = UUID.randomUUID().toString().substring(0, 8)
        val newTask = ScheduledTask(
            id,
            instruction,
            executionTime,
            loopMode,
            agentChatId,
            executionTime.calendarAnchorTimeMillisFor(loopMode, zoneId),
        )
        return stateLock.withLock {
            val candidate = tasks + newTask
            persistTasks(candidate).requireDurable()
            tasks.add(newTask)
            id
        }
    }

    /**
     * 获取当前所有已调度任务的快照。
     *
     * @return 按当前调度顺序排列的任务列表；没有任务时返回空列表。修改返回列表不会影响调度器。
     */
    fun listTasks(): List<ScheduledTask> = stateLock.withLock { tasks.toList() }

    /**
     * 取消指定任务并在取消成功时持久化最新任务列表。
     *
     * 找到任务时，只有原子替换与目录项同步均确认耐久才会从内存移除并返回 `true`；
     * 写入失败或耐久性未知会抛出且内存不变。
     *
     * @param taskId 要取消的任务标识；空字符串或不存在的标识不会取消任务。
     * @return 找到并成功持久化移除任务时返回 `true`；不存在匹配任务时返回 `false`。
     * @throws java.io.IOException 原子替换失败或目录项耐久性无法确认时抛出；不会移除内存任务。
     */
    fun cancelTask(taskId: String): Boolean = stateLock.withLock {
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) {
            return@withLock false
        }
        val candidate = tasks.toMutableList().apply { removeAt(index) }
        persistTasks(candidate).requireDurable()
        tasks.clear()
        tasks.addAll(candidate)
        true
    }

    /**
     * 停止后台扫描任务。
     *
     * 此方法不会等待已开始的任务执行结束，且可重复调用。
     */
    @Synchronized
    override fun close() {
        job?.cancel()
        job = null
        logger.info("Task scheduler stopped.")
    }

    private fun ScheduledTask.requiresCalendarAnchor(): Boolean =
        loopMode == LoopMode.DAILY || loopMode == LoopMode.WEEKLY

    /** 返回已验证锚点，或为兼容旧任务从当前执行时间推导锚点。 */
    private fun ScheduledTask.calendarAnchorTimeMillisOrLegacy(zoneId: ZoneId): Int? = when {
        !requiresCalendarAnchor() -> null
        calendarAnchorTimeMillis == null -> executionTime.calendarAnchorTimeMillisFor(loopMode, zoneId)
        calendarAnchorTimeMillis.toCalendarAnchorTimeOrNull() != null -> calendarAnchorTimeMillis
        else -> null
    }

    private data class PreparedTask(
        val task: ScheduledTask,
        val telegramLease: TelegramBotLease,
    )

    private sealed interface TaskAuthorization {
        data class Authorized(val telegramLease: TelegramBotLease) : TaskAuthorization
        data object Revoked : TaskAuthorization
        data object Retain : TaskAuthorization
    }
}

/**
 * 计算循环任务在 [now] 之后的首个可表示执行时刻。
 *
 * 小时模式按 Unix epoch 的固定一小时相位计算；日和周模式使用 [zoneId] 中 [lastExecutionTime] 的本地
 * 日期/星期和时分秒作为锚点。错过任意数量的周期都只计算一个未来时刻，不逐期补跑。DST gap 取跳变后的
 * 第一个有效本地时间，overlap 明确使用跳变前（较早）的 offset。`Long`、日历或 Instant 转换溢出时返回
 * `null`，调用方应将其作为不可继续调度处理。
 *
 * @param lastExecutionTime 最近一次已到期实例的 Unix 时间戳，单位为毫秒。
 * @param loopMode 循环方式；[LoopMode.ONCE] 没有下一次执行，返回 `null`。
 * @param now 当前绝对时刻；成功返回值严格晚于该时刻。
 * @param zoneId 日和周循环解释本地日历锚点的服务器时区。
 * @param calendarAnchorTimeMillis 日/周任务原始本地时刻距当天 `00:00` 的毫秒数，必须在 `0..86399999`；为
 * `null` 时从 [lastExecutionTime] 兼容推导，非空越界值返回 `null` 供调度器安全删除任务。
 * @return 首个严格晚于 [now] 的 Unix 时间戳，单位为毫秒；无需循环或无法表示未来时刻时为 `null`。
 */
internal fun calculateNextExecutionTime(
    lastExecutionTime: Long,
    loopMode: LoopMode,
    now: Instant,
    zoneId: ZoneId,
    calendarAnchorTimeMillis: Int? = null,
): Long? = try {
    when (loopMode) {
        LoopMode.ONCE -> null
        LoopMode.HOURLY -> nextHourlyExecutionTime(lastExecutionTime, now)
        LoopMode.DAILY -> nextCalendarExecutionTime(
            lastExecutionTime,
            now,
            zoneId,
            daysPerPeriod = 1,
            calendarAnchorTimeMillis,
        )

        LoopMode.WEEKLY -> nextCalendarExecutionTime(
            lastExecutionTime,
            now,
            zoneId,
            daysPerPeriod = 7,
            calendarAnchorTimeMillis,
        )
    }
} catch (_: ArithmeticException) {
    null
} catch (_: DateTimeException) {
    null
}

private const val HOUR_MILLIS = 60L * 60L * 1000L

private fun nextHourlyExecutionTime(lastExecutionTime: Long, now: Instant): Long {
    val nowMillis = now.toEpochMilli()
    if (lastExecutionTime > nowMillis) {
        return lastExecutionTime
    }
    val elapsed = Math.subtractExact(nowMillis, lastExecutionTime)
    val missedPeriods = Math.floorDiv(elapsed, HOUR_MILLIS)
    val periodsToAdvance = Math.addExact(missedPeriods, 1L)
    return Math.addExact(lastExecutionTime, Math.multiplyExact(periodsToAdvance, HOUR_MILLIS))
}

private fun nextCalendarExecutionTime(
    lastExecutionTime: Long,
    now: Instant,
    zoneId: ZoneId,
    daysPerPeriod: Long,
    calendarAnchorTimeMillis: Int?,
): Long? {
    val anchor = Instant.ofEpochMilli(lastExecutionTime).atZone(zoneId)
    if (anchor.toInstant().isAfter(now)) {
        return lastExecutionTime
    }

    val anchorTime = when (calendarAnchorTimeMillis) {
        null -> anchor.toLocalTime()
        else -> calendarAnchorTimeMillis.toCalendarAnchorTimeOrNull() ?: return null
    }
    val nowDate = now.atZone(zoneId).toLocalDate()
    val targetDate = if (daysPerPeriod == 1L) {
        nowDate
    } else {
        nextDateWithDayOfWeek(nowDate, anchor.dayOfWeek.value)
    }
    var candidate = resolveLocalExecutionTime(targetDate, anchorTime, zoneId)
    if (!candidate.toInstant().isAfter(now)) {
        candidate = resolveLocalExecutionTime(targetDate.plusDays(daysPerPeriod), anchorTime, zoneId)
    }
    return candidate.toInstant().toEpochMilli()
}

private fun nextDateWithDayOfWeek(date: LocalDate, targetDayOfWeek: Int): LocalDate {
    val daysUntilTarget = Math.floorMod(targetDayOfWeek - date.dayOfWeek.value, 7)
    return date.plusDays(daysUntilTarget.toLong())
}

private fun resolveLocalExecutionTime(
    date: LocalDate,
    time: LocalTime,
    zoneId: ZoneId,
): ZonedDateTime {
    val localDateTime = LocalDateTime.of(date, time)
    val rules = zoneId.rules
    val validOffsets = rules.getValidOffsets(localDateTime)
    return when (validOffsets.size) {
        0 -> {
            val transition = requireNotNull(rules.getTransition(localDateTime))
            ZonedDateTime.ofLocal(transition.dateTimeAfter, zoneId, transition.offsetAfter)
        }

        1 -> ZonedDateTime.ofLocal(localDateTime, zoneId, validOffsets.single())
        else -> {
            val transition = requireNotNull(rules.getTransition(localDateTime))
            ZonedDateTime.ofLocal(localDateTime, zoneId, transition.offsetBefore)
        }
    }

}

private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000

private fun Long.calendarAnchorTimeMillisFor(loopMode: LoopMode, zoneId: ZoneId): Int? =
    when (loopMode) {
        LoopMode.DAILY,
        LoopMode.WEEKLY,
            -> (Instant.ofEpochMilli(this).atZone(zoneId).toLocalTime().toNanoOfDay() / 1_000_000L).toInt()

        LoopMode.ONCE,
        LoopMode.HOURLY,
            -> null
    }

private fun Int.toCalendarAnchorTimeOrNull(): LocalTime? =
    takeIf { it in 0..<MILLIS_PER_DAY }?.let { LocalTime.ofNanoOfDay(it.toLong() * 1_000_000L) }
