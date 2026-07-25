package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.inject.Provider
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/**
 * 定时任务服务创建、取消和执行行为的测试设计。
 */
class TaskSchedulerServiceTest {

    private lateinit var telegramService: TelegramService
    private lateinit var agentService: AgentService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var service: TaskSchedulerService
    private val scheduleFile = File("config/schedule.json")

    @BeforeTest
    fun setup() {
        if (scheduleFile.exists()) {
            scheduleFile.delete()
        }
        telegramService = mockk()
        agentService = mockk()
        settingsRepository = mockk(relaxed = true)

        val agentProvider = Provider { agentService }
        val testScope = CoroutineScope(EmptyCoroutineContext)

        service = TaskSchedulerService(testScope, telegramService, agentProvider)
    }

    @AfterTest
    fun teardown() {
        if (scheduleFile.exists()) {
            scheduleFile.delete()
        }
        service.close()
    }

    /**
     * 验证创建和列出任务的设计。
     *
     * 验证创建后的任务会出现在任务列表中。
     */
    @Test
    fun testCreateAndListTasks() {
        val id = service.createTask("Test instruction", System.currentTimeMillis() + 10000, LoopMode.ONCE, "12345")
        assertNotNull(id)

        val tasks = service.listTasks()
        assertEquals(1, tasks.size)
        assertEquals(id, tasks[0].id)
        assertEquals("Test instruction", tasks[0].instruction)
    }

    /**
     * 验证取消任务的设计。
     *
     * 验证取消后任务不会保留在任务列表中。
     */
    @Test
    fun testCancelTask() {
        val id = service.createTask("Test instruction", System.currentTimeMillis() + 10000, LoopMode.ONCE, "12345")
        assertTrue(service.cancelTask(id))
        assertEquals(0, service.listTasks().size)
    }

    /**
     * 验证单次任务的执行设计。
     *
     * 验证任务到期后会调用代理并向目标聊天发送结果。
     */
    @Test
    fun testExecuteTask() = runTest {
        val chatId = "12345"
        val instruction = "Test instruction"
        service.createTask(instruction, System.currentTimeMillis() - 1000, LoopMode.ONCE, chatId)

        coEvery { agentService.sendMessage(any()) } returns "LLM result"
        coEvery { telegramService.sendMessage(any(), any()) } returns mockk()

        service.scanAndExecute()

        coVerify { agentService.sendMessage(any()) }
        coVerify { telegramService.sendMessage(chatId, match { it.contains("LLM result") }) }

        assertEquals(0, service.listTasks().size, "ONCE task should be removed after execution")
    }

    /**
     * 验证循环任务的执行设计。
     *
     * 验证任务执行后会按循环规则重新调度。
     */
    @Test
    fun testExecuteCyclicTask() = runTest {
        val chatId = "12345"
        val instruction = "Hourly task"
        val executionTime = System.currentTimeMillis() - 1000
        service.createTask(instruction, executionTime, LoopMode.HOURLY, chatId)

        coEvery { agentService.sendMessage(any()) } returns "LLM result"
        coEvery { telegramService.sendMessage(any(), any()) } returns mockk()

        service.scanAndExecute()

        val tasks = service.listTasks()
        assertEquals(1, tasks.size)
        assertTrue(tasks[0].executionTime > executionTime, "Next execution time should be in the future")
    }
}
