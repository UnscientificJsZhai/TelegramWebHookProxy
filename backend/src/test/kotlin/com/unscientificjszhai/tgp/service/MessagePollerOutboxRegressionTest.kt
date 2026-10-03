package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.TelegramReplyDeliveryStage
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerOutboxRegressionTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `late old token outbox success is retained for replacement token`() = runBlocking {
        val fixture = fixture()
        val oldSendStarted = CompletableDeferred<Unit>()
        val releaseOldSend = CompletableDeferred<Unit>()
        val newSendStarted = CompletableDeferred<Unit>()
        val releaseNewSend = CompletableDeferred<Unit>()
        fixture.updates.completeAgentUpdate("100", 11, PendingTelegramReply(11, "123", "reply"))
        fixture.saveSettings(AppSettings(telegramToken = "100:old"))
        coEvery { fixture.telegram.getUpdatesForToken("100:old", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("100:new", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:old", "123", "reply", any()) } coAnswers {
            oldSendStarted.complete(Unit)
            withContext(NonCancellable) { releaseOldSend.await() }
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }
        coEvery { fixture.telegram.sendMessageForToken("100:new", "123", "reply", any()) } coAnswers {
            newSendStarted.complete(Unit)
            releaseNewSend.await()
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        withTestCleanup(cleanup = {
            releaseOldSend.complete(Unit)
            releaseNewSend.complete(Unit)
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { oldSendStarted.await() }
            fixture.saveSettings(AppSettings(telegramToken = "100:new"))
            releaseOldSend.complete(Unit)
            withTimeout(5.seconds) { newSendStarted.await() }
            eventually { assertEquals(1, fixture.updates.getPendingTelegramReplies("100").size) }

            releaseNewSend.complete(Unit)
            eventually { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:old", "123", "reply", any()) }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:new", "123", "reply", any()) }
        }
    }

    @Test
    fun `long reply chunks preserve order and quote only first chunk`() = runBlocking {
        val fixture = fixture()
        val source = "a".repeat(4096) + "b"
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "123", source, ReplyParameters(1)),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken(
                "100:token",
                "123",
                "a".repeat(4096),
                ReplyParameters(1),
            )
        } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "b", null) } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        withTestCleanup(cleanup = {
            fixture.poller.closeAndJoin()
        }) {
            eventually {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerifyOrder {
                    fixture.telegram.sendMessageForToken(
                        "100:token",
                        "123",
                        "a".repeat(4096),
                        ReplyParameters(1),
                    )
                    fixture.telegram.sendMessageForToken("100:token", "123", "b", null)
                }
            }
        }
    }

    @Test
    fun `permanent rejection transitions original reply to fallback before removal`() = runBlocking {
        val fixture = fixture()
        val fallbackStarted = CompletableDeferred<Unit>()
        val releaseFallback = CompletableDeferred<Unit>()
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "123", "original", ReplyParameters(1)),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.BadRequest, "not-json")
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
        } returns TelegramApiResponse(HttpStatusCode.BadRequest, "not-json")
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
        } coAnswers {
            fallbackStarted.complete(Unit)
            releaseFallback.await()
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        withTestCleanup(cleanup = {
            releaseFallback.complete(Unit)
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { fallbackStarted.await() }
            fixture.updates.getPendingTelegramReplies("100").single().let { pending ->
                assertEquals("original", pending.text)
                assertEquals(TelegramReplyDeliveryStage.FALLBACK, pending.deliveryStage)
                assertNull(pending.replyParameters)
                assertEquals(1, pending.deliveryAttempts)
                assertEquals(0, pending.permanentRejectionCount)
            }
            coVerify(exactly = 1) {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
            }
            coVerify(exactly = 2) {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
            }

            releaseFallback.complete(Unit)
            eventually {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
            }
            coVerifyOrder {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
            }
        }
    }

    @Test
    fun `retry after survives session replacement and outbox signals cannot send early`() = runBlocking {
        val file = tempDirectory.resolve("rate-limited-outbox.json")
        val fixture = fixture(updatesOverride = UpdatesRepository(file))
        fixture.updates.completeAgentUpdate(
            "100", 11, PendingTelegramReply(11, "123", "original", deliveryStage = TelegramReplyDeliveryStage.FALLBACK),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:old"))
        coEvery { fixture.telegram.getUpdatesForToken(any(), 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken(any(), "123", any(), null) } returns
                TelegramApiResponse(
                    HttpStatusCode.TooManyRequests,
                    """{"ok":false,"error_code":429,"parameters":{"retry_after":30}}""",
                )

        fixture.poller.start()
        withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
            eventually {
                val saved = UpdatesRepository(file).getPendingTelegramReplies("100").single()
                assertEquals(0, saved.deliveryAttempts)
                assertTrue(saved.nextDeliveryAtEpochMillis > System.currentTimeMillis() + 25_000)
            }
            fixture.saveSettings(AppSettings(telegramToken = "100:new"))
            repeat(20) { runtime(fixture.poller).signalOutboxForBot("100") }
            delay(150)
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken(any(), "123", any(), null) }
            assertEquals(0, UpdatesRepository(file).getPendingTelegramReplies("100").single().deliveryAttempts)
        }
    }

    @Test
    fun `three rate limits do not exhaust fallback and it can later succeed`() = runBlocking {
        val fixture = fixture()
        val sends = AtomicInteger()
        fixture.updates.completeAgentUpdate(
            "100", 11, PendingTelegramReply(11, "123", "original", deliveryStage = TelegramReplyDeliveryStage.FALLBACK),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", any(), null) } coAnswers {
            if (sends.incrementAndGet() <= 3) {
                TelegramApiResponse(
                    HttpStatusCode.TooManyRequests,
                    """{"ok":false,"error_code":429,"parameters":{"retry_after":1}}""",
                )
            } else {
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            }
        }

        fixture.poller.start()
        withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
            eventually(timeout = 5.seconds) {
                assertEquals(3, sends.get())
                assertEquals(0, fixture.updates.getPendingTelegramReplies("100").single().deliveryAttempts)
            }
            eventually(timeout = 5.seconds) { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            assertEquals(4, sends.get())
        }
    }

    @Test
    fun `old session rate limit delays replacement session for the same bot`() = runBlocking {
        val fixture = fixture()
        val oldSendStarted = CompletableDeferred<Unit>()
        val releaseOldResponse = CompletableDeferred<Unit>()
        val oldResponseReturned = CompletableDeferred<Unit>()
        val newSendStarted = CompletableDeferred<Long>()
        val releaseNewResponse = CompletableDeferred<Unit>()
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(
                11,
                "123",
                "original",
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
                deliveryAttempts = 2,
                fallbackFailureCount = 2,
            ),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:old"))
        coEvery { fixture.telegram.getUpdatesForToken(any(), 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:old", "123", any(), null) } coAnswers {
            oldSendStarted.complete(Unit)
            withContext(NonCancellable) { releaseOldResponse.await() }
            oldResponseReturned.complete(Unit)
            TelegramApiResponse(
                HttpStatusCode.TooManyRequests,
                """{"ok":false,"error_code":429,"parameters":{"retry_after":2}}""",
            )
        }
        coEvery { fixture.telegram.sendMessageForToken("100:new", "123", any(), null) } coAnswers {
            newSendStarted.complete(System.currentTimeMillis())
            releaseNewResponse.await()
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        withTestCleanup(cleanup = {
            releaseOldResponse.complete(Unit)
            releaseNewResponse.complete(Unit)
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { oldSendStarted.await() }
            assertEquals(3, fixture.updates.getPendingTelegramReplies("100").single().deliveryAttempts)
            fixture.saveSettings(AppSettings(telegramToken = "100:new"))
            releaseOldResponse.complete(Unit)
            withTimeout(5.seconds) { oldResponseReturned.await() }
            eventually {
                val deferred = fixture.updates.getPendingTelegramReplies("100").single()
                assertEquals(2, deferred.deliveryAttempts)
                assertEquals(2, deferred.fallbackFailureCount)
                assertTrue(deferred.nextDeliveryAtEpochMillis > System.currentTimeMillis() + 1_000)
            }
            val deadline = fixture.updates.getPendingTelegramReplies("100").single().nextDeliveryAtEpochMillis
            eventually { assertEquals("100:new", sessionToken(currentSession(fixture.poller))) }
            repeat(20) { runtime(fixture.poller).signalOutboxForBot("100") }
            delay(150)
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken("100:new", "123", any(), null) }
            val sentAt = withTimeout(5.seconds) { newSendStarted.await() }
            assertTrue(sentAt >= deadline)
            val resumed = fixture.updates.getPendingTelegramReplies("100").single()
            assertEquals(3, resumed.deliveryAttempts)
            assertEquals(2, resumed.fallbackFailureCount)
            releaseNewResponse.complete(Unit)
            eventually { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:old", "123", any(), null) }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:new", "123", any(), null) }
        }
    }

    @Test
    fun `old bot rate limit does not delay a replacement session for another bot`() = runBlocking {
        val fixture = fixture()
        val oldSendStarted = CompletableDeferred<Unit>()
        val releaseOldResponse = CompletableDeferred<Unit>()
        val newSendStarted = CompletableDeferred<Unit>()
        fixture.updates.completeAgentUpdate("100", 11, PendingTelegramReply(11, "123", "old"))
        fixture.updates.completeAgentUpdate("200", 12, PendingTelegramReply(12, "456", "new"))
        fixture.saveSettings(AppSettings(telegramToken = "100:old"))
        coEvery { fixture.telegram.getUpdatesForToken(any(), any(), 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:old", "123", "old", any()) } coAnswers {
            oldSendStarted.complete(Unit)
            withContext(NonCancellable) { releaseOldResponse.await() }
            TelegramApiResponse(
                HttpStatusCode.TooManyRequests,
                """{"ok":false,"error_code":429,"parameters":{"retry_after":30}}""",
            )
        }
        coEvery { fixture.telegram.sendMessageForToken("200:new", "456", "new", any()) } coAnswers {
            newSendStarted.complete(Unit)
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        withTestCleanup(cleanup = {
            releaseOldResponse.complete(Unit)
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { oldSendStarted.await() }
            fixture.saveSettings(AppSettings(telegramToken = "200:new"))
            releaseOldResponse.complete(Unit)
            withTimeout(5.seconds) { newSendStarted.await() }
            eventually {
                assertTrue(fixture.updates.getPendingTelegramReplies("200").isEmpty())
                assertTrue(
                    fixture.updates.getPendingTelegramReplies("100").single().nextDeliveryAtEpochMillis >
                            System.currentTimeMillis() + 25_000,
                )
            }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("200:new", "456", "new", any()) }
        }
    }

    @Test
    fun `three permanent fallback failures discard reply`() = runBlocking {
        val fixture = fixture()
        fixture.updates.completeAgentUpdate(
            "100", 11, PendingTelegramReply(11, "123", "original", deliveryStage = TelegramReplyDeliveryStage.FALLBACK),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", any(), null) } returns
                TelegramApiResponse(HttpStatusCode.BadRequest, "not-json")

        fixture.poller.start()
        withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
            eventually(timeout = 5.seconds) { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            coVerify(exactly = 3) { fixture.telegram.sendMessageForToken("100:token", "123", any(), null) }
        }
    }

    @Test
    fun `unknown network result does not use last fallback failure allowance`() = runBlocking {
        val fixture = fixture()
        val sends = AtomicInteger()
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(
                11,
                "123",
                "original",
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
                deliveryAttempts = 2,
                fallbackFailureCount = 2,
            ),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", any(), null) } coAnswers {
            if (sends.incrementAndGet() == 1) throw IOException("unknown result")
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
            eventually(timeout = 5.seconds) { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            assertEquals(2, sends.get())
        }
    }

    @Test
    fun `temporary fallback failures retain their permanent failure budget`() = runBlocking {
        val fixture = fixture()
        val sends = AtomicInteger()
        fixture.updates.completeAgentUpdate(
            "100", 11,
            PendingTelegramReply(
                11, "123", "original",
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK, fallbackFailureCount = 2
            ),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", any(), null) } coAnswers {
            assertEquals(2, fixture.updates.getPendingTelegramReplies("100").single().fallbackFailureCount)
            when (sends.incrementAndGet()) {
                1 -> TelegramApiResponse(HttpStatusCode.InternalServerError, "not-json")
                2 -> TelegramApiResponse(HttpStatusCode.OK, """{"ok":false,"error_code":503}""")
                else -> TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            }
        }
        fixture.poller.start()
        withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
            eventually(timeout = 5.seconds) { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            assertEquals(3, sends.get())
        }
    }
}
