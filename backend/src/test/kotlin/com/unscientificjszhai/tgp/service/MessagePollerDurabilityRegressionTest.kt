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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerDurabilityRegressionTest : MessagePollerFacadeTestSupport() {
    /** 重启时原更新即使已不在上游队列中，也必须先恢复账本，且不得重放未完成的 Agent。 */
    @Test
    fun `restart restores unconfirmed journal before polling without a checkpoint`() = runBlocking {
        for (storedOffset in listOf(0L, 10L)) {
            val file = tempDirectory.resolve("restart-journal-$storedOffset.json")
            UpdatesRepository(file).apply {
                saveLastUpdateId("100", storedOffset)
                claimAgentTurn("100", 11, "123", ReplyParameters(1))
                finalizeAgentTurn("100", 11, "saved-first")
                claimAgentTurn("100", 12, "123", ReplyParameters(2))
                claimAgentTurn("100", 13, "123", ReplyParameters(3))
                finalizeAgentTurn("100", 13, "saved-last")
            }
            val fixture = fixture(updatesOverride = UpdatesRepository(file))
            fixture.saveSettings(AppSettings(telegramToken = "100:token"))
            val pollingStarted = CompletableDeferred<Unit>()
            coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } coAnswers {
                assertEquals(14L, secondArg<Long?>())
                pollingStarted.complete(Unit)
                awaitCancellation()
            }
            coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } coAnswers {
                awaitCancellation()
            }
            fixture.poller.start()
            withTestCleanup(cleanup = {
                fixture.poller.closeAndJoin()
            }) {
                withTimeout(7.seconds) { pollingStarted.await() }
                val restored = fixture.updates.getData("100")
                assertEquals(13L, restored.lastUpdateId)
                assertNull(restored.retryCheckpoint)
                assertTrue(restored.agentTurnJournal.isEmpty())
                assertEquals(listOf("saved-first", "saved-last"), restored.pendingTelegramReplies.map { it.text })
                assertEquals(listOf(11L, 13L), restored.pendingTelegramReplies.map { it.updateId })
                coVerify(exactly = 0) { fixture.agent.sendMessage(any(), any()) }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            }
        }
    }

    /** 恢复写盘失败时必须保留 FINAL 并建立检查点，不得向上游确认或越过该回合。 */
    @Test
    fun `failed startup recovery retains final journal without polling past it`() = runBlocking {
        val file = tempDirectory.resolve("failed-startup-recovery.json")
        UpdatesRepository(file).apply {
            claimAgentTurn("100", 11, "123", ReplyParameters(1))
            finalizeAgentTurn("100", 11, "saved-reply")
        }
        val retryStarted = CompletableDeferred<Unit>()
        val allowRetry = CompletableDeferred<Unit>()
        val pollingAfterRecovery = CompletableDeferred<Unit>()
        val rejectCommit = AtomicBoolean(true)
        val updates = UpdatesRepository(file) { state ->
            if (state.bots["100"]?.pendingTelegramReplies?.isNotEmpty() == true && rejectCommit.get()) {
                throw IOException("injected startup completion failure")
            }
        }
        val fixture = fixture(updatesOverride = updates, retryDelay = {
            retryStarted.complete(Unit)
            allowRetry.await()
        })
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } coAnswers {
            pollingAfterRecovery.complete(Unit)
            awaitCancellation()
        }
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } coAnswers {
            awaitCancellation()
        }
        fixture.poller.start()
        withTestCleanup(cleanup = {
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(3.seconds) { retryStarted.await() }
            val retained = updates.getData("100")
            assertEquals(0L, retained.lastUpdateId)
            assertEquals(11L, retained.retryCheckpoint?.targetUpdateId)
            assertEquals("saved-reply", retained.agentTurnJournal.single().reply)
            assertTrue(retained.pendingTelegramReplies.isEmpty())
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any(), any()) }
            rejectCommit.set(false)
            allowRetry.complete(Unit)
            withTimeout(3.seconds) { pollingAfterRecovery.await() }
            val recovered = updates.getData("100")
            assertEquals(11L, recovered.lastUpdateId)
            assertNull(recovered.retryCheckpoint)
            assertTrue(recovered.agentTurnJournal.isEmpty())
            assertEquals("saved-reply", recovered.pendingTelegramReplies.single().text)
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
        }
    }

    @Test
    fun `failed lower final commit blocks higher queued update`() = runBlocking {
        val file = tempDirectory.resolve("blocked-higher-agent-update.json")
        val rejectLowerCompletion = AtomicBoolean(true)
        val updates = UpdatesRepository(file) { state ->
            val bot = state.bots["100"]
            if (
                bot?.lastUpdateId == 11L &&
                bot.pendingTelegramReplies.any { it.updateId == 11L } &&
                bot.agentTurnJournal.any { it.updateId == 11L && it.reply == "eleven" } &&
                rejectLowerCompletion.compareAndSet(true, false)
            ) {
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
        withTestCleanup(cleanup = {
            allowRetry.complete(Unit)
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { retryStarted.await() }
            assertFalse(rejectLowerCompletion.get())
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 1) { fixture.agent.sendMessage("eleven") }
            coVerify(exactly = 0) { fixture.agent.sendMessage("twelve") }
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
        withTestCleanup(cleanup = {
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { writeAttempted.await() }
            withTimeout(5.seconds) { retryStarted.await() }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            assertEquals(0, fixture.updates.getData("100").lastUpdateId)
            assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
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
            withTestCleanup(cleanup = {
                fixture.poller.closeAndJoin()
            }) {
                eventually {
                    assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                    assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                    coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
                    coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
                }
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
        withTestCleanup(cleanup = {
            fixture.poller.closeAndJoin()
        }) {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                val replies = fixture.updates.getPendingTelegramReplies("100")
                assertEquals(1, replies.size)
                replies.single().let { reply ->
                    assertEquals("saved-reply", reply.text)
                    assertEquals(ReplyParameters(1), reply.replyParameters)
                }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            }
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
        withTestCleanup(cleanup = {
            holdRequest.cancel()
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { requestStarted.await() }
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", Long.MAX_VALUE, 30) }
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", Long.MIN_VALUE, 30) }
            assertNull(fixture.updates.getData("100").retryCheckpoint)
        }
    }
}
