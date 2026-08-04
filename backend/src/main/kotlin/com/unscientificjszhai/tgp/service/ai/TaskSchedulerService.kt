package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.repository.ActiveTelegramBotUnavailableException
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.TelegramBotLease
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.TelegramApiResponse
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
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
 * 控制路径中调用。每次执行会先在状态锁内重新确认任务内容仍等于扫描快照且仍到期，随后
 * 原子持久化删除单次任务或推进循环任务，最后才调用代理和 Telegram。该预消费提交成功后，即使代理、投递、
 * 协程取消或进程崩溃发生在副作用完成前，也绝不会恢复或重试该次；这刻意提供 at-most-once，而非
 * exactly-once，代价是提交与副作用之间崩溃或取消可能遗漏一次执行。预提交失败、任务被取消或替换、
 * 或已经不再到期时不会调用代理。
 * 调度的服务器时间和日历时区分别由 [Clock] 与 [ZoneId] 决定。错过的循环周期只预消费一次并跳到下一次
 * 未来执行，不逐期追赶；小时任务保持 Unix epoch 相位，日/周任务保持服务器时区中的本地日历锚点。DST
 * gap 解析到首个有效本地时间，overlap 使用较早偏移量；日/周任务会持久化创建时的本地时刻锚点，所以 gap
 * 当次的延后时刻不会漂移到之后的日期或周。无法表示未来时刻或存在无效日历锚点时同样预消费删除并记录警告，
 * 避免永久重复。
 * 扫描会在 [AgentService.withReadyService] 的同一次模型切换屏障准入中捕获短暂的 Bot token 租约、预消费
 * 任务、完成 Agent 回合并投递结果。准入后的完整链路受 [AgentExecutionDeadlines.scheduledTurn] 的总体时限
 * 约束；超时任务已预消费但不会重试或投递迟到结果。因此切换已发生时旧任务不会调用旧 Agent 或旧 token；
 * 任务已准入时，对应的 Agent 切换最多等待该完整回合结束或超时，投递始终使用所捕获的旧 token。
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
    private val storage: AtomicJsonStorage,
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
        AtomicJsonStorage(File("config/schedule.json").toPath(), ResourceLimits.SCHEDULE_BYTES),
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
     * @param settingsRepository 用于确认任务 Bot 所有者并捕获 token 的仓储。
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
        AtomicJsonStorage(scheduleFile.toPath(), ResourceLimits.SCHEDULE_BYTES, fileOperations),
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
    private var requiresStorageValidationBeforeWrite = false
    private var job: Job? = null

    init {
        loadTasks()
        if (startImmediately) {
            start()
        }
    }

    private fun loadTasks() {
        when (val read = storage.readValidated(::decodeTasks)) {
            AtomicJsonRead.Missing -> Unit
            is AtomicJsonRead.Valid -> stateLock.withLock {
                tasks.clear()
                tasks.addAll(read.value)
                logger.info("Loaded {} scheduled tasks.", read.value.size)
            }

            is AtomicJsonRead.Corrupt -> {
                requiresStorageValidationBeforeWrite = true
                logger.error(
                    "Schedule file is semantically invalid; preserving it; category={}",
                    SafeLogging.failureCategory(read.cause).wireName,
                )
            }

            is AtomicJsonRead.IoFailure -> {
                requiresStorageValidationBeforeWrite = true
                logger.error(
                    "Unable to read scheduled tasks; delaying writes until it can be revalidated; category={}",
                    SafeLogging.failureCategory(read.cause).wireName,
                )
            }

        }
    }

    private fun decodeTasks(bytes: ByteArray): List<ScheduledTask> {
        val content = bytes.toString(StandardCharsets.UTF_8)
        if (content.isBlank()) {
            throw IllegalArgumentException("Scheduled tasks data must not be blank")
        }
        return ConfigJson.decodeFromString(content)
    }

    private fun persistTasks(candidate: List<ScheduledTask>) {
        ensureStorageValidatedBeforeMutation()
        storage.commit(ConfigJson.encodeToString(candidate).toByteArray(StandardCharsets.UTF_8))
    }

    private fun ensureStorageValidatedBeforeMutation() {
        if (!requiresStorageValidationBeforeWrite) {
            return
        }
        val validated = when (val read = storage.readValidated(::decodeTasks)) {
            AtomicJsonRead.Missing -> emptyList()
            is AtomicJsonRead.Valid -> read.value
            is AtomicJsonRead.Corrupt -> throw IllegalStateException("定时任务文件已损坏，拒绝覆盖现场。", read.cause)
            is AtomicJsonRead.IoFailure -> throw IllegalStateException(
                "定时任务文件尚不可读取，拒绝覆盖现场。",
                read.cause
            )
        }
        tasks.clear()
        tasks.addAll(validated)
        requiresStorageValidationBeforeWrite = false
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
     * 候选任务会在短临界区内声明执行权；并发扫描不会重复执行同一任务。每个候选进入 Agent 就绪屏障后，
     * 会重新确认扫描快照和到期状态，并在任何 Agent 或 Telegram 副作用前原子持久化预消费状态。
     * 因而预提交成功后的失败、取消和进程重启都不会重放该次，提交失败则不会调用 Agent 且会在后续扫描保留
     * 任务。代理调用与 Telegram I/O 永远不在状态锁或 token 租约内运行。
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
            try {
                ensureStorageValidatedBeforeMutation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                logger.error(
                    "Failed to revalidate task storage before scanning; this scan will retry later; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
                return@withLock emptyList()
            }
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
     * token 租约只用于短暂捕获投递 token；状态锁只用于重新验证扫描快照、以 fresh now 预消费并持久化。
     * 二者都会在副作用前释放。预消费成功后，代理失败、普通异常、取消以及 Telegram 失败或取消均不会恢复
     * 或重试任务；这在崩溃或取消落在提交和副作用之间时可能遗漏一次执行，但避免重复外部副作用。
     *
     * @param task 已到期且已被当前扫描声明执行权的任务。
     * [AgentExecutionDeadlines.scheduledTurn] 到期只记录稳定任务标识并停止后续投递，不重试已预消费任务；
     * 普通 [CancellationException] 仍会原样抛出，且已预消费状态不回滚。
     *
     * @throws CancellationException 当 Agent、Telegram 调用或当前协程被普通取消时原样抛出；已预消费状态不回滚。
     */
    private suspend fun executeTask(task: ScheduledTask) {
        agentService.get().withReadyService { readyAgent ->
            try {
                withTimeout(deadlines.scheduledTurn) {
                    val preparedTask = prepareTaskForExecution(task) ?: return@withTimeout
                    logger.info("Executing precommitted task {}", preparedTask.task.id)
                    val result = readyAgent.sendMessage(
                        "以下是一个定时任务指令：\n${preparedTask.task.instruction}\n\n请直接执行并返回结果。",
                    )
                    if (result.isNotBlank()) {
                        deliverTaskResult(preparedTask.task, preparedTask.botLease.token, result)
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
    }

    /**
     * 在副作用前确认 token 和扫描快照，并原子持久化本次预消费状态。
     *
     * @param task 扫描时声明执行权的不可变任务快照。
     * @return 已提交且可安全执行的任务与 token 快照；token、快照、到期或持久化验证不满足时返回 `null`。
     * @throws CancellationException 当调用协程在存储操作期间被取消时原样抛出。
     */
    private fun prepareTaskForExecution(task: ScheduledTask): PreparedTask? {
        val botLease = try {
            settingsRepository.withActiveTelegramBotLease { it }
        } catch (_: ActiveTelegramBotUnavailableException) {
            logger.warn("Skipping task {} because no valid active Telegram Bot is available", task.id)
            return null
        }

        return try {
            stateLock.withLock {
                ensureStorageValidatedBeforeMutation()
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
                    persistTasks(candidate)
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
                persistTasks(candidate)
                tasks.clear()
                tasks.addAll(candidate)
                PreparedTask(currentTask, botLease)
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
     * 尽力向 Telegram 投递已完成代理回合的结果。
     *
     * 非取消失败和 API 非成功响应只记录日志，避免因投递问题重复执行可能带外部副作用的代理回合。
     *
     * @param task 已完成代理回合的任务。
     * @param token 在任务执行租约中捕获的 Telegram Bot token。
     * @param result 要发送给任务会话的非空结果文本。
     * @throws CancellationException 当当前协程或 Telegram 调用被取消时抛出。
     */
    private suspend fun deliverTaskResult(task: ScheduledTask, token: String, result: String) {
        try {
            val response = telegramService.sendMessageForToken(
                token,
                task.agentChatId,
                "⏰ 定时任务执行结果：\n\n$result",
            )
            if (!response.isTelegramOk()) {
                logger.warn("Telegram did not accept task result for {}", task.id)
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

    /**
     * 判断 Telegram HTTP 响应是否同时具有成功状态码和 API `ok: true` 标记。
     *
     * @receiver 已完整读取的 Telegram 响应快照。
     * @return HTTP 状态成功且响应 JSON 的 `ok` 字段为 `true` 时返回 `true`，否则返回 `false`。
     */
    private fun TelegramApiResponse.isTelegramOk(): Boolean {
        return status.isSuccess() && try {
            Json.parseToJsonElement(body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 创建并持久化一个定时任务。
     *
     * 仅当完整任务列表已原子持久化成功后才返回新标识；持久化失败时内存任务列表保持不变。
     * @param instruction 到期时发送给 AI 代理的指令文本；允许为空字符串，将按原样保存。
     * @param executionTime 首次执行的 Unix 时间戳，单位为毫秒；可为过去时间，此时会在下一次
     * 扫描时执行。
     * @param loopMode 任务到期后的循环方式；[LoopMode.ONCE] 表示仅执行一次。
     * @param agentChatId 接收执行结果的 Telegram 会话标识；允许为空字符串，将按原样保存。
     * @return 已成功持久化的新任务八位标识符。
     * @throws IllegalStateException 文件已损坏或暂不可读取时抛出；不会添加内存任务。
     * @throws Exception 编码或原子持久化失败时抛出；不会添加内存任务。
     */
    fun createTask(instruction: String, executionTime: Long, loopMode: LoopMode, agentChatId: String): String {
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
            ensureStorageValidatedBeforeMutation()
            val candidate = tasks + newTask
            persistTasks(candidate)
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
     * 找到任务时，只有原子持久化成功才会从内存移除并返回 `true`；写入失败会抛出且内存不变。
     *
     * @param taskId 要取消的任务标识；空字符串或不存在的标识不会取消任务。
     * @return 找到并成功持久化移除任务时返回 `true`；不存在匹配任务时返回 `false`。
     * @throws IllegalStateException 文件已损坏或暂不可读取时抛出；不会移除内存任务。
     * @throws Exception 编码或原子持久化失败时抛出；不会移除内存任务。
     */
    fun cancelTask(taskId: String): Boolean = stateLock.withLock {
        ensureStorageValidatedBeforeMutation()
        val index = tasks.indexOfFirst { it.id == taskId }
        if (index == -1) {
            return@withLock false
        }
        val candidate = tasks.toMutableList().apply { removeAt(index) }
        persistTasks(candidate)
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
        val botLease: TelegramBotLease,
    )
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
