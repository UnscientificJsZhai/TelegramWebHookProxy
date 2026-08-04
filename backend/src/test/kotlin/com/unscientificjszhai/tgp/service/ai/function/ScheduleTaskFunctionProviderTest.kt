package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.io.IOException
import javax.inject.Provider
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 定时任务模型函数的持久化失败结果测试设计。
 */
class ScheduleTaskFunctionProviderTest {
    /**
     * 验证创建或取消持久化失败时，工具结果只返回错误而不声称成功。
     */
    @Test
    fun `create and cancel persistence failures return errors without success`() = runTest {
        val scheduler = mockk<TaskSchedulerService>()
        val settingsRepository = mockk<SettingsRepository>()
        every {
            settingsRepository.settingsFlow
        } returns MutableStateFlow(AppSettings(ai = AISettings(agentChatId = "12345")))
        every { scheduler.createTask(any(), any(), any(), any()) } throws IOException("disk unavailable")
        every { scheduler.cancelTask(any()) } throws IOException("disk unavailable")
        val provider = ScheduleTaskFunctionProvider(Provider { scheduler }, settingsRepository)

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
}
