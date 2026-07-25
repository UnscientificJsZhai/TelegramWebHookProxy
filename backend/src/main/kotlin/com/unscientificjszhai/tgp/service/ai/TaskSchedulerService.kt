package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

/**
 * 持久化并按时执行面向 AI 代理的定时任务。
 *
 * 服务在创建时从 `config/schedule.json` 恢复任务并启动后台扫描；任务执行结果会发送到
 * 对应的 Telegram 会话。调用 [close] 会停止后续扫描；[start] 与 [close] 应在同一生命周期
 * 控制路径中调用。
 *
 * @param parentScope 后台扫描任务所属的协程作用域；取消该作用域会停止扫描。
 * @param telegramService 用于投递任务执行结果的 Telegram 服务。
 * @param agentService 用于取得执行任务指令的 AI 代理服务的提供者。
 */
@Singleton
class TaskSchedulerService @Inject constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val agentService: Provider<AgentService>
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(TaskSchedulerService::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job.Key])

    private val scheduleFile = File("config/schedule.json")

    private val tasks = mutableListOf<ScheduledTask>()
    private var job: Job? = null

    init {
        if (!scheduleFile.parentFile.exists()) {
            scheduleFile.parentFile.mkdirs()
        }
        loadTasks()
        start()
    }

    private fun loadTasks() {
        if (scheduleFile.exists()) {
            try {
                val content = scheduleFile.readText()
                val loadedTasks = ConfigJson.decodeFromString<List<ScheduledTask>>(content)
                synchronized(tasks) {
                    tasks.clear()
                    tasks.addAll(loadedTasks)
                }
                logger.info("Loaded ${loadedTasks.size} scheduled tasks.")
            } catch (e: Exception) {
                logger.error("Failed to load scheduled tasks", e)
            }
        }
    }

    private fun saveTasks() {
        try {
            val content = synchronized(tasks) {
                ConfigJson.encodeToString(tasks.toList())
            }
            scheduleFile.writeText(content)
        } catch (e: Exception) {
            logger.error("Failed to save scheduled tasks", e)
        }
    }

    /**
     * 启动每分钟一次的后台任务扫描。
     *
     * 已启动扫描任务时此方法不执行额外操作；扫描任务会持续到 [close] 被调用或其协程
     * 作用域被取消。
     */
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
     * 每个任务都会通过 AI 代理依次执行，并在执行后更新其下一次执行时间或将一次性任务删除；
     * 执行结果或失败信息会发送到任务关联的 Telegram 会话。调用方应避免并发调用本方法，
     * 否则同一到期任务可能被重复执行。
     */
    suspend fun scanAndExecute() {
        val currentTime = System.currentTimeMillis()
        val tasksToExecute = synchronized(tasks) {
            tasks.filter { it.executionTime <= currentTime }
        }

        for (task in tasksToExecute) {
            executeTask(task)
        }
    }

    private suspend fun executeTask(task: ScheduledTask) {
        logger.info("Executing task: ${task.id} - ${task.instruction}")
        try {
            val result = agentService.get()
                .sendMessage("以下是一个定时任务指令：\n${task.instruction}\n\n请直接执行并返回结果。")
            if (result.isNotBlank()) {
                telegramService.sendMessage(task.agentChatId, "⏰ 定时任务执行结果：\n\n$result")
            }
        } catch (e: Exception) {
            logger.error("Failed to execute task ${task.id}", e)
            telegramService.sendMessage(task.agentChatId, "❌ 定时任务执行失败：${task.id}\n错误信息：${e.message}")
        } finally {
            updateTaskAfterExecution(task)
        }
    }

    private fun updateTaskAfterExecution(task: ScheduledTask) {
        synchronized(tasks) {
            val index = tasks.indexOfFirst { it.id == task.id }
            if (index != -1) {
                val nextExecutionTime = calculateNextExecutionTime(task.executionTime, task.loopMode)
                if (nextExecutionTime != null) {
                    tasks[index] = task.copy(executionTime = nextExecutionTime)
                } else {
                    tasks.removeAt(index)
                }
            }
        }
        saveTasks()
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
     * @param instruction 到期时发送给 AI 代理的指令文本；允许为空字符串，将按原样保存。
     * @param executionTime 首次执行的 Unix 时间戳，单位为毫秒；可为过去时间，此时会在下一次
     * 扫描时执行。
     * @param loopMode 任务到期后的循环方式；[LoopMode.ONCE] 表示仅执行一次。
     * @param agentChatId 接收执行结果的 Telegram 会话标识；允许为空字符串，将按原样保存。
     * @return 新任务的八位标识符。
     */
    fun createTask(instruction: String, executionTime: Long, loopMode: LoopMode, agentChatId: String): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val newTask = ScheduledTask(id, instruction, executionTime, loopMode, agentChatId)
        synchronized(tasks) {
            tasks.add(newTask)
        }
        saveTasks()
        return id
    }

    /**
     * 获取当前所有已调度任务的快照。
     *
     * @return 按当前调度顺序排列的任务列表；没有任务时返回空列表。修改返回列表不会影响调度器。
     */
    fun listTasks(): List<ScheduledTask> {
        return synchronized(tasks) {
            tasks.toList()
        }
    }

    /**
     * 取消指定任务并在取消成功时持久化最新任务列表。
     *
     * @param taskId 要取消的任务标识；空字符串或不存在的标识不会取消任务。
     * @return 找到并移除任务时返回 `true`；不存在匹配任务时返回 `false`。
     */
    fun cancelTask(taskId: String): Boolean {
        val removed = synchronized(tasks) {
            tasks.removeIf { it.id == taskId }
        }
        if (removed) {
            saveTasks()
        }
        return removed
    }

    /**
     * 停止后台扫描任务。
     *
     * 此方法不会等待已开始的任务执行结束，且可重复调用。
     */
    override fun close() {
        job?.cancel()
        job = null
        logger.info("Task scheduler stopped.")
    }
}
