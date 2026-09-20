package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.*
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class TelegramRichOutboxTest : MessagePollerFacadeTestSupport() {
    private val accepted = TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
    private val rejected = TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false,"error_code":400}""")

    @Test
    fun `text and voice AI replies persist rich plans before their first send`() = runBlocking {
        for (voiceInput in listOf(false, true)) {
            val fixture = fixture()
            val reply = "# 回复\n\n**已处理**"
            val sent = CompletableDeferred<PendingTelegramReply>()
            val release = CompletableDeferred<Unit>()
            val chat = Chat(id = 123, type = "private", firstName = "Test")
            val message = if (voiceInput) authorizedMessage(1, chat, voice = Voice("voice-id", "unique", 1))
            else authorizedMessage(1, chat, text = "问题")
            fixture.updates.saveLastUpdateId("100", 10)
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:token",
                    ai = AISettings(agentEnabled = true, agentChatId = "123")
                )
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message))
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.sendChatActionForToken(any(), any(), any()) } returns accepted
            coEvery { fixture.agent.sendMessage("问题") } returns reply
            val media = byteArrayOf(1, 2, 3)
            coEvery { fixture.telegram.getFileForToken("100:token", "voice-id") } returns FileResponse(
                true,
                TelegramFile("voice-id", "unique", filePath = "voice.ogg")
            )
            coEvery { fixture.telegram.downloadFileForToken("100:token", "voice.ogg") } returns media
            coEvery { fixture.agent.sendMessage(null, listOf(MediaData(media, "audio/ogg"))) } returns reply
            coEvery {
                fixture.telegram.sendRichMessageForToken(
                    "100:token",
                    "123",
                    any(),
                    ReplyParameters(1)
                )
            } coAnswers {
                val pending = fixture.updates.getPendingTelegramReplies("100").single()
                sent.complete(pending)
                release.await()
                accepted
            }
            fixture.poller.start()
            withTestCleanup(cleanup = {
                release.complete(Unit)
                fixture.poller.closeAndJoin()
            }) {
                val pending = withTimeout(5.seconds) { sent.await() }
                assertEquals(reply, pending.text)
                assertEquals(reply, pending.deliveryPlan?.single()?.text)
                assertEquals(TelegramRichFormat.MARKDOWN, pending.deliveryPlan?.single()?.format)
                assertEquals(1, pending.deliveryAttempts)
                release.complete(Unit)
                eventually { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
                coVerify(exactly = 1) { fixture.telegram.sendRichMessageForToken(any(), any(), any(), any()) }
                coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
            }
        }
    }

    @Test
    fun `quoted rich rejection falls back locally and resumes midway after restart`() = runBlocking {
        val file = tempDirectory.resolve("rich-outbox.json")
        val source = "a".repeat(5000) + "后续"
        val plan = listOf(
            TelegramReplyPart("```text\n${source.take(5000)}\n```", 0, 5000, TelegramRichFormat.MARKDOWN),
            TelegramReplyPart("**后续**", 5000, source.length, TelegramRichFormat.MARKDOWN),
        )
        val fixture = fixture(updatesOverride = UpdatesRepository(file))
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "123", source, ReplyParameters(1), deliveryPlan = plan)
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendRichMessageForToken(
                "100:token",
                "123",
                InputRichMessage(markdown = plan[0].text),
                any()
            )
        } returns rejected
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "a".repeat(4096), null) } returns accepted
        val secondPlainStarted = CompletableDeferred<Unit>()
        val releaseSecondPlain = CompletableDeferred<Unit>()
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "a".repeat(904), null) } coAnswers {
            secondPlainStarted.complete(Unit)
            releaseSecondPlain.await()
            accepted
        }

        fixture.poller.start()
        withTestCleanup(cleanup = {
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(8.seconds) { secondPlainStarted.await() }
            val saved = UpdatesRepository(file).getPendingTelegramReplies("100").single()
            assertEquals(plan, saved.deliveryPlan)
            assertEquals(TelegramReplyDeliveryStage.PLAIN_FALLBACK, saved.deliveryStage)
            assertEquals(4096, saved.plainFallbackStart)
            coVerifyOrder {
                fixture.telegram.sendRichMessageForToken(
                    "100:token",
                    "123",
                    InputRichMessage(markdown = plan[0].text),
                    ReplyParameters(1)
                )
                fixture.telegram.sendRichMessageForToken(
                    "100:token",
                    "123",
                    InputRichMessage(markdown = plan[0].text),
                    null
                )
                fixture.telegram.sendMessageForToken("100:token", "123", "a".repeat(4096), null)
                fixture.telegram.sendMessageForToken("100:token", "123", "a".repeat(904), null)
            }
        }

        val restarted = fixture(updatesOverride = UpdatesRepository(file))
        restarted.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { restarted.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { restarted.telegram.sendMessageForToken("100:token", "123", "a".repeat(904), null) } returns accepted
        coEvery {
            restarted.telegram.sendRichMessageForToken(
                "100:token",
                "123",
                InputRichMessage(markdown = "**后续**"),
                null
            )
        } returns accepted
        restarted.poller.start()
        withTestCleanup(cleanup = {
            restarted.poller.closeAndJoin()
        }) {
            eventually { assertTrue(restarted.updates.getPendingTelegramReplies("100").isEmpty()) }
            coVerifyOrder {
                restarted.telegram.sendMessageForToken("100:token", "123", "a".repeat(904), null)
                restarted.telegram.sendRichMessageForToken(
                    "100:token",
                    "123",
                    InputRichMessage(markdown = "**后续**"),
                    null
                )
            }
            coVerify(exactly = 1) { restarted.telegram.sendMessageForToken(any(), any(), any(), any()) }
            coVerify(exactly = 1) { restarted.telegram.sendRichMessageForToken(any(), any(), any(), any()) }
            coVerify(exactly = 0) { restarted.agent.sendMessage(any<String>()) }
        }
    }

    @Test
    fun `temporary rich failures keep format and quoted state until accepted`() = runBlocking {
        val fixture = fixture()
        val plan = listOf(TelegramReplyPart("**回复**", 0, 2, TelegramRichFormat.MARKDOWN))
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "123", "回复", ReplyParameters(1), deliveryPlan = plan)
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        val attempts = AtomicInteger()
        val failures =
            listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable, HttpStatusCode.RequestTimeout)
        coEvery {
            fixture.telegram.sendRichMessageForToken(
                "100:token",
                "123",
                InputRichMessage(markdown = "**回复**"),
                ReplyParameters(1)
            )
        } coAnswers {
            val pending = fixture.updates.getPendingTelegramReplies("100").single()
            assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
            assertEquals(0, pending.nextPartIndex)
            assertTrue(pending.deliveryAttempts > 0, "发请求前已持久化次数")
            when (val attempt = attempts.getAndIncrement()) {
                in failures.indices -> TelegramApiResponse(failures[attempt], """{"ok":false}""")
                3 -> throw IOException("unknown network outcome")
                else -> accepted
            }
        }
        fixture.poller.start()
        withTestCleanup(cleanup = {
            fixture.poller.closeAndJoin()
        }) {
            eventually(10.seconds) { assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty()) }
            assertEquals(5, attempts.get())
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
        }
    }
}
