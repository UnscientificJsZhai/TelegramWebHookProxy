package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.models.ScheduledTask
import com.unscientificjszhai.tgp.service.ai.ScheduledTaskService
import com.unscientificjszhai.tgp.service.ai.calculateNextExecutionTime
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * 定时任务 CRUD、持久化与预消费事务的测试设计。
 */
class ScheduledTaskServiceTest {

    private lateinit var service: ScheduledTaskService
    private val tempDirectory = createTempDirectory("task-scheduler-test").toFile()
    private val scheduleFile = File(tempDirectory, "schedule.json")

    @BeforeTest
    fun setup() {
        service = ScheduledTaskService(scheduleFile)
    }

    @AfterTest
    fun teardown() {
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
        service = ScheduledTaskService(scheduleFile, failingOperations)

        assertFailsWith<IOException> {
            service.createTask("will fail", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")
        }
        assertTrue(service.listTasks().isEmpty())

        val cancelScheduleFile = File(tempDirectory, "cancel-schedule.json")
        service = ScheduledTaskService(cancelScheduleFile)
        val taskId = service.createTask("persisted", System.currentTimeMillis() + 10_000, LoopMode.ONCE, "12345")

        service = ScheduledTaskService(
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
        service = newService(scheduleFile)

        assertNull(service.listTasks().single().calendarAnchorTimeMillis)

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
        scheduleFile.writeText(deepJson)

        assertFailsWith<IllegalStateException> {
            ScheduledTaskService(scheduleFile)
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

        service = newService(scheduleFile, primaryReplaceFailingOperations())

        assertFailsWith<IOException> {
            service.createTask("new", Long.MAX_VALUE, LoopMode.ONCE, "chat-a")
        }

        assertEquals(primaryBefore, scheduleFile.readText())
        assertFalse(backupFile.exists())
        assertEquals(1, service.listTasks().size)
    }

    @Test
    fun `precommit samples fresh time only after acquiring the task state lock`() {
        val blockingOperations = BlockingDirectorySyncFileOperations()
        service = ScheduledTaskService(scheduleFile, blockingOperations)
        val expectedId = service.createTask("due", 1L, LoopMode.ONCE, "chat-a")
        val expected = service.listTasks().single { it.id == expectedId }
        blockingOperations.blockNextDirectorySync = true

        val writerFailure = AtomicReference<Throwable?>()
        val writer = thread(name = "scheduled-task-lock-holder") {
            runCatching {
                service.createTask("writer", Long.MAX_VALUE, LoopMode.ONCE, "chat-a")
            }.exceptionOrNull()?.let(writerFailure::set)
        }
        assertTrue(blockingOperations.directorySyncEntered.await(5, TimeUnit.SECONDS))

        val precommitStarted = CountDownLatch(1)
        val timeSampled = CountDownLatch(1)
        val precommitFailure = AtomicReference<Throwable?>()
        val precommitted = AtomicReference<ScheduledTask?>()
        val precommit = thread(name = "scheduled-task-precommit") {
            precommitStarted.countDown()
            runCatching {
                service.precommitExecution(expected) {
                    timeSampled.countDown()
                    expected.executionTime
                }
            }.onSuccess(precommitted::set)
                .exceptionOrNull()
                ?.let(precommitFailure::set)
        }
        assertTrue(precommitStarted.await(5, TimeUnit.SECONDS))

        try {
            assertFalse(
                timeSampled.await(200, TimeUnit.MILLISECONDS),
                "The fresh clock must not be sampled while another persistence transaction owns the state lock.",
            )
        } finally {
            blockingOperations.releaseDirectorySync.countDown()
        }

        writer.join(5_000)
        precommit.join(5_000)
        assertFalse(writer.isAlive)
        assertFalse(precommit.isAlive)
        writerFailure.get()?.let { throw it }
        precommitFailure.get()?.let { throw it }
        assertEquals(expected, precommitted.get())
        assertTrue(timeSampled.await(0, TimeUnit.MILLISECONDS))
    }

    private fun newService(
        file: File,
        fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): ScheduledTaskService = ScheduledTaskService(file, fileOperations, zoneId)

    private fun primaryReplaceFailingOperations(targetFile: File = scheduleFile): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == targetFile.toPath()) {
                    throw IOException("injected primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }

    private class BlockingDirectorySyncFileOperations :
        AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        @Volatile
        var blockNextDirectorySync = false
        val directorySyncEntered = CountDownLatch(1)
        val releaseDirectorySync = CountDownLatch(1)

        override fun forceDirectory(path: Path) {
            if (blockNextDirectorySync) {
                blockNextDirectorySync = false
                directorySyncEntered.countDown()
                check(releaseDirectorySync.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the injected directory sync."
                }
            }
            DefaultAtomicJsonFileOperations.forceDirectory(path)
        }
    }

}
