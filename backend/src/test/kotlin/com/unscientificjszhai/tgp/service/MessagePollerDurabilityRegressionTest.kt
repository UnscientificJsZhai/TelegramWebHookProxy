package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.repository.AgentTurnClaim
import com.unscientificjszhai.tgp.repository.AgentTurnJournalStatus
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerDurabilityRegressionTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `failed lower final commit blocks higher queued update`() = runBlocking {
        val file = tempDirectory.resolve("blocked-higher-agent-update.json")
        var rejectLowerCompletion = true
        val updates = UpdatesRepository(file) { state ->
            val bot = state.bots["100"]
            if (
                rejectLowerCompletion &&
                bot?.lastUpdateId == 11L &&
                bot.pendingTelegramReplies.any { it.updateId == 11L } &&
                bot.agentTurnJournal.any { it.updateId == 11L && it.reply == "eleven" }
            ) {
                rejectLowerCompletion = false
                throw IOException("injected lower completion failure")
            }
        }
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        val fixture = fixture(
            updatesOverride = updates,
            retryDelay = {
                retryStarted.complete(Unit)
                allowRetry.await()
            },
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, text = "eleven")),
                Update(12, message = authorizedMessage(2, chat, text = "twelve")),
            ),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("eleven") } returns "eleven"

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            assertFalse(rejectLowerCompletion)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 1) { fixture.agent.sendMessage("eleven") }
            coVerify(exactly = 0) { fixture.agent.sendMessage("twelve") }
        } finally {
            allowRetry.complete(Unit)
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `agent turn claim write failure never enters agent`() = runBlocking {
        val writeAttempted = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<Unit>()
        val updates = UpdatesRepository(tempDirectory.resolve("failed-poller-agent-claim.json")) { state ->
            if (
                state.bots["100"]?.agentTurnJournal?.any {
                    it.status == AgentTurnJournalStatus.IN_PROGRESS
                } == true
            ) {
                writeAttempted.complete(Unit)
                throw IOException("injected agent journal write failure")
            }
        }
        val fixture = fixture(
            retryDelay = {
                retryStarted.complete(Unit)
                awaitCancellation()
            },
            updatesOverride = updates,
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", -1, 0) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(1, message = authorizedMessage(1, chat, text = "never"))),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { writeAttempted.await() }
            withTimeout(2.seconds) { retryStarted.await() }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            assertEquals(0, fixture.updates.getData("100").lastUpdateId)
            assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
        } finally {
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `disabled or unavailable agent silently confirms durable in progress without replay`() = runBlocking {
        val configurations = listOf(
            "disabled" to AppSettings(telegramToken = "100:disabled"),
            "missing-key" to AppSettings(
                telegramToken = "100:missing-key",
                ai = AISettings(agentEnabled = true, agentChatId = "123", geminiApiKey = ""),
            ),
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        configurations.forEach { (label, settings) ->
            val file = tempDirectory.resolve("unavailable-$label-in-progress.json")
            UpdatesRepository(file).apply {
                saveLastUpdateId("100", 10)
                assertEquals(AgentTurnClaim.CLAIMED, claimAgentTurn("100", 11, "123", ReplyParameters(1)))
            }
            val fixture = fixture(updatesOverride = UpdatesRepository(file))
            fixture.saveRawSettings(settings)
            coEvery { fixture.telegram.getUpdatesForToken(settings.telegramToken, 11, 30) } returns GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message = authorizedMessage(1, chat, text = "must-not-run"))),
            ) andThen GetUpdatesResponse(ok = true)

            fixture.poller.start()
            try {
                eventually {
                    assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                    assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                    coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
                    coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
                }
            } finally {
                fixture.poller.closeAndJoin()
            }
        }
    }

    @Test
    fun `disabled agent commits durable final to outbox without replay`() = runBlocking {
        val file = tempDirectory.resolve("disabled-agent-final-journal.json")
        UpdatesRepository(file).apply {
            saveLastUpdateId("100", 10)
            assertEquals(AgentTurnClaim.CLAIMED, claimAgentTurn("100", 11, "123", ReplyParameters(1)))
            assertNotNull(finalizeAgentTurn("100", 11, "saved-reply"))
        }
        val fixture = fixture(updatesOverride = UpdatesRepository(file))
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "ignored-current-config"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "saved-reply", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                fixture.updates.getPendingTelegramReplies("100").single().let { reply ->
                    assertEquals("saved-reply", reply.text)
                    assertEquals(ReplyParameters(1), reply.replyParameters)
                }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            }
        } finally {
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `largest persisted offset requests Long MAX without wrapping`() = runBlocking {
        val requestStarted = CompletableDeferred<Unit>()
        val holdRequest = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", Long.MAX_VALUE - 1)
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", Long.MAX_VALUE, 30) } coAnswers {
            requestStarted.complete(Unit)
            holdRequest.await()
            GetUpdatesResponse(ok = true)
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { requestStarted.await() }
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", Long.MAX_VALUE, 30) }
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", Long.MIN_VALUE, 30) }
            assertNull(fixture.updates.getData("100").retryCheckpoint)
        } finally {
            holdRequest.cancel()
            fixture.poller.closeAndJoin()
        }
    }
}
