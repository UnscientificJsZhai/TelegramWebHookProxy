package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 应用停止时 AI 代理资源清理的测试设计。
 */
class ApplicationLifecycleTest {

    /**
     * 验证应用停止会关闭 Telegram 与委派 Agent，并等待 Agent 的异步清理。
     */
    @Test
    fun `ApplicationStopped waits for agent cleanup`() = runBlocking {
        val telegramService = mockk<TelegramService>(relaxed = true)
        val agentService = mockk<AgentService>()
        val agentCloseJob = Job()
        val agentCloseCalled = CompletableDeferred<Unit>()
        every { agentService.close() } answers {
            agentCloseCalled.complete(Unit)
            agentCloseJob
        }

        testApplication {
            application {
                registerApplicationStopCleanup(telegramService, agentService)
            }
            startApplication()

            val stopped = async(Dispatchers.Default) {
                application.monitor.raise(ApplicationStopped, application)
            }
            withTimeout(5.seconds) { agentCloseCalled.await() }
            assertFalse(stopped.isCompleted)

            agentCloseJob.complete()
            withTimeout(5.seconds) { stopped.await() }
        }

        verify(atLeast = 1) { telegramService.close() }
        verify(atLeast = 1) { agentService.close() }
        assertTrue(agentCloseJob.isCompleted)
    }
}
