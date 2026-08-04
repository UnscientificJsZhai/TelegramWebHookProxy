package com.unscientificjszhai.tgp.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.calculateNextExecutionTime
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
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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
        allowReadyServiceScope(agentService)

        val agentProvider = Provider { agentService }
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), ModelSwitchBarrier())
        settingsRepository.saveSettings(enabledSettings())

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
     * 验证新建任务拒绝空白会话标识，且非空白标识不会在持久化前被修改。
     */
    @Test
    fun `create task rejects blank chat ids and preserves nonblank chat ids exactly`() {
        assertFailsWith<IllegalArgumentException> {
            service.createTask("blank", Long.MAX_VALUE, LoopMode.ONCE, "")
        }
        assertFailsWith<IllegalArgumentException> {
            service.createTask("whitespace", Long.MAX_VALUE, LoopMode.ONCE, " \t ")
        }

        val taskId = service.createTask("preserve", Long.MAX_VALUE, LoopMode.ONCE, " chat with spaces ")

        assertEquals(
            " chat with spaces ",
            service.listTasks().single { it.id == taskId }.agentChatId,
        )
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
     * 验证实际任务执行日志不记录 instruction，也不会通过 Telegram 网络异常保留 token 或 Throwable。
     */
    @Test
    fun `task execution logs safe identifiers and failure category without instruction or token`() = runTest {
        val instructionCanary = "TASK_INSTRUCTION_CANARY"
        val tokenCanary = "TASK_TELEGRAM_TOKEN_CANARY"
        val taskId = service.createTask(instructionCanary, System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } returns "result"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } throws IOException(
            "https://api.telegram.org/bot$tokenCanary/sendMessage",
        )

        val logger = LoggerFactory.getLogger(TaskSchedulerService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            service.scanAndExecute()
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }

        val messages = appender.list.map { it.formattedMessage }
        assertTrue(messages.any { it.contains("Executing precommitted task $taskId") })
        assertTrue(messages.any { it.contains("Failed to send task result for $taskId; category=network") })
        assertTrue(messages.none { it.contains(instructionCanary) })
        assertTrue(messages.none { it.contains(tokenCanary) })
        assertTrue(appender.list.none { it.throwableProxy != null })
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
     * 验证副作用前的预提交失败不会调用代理，恢复存储后才会执行一次。
     */
    @Test
    fun `precommit failure retains task without calling agent until storage recovers`() = runTest {
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

        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        assertEquals(listOf(taskId), service.listTasks().map { it.id })

        service.close()
        service = newService(scheduleFile)
        service.scanAndExecute()

        coVerify(exactly = 1) { agentService.sendMessage(any()) }
        assertTrue(service.listTasks().isEmpty())
    }

    /**
     * 验证一次性任务在预消费后即使 Agent 回合失败并重启服务，也不会被重新调用。
     */
    @Test
    fun `restarting after a precommitted failed turn does not replay the task`() = runTest {
        service.createTask("no replay after restart", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } throws AgentTurnFailedException("failed after commit")

        service.scanAndExecute()
        service.close()
        service = newService(scheduleFile)

        service.scanAndExecute()

        coVerify(exactly = 1) { agentService.sendMessage(any()) }
        assertTrue(service.listTasks().isEmpty())
    }

    /**
     * 验证历史周期跳过、DST 解析和不可表示的时间都使用有界且确定的下一次计算。
     */
    @Test
    fun `next execution calculation skips history and resolves DST deterministically`() {
        val newYork = ZoneId.of("America/New_York")
        val hourlyNow = Instant.parse("2025-01-01T00:00:00Z")
        val millionHoursAgo = hourlyNow.minusSeconds(1_000_000L * 60L * 60L)
        val hourlyNext = requireNotNull(
            calculateNextExecutionTime(millionHoursAgo.toEpochMilli(), LoopMode.HOURLY, hourlyNow, newYork),
        )
        assertEquals(hourlyNow.toEpochMilli() + 60L * 60L * 1000L, hourlyNext)

        val gapLast = LocalDateTime.of(2024, 3, 9, 2, 30).atZone(newYork).toInstant()
        val gapNow = Instant.parse("2024-03-10T06:30:00Z")
        val gapNext = Instant.ofEpochMilli(
            requireNotNull(calculateNextExecutionTime(gapLast.toEpochMilli(), LoopMode.DAILY, gapNow, newYork)),
        )
        assertEquals(Instant.parse("2024-03-10T07:00:00Z"), gapNext)

        val overlapLast = LocalDateTime.of(2024, 11, 2, 1, 30).atZone(newYork).toInstant()
        val overlapNow = Instant.parse("2024-11-03T04:00:00Z")
        val overlapNext = Instant.ofEpochMilli(
            requireNotNull(calculateNextExecutionTime(overlapLast.toEpochMilli(), LoopMode.DAILY, overlapNow, newYork)),
        )
        assertEquals(Instant.parse("2024-11-03T05:30:00Z"), overlapNext)

        val weeklyLast = LocalDateTime.of(2024, 1, 1, 9, 0).atZone(newYork).toInstant()
        val weeklyNow = Instant.parse("2024-01-14T15:00:00Z")
        val weeklyNext = Instant.ofEpochMilli(
            requireNotNull(calculateNextExecutionTime(weeklyLast.toEpochMilli(), LoopMode.WEEKLY, weeklyNow, newYork)),
        )
        assertEquals(Instant.parse("2024-01-15T14:00:00Z"), weeklyNext)

        assertEquals(null, calculateNextExecutionTime(Long.MIN_VALUE, LoopMode.HOURLY, hourlyNow, newYork))
    }

    /**
     * 验证每日任务跨 spring gap 后会在下一日恢复创建时的本地锚点，且重启不会丢失该锚点。
     */
    @Test
    fun `daily spring gap keeps its calendar anchor across restart`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val beforeGap = Clock.fixed(Instant.parse("2024-03-10T06:00:00Z"), newYork)
        val afterGap = Clock.fixed(Instant.parse("2024-03-10T08:00:00Z"), newYork)
        service.close()
        service = newService(scheduleFile, clock = beforeGap, zoneId = newYork)
        service.createTask(
            "daily gap",
            LocalDateTime.of(2024, 3, 9, 2, 30).atZone(newYork).toInstant().toEpochMilli(),
            LoopMode.DAILY,
            "12345",
        )
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        service.scanAndExecute()

        val gapTask = service.listTasks().single()
        assertEquals(Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), gapTask.executionTime)
        assertEquals(2 * 60 * 60 * 1000 + 30 * 60 * 1000, gapTask.calendarAnchorTimeMillis)

        service.close()
        service = newService(scheduleFile, clock = afterGap, zoneId = newYork)
        service.scanAndExecute()

        val nextDayTask = service.listTasks().single()
        assertEquals(Instant.parse("2024-03-11T06:30:00Z").toEpochMilli(), nextDayTask.executionTime)
        assertEquals(gapTask.calendarAnchorTimeMillis, nextDayTask.calendarAnchorTimeMillis)
    }

    /**
     * 验证缺少日历锚点的旧版 DAILY JSON 会在首次预消费时安全迁移并跨重启恢复原本地时刻。
     */
    @Test
    fun `legacy daily JSON persists inferred calendar anchor across spring gap`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val beforeGap = Clock.fixed(Instant.parse("2024-03-10T06:00:00Z"), newYork)
        val afterGap = Clock.fixed(Instant.parse("2024-03-10T08:00:00Z"), newYork)
        val legacyExecutionTime = LocalDateTime.of(2024, 3, 9, 2, 30).atZone(newYork).toInstant().toEpochMilli()
        service.close()
        scheduleFile.writeText(
            """[{"id":"legacy-daily","instruction":"legacy daily gap","executionTime":$legacyExecutionTime,"loopMode":"DAILY","agentChatId":"12345"}]""",
        )
        service = newService(scheduleFile, clock = beforeGap, zoneId = newYork)
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        service.scanAndExecute()

        val persistedGapTask = ConfigJson.decodeFromString<List<ScheduledTask>>(scheduleFile.readText()).single()
        assertEquals(Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), persistedGapTask.executionTime)
        assertEquals(2 * 60 * 60 * 1000 + 30 * 60 * 1000, persistedGapTask.calendarAnchorTimeMillis)

        service.close()
        service = newService(scheduleFile, clock = afterGap, zoneId = newYork)
        service.scanAndExecute()

        val nextDayTask = service.listTasks().single()
        assertEquals(Instant.parse("2024-03-11T06:30:00Z").toEpochMilli(), nextDayTask.executionTime)
        assertEquals(persistedGapTask.calendarAnchorTimeMillis, nextDayTask.calendarAnchorTimeMillis)
    }

    /**
     * 验证每周任务跨 spring gap 后会在下一周恢复创建时的本地锚点。
     */
    @Test
    fun `weekly spring gap keeps its calendar anchor for the next week`() = runTest {
        val newYork = ZoneId.of("America/New_York")
        val beforeGap = Clock.fixed(Instant.parse("2024-03-10T06:00:00Z"), newYork)
        val afterGap = Clock.fixed(Instant.parse("2024-03-10T08:00:00Z"), newYork)
        service.close()
        service = newService(scheduleFile, clock = beforeGap, zoneId = newYork)
        service.createTask(
            "weekly gap",
            LocalDateTime.of(2024, 3, 3, 2, 30).atZone(newYork).toInstant().toEpochMilli(),
            LoopMode.WEEKLY,
            "12345",
        )
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        service.scanAndExecute()
        assertEquals(Instant.parse("2024-03-10T07:00:00Z").toEpochMilli(), service.listTasks().single().executionTime)

        service.close()
        service = newService(scheduleFile, clock = afterGap, zoneId = newYork)
        service.scanAndExecute()

        val nextWeekTask = service.listTasks().single()
        assertEquals(Instant.parse("2024-03-17T06:30:00Z").toEpochMilli(), nextWeekTask.executionTime)
        assertEquals(2 * 60 * 60 * 1000 + 30 * 60 * 1000, nextWeekTask.calendarAnchorTimeMillis)
    }

    /**
     * 验证损坏主文件会禁用创建和取消，且不会访问遗留 `.bak` 文件。
     */
    @Test
    fun `damaged schedule primary disables create and cancel without touching legacy bak`() {
        val sidecarFile = File(tempDirectory, "schedule.json.bak")
        val damagedPrimary = "[ invalid"
        val sidecarContent = ConfigJson.encodeToString(
            listOf(ScheduledTask("ignored", "instruction", 1L, LoopMode.ONCE, "12345")),
        )
        scheduleFile.writeText(damagedPrimary)
        sidecarFile.writeText(sidecarContent)
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            settingsRepository,
            scheduleFile,
            rejectBakOperations(),
        )

        assertFailsWith<IllegalStateException> {
            service.createTask("new", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")
        }
        assertFailsWith<IllegalStateException> { service.cancelTask("ignored") }
        assertEquals(damagedPrimary, scheduleFile.readText())
        assertEquals(sidecarContent, sidecarFile.readText())
    }

    /**
     * 验证主调度文件缺失时不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `missing schedule primary ignores legacy bak`() {
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
            rejectBakOperations(),
        )

        assertTrue(service.listTasks().isEmpty())
        assertFalse(scheduleFile.exists())
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
     * 验证损坏主调度文件时不会读取遗留 `.bak` 任务或执行它。
     */
    @Test
    fun `scan ignores legacy bak task when primary is corrupt`() = runTest {
        val sidecarFile = File(tempDirectory, "schedule.json.bak")
        val ignoredSidecarTask = ScheduledTask(
            "ignored",
            "instruction",
            System.currentTimeMillis() - 1_000,
            LoopMode.ONCE,
            "12345",
        )
        scheduleFile.writeText("[ invalid")
        sidecarFile.writeText(ConfigJson.encodeToString(listOf(ignoredSidecarTask)))
        service.close()
        service = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegramService,
            Provider { agentService },
            settingsRepository,
            scheduleFile,
            rejectBakOperations(),
        )
        coEvery { agentService.sendMessage(any()) } returns "done"
        coEvery { telegramService.sendMessageForToken(any(), any(), any(), any()) } returns successfulTelegramResponse()

        service.scanAndExecute()

        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        assertTrue(service.listTasks().isEmpty())
    }

    /**
     * 验证预消费后的 Agent 失败和普通异常不会重放一次性任务。
     */
    @Test
    fun `failed agent turns and ordinary failures do not replay preconsumed tasks`() = runTest {
        val incompleteTask =
            service.createTask("incomplete", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } throws AgentTurnFailedException("未完成")

        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }

        service.scanAndExecute()
        coVerify(exactly = 1) { agentService.sendMessage(match { it.contains(incompleteTask) || it.contains("incomplete") }) }

        service.createTask("ordinary", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } throws IllegalStateException("ordinary failure")
        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
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
     * 验证结果投递期间的取消会向上传播，但已经预消费的任务不会恢复。
     */
    @Test
    fun `cancelled result delivery does not restore preconsumed one shot task`() = runTest {
        service.createTask("delivery cancel", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
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

        assertTrue(service.listTasks().isEmpty())
    }

    /**
     * 验证取消执行时会传播取消，但一次性任务已经预消费且不会重试。
     */
    @Test
    fun `cancelled task execution does not restore preconsumed one shot task`() = runTest {
        service.createTask("cancel", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.sendMessage(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> { service.scanAndExecute() }

        assertTrue(service.listTasks().isEmpty())
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
        settingsRepository.saveSettings(enabledSettings(BOT_A_TOKEN, "chat-a"))
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
        settingsRepository.saveSettings(enabledSettings(BOT_B_TOKEN, "chat-b"))
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
        settingsRepository.saveSettings(enabledSettings(BOT_A_TOKEN, "chat-a"))
        val taskId = service.createTask("task", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "chat-a")
        settingsRepository.saveSettings(enabledSettings("invalid-token", "chat-a"))

        service.scanAndExecute()

        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }
        assertEquals(listOf(taskId), service.listTasks().map { it.id })
    }

    /**
     * 验证当前 AI 授权撤销时，到期任务会在任何模型或 Telegram 副作用前删除；循环任务不会被推进。
     */
    @Test
    fun `revoked task authorization deletes once hourly and legacy tasks without side effects`() = runTest {
        val dueTime = System.currentTimeMillis() - 1_000

        service.createTask("mismatched owner", dueTime, LoopMode.ONCE, "task-owner")
        settingsRepository.saveSettings(enabledSettings(agentChatId = "other-owner"))
        service.scanAndExecute()
        assertTrue(service.listTasks().isEmpty())

        service.createTask("disabled hourly", dueTime, LoopMode.HOURLY, "12345")
        settingsRepository.saveSettings(
            AppSettings(
                telegramToken = BOT_A_TOKEN,
                ai = AISettings(agentEnabled = false, agentChatId = "12345"),
            ),
        )
        service.scanAndExecute()
        assertTrue(service.listTasks().isEmpty())

        service.createTask("missing AI", dueTime, LoopMode.ONCE, "12345")
        settingsRepository.saveSettings(AppSettings(telegramToken = BOT_A_TOKEN))
        service.scanAndExecute()
        assertTrue(service.listTasks().isEmpty())

        service.createTask("blank current chat", dueTime, LoopMode.HOURLY, "12345")
        settingsRepository.saveSettings(enabledSettings(agentChatId = " \t "))
        service.scanAndExecute()
        assertTrue(service.listTasks().isEmpty())

        service.close()
        scheduleFile.writeText(
            ConfigJson.encodeToString(
                listOf(ScheduledTask("legacy-blank-chat", "legacy", dueTime, LoopMode.HOURLY, "")),
            ),
        )
        service = newService(scheduleFile)
        settingsRepository.saveSettings(enabledSettings())
        service.scanAndExecute()

        assertTrue(service.listTasks().isEmpty())
        assertTrue(ConfigJson.decodeFromString<List<ScheduledTask>>(scheduleFile.readText()).isEmpty())
        coVerify(exactly = 0) { agentService.withReadyService<Any?>(any()) }
        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }
    }

    /**
     * 验证首次授权检查通过后，模型就绪屏障等待期间被撤销的任务会在第二次检查中删除。
     */
    @Test
    fun `authorization revoked after ready barrier is deleted before precommit`() = runTest {
        val taskId =
            service.createTask("revoked while waiting", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        coEvery { agentService.withReadyService<Any?>(any()) } coAnswers {
            settingsRepository.saveSettings(
                AppSettings(
                    telegramToken = BOT_A_TOKEN,
                    ai = AISettings(agentEnabled = false, agentChatId = "12345"),
                ),
            )
            firstArg<suspend (AgentService) -> Any?>().invoke(agentService)
        }

        service.scanAndExecute()

        coVerify(exactly = 1) { agentService.withReadyService<Any?>(any()) }
        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }
        assertTrue(service.listTasks().isEmpty())
        assertTrue(ConfigJson.decodeFromString<List<ScheduledTask>>(scheduleFile.readText()).none { it.id == taskId })
    }

    /**
     * 验证撤销任务的持久化删除失败时，内存和文件都保留扫描前快照且不产生副作用。
     */
    @Test
    fun `revoked task deletion persistence failure retains the task without side effects`() = runTest {
        val taskId = service.createTask("cannot remove", System.currentTimeMillis() - 1_000, LoopMode.ONCE, "12345")
        val primaryBefore = scheduleFile.readText()
        service.close()
        service = newService(scheduleFile, primaryReplaceFailingOperations())
        settingsRepository.saveSettings(enabledSettings(agentChatId = "different-owner"))

        service.scanAndExecute()

        assertEquals(primaryBefore, scheduleFile.readText())
        assertEquals(listOf(taskId), service.listTasks().map { it.id })
        coVerify(exactly = 0) { agentService.sendMessage(any()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }
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
        clock: Clock = Clock.systemDefaultZone(),
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): TaskSchedulerService = TaskSchedulerService(
        CoroutineScope(EmptyCoroutineContext),
        telegramService,
        Provider { agentService },
        settingsRepository,
        file,
        fileOperations,
        clock = clock,
        zoneId = zoneId,
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

    /** 让普通 Mock Agent 模拟 [AgentService] 默认的同步就绪作用域。 */
    private fun allowReadyServiceScope(agent: AgentService) {
        coEvery { agent.withReadyService<Any?>(any()) } coAnswers {
            firstArg<suspend (AgentService) -> Any?>().invoke(agent)
        }
    }

    private fun rejectBakOperations(): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be read" }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }

            override fun writeAndForce(path: Path, bytes: ByteArray) {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be written" }
                DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
            }
        }
}

private const val BOT_A_TOKEN = "100:token-a"
private const val BOT_B_TOKEN = "200:token-b"

private fun enabledSettings(
    token: String = BOT_A_TOKEN,
    agentChatId: String = "12345",
): AppSettings = AppSettings(
    telegramToken = token,
    ai = AISettings(agentEnabled = true, agentChatId = agentChatId),
)
