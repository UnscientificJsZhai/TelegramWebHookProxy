package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.service.ai.ScheduledTaskService
import com.unscientificjszhai.tgp.service.ai.ScheduledTaskWorker
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 定时任务 worker 的耐久预消费、授权和副作用边界测试。 */
class ScheduledTaskWorkerTest {
    private val fixedInstant = Instant.parse("2026-01-01T00:00:00Z")
    private val clock = Clock.fixed(fixedInstant, ZoneId.of("UTC"))
    private val tempDirectory = createTempDirectory("scheduled-task-worker-test").toFile()
    private val scheduleFile = File(tempDirectory, "schedule.json")
    private lateinit var parentScope: CoroutineScope
    private lateinit var settingsChangeCoordinator: SettingsChangeCoordinator
    private lateinit var scheduledTaskService: ScheduledTaskService
    private lateinit var telegramService: TelegramService
    private lateinit var agentService: AgentService
    private lateinit var worker: ScheduledTaskWorker

    @BeforeTest
    fun setup() {
        parentScope = CoroutineScope(SupervisorJob())
        settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(
            File(tempDirectory, "settings.json"),
            ModelSwitchBarrier(),
        )
        settingsChangeCoordinator.replaceSettingsForTest(enabledSettings())
        scheduledTaskService = ScheduledTaskService(scheduleFile, zoneId = clock.zone)
        telegramService = mockk()
        agentService = mockk()
        allowReadyServiceScope(agentService)
        worker = newWorker(scheduledTaskService)
    }

    @AfterTest
    fun teardown() {
        worker.close()
        parentScope.cancel()
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `durable precommit happens before agent and a due task executes at most once`() = runBlocking {
        scheduledTaskService.createTask(
            "run once",
            fixedInstant.minusSeconds(1).toEpochMilli(),
            LoopMode.ONCE,
            CHAT_ID,
        )
        coEvery { agentService.sendMessage(any<String>()) } coAnswers {
            assertTrue(scheduledTaskService.listTasks().isEmpty(), "Agent must run only after durable precommit")
            "finished"
        }
        coEvery {
            telegramService.sendMessageForToken(BOT_TOKEN, CHAT_ID, any(), null)
        } returns successfulTelegramResponse()

        worker.scanAndExecute()
        worker.scanAndExecute()

        assertTrue(scheduledTaskService.listTasks().isEmpty())
        coVerify(exactly = 1) { agentService.sendMessage(any<String>()) }
        coVerify(exactly = 1) {
            telegramService.sendMessageForToken(BOT_TOKEN, CHAT_ID, match { it.contains("finished") }, null)
        }
    }

    @Test
    fun `revoked chat task is durably removed without agent or telegram side effects`() = runBlocking {
        scheduledTaskService.createTask(
            "old chat",
            fixedInstant.minusSeconds(1).toEpochMilli(),
            LoopMode.ONCE,
            "old-chat",
        )

        worker.scanAndExecute()

        assertTrue(scheduledTaskService.listTasks().isEmpty())
        coVerify(exactly = 0) { agentService.sendMessage(any<String>()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }
    }

    @Test
    fun `unknown precommit durability withholds side effects until a durable retry`() = runBlocking {
        val fileOperations = ToggleDirectorySyncFileOperations()
        scheduledTaskService = ScheduledTaskService(scheduleFile, fileOperations, clock.zone)
        scheduledTaskService.createTask(
            "retry durable commit",
            fixedInstant.minusSeconds(1).toEpochMilli(),
            LoopMode.ONCE,
            CHAT_ID,
        )
        worker.close()
        worker = newWorker(scheduledTaskService)
        coEvery { agentService.sendMessage(any<String>()) } returns "finished"
        coEvery {
            telegramService.sendMessageForToken(BOT_TOKEN, CHAT_ID, any(), null)
        } returns successfulTelegramResponse()

        fileOperations.failDirectorySync = true
        worker.scanAndExecute()

        assertEquals(1, scheduledTaskService.listTasks().size)
        coVerify(exactly = 0) { agentService.sendMessage(any<String>()) }
        coVerify(exactly = 0) { telegramService.sendMessageForToken(any(), any(), any(), any()) }

        fileOperations.failDirectorySync = false
        worker.scanAndExecute()

        assertTrue(scheduledTaskService.listTasks().isEmpty())
        coVerify(exactly = 1) { agentService.sendMessage(any<String>()) }
    }

    @Test
    fun `stopped worker does not admit a due task`() = runBlocking {
        scheduledTaskService.createTask(
            "keep after stop",
            fixedInstant.minusSeconds(1).toEpochMilli(),
            LoopMode.ONCE,
            CHAT_ID,
        )
        worker.requestStop()

        worker.scanAndExecute()

        assertEquals(1, scheduledTaskService.listTasks().size)
        coVerify(exactly = 0) { agentService.sendMessage(any<String>()) }
    }

    @Test
    fun `stopping worker rejects task creation while preserving existing tasks`() {
        val existingId = scheduledTaskService.createTask(
            "existing",
            fixedInstant.plusSeconds(60).toEpochMilli(),
            LoopMode.ONCE,
            CHAT_ID,
        )

        worker.requestStop()

        assertFailsWith<IllegalStateException> {
            scheduledTaskService.createTask(
                "late",
                fixedInstant.plusSeconds(120).toEpochMilli(),
                LoopMode.ONCE,
                CHAT_ID,
            )
        }
        assertEquals(listOf(existingId), scheduledTaskService.listTasks().map { it.id })
    }

    @Test
    fun `shutdown atomically closes creation and scan admission under contention`() {
        worker.close()
        val contentionFile = File(tempDirectory, "contention-schedule.json")
        scheduledTaskService = spyk(ScheduledTaskService(contentionFile, zoneId = clock.zone))
        val existingId = scheduledTaskService.createTask(
            "existing due task",
            fixedInstant.minusSeconds(1).toEpochMilli(),
            LoopMode.ONCE,
            CHAT_ID,
        )

        val creationGateClosed = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        val scanReachedAdmission = CountDownLatch(1)
        val releaseScanToAdmission = CountDownLatch(1)
        every { scheduledTaskService.stopAcceptingNewTasks() } answers {
            callOriginal()
            creationGateClosed.countDown()
            check(releaseStop.await(5, TimeUnit.SECONDS)) {
                "Timed out waiting to release the shutdown admission boundary."
            }
        }
        every { scheduledTaskService.dueTaskSnapshots(any()) } answers {
            callOriginal().also {
                scanReachedAdmission.countDown()
                check(releaseScanToAdmission.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the scan at its lifecycle admission boundary."
                }
            }
        }
        worker = newWorker(scheduledTaskService)

        val stopFailure = AtomicReference<Throwable?>()
        val stopper = thread(name = "scheduled-task-stopper") {
            runCatching { worker.requestStop() }.exceptionOrNull()?.let(stopFailure::set)
        }
        assertTrue(creationGateClosed.await(5, TimeUnit.SECONDS))

        val scanFinished = CountDownLatch(1)
        val scanFailure = AtomicReference<Throwable?>()
        val scanner = thread(name = "scheduled-task-scanner") {
            runCatching { runBlocking { worker.scanAndExecute() } }
                .exceptionOrNull()
                ?.let(scanFailure::set)
            scanFinished.countDown()
        }
        assertTrue(scanReachedAdmission.await(5, TimeUnit.SECONDS))

        val createFailure = AtomicReference<Throwable?>()
        val creator = thread(name = "scheduled-task-late-creator") {
            runCatching {
                scheduledTaskService.createTask(
                    "late task",
                    fixedInstant.plusSeconds(60).toEpochMilli(),
                    LoopMode.ONCE,
                    CHAT_ID,
                )
            }.exceptionOrNull()?.let(createFailure::set)
        }
        creator.join(5_000)

        try {
            assertFalse(creator.isAlive)
            assertTrue(createFailure.get() is IllegalStateException)
            releaseScanToAdmission.countDown()
            assertFalse(
                scanFinished.await(200, TimeUnit.MILLISECONDS),
                "A scan must not pass lifecycle admission while shutdown is publishing the closed state.",
            )
        } finally {
            releaseScanToAdmission.countDown()
            releaseStop.countDown()
        }

        stopper.join(5_000)
        scanner.join(5_000)
        assertFalse(stopper.isAlive)
        assertFalse(scanner.isAlive)
        stopFailure.get()?.let { throw it }
        scanFailure.get()?.let { throw it }
        assertEquals(listOf(existingId), scheduledTaskService.listTasks().map { it.id })
        coVerify(exactly = 0) { agentService.sendMessage(any<String>()) }
    }

    private fun newWorker(taskService: ScheduledTaskService): ScheduledTaskWorker = ScheduledTaskWorker(
        parentScope = parentScope,
        scheduledTaskService = taskService,
        telegramService = telegramService,
        agentService = agentService,
        settingsChangeCoordinator = settingsChangeCoordinator,
        clock = clock,
    )

    private fun allowReadyServiceScope(agent: AgentService) {
        coEvery { agent.withReadyService<Any?>(any()) } coAnswers {
            firstArg<suspend (AgentService) -> Any?>().invoke(agent)
        }
    }

    private fun successfulTelegramResponse(): TelegramApiResponse =
        TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

    private class ToggleDirectorySyncFileOperations(
        var failDirectorySync: Boolean = false,
    ) : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        override fun forceDirectory(path: Path) {
            if (failDirectorySync) {
                throw IOException("injected directory sync failure")
            }
            DefaultAtomicJsonFileOperations.forceDirectory(path)
        }
    }

    private companion object {
        const val BOT_TOKEN = "100:token-a"
        const val CHAT_ID = "12345"

        fun enabledSettings(): AppSettings = AppSettings(
            telegramToken = BOT_TOKEN,
            ai = AISettings(agentEnabled = true, agentChatId = CHAT_ID),
        )
    }
}
