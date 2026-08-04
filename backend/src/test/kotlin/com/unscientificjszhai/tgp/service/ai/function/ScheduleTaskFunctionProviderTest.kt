package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SettingsUpdate
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentToolExecutionContext
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 定时任务模型函数的执行时间和持久化失败结果测试设计。
 */
class ScheduleTaskFunctionProviderTest {
    /**
     * 验证创建或取消持久化失败时，工具结果只返回错误而不声称成功。
     */
    @Test
    fun `create and cancel persistence failures return errors without success`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()
        val settingsRepository = settingsRepository()
        every { scheduler.createTask(any(), any(), any(), any()) } throws IOException("disk unavailable")
        every { scheduler.cancelTask(any()) } throws IOException("disk unavailable")
        val provider = ScheduleTaskFunctionProvider(schedulerProvider(scheduler), settingsRepository)

        val createResult = provider.execute(
            "create_scheduled_task",
            mapOf("instruction" to "test", "executionTime" to "+1h"),
        )
        val cancelResult = provider.execute("cancel_scheduled_task", mapOf("taskId" to "task-1"))

        assertNotNull(createResult["error"])
        assertNull(createResult["status"])
        assertNull(createResult["taskId"])
        assertNotNull(cancelResult["error"])
        assertNull(cancelResult["status"])
    }

    /**
     * 验证严格绝对时间按注入时区转换，并以相同的时区展示。
     */
    @Test
    fun `strict absolute execution time uses injected zone consistently`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()
        val executionTime = slot<Long>()
        every { scheduler.createTask(any(), capture(executionTime), any(), any()) } returns "task-1"
        val zone = ZoneId.of("Asia/Shanghai")
        val provider = provider(scheduler, Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), zone))

        val result = provider.execute("create_scheduled_task", createArgs("2024-02-29 12:34:56"))

        val expected = ZonedDateTime.of(2024, 2, 29, 12, 34, 56, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, executionTime.captured)
        assertEquals("success", result["status"]?.toString()?.trim('"'))
        assertTrue(result["message"].toString().contains("2024-02-29 12:34:56"))
    }

    /**
     * 验证所有相对单位及允许的最大值不会发生整数收窄回绕。
     */
    @Test
    fun `relative execution times use long arithmetic without narrowing`() = runTest {
        val zone = ZoneId.of("Asia/Shanghai")
        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), zone)
        val baseTime = ZonedDateTime.now(clock)
        val cases = listOf(
            "+1s" to baseTime.plusSeconds(1).toInstant().toEpochMilli(),
            "+1m" to baseTime.plusMinutes(1).toInstant().toEpochMilli(),
            "+1h" to baseTime.plusHours(1).toInstant().toEpochMilli(),
            "+1d" to baseTime.plusDays(1).toInstant().toEpochMilli(),
            "+2147483647s" to baseTime.plusSeconds(Int.MAX_VALUE.toLong()).toInstant().toEpochMilli(),
        )

        cases.forEach { (input, expected) ->
            val scheduler = mockk<TaskSchedulerService>()
            val executionTime = slot<Long>()
            every { scheduler.createTask(any(), capture(executionTime), any(), any()) } returns "task-$input"
            val provider = provider(scheduler, clock)

            val result = provider.execute("create_scheduled_task", createArgs(input))

            assertNotNull(result["status"])
            assertEquals(expected, executionTime.captured, input)
        }
    }

    /**
     * 验证非法绝对和相对时间不会创建任务。
     */
    @Test
    fun `invalid execution times are rejected before scheduling`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()
        val zone = ZoneId.of("Asia/Shanghai")
        val provider = provider(scheduler, Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), zone))
        val invalidTimes = listOf(
            "2024-02-30 12:00:00",
            "2024-2-29 12:00:00",
            "2024-02-29 12:00:00 trailing",
            "+0h",
            "+-1h",
            "+1H",
            "+2147483648s",
        )

        invalidTimes.forEach { input ->
            val result = provider.execute("create_scheduled_task", createArgs(input))
            assertNotNull(result["error"], input)
        }

        verify(exactly = 0) { scheduler.createTask(any(), any(), any(), any()) }
    }

    /**
     * 验证夏令时不存在的本地时间被拒绝，重叠时间采用较早偏移量。
     */
    @Test
    fun `daylight saving transitions have explicit scheduling behavior`() = runTest {
        val zone = ZoneId.of("America/New_York")
        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), zone)
        val gapScheduler = mockk<TaskSchedulerService>()
        val gapProvider = provider(gapScheduler, clock)

        val gapResult = gapProvider.execute("create_scheduled_task", createArgs("2024-03-10 02:30:00"))

        assertNotNull(gapResult["error"])
        verify(exactly = 0) { gapScheduler.createTask(any(), any(), any(), any()) }

        val overlapScheduler = mockk<TaskSchedulerService>()
        val overlapExecutionTime = slot<Long>()
        every { overlapScheduler.createTask(any(), capture(overlapExecutionTime), any(), any()) } returns "task-overlap"
        val overlapProvider = provider(overlapScheduler, clock)

        val overlapResult = overlapProvider.execute("create_scheduled_task", createArgs("2024-11-03 01:30:00"))

        val localOverlap = LocalDateTime.of(2024, 11, 3, 1, 30)
        val expected = ZonedDateTime.ofLocal(localOverlap, zone, zone.rules.getValidOffsets(localOverlap).first())
            .toInstant()
            .toEpochMilli()
        assertNotNull(overlapResult["status"])
        assertEquals(expected, overlapExecutionTime.captured)
    }

    /**
     * 验证显式时区必须与时钟时区一致，避免解析与展示使用不同时间线。
     */
    @Test
    fun `clock and explicit zone must match`() {
        val scheduler = mockk<TaskSchedulerService>()
        val clock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC"))
        val settingsRepository = settingsRepository()

        assertFailsWith<IllegalArgumentException> {
            ScheduleTaskFunctionProvider(
                schedulerProvider(scheduler),
                settingsRepository,
                clock,
                ZoneId.of("Asia/Shanghai")
            )
        }
    }

    /**
     * 验证 Agent 回合中创建任务使用固定会话，直接调用则使用当前设置。
     */
    @Test
    fun `Agent tool context keeps scheduled task chat fixed across settings changes`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()
        val createdChatIds = mutableListOf<String>()
        every { scheduler.createTask(any(), any(), any(), any()) } answers {
            createdChatIds += invocation.args[3] as String
            "task-${createdChatIds.size}"
        }
        val settingsRepository = settingsRepository("chat-b")
        val executionContext = AgentToolExecutionContext.from(
            SettingsUpdate(
                AppSettings(ai = AISettings(agentChatId = "chat-a")),
                version = 1,
                switchGeneration = null,
            ),
        )
        val provider = ScheduleTaskFunctionProvider(schedulerProvider(scheduler), settingsRepository)

        withContext(executionContext) {
            provider.execute("create_scheduled_task", createArgs("+1h"))
        }
        provider.execute("create_scheduled_task", createArgs("+1h"))

        assertEquals(listOf("chat-a", "chat-b"), createdChatIds)
    }

    /**
     * 验证 Agent 回合上下文缺失定时任务会话时，不回退读取切换后的当前设置。
     */
    @Test
    fun `Agent tool context with missing task chat does not fall back to current settings`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()
        val settingsRepository = settingsRepository("chat-b")
        val missingChatContext = AgentToolExecutionContext.from(
            SettingsUpdate(AppSettings(ai = null), 1, null),
        )
        val provider = ScheduleTaskFunctionProvider(schedulerProvider(scheduler), settingsRepository)

        val create = withContext(missingChatContext) {
            provider.execute("create_scheduled_task", createArgs("+1h"))
        }

        assertNotNull(create["error"])
        verify(exactly = 0) { scheduler.createTask(any(), any(), any(), any()) }
    }

    /**
     * 验证创建任务时，缺失、空白或仅含空白字符的代理会话标识都会在调用调度器前被拒绝。
     */
    @Test
    fun `blank agent chat id does not create a scheduled task`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()

        listOf<String?>(null, "", " \t ").forEach { agentChatId ->
            val settingsRepository = settingsRepository(agentChatId)
            val provider = ScheduleTaskFunctionProvider(schedulerProvider(scheduler), settingsRepository)

            val result = provider.execute("create_scheduled_task", createArgs("+1h"))

            assertNotNull(result["error"], "agentChatId=$agentChatId")
            assertNull(result["status"])
        }

        verify(exactly = 0) { scheduler.createTask(any(), any(), any(), any()) }
    }

    private fun provider(scheduler: TaskSchedulerService, clock: Clock): ScheduleTaskFunctionProvider =
        ScheduleTaskFunctionProvider(schedulerProvider(scheduler), settingsRepository(), clock)

    private fun schedulerProvider(scheduler: TaskSchedulerService): Provider<TaskSchedulerService> =
        Provider { scheduler }

    private fun settingsRepository(agentChatId: String? = "12345"): SettingsRepository =
        mockk<SettingsRepository>().also { repository ->
            every {
                repository.settingsFlow
            } returns MutableStateFlow(AppSettings(ai = agentChatId?.let { AISettings(agentChatId = it) }))
        }

    private fun createArgs(executionTime: String): Map<String, Any?> = mapOf(
        "instruction" to "test",
        "executionTime" to executionTime,
        "loopMode" to LoopMode.ONCE.name,
    )
}
