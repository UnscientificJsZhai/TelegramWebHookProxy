package com.unscientificjszhai.tgp.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AgentTurnFailedException
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.ai.calculateNextExecutionTime
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.ktor.http.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
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
        settingsRepository.replaceSettingsForTest(enabledSettings())

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

    /** 调度入口拒绝非法 UTF-8 与未知 v1 版本，启动失败前不改写可供恢复的原始字节。 */
    @Test
    fun `scheduler load preserves malformed UTF8 and future version bytes`() {
        service.close()
        val cases = listOf(
            "malformed-utf8" to ("[{\"id\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\"}]".encodeToByteArray()),
            "future-version" to """{"schemaVersion":2,"data":[]}""".encodeToByteArray(),
        )

        cases.forEach { (name, original) ->
            scheduleFile.writeBytes(original)

            assertFailsWith<IllegalStateException> { newService(scheduleFile) }
            assertContentEquals(original, scheduleFile.readBytes(), "case=$name")
        }
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




    @Test
    fun `schedule schema repairs optional fields and rejects damaged required fields`() {
        scheduleFile.writeText(
            """[{"id":"repair","instruction":"ok","executionTime":1,"loopMode":"ONCE","agentChatId":"12345","calendarAnchorTimeMillis":"invalid"}]""",
        )
        service.close()
        service = newService(scheduleFile)

        assertNull(service.listTasks().single().calendarAnchorTimeMillis)

        service.close()
        scheduleFile.writeText(
            """[{"id":"fatal","executionTime":1,"loopMode":"ONCE","agentChatId":"12345"}]""",
        )
        assertFailsWith<IllegalStateException> { newService(scheduleFile) }
    }









    /** 深层调度文件必须在 DTO 解码前被标记为损坏，且不会被后续写入覆盖。 */
    @Test
    fun `deep schedule JSON is rejected before task DTO decode`() {
        val deepJson = buildString {
            repeat(65) { append("{\"next\":") }
            append("\"leaf\"")
            repeat(65) { append('}') }
        }
        service.close()
        scheduleFile.writeText(deepJson)

        assertFailsWith<IllegalStateException> {
            TaskSchedulerService(
                CoroutineScope(EmptyCoroutineContext),
                telegramService,
                Provider { agentService },
                settingsRepository,
                scheduleFile,
            )
        }
        assertEquals(deepJson, scheduleFile.readText())
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

    /** 可在测试中切换目录同步故障的文件操作。 */
    private class ToggleDirectorySyncFileOperations(
        var failDirectorySync: Boolean,
    ) : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        override fun forceDirectory(path: Path) {
            if (failDirectorySync) {
                throw IOException("injected directory sync failure")
            }
            DefaultAtomicJsonFileOperations.forceDirectory(path)
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
