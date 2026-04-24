package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import javax.inject.Provider
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

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

    @Test
    fun testCreateAndListTasks() {
        val id = service.createTask("Test instruction", System.currentTimeMillis() + 10000, LoopMode.ONCE, "12345")
        assertNotNull(id)
        
        val tasks = service.listTasks()
        assertEquals(1, tasks.size)
        assertEquals(id, tasks[0].id)
        assertEquals("Test instruction", tasks[0].instruction)
    }

    @Test
    fun testCancelTask() {
        val id = service.createTask("Test instruction", System.currentTimeMillis() + 10000, LoopMode.ONCE, "12345")
        assertTrue(service.cancelTask(id))
        assertEquals(0, service.listTasks().size)
    }

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
