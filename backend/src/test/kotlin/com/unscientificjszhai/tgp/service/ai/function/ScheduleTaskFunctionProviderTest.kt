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
import java.io.IOException
import java.time.*
import javax.inject.Provider
import kotlin.test.*

/**
 * 定时任务模型函数的执行时间和持久化失败结果测试设计。
 */
class ScheduleTaskFunctionProviderTest {





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
