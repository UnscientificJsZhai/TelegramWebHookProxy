package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.service.BotCommandReconciler
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.ktor.server.application.*
import io.ktor.server.testing.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import java.util.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** 应用停止编排的顺序与等待语义测试设计。 */
class ApplicationLifecycleTest {

    /**
     * 验证总停止预算不会按步骤重复计算：worker 的异常和一个永不返回的等待都不能阻止后续 Telegram 与 Agent
     * 关闭，并且监听器会在短预算后返回。
     */
    @Test
    fun `ApplicationStopPreparing applies one deadline and continues after failed or stuck steps`() = runBlocking {
        val messagePoller = mockk<MessagePoller>()
        val taskScheduler = mockk<TaskSchedulerService>()
        val botCommandReconciler = mockk<BotCommandReconciler>()
        val telegramService = mockk<TelegramService>(relaxed = true)
        val agentService = mockk<AgentService>()
        val events = Collections.synchronizedList(mutableListOf<String>())
        val releaseSchedulerAwait = CompletableDeferred<Unit>()
        val schedulerAwaitReleased = CompletableDeferred<Unit>()

        every { messagePoller.requestStop() } answers { events += "poller-request-stop" }
        every { taskScheduler.requestStop() } answers { events += "scheduler-request-stop" }
        every { botCommandReconciler.requestStop() } answers { events += "reconciler-request-stop" }
        coEvery { messagePoller.awaitStopped() } throws IllegalStateException("injected poller failure")
        coEvery { taskScheduler.awaitStopped() } coAnswers {
            events += "scheduler-await"
            withContext(NonCancellable) {
                try {
                    releaseSchedulerAwait.await()
                } finally {
                    schedulerAwaitReleased.complete(Unit)
                }
            }
        }
        coEvery { botCommandReconciler.awaitStopped() } coAnswers { events += "reconciler-await" }
        every { telegramService.close() } answers { events += "telegram-close" }
        every { agentService.close() } answers {
            events += "agent-close"
            Job().apply { complete() }
        }

        try {
            testApplication {
                application {
                    registerApplicationStopCleanup(
                        messagePoller,
                        taskScheduler,
                        botCommandReconciler,
                        telegramService,
                        agentService,
                        shutdownTimeout = 200.milliseconds,
                    )
                }
                startApplication()

                val startedAt = TimeSource.Monotonic.markNow()
                val stopping = this@runBlocking.async(Dispatchers.Default) {
                    application.monitor.raise(ApplicationStopPreparing, application.environment)
                }
                withTimeout(2.seconds) { stopping.await() }
                val elapsed = startedAt.elapsedNow()
                withTimeout(2.seconds) {
                    while (!events.contains("telegram-close") || !events.contains("agent-close")) {
                        kotlinx.coroutines.yield()
                    }
                }
                assertTrue(elapsed >= 120.milliseconds, "elapsed=$elapsed")
                assertTrue(elapsed < 1.seconds, "elapsed=$elapsed")
            }
        } finally {
            releaseSchedulerAwait.complete(Unit)
            withTimeout(2.seconds) { schedulerAwaitReleased.await() }
        }

        assertEquals(
            listOf(
                "poller-request-stop",
                "scheduler-request-stop",
                "reconciler-request-stop",
                "scheduler-await",
            ),
            events.take(4),
        )
        assertTrue("telegram-close" in events)
        assertTrue("agent-close" in events)
        verify(exactly = 1) { telegramService.close() }
        verify(exactly = 1) { agentService.close() }
    }

    /**
     * 验证同步 Telegram 关闭即使永久阻塞，监听器仍会在总预算内返回；此时 Agent 关闭会降级为独立启动，避免
     * 被 Telegram 阻塞。
     */
    @Test
    fun `ApplicationStopPreparing returns by deadline when synchronous Telegram close blocks`() = runBlocking {
        val messagePoller = mockk<MessagePoller>()
        val taskScheduler = mockk<TaskSchedulerService>()
        val botCommandReconciler = mockk<BotCommandReconciler>()
        val telegramService = mockk<TelegramService>()
        val agentService = mockk<AgentService>()
        val telegramCloseStarted = CompletableDeferred<Unit>()
        val releaseTelegramClose = CompletableDeferred<Unit>()
        val agentCloseStarted = CompletableDeferred<Unit>()

        every { messagePoller.requestStop() } returns Unit
        every { taskScheduler.requestStop() } returns Unit
        every { botCommandReconciler.requestStop() } returns Unit
        coEvery { messagePoller.awaitStopped() } returns Unit
        coEvery { taskScheduler.awaitStopped() } returns Unit
        coEvery { botCommandReconciler.awaitStopped() } returns Unit
        every { telegramService.close() } answers {
            telegramCloseStarted.complete(Unit)
            runBlocking { withContext(NonCancellable) { releaseTelegramClose.await() } }
        }
        every { agentService.close() } answers {
            agentCloseStarted.complete(Unit)
            Job().apply { complete() }
        }

        testApplication {
            application {
                registerApplicationStopCleanup(
                    messagePoller,
                    taskScheduler,
                    botCommandReconciler,
                    telegramService,
                    agentService,
                    shutdownTimeout = 100.milliseconds,
                )
            }
            startApplication()

            try {
                val startedAt = TimeSource.Monotonic.markNow()
                val stopping = this@runBlocking.async(Dispatchers.Default) {
                    application.monitor.raise(ApplicationStopPreparing, application.environment)
                }
                withTimeout(2.seconds) { telegramCloseStarted.await() }
                withTimeout(2.seconds) { stopping.await() }
                assertTrue(startedAt.elapsedNow() < 1.seconds)
                withTimeout(2.seconds) { agentCloseStarted.await() }
                verify(exactly = 1) { agentService.close() }
            } finally {
                releaseTelegramClose.complete(Unit)
            }
        }
    }

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
