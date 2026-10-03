package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerRecoveryRegressionTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `network failure never leaves a resume signal that skips a later voice retry`() = runBlocking {
        val fixture = fixture(retryDelay = {})
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:test",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            )
        )
        val requests = AtomicInteger()
        val admissions = AtomicInteger()
        val progressed = CompletableDeferred<Unit>()
        val batch = listOf(
            Update(11, message = authorizedMessage(11, Chat(123, "private"), voice = Voice("voice", "unique", 1))),
            Update(12, message = authorizedMessage(12, Chat(123, "private"), text = "later")),
        )
        var retryOffset: Long? = null
        coEvery { fixture.telegram.getUpdatesForToken("100:test", any(), any()) } coAnswers {
            when (requests.incrementAndGet()) {
                1 -> throw IOException("injected network failure before any work")
                2 -> GetUpdatesResponse(true, batch)
                else -> {
                    retryOffset = secondArg<Long?>()
                    progressed.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        // 第二项在消费者注册 Retry 握手后才入队，验证它不会消费上一次网络失败留下的信号。
        every { fixture.agent.isAiFeatureEnabled(any()) } answers {
            if (admissions.incrementAndGet() == 2) {
                runBlocking {
                    withTimeout(5.seconds) {
                        while (runtime(fixture.poller).withSessionLock {
                                currentSession(fixture.poller).consumerResumeWaiter == null
                            }) delay(1)
                    }
                }
            }
            true
        }
        coEvery { fixture.telegram.getFileForToken("100:test", "voice") } returns FileResponse(false, errorCode = 500)
        try {
            fixture.poller.start()
            withTimeout(8.seconds) { progressed.await() }
            assertEquals(11L, retryOffset)
            assertEquals(10L, fixture.updates.getData("100").lastUpdateId)
            assertEquals(11L, fixture.updates.getData("100").retryCheckpoint?.targetUpdateId)
            coVerify(exactly = 0) { fixture.agent.sendMessage("later", any()) }
        } finally {
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `confirmation write failure resumes a consumer paused by a later voice retry`() = runBlocking {
        val rejected = AtomicBoolean(false)
        val failureObserved = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        val firstFileAttempt = CompletableDeferred<Unit>()
        val progressed = CompletableDeferred<Unit>()
        val updates = UpdatesRepository(tempDirectory.resolve("confirmation-failure.json")) { state ->
            if (state.bots["100"]?.lastUpdateId == 11L && rejected.compareAndSet(false, true)) {
                throw IOException("injected confirmation failure")
            }
        }
        val fixture = fixture(updatesOverride = updates, retryDelay = {
            failureObserved.complete(Unit)
            allowRetry.await()
        })
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:test",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            )
        )
        val updatesBatch = listOf(
            Update(11, message = Message(11, Chat(456, "private"), text = "skip")),
            Update(
                12, message = authorizedMessage(
                    12,
                    Chat(123, "private"),
                    voice = Voice("voice", "unique", 1),
                    caption = "voice",
                )
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:test", any(), any()) } coAnswers {
            if (secondArg<Long?>() == 13L) {
                progressed.complete(Unit)
                awaitCancellation()
            }
            GetUpdatesResponse(true, updatesBatch)
        }
        val fileAttempts = AtomicInteger()
        coEvery { fixture.telegram.getFileForToken("100:test", "voice") } coAnswers {
            if (fileAttempts.incrementAndGet() == 1) {
                firstFileAttempt.complete(Unit)
                FileResponse(false, errorCode = 500)
            } else {
                FileResponse(true, TelegramFile("voice", "unique", 3, "voice.ogg"))
            }
        }
        coEvery { fixture.telegram.downloadFileForToken(any(), any()) } returns byteArrayOf(1, 2, 3)
        coEvery { fixture.telegram.sendChatActionForToken(any(), any(), any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        coEvery { fixture.agent.sendMessage("voice") } returns ""
        try {
            fixture.poller.start()
            withTimeout(5.seconds) {
                failureObserved.await()
                firstFileAttempt.await()
            }
            allowRetry.complete(Unit)
            withTimeout(5.seconds) { progressed.await() }
            assertEquals(12L, updates.getData("100").lastUpdateId)
            assertNull(updates.getData("100").retryCheckpoint)
            assertEquals(2, fileAttempts.get())
            coVerify(exactly = 1) { fixture.agent.sendMessage("voice", any()) }
        } finally {
            allowRetry.complete(Unit)
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `lower Telegram sequence processes its first update and persists the new cursor`() = runBlocking {
        val file = tempDirectory.resolve("sequence-reset.json")
        val fixture = fixture(updatesOverride = UpdatesRepository(file), retryDelay = {})
        fixture.updates.saveLastUpdateId("100", 900_000_000)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:test",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            )
        )
        val offsets = CopyOnWriteArrayList<Long?>()
        val progressed = CompletableDeferred<Unit>()
        val update = Update(400_000_000, message = authorizedMessage(1, Chat(123, "private"), text = "new sequence"))
        coEvery { fixture.telegram.getUpdatesForToken("100:test", any(), any()) } coAnswers {
            offsets += secondArg<Long?>()
            if (secondArg<Long?>() == 400_000_001L) {
                progressed.complete(Unit)
                awaitCancellation()
            }
            GetUpdatesResponse(true, listOf(update))
        }
        coEvery { fixture.agent.sendMessage("new sequence") } returns ""
        try {
            fixture.poller.start()
            withTimeout(5.seconds) { progressed.await() }
            assertEquals(listOf(900_000_001L, 400_000_000L, 400_000_001L), offsets.toList())
            assertEquals(400_000_000L, UpdatesRepository(file).getData("100").lastUpdateId)
            assertNull(fixture.updates.getData("100").retryCheckpoint)
            coVerify(exactly = 1) { fixture.agent.sendMessage("new sequence") }
        } finally {
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `restart after sequence reset fetches the first checkpoint instead of skipping to the latest update`() =
        runBlocking {
            val file = tempDirectory.resolve("sequence-reset-restart.json")
            UpdatesRepository(file).saveLastUpdateId("100", 900_000_000)
            val resetCommitted = CompletableDeferred<Unit>()
            val beforeRestart = fixture(updatesOverride = UpdatesRepository(file), retryDelay = {
                resetCommitted.complete(Unit)
                awaitCancellation()
            })
            beforeRestart.saveRawSettings(AppSettings(telegramToken = "100:test"))
            val batch = GetUpdatesResponse(true, listOf(Update(1), Update(2)))
            coEvery { beforeRestart.telegram.getUpdatesForToken("100:test", 900_000_001, 30) } returns batch
            try {
                beforeRestart.poller.start()
                withTimeout(5.seconds) { resetCommitted.await() }
                assertEquals(0L, UpdatesRepository(file).getData("100").lastUpdateId)
                assertEquals(1L, UpdatesRepository(file).getData("100").retryCheckpoint?.targetUpdateId)
            } finally {
                beforeRestart.poller.closeAndJoin()
            }

            val afterRestart = fixture(updatesOverride = UpdatesRepository(file))
            afterRestart.saveRawSettings(AppSettings(telegramToken = "100:test"))
            val progressed = CompletableDeferred<Unit>()
            coEvery { afterRestart.telegram.getUpdatesForToken("100:test", 1, 30) } returns batch
            coEvery { afterRestart.telegram.getUpdatesForToken("100:test", 3, 30) } coAnswers {
                progressed.complete(Unit)
                awaitCancellation()
            }
            try {
                afterRestart.poller.start()
                withTimeout(5.seconds) { progressed.await() }
                assertEquals(2L, afterRestart.updates.getData("100").lastUpdateId)
                assertNull(afterRestart.updates.getData("100").retryCheckpoint)
                coVerify(exactly = 0) { afterRestart.telegram.getUpdatesForToken("100:test", -1, any()) }
            } finally {
                afterRestart.poller.closeAndJoin()
            }
        }
}
