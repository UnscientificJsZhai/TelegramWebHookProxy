package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.TelegramReplyDeliveryStage
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
        try {
            withTimeout(2.seconds) { oldSendStarted.await() }
            fixture.saveSettings(AppSettings(telegramToken = "100:new"))
            releaseOldSend.complete(Unit)
            withTimeout(2.seconds) { newSendStarted.await() }
            eventually { assertEquals(1, fixture.updates.getPendingTelegramReplies("100").size) }

            releaseNewSend.complete(Unit)
            eventually { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:old", "123", "reply", any()) }
            coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:new", "123", "reply", any()) }
        } finally {
            releaseOldSend.complete(Unit)
            releaseNewSend.complete(Unit)
            fixture.poller.closeAndJoin()
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
        try {
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
        } finally {
            fixture.poller.closeAndJoin()
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
        try {
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
        } finally {
            releaseFallback.complete(Unit)
            fixture.poller.closeAndJoin()
        }
    }
}
