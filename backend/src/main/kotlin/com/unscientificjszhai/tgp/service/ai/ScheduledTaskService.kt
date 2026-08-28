package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.utils.AtomicJsonCommitResult
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.SchemaValidatedJsonStorage
import com.unscientificjszhai.tgp.utils.requireDurable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import java.io.File
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
import javax.inject.Singleton
import kotlin.concurrent.withLock

/**
 * 持久化定时任务并提供线程安全的任务状态事务。
 *
 * 本服务只负责加载、CRUD 和持久化，不启动协程，也不依赖 Agent、Telegram 或应用生命周期。
 * worker 在调用外部副作用前必须使用 [precommitExecution] 原子确认扫描快照仍有效，并耐久删除单次任务
 * 或推进循环任务。只有该方法返回任务时，调用方才可以执行不可重放副作用。
 *
 * @param storage 按 [ScheduledTask] schema 读写任务列表的 JSON 存储。
 * @param zoneId 日、周循环任务使用的服务器日历时区。
 */
@Singleton
class ScheduledTaskService private constructor(
    private val storage: SchemaValidatedJsonStorage<List<ScheduledTask>>,
    private val zoneId: ZoneId,
) {
    /** 创建使用 `config/schedule.json` 和系统日历时区的任务服务。 */
    @Inject
    internal constructor() : this(
        SchemaValidatedJsonStorage(
            AtomicJsonStorage(File("config/schedule.json").toPath(), ResourceLimits.SCHEDULE_BYTES),
            ListSerializer(ScheduledTask.serializer()),
        ),
        ZoneId.systemDefault(),
    )

    /**
     * 为临时配置文件、故障注入和确定性时区测试创建任务服务。
     *
     * @param scheduleFile 测试使用的任务 JSON 文件。
     * @param fileOperations 原子 JSON 存储使用的文件操作实现。
     * @param zoneId 日、周循环任务使用的日历时区。
     */
    internal constructor(
        scheduleFile: File,
        fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ) : this(
        SchemaValidatedJsonStorage(
            AtomicJsonStorage(scheduleFile.toPath(), ResourceLimits.SCHEDULE_BYTES, fileOperations),
            ListSerializer(ScheduledTask.serializer()),
        ),
        zoneId,
    )

    private val logger = LoggerFactory.getLogger(ScheduledTaskService::class.java)
    private val stateLock = ReentrantLock()
    private val tasks = mutableListOf<ScheduledTask>()
    private var acceptsNewTasks = true

    init {
        loadTasks()
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

    private fun persistTasks(candidate: List<ScheduledTask>): AtomicJsonCommitResult = storage.commit(candidate)

    /**
     * 创建并耐久保存一个定时任务。
     *
     * @param instruction 任务到期后交给 Agent 执行的指令。
     * @param executionTime 首次执行的 epoch 毫秒时间。
     * @param loopMode 单次或循环执行模式。
     * @param agentChatId 创建任务时绑定的 Agent 私聊标识。
     * @return 新任务的稳定短标识。
     */
    fun createTask(instruction: String, executionTime: Long, loopMode: LoopMode, agentChatId: String): String {
        require(agentChatId.isNotBlank()) { "定时任务必须绑定非空代理会话标识。" }
        val id = UUID.randomUUID().toString().substring(0, 8)
        val newTask = ScheduledTask(
            id = id,
            instruction = instruction,
            executionTime = executionTime,
            loopMode = loopMode,
            agentChatId = agentChatId,
            calendarAnchorTimeMillis = executionTime.calendarAnchorTimeMillisFor(loopMode, zoneId),
        )
        return stateLock.withLock {
            check(acceptsNewTasks) { "Scheduled task creation is stopped." }
            val candidate = tasks + newTask
            persistTasks(candidate).requireDurable()
            tasks.add(newTask)
            id
        }
    }

    /**
     * 返回当前任务顺序的不可变快照。
     *
     * @return 按持久化顺序复制的任务列表。
     */
    fun listTasks(): List<ScheduledTask> = stateLock.withLock { tasks.toList() }

    /**
     * 按标识取消任务。
     *
     * @param taskId 要取消的任务标识。
     * @return 找到并耐久删除任务时返回 `true`；任务不存在时返回 `false`。
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
     * 返回在 [nowMillis] 已到期的任务快照。
     *
     * 返回值不声明执行权；worker 仍须在自身生命周期锁中去重，并在副作用前调用 [precommitExecution]。
     *
     * @param nowMillis 判定到期状态的 epoch 毫秒时间。
     * @return 当前执行时间不晚于 [nowMillis] 的不可变任务快照。
     */
    internal fun dueTaskSnapshots(nowMillis: Long): List<ScheduledTask> = stateLock.withLock {
        tasks.filter { it.executionTime <= nowMillis }
    }

    /**
     * 条件预消费扫描快照，并仅在新状态确认耐久后返回可执行任务。
     *
     * 快照已取消或替换、已不再到期、日历锚点无效或未来时间不可表示时返回 `null`。无效锚点及不可继续
     * 循环的任务会耐久删除。持久化失败或替换后耐久性未知时抛出，且内存状态保持不变。
     * [currentTimeMillis] 在状态锁内、紧邻到期复核时调用，必须是无阻塞且无副作用的快速时钟读取。
     *
     * @param expected worker 扫描时取得且必须仍与当前任务相等的快照。
     * @param currentTimeMillis 在状态锁内读取当前 epoch 毫秒时间的函数。
     * @return 已耐久预消费、可以执行外部副作用的任务；验证不通过时返回 `null`。
     */
    internal fun precommitExecution(
        expected: ScheduledTask,
        currentTimeMillis: () -> Long,
    ): ScheduledTask? = stateLock.withLock {
        val index = tasks.indexOfFirst { current -> current.id == expected.id && current == expected }
        if (index == -1) {
            logger.info("Skipping task {} because it was cancelled or replaced before admission", expected.id)
            return@withLock null
        }

        val currentTask = tasks[index]
        val nowMillis = currentTimeMillis()
        if (currentTask.executionTime > nowMillis) {
            logger.info("Skipping task {} because it is no longer due", expected.id)
            return@withLock null
        }

        val calendarAnchorTimeMillis = currentTask.calendarAnchorTimeMillisOrLegacy(zoneId)
        val hasInvalidCalendarAnchor = currentTask.requiresCalendarAnchor() &&
                currentTask.calendarAnchorTimeMillis != null && calendarAnchorTimeMillis == null
        if (hasInvalidCalendarAnchor) {
            logger.warn("Removing task {} because its persisted calendar anchor is invalid", expected.id)
            val candidate = tasks.toMutableList().apply { removeAt(index) }
            persistTasks(candidate).requireDurable()
            tasks.clear()
            tasks.addAll(candidate)
            return@withLock null
        }

        val nextExecutionTime = calculateNextExecutionTime(
            currentTask.executionTime,
            currentTask.loopMode,
            Instant.ofEpochMilli(nowMillis),
            zoneId,
            calendarAnchorTimeMillis,
        )
        if (currentTask.loopMode != LoopMode.ONCE && nextExecutionTime == null) {
            logger.warn("Removing task {} because no future execution time can be represented", expected.id)
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
        persistTasks(candidate).requireDurable()
        tasks.clear()
        tasks.addAll(candidate)
        currentTask
    }

    /**
     * 仅当当前任务仍与 [expected] 完全一致时耐久删除。
     *
     * 返回 `false` 表示任务已经取消或替换；持久化失败时内存保持不变并抛出。
     *
     * @param expected 必须仍与当前状态相等的任务快照。
     * @return 快照匹配且任务已耐久删除时返回 `true`。
     */
    internal fun removeIfUnchanged(expected: ScheduledTask): Boolean = stateLock.withLock {
        val index = tasks.indexOfFirst { current -> current.id == expected.id && current == expected }
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
     * 关闭新建任务准入。
     *
     * 返回后，与本方法在线性化点之前并发进入的创建已完成，之后的 [createTask] 都会失败；查询、取消及
     * worker 对既有任务的耐久预消费仍保持可用，以便应用完成关停收尾。
     */
    internal fun stopAcceptingNewTasks() {
        stateLock.withLock { acceptsNewTasks = false }
    }

    private fun ScheduledTask.requiresCalendarAnchor(): Boolean =
        loopMode == LoopMode.DAILY || loopMode == LoopMode.WEEKLY

    private fun ScheduledTask.calendarAnchorTimeMillisOrLegacy(zoneId: ZoneId): Int? = when {
        !requiresCalendarAnchor() -> null
        calendarAnchorTimeMillis == null -> executionTime.calendarAnchorTimeMillisFor(loopMode, zoneId)
        calendarAnchorTimeMillis.toCalendarAnchorTimeOrNull() != null -> calendarAnchorTimeMillis
        else -> null
    }
}

/**
 * 计算循环任务在 [now] 之后的首个可表示执行时刻。
 *
 * @param lastExecutionTime 上一次计划执行的 epoch 毫秒时间。
 * @param loopMode 任务循环模式。
 * @param now 用于跳过错过周期的当前时刻。
 * @param zoneId 日、周任务使用的日历时区。
 * @param calendarAnchorTimeMillis 日、周任务持久化的本地日内毫秒锚点。
 * @return 下一次执行的 epoch 毫秒时间；单次任务或无法表示未来时间时返回 `null`。
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

private fun resolveLocalExecutionTime(date: LocalDate, time: LocalTime, zoneId: ZoneId): ZonedDateTime {
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
