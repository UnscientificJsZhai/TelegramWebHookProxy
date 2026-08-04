package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var service: TaskSchedulerService
    private val tempDirectory = createTempDirectory("task-scheduler-test").toFile()
    private val scheduleFile = File(tempDirectory, "schedule.json")

    @BeforeTest
    fun setup() {
        telegramService = mockk()
        agentService = mockk()

        val agentProvider = Provider { agentService }
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), ModelSwitchBarrier())
        settingsRepository.saveSettings(AppSettings(telegramToken = BOT_A_TOKEN))

        service = TaskSchedulerService(testScope, telegramService, agentProvider, settingsRepository, scheduleFile)
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
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        service.scanAndExecute()

        coVerify { agentService.sendMessage(any()) }
        coVerify {
            telegramService.sendMessageForToken(BOT_A_TOKEN, chatId, match { it.contains("LLM result") }, any())
        }

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
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

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
            settingsRepository,
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
            settingsRepository,
            cancelScheduleFile,
        )
        val taskId = service.createTask("persisted", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")

        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            settingsRepository,
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
            settingsRepository,
            scheduleFile,
            primaryReplaceFailingOperations(),
        )
        coEvery { agentService.sendMessage(any()) } returns "result"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

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
            settingsRepository,
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
            settingsRepository,
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
            settingsRepository,
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
        val recoveredTask = ScheduledTask(
            "backup",
            "instruction",
            System.currentTimeMillis() - 1_000,
            LoopMode.ONCE,
            "12345",
        )
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
            settingsRepository,
            scheduleFile,
            fileOperations,
        )
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        blockBackupRead = false
        service.scanAndExecute()

        coVerify { agentService.sendMessage(any()) }
        assertTrue(service.listTasks().isEmpty())
    }

    /**
     * 验证未完成代理回合和普通代理异常都会保留一次性任务，以便后续扫描至少一次重试。
     */
    @Test
    fun `failed agent turns keep one shot tasks for retry`() = runTest {
        val incompleteTask =
            service.createTask("incomplete", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } throws AgentTurnFailedException("未完成")

        service.scanAndExecute()

        assertEquals(listOf(incompleteTask), service.listTasks().map { it.id })
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }

        coEvery { agentService.sendMessage(any()) } throws IllegalStateException("ordinary failure")
        service.scanAndExecute()

        assertEquals(listOf(incompleteTask), service.listTasks().map { it.id })
        coVerify(exactly = 2) { agentService.sendMessage(any()) }
    }

    /**
     * 验证模型正常返回的 `Error:` 文本仍会完成任务，而不是作为代理失败重试。
     */
    @Test
    fun `normal error text completes task`() = runTest {
        service.createTask("normal error text", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } returns "Error: 模型正常文本"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
        coVerify(exactly = 1) { agentService.sendMessage(any()) }
    }

    /**
     * 验证代理完成后的 Telegram 投递失败不会再次调用代理，也不会保留已完成的任务。
     */
    @Test
    fun `result delivery failure does not rerun completed agent turn`() = runTest {
        service.createTask("delivery throw", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery {
            telegramService.sendMessageForToken(
                any(),
                any(),
                any(),
                any()
            )
        } throws IOException("delivery failure")

        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
        coVerify(exactly = 1) { agentService.sendMessage(any()) }
    }

    /**
     * 验证 Telegram API 非 `ok` 响应同样不会重跑已经完成的代理回合。
     */
    @Test
    fun `non ok result delivery does not rerun completed agent turn`() = runTest {
        service.createTask("delivery non ok", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns TelegramApiResponse(
            HttpStatusCode.OK,
            """{"ok":false}""",
        )

        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
        coVerify(exactly = 1) { agentService.sendMessage(any()) }
    }

    /**
     * 验证结果投递期间的取消会向上传播，且不会推进已经完成的代理回合。
     */
    @Test
    fun `cancelled result delivery keeps one shot task for retry`() = runTest {
        val taskId = service.createTask("delivery cancel", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery {
            telegramService.sendMessageForToken(
                any(),
                any(),
                any(),
                any()
            )
        } throws CancellationException("delivery cancelled")

        assertFailsWith<CancellationException> { service.scanAndExecute() }

        assertEquals(listOf(taskId), service.listTasks().map { it.id })
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

    /**
     * 验证一批任务中前一项取消后，未执行任务的执行权会释放并可在下次扫描执行。
     */
    @Test
    fun `cancellation releases unexecuted task claims for the next scan`() = runTest {
        service.createTask("first", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        service.createTask("second", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        var attempts = 0
        coEvery { agentService.sendMessage(any()) } coAnswers {
            if (++attempts == 1) {
                throw CancellationException("first task cancelled")
            }
            "done"
        }
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        assertFailsWith<CancellationException> { service.scanAndExecute() }
        coVerify(exactly = 0) { agentService.sendMessage(match { it.contains("second") }) }

        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
        coVerify(exactly = 1) { agentService.sendMessage(match { it.contains("second") }) }
    }

    /**
     * 验证租约内捕获的 token 会用于已开始回合的投递，即使回合期间切换了活动 Bot。
     */
    @Test
    fun `in flight task delivery uses the token captured by its execution lease`() = runTest {
        service.createTask("task", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "chat-a")
        val agentStarted = CompletableDeferred<Unit>()
        val allowAgentToFinish = CompletableDeferred<Unit>()
        coEvery { agentService.sendMessage(any()) } coAnswers {
            agentStarted.complete(Unit)
            allowAgentToFinish.await()
            "done"
        }
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        val execution = async { service.scanAndExecute() }
        agentStarted.await()
        settingsRepository.saveSettings(AppSettings(telegramToken = BOT_B_TOKEN))
        allowAgentToFinish.complete(Unit)
        execution.await()

        coVerify(exactly = 1) { telegramService.sendMessageForToken(BOT_A_TOKEN, "chat-a", any(), any()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(BOT_B_TOKEN, any(), any(), any()) }
    }

    /**
     * 验证无效 token 不会执行、投递或推进定时任务。
     */
    @Test
    fun `invalid token leaves scheduled tasks untouched`() = runTest {
        val taskId = service.createTask("task", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "chat-a")
        settingsRepository.saveSettings(AppSettings(telegramToken = "invalid-token"))

        service.scanAndExecute()

        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }
        assertEquals(listOf(taskId), service.listTasks().map { it.id })
    }

    /**
     * 验证主文件替换失败时内存和主文件都维持提交前快照，且不产生备份。
     */
    @Test
    fun `primary replacement failure leaves memory and primary unchanged without creating a backup`() {
        service.createTask("existing", Long.MAX_VALUE, LoopMode.ONCE, "chat-a")
        val primaryBefore = scheduleFile.readText()
        val backupFile = File(tempDirectory, "schedule.json.bak")
        assertFalse(backupFile.exists())

        service.close()
        service = newService(scheduleFile, primaryReplaceFailingOperations())

        assertFailsWith<IOException> {
            service.createTask("new", Long.MAX_VALUE, LoopMode.ONCE, "chat-a")
        }

        assertEquals(primaryBefore, scheduleFile.readText())
        assertFalse(backupFile.exists())
        assertEquals(1, service.listTasks().size)
    }

    private fun newService(
        file: File,
        fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
    ): TaskSchedulerService = TaskSchedulerService(
        CoroutineScope(EmptyCoroutineContext),
        telegramService,
        Provider { agentService },
        settingsRepository,
        file,
        fileOperations,
    )

    private fun primaryReplaceFailingOperations(targetFile: File = scheduleFile): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == targetFile.toPath()) {
                    throw IOException("injected primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }

    private fun successfulTelegramResponse(): TelegramApiResponse =
        TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
}

private const val BOT_A_TOKEN = "100:token-a"
private const val BOT_B_TOKEN = "200:token-b"
