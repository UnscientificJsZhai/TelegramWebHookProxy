package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Calendar
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
 * 控制路径中调用。任务执行完成后的状态只有在持久化成功后才会从内存移除或推进，因此发生
 * 存储故障时保留任务并按至少一次语义重试，而非承诺 exactly-once。
 *
 * @param parentScope 后台扫描任务所属的协程作用域；取消该作用域会停止扫描。
 * @param telegramService 用于投递任务执行结果的 Telegram 服务。
 * @param agentService 用于取得执行任务指令的 AI 代理服务的提供者。
 */
@Singleton
class TaskSchedulerService private constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val agentService: Provider<AgentService>,
    private val storage: AtomicJsonStorage,
    startImmediately: Boolean,
) : AutoCloseable {

    /**
     * 创建使用默认配置文件且立即启动扫描的调度服务。
     *
     * @constructor 创建使用 `config/schedule.json` 的服务。
     * @param parentScope 后台扫描任务所属的协程作用域。
     * @param telegramService 用于投递任务执行结果的 Telegram 服务。
     * @param agentService 用于取得 AI 代理的提供者。
     */
    @Inject
    constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: Provider<AgentService>,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        AtomicJsonStorage(File("config/schedule.json").toPath()),
        startImmediately = true,
    )

    /** 为临时配置文件和故障注入测试创建不自动扫描的调度服务。 */
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: Provider<AgentService>,
        scheduleFile: File,
        fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        startImmediately: Boolean = false,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        AtomicJsonStorage(scheduleFile.toPath(), fileOperations),
        startImmediately,
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
        when (val read = storage.readValidatedAndRecover(::decodeTasks)) {
            AtomicJsonRead.Missing -> Unit
            is AtomicJsonRead.Valid -> stateLock.withLock {
                tasks.clear()
                tasks.addAll(read.value)
                logger.info("Loaded {} scheduled tasks.", read.value.size)
            }

            is AtomicJsonRead.Corrupt -> {
                requiresStorageValidationBeforeWrite = true
                logger.error("Schedule file and its backup are semantically invalid; preserving both files", read.cause)
            }

            is AtomicJsonRead.IoFailure -> {
                requiresStorageValidationBeforeWrite = true
                logger.error("Unable to read scheduled tasks; delaying writes until it can be revalidated", read.cause)
            }

            is AtomicJsonRead.RecoveryFailed -> {
                requiresStorageValidationBeforeWrite = true
                logger.error(
                    "Validated schedule backup could not be restored; preserving files and disabling writes",
                    read.cause,
                )
            }

            is AtomicJsonRead.RecoverabilityPending -> {
                requiresStorageValidationBeforeWrite = true
                logger.error(
                    "Schedule recovery is blocked by I/O; delaying writes until revalidation",
                    read.cause,
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
        val validated = when (val read = storage.readValidatedAndRecover(::decodeTasks)) {
            AtomicJsonRead.Missing -> emptyList()
            is AtomicJsonRead.Valid -> read.value
            is AtomicJsonRead.Corrupt -> throw IllegalStateException("定时任务文件及备份均已损坏，拒绝覆盖现场。", read.cause)
            is AtomicJsonRead.IoFailure -> throw IllegalStateException("定时任务文件尚不可读取，拒绝覆盖现场。", read.cause)
            is AtomicJsonRead.RecoveryFailed ->
                throw IllegalStateException("有效定时任务备份无法恢复主文件，拒绝覆盖现场。", read.cause)

            is AtomicJsonRead.RecoverabilityPending ->
                throw IllegalStateException("定时任务备份尚不可读取或验证，拒绝覆盖现场。", read.cause)
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
                } catch (e: Exception) {
                    logger.error("Error during task scanning", e)
                }
                delay(1.minutes)
            }
        }
        logger.info("Task scheduler started.")
    }

    /**
     * 扫描并依次执行所有执行时间已到的任务。
     *
     * 候选任务会在短临界区内声明执行权；并发扫描不会重复执行同一任务。代理调用和 Telegram
     * I/O 永远不在状态锁内运行。存储可恢复性未决时会在短锁内重新验证并重载任务；验证失败
     * 时本轮不执行任务，后续扫描会重试。
     *
     * @throws CancellationException 扫描或任务执行被取消时抛出；被取消的任务不会推进或删除。
     */
    suspend fun scanAndExecute() {
        val currentTime = System.currentTimeMillis()
        val tasksToExecute = stateLock.withLock {
            try {
                ensureStorageValidatedBeforeMutation()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                logger.error("Failed to revalidate task storage before scanning; this scan will retry later", e)
                return@withLock emptyList()
            }
            tasks.filter { task ->
                task.executionTime <= currentTime && executingTaskIds.add(task.id)
            }
        }

        for (task in tasksToExecute) {
            try {
                executeTask(task)
            } finally {
                stateLock.withLock { executingTaskIds.remove(task.id) }
            }
        }
    }

    private suspend fun executeTask(task: ScheduledTask) {
        logger.info("Executing task: {} - {}", task.id, task.instruction)
        var completedExecutionAttempt = false
        try {
            val result = agentService.get()
                .sendMessage("以下是一个定时任务指令：\n${task.instruction}\n\n请直接执行并返回结果。")
            if (result.isNotBlank()) {
                telegramService.sendMessage(task.agentChatId, "⏰ 定时任务执行结果：\n\n$result")
            }
            completedExecutionAttempt = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to execute task ${task.id}", e)
            try {
                telegramService.sendMessage(task.agentChatId, "❌ 定时任务执行失败：${task.id}\n错误信息：${e.message}")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (notificationError: Exception) {
                logger.error("Failed to send task failure notification for ${task.id}", notificationError)
            }
            completedExecutionAttempt = true
        } finally {
            if (completedExecutionAttempt) {
                updateTaskAfterExecution(task)
            }
        }
    }

    private fun updateTaskAfterExecution(task: ScheduledTask) {
        stateLock.withLock {
            try {
                ensureStorageValidatedBeforeMutation()
            } catch (e: Exception) {
                logger.error("Failed to revalidate task storage after execution; task will be retried", e)
                return
            }
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index == -1) {
                return
            }
            val currentTask = tasks[index]
            val candidate = tasks.toMutableList()
            val nextExecutionTime = calculateNextExecutionTime(currentTask.executionTime, currentTask.loopMode)
            if (nextExecutionTime == null) {
                candidate.removeAt(index)
            } else {
                candidate[index] = currentTask.copy(executionTime = nextExecutionTime)
            }

            try {
                persistTasks(candidate)
            } catch (e: Exception) {
                logger.error("Failed to persist post-execution state for task {}; task will be retried", task.id, e)
                return
            }
            tasks.clear()
            tasks.addAll(candidate)
        }
    }

    private fun calculateNextExecutionTime(lastExecutionTime: Long, loopMode: LoopMode): Long? {
        val calendar = Calendar.getInstance().apply { timeInMillis = lastExecutionTime }
        val currentTime = System.currentTimeMillis()

        do {
            when (loopMode) {
                LoopMode.ONCE -> return null
                LoopMode.HOURLY -> calendar.add(Calendar.HOUR_OF_DAY, 1)
                LoopMode.DAILY -> calendar.add(Calendar.DAY_OF_YEAR, 1)
                LoopMode.WEEKLY -> calendar.add(Calendar.WEEK_OF_YEAR, 1)
            }
        } while (calendar.timeInMillis <= currentTime)

        return calendar.timeInMillis
    }

    /**
     * 创建并持久化一个定时任务。
     *
     * 仅当完整任务列表已原子持久化成功后才返回新标识；持久化失败时内存任务列表保持不变。
     *
     * @param instruction 到期时发送给 AI 代理的指令文本；允许为空字符串，将按原样保存。
     * @param executionTime 首次执行的 Unix 时间戳，单位为毫秒；可为过去时间，此时会在下一次
     * 扫描时执行。
     * @param loopMode 任务到期后的循环方式；[LoopMode.ONCE] 表示仅执行一次。
     * @param agentChatId 接收执行结果的 Telegram 会话标识；允许为空字符串，将按原样保存。
     * @return 已成功持久化的新任务八位标识符。
     * @throws IllegalStateException 文件、备份不可安全恢复或暂不可读取时抛出；不会添加内存任务。
     * @throws Exception 编码或原子持久化失败时抛出；不会添加内存任务。
     */
    fun createTask(instruction: String, executionTime: Long, loopMode: LoopMode, agentChatId: String): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val newTask = ScheduledTask(id, instruction, executionTime, loopMode, agentChatId)
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
     * @throws IllegalStateException 文件、备份不可安全恢复或暂不可读取时抛出；不会移除内存任务。
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
}
