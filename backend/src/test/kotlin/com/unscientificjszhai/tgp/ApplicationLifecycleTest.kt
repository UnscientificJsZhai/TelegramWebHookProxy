package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.BotCommandReconciler
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.ktor.server.application.ApplicationStopPreparing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 应用停止编排的顺序与等待语义测试设计。 */
class ApplicationLifecycleTest {

    /**
     * 验证 `ApplicationStopPreparing` 先关闭三个 worker 的准入，且不可取消 worker 未结束时不会关闭 Telegram
     * 或 Agent；重复事件也不会重复关闭资源。
     */
    @Test
    fun `ApplicationStopPreparing gates workers before dependencies and waits for their terminal completion`() =
        runBlocking {
            val messagePoller = mockk<MessagePoller>()
            val taskScheduler = mockk<TaskSchedulerService>()
            val botCommandReconciler = mockk<BotCommandReconciler>()
            val telegramService = mockk<TelegramService>(relaxed = true)
            val agentService = mockk<AgentService>()
            val events = Collections.synchronizedList(mutableListOf<String>())
            val releasePoller = CompletableDeferred<Unit>()
            val releaseScheduler = CompletableDeferred<Unit>()
            val releaseReconciler = CompletableDeferred<Unit>()
            val pollerAwaiting = CompletableDeferred<Unit>()
            val schedulerAwaiting = CompletableDeferred<Unit>()
            val reconcilerAwaiting = CompletableDeferred<Unit>()
            val agentCloseJob = Job()

            every { messagePoller.requestStop() } answers { events += "poller-request-stop" }
            every { taskScheduler.requestStop() } answers { events += "scheduler-request-stop" }
            every { botCommandReconciler.requestStop() } answers { events += "reconciler-request-stop" }
            coEvery { messagePoller.awaitStopped() } coAnswers {
                events += "poller-await"
                pollerAwaiting.complete(Unit)
                withContext(NonCancellable) { releasePoller.await() }
            }
            coEvery { taskScheduler.awaitStopped() } coAnswers {
                events += "scheduler-await"
                schedulerAwaiting.complete(Unit)
                withContext(NonCancellable) { releaseScheduler.await() }
            }
            coEvery { botCommandReconciler.awaitStopped() } coAnswers {
                events += "reconciler-await"
                reconcilerAwaiting.complete(Unit)
                withContext(NonCancellable) { releaseReconciler.await() }
            }
            every { telegramService.close() } answers { events += "telegram-close" }
            every { agentService.close() } answers {
                events += "agent-close"
                agentCloseJob
            }

            testApplication {
                application {
                    registerApplicationStopCleanup(
                        messagePoller,
                        taskScheduler,
                        botCommandReconciler,
                        telegramService,
                        agentService,
                    )
                    // 重复注册只能保留同一个停止编排器。
                    registerApplicationStopCleanup(
                        messagePoller,
                        taskScheduler,
                        botCommandReconciler,
                        telegramService,
                        agentService,
                    )
                }
                startApplication()

                val stopping = this@runBlocking.async(Dispatchers.Default) {
                    application.monitor.raise(ApplicationStopPreparing, application.environment)
                }
                withTimeout(5.seconds) { pollerAwaiting.await() }

                assertEquals(
                    listOf("poller-request-stop", "scheduler-request-stop", "reconciler-request-stop", "poller-await"),
                    events,
                )
                assertFalse(stopping.isCompleted)
                verify(exactly = 0) { telegramService.close() }
                verify(exactly = 0) { agentService.close() }

                releasePoller.complete(Unit)
                withTimeout(5.seconds) { schedulerAwaiting.await() }
                assertFalse(stopping.isCompleted)
                verify(exactly = 0) { telegramService.close() }
                verify(exactly = 0) { agentService.close() }

                releaseScheduler.complete(Unit)
                withTimeout(5.seconds) { reconcilerAwaiting.await() }
                assertFalse(stopping.isCompleted)
                verify(exactly = 0) { telegramService.close() }
                verify(exactly = 0) { agentService.close() }

                releaseReconciler.complete(Unit)
                withTimeout(5.seconds) {
                    while (events.lastOrNull() != "agent-close") {
                        kotlinx.coroutines.yield()
                    }
                }
                assertFalse(stopping.isCompleted)
                agentCloseJob.complete()
                withTimeout(5.seconds) { stopping.await() }
                assertEquals(
                    listOf(
                        "poller-request-stop",
                        "scheduler-request-stop",
                        "reconciler-request-stop",
                        "poller-await",
                        "scheduler-await",
                        "reconciler-await",
                        "telegram-close",
                        "agent-close",
                    ),
                    events,
                )

                application.monitor.raise(ApplicationStopPreparing, application.environment)
            }

            verify(exactly = 1) { messagePoller.requestStop() }
            verify(exactly = 1) { taskScheduler.requestStop() }
            verify(exactly = 1) { botCommandReconciler.requestStop() }
            verify(exactly = 1) { telegramService.close() }
            verify(exactly = 1) { agentService.close() }
            assertTrue(agentCloseJob.isCompleted)
        }
}
