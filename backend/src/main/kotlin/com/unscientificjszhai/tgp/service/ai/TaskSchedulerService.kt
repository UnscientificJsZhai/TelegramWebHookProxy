package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.service.TelegramService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Calendar
import java.util.UUID
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

@Singleton
class TaskSchedulerService @Inject constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val geminiAgentService: Provider<GeminiAgentService>
) : AutoCloseable {

    private val logger = LoggerFactory.getLogger(TaskSchedulerService::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job.Key])

    private val scheduleFile = File("config/schedule.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

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
                val loadedTasks = json.decodeFromString<List<ScheduledTask>>(content)
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
                json.encodeToString(tasks.toList())
            }
            scheduleFile.writeText(content)
        } catch (e: Exception) {
            logger.error("Failed to save scheduled tasks", e)
        }
    }

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
            val result = geminiAgentService.get()
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

    fun createTask(instruction: String, executionTime: Long, loopMode: LoopMode, agentChatId: String): String {
        val id = UUID.randomUUID().toString().substring(0, 8)
        val newTask = ScheduledTask(id, instruction, executionTime, loopMode, agentChatId)
        synchronized(tasks) {
            tasks.add(newTask)
        }
        saveTasks()
        return id
    }

    fun listTasks(): List<ScheduledTask> {
        return synchronized(tasks) {
            tasks.toList()
        }
    }

    fun cancelTask(taskId: String): Boolean {
        val removed = synchronized(tasks) {
            tasks.removeIf { it.id == taskId }
        }
        if (removed) {
            saveTasks()
        }
        return removed
    }

    override fun close() {
        job?.cancel()
        job = null
        logger.info("Task scheduler stopped.")
    }
}