package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Path
import javax.inject.Provider
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * 定时任务服务创建、取消和执行行为的测试设计。
 */
class TaskSchedulerServiceTest {

    private lateinit var telegramService: TelegramService
    private lateinit var agentService: AgentService
    private lateinit var service: TaskSchedulerService
    private val tempDirectory = createTempDirectory("task-scheduler-test").toFile()
    private val scheduleFile = File(tempDirectory, "schedule.json")

    @BeforeTest
    fun setup() {
        telegramService = mockk()
        agentService = mockk()

        val agentProvider = Provider { agentService }
        val testScope = CoroutineScope(EmptyCoroutineContext)

        service = TaskSchedulerService(testScope, telegramService, agentProvider, scheduleFile)
    }

    @AfterTest
    fun teardown() {
        service.close()
        tempDirectory.deleteRecursively()
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

    /**
     * 验证创建和取消在主文件替换失败时不会变更内存任务列表。
     */
    @Test
    fun `create and cancel keep memory unchanged when persistence fails`() {
        val failingOperations = primaryReplaceFailingOperations()
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            scheduleFile,
            failingOperations,
        )

        assertFailsWith<IOException> {
            service.createTask("will fail", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")
        }
        assertTrue(service.listTasks().isEmpty())

        val cancelScheduleFile = File(tempDirectory, "cancel-schedule.json")
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            cancelScheduleFile,
        )
        val taskId = service.createTask("persisted", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")

        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            cancelScheduleFile,
            primaryReplaceFailingOperations(cancelScheduleFile),
        )
        assertFailsWith<IOException> { service.cancelTask(taskId) }
        assertEquals(listOf(taskId), service.listTasks().map { it.id })
    }

    /**
     * 验证任务已经执行但后续保存失败时仍留在内存中以便至少一次重试。
     */
    @Test
    fun `post execution persistence failure retains task for retry`() = runTest {
        val taskId = service.createTask("due", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            scheduleFile,
            primaryReplaceFailingOperations(),
        )
        coEvery { agentService.sendMessage(any()) } returns "result"
        coEvery { telegramService.sendMessage(any(), any()) } returns mockk()

        service.scanAndExecute()

        coVerify { agentService.sendMessage(any()) }
        assertEquals(listOf(taskId), service.listTasks().map { it.id })
    }

    /**
     * 验证有效备份恢复失败会禁用创建和取消，避免把损坏主文件复制到备份。
     */
    @Test
    fun `failed schedule recovery disables create and cancel without touching primary or backup`() {
        val backupFile = File(tempDirectory, "schedule.json.bak")
        val damagedPrimary = "[ invalid"
        val validBackup = ConfigJson.encodeToString(
            listOf(ScheduledTask("backup", "instruction", 1L, LoopMode.ONCE, "12345")),
        )
        scheduleFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            scheduleFile,
            primaryReplaceFailingOperations(),
        )

        assertFailsWith<IllegalStateException> {
            service.createTask("new", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")
        }
        assertFailsWith<IllegalStateException> { service.cancelTask("backup") }
        assertEquals(damagedPrimary, scheduleFile.readText())
        assertEquals(validBackup, backupFile.readText())
    }

    /**
     * 验证主调度文件缺失时会恢复有效备份，任务仍可被正常列出。
     */
    @Test
    fun `missing schedule primary restores valid backup`() {
        val backupFile = File(tempDirectory, "schedule.json.bak")
        val expectedTask = ScheduledTask("backup", "instruction", 1L, LoopMode.ONCE, "12345")
        val backupContent = ConfigJson.encodeToString(listOf(expectedTask))
        backupFile.writeText(backupContent)
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            scheduleFile,
        )

        assertEquals(listOf(expectedTask), service.listTasks())
        assertEquals(backupContent, scheduleFile.readText())
        assertEquals(backupContent, backupFile.readText())
    }

    /**
     * 验证主调度文件和备份均损坏时，创建和取消均拒绝且现场不被默认状态覆盖。
     */
    @Test
    fun `double damaged schedule files reject create and cancel without overwriting either file`() {
        val backupFile = File(tempDirectory, "schedule.json.bak")
        val damagedPrimary = "[ invalid"
        val damagedBackup = "{ invalid"
        scheduleFile.writeText(damagedPrimary)
        backupFile.writeText(damagedBackup)
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            scheduleFile,
        )

        assertFailsWith<IllegalStateException> {
            service.createTask("new", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")
        }
        assertFailsWith<IllegalStateException> { service.cancelTask("missing") }
        assertEquals(damagedPrimary, scheduleFile.readText())
        assertEquals(damagedBackup, backupFile.readText())
    }

    /**
     * 验证扫描会在短锁内重试恢复成功的备份，并执行恢复出的到期任务。
     */
    @Test
    fun `scan revalidates recovered backup before executing due task`() = runTest {
        val backupFile = File(tempDirectory, "schedule.json.bak")
        val recoveredTask = ScheduledTask("backup", "instruction", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        scheduleFile.writeText("[ invalid")
        backupFile.writeText(ConfigJson.encodeToString(listOf(recoveredTask)))
        var blockBackupRead = true
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAllBytes(path: Path): ByteArray {
                if (blockBackupRead && path == backupFile.toPath()) {
                    throw IOException("injected backup read failure")
                }
                return DefaultAtomicJsonFileOperations.readAllBytes(path)
            }
        }
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            scheduleFile,
            fileOperations,
        )
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessage(any(), any()) } returns mockk()

        blockBackupRead = false
        service.scanAndExecute()

        coVerify { agentService.sendMessage(any()) }
        assertTrue(service.listTasks().isEmpty())
    }

    /**
     * 验证取消执行时会传播取消，且一次性任务不会被删除或推进。
     */
    @Test
    fun `cancelled task execution keeps one shot task for retry`() = runTest {
        val taskId = service.createTask("cancel", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { service.scanAndExecute() }

        assertEquals(listOf(taskId), service.listTasks().map { it.id })
    }

    private fun primaryReplaceFailingOperations(targetFile: File = scheduleFile): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == targetFile.toPath()) {
                    throw IOException("injected primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
}
