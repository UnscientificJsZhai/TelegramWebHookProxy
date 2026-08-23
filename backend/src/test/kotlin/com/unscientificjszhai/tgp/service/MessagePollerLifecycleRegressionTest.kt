package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.Update
import io.ktor.http.HttpStatusCode
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerLifecycleRegressionTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `late 401 or 403 from expired session leaves replacement untouched`() = runBlocking {
        listOf(401, 403).forEach { errorCode ->
            val firstRequestStarted = CompletableDeferred<Unit>()
            val allowLateFailure = CompletableDeferred<Unit>()
            val fixture = fixture()
            fixture.updates.saveLastUpdateId("100", 10)
            coEvery { fixture.telegram.getUpdatesForToken("100:A$errorCode", 11, 30) } coAnswers {
                firstRequestStarted.complete(Unit)
                withContext(NonCancellable) { allowLateFailure.await() }
                GetUpdatesResponse(ok = false, errorCode = errorCode, description = "Late Unauthorized")
            }
            coEvery { fixture.telegram.getUpdatesForToken("200:B$errorCode", -1, 0) } returns
                    GetUpdatesResponse(ok = true)
            every { fixture.agent.resetSession() } returns Job().apply { complete() }
            fixture.saveSettings(AppSettings(telegramToken = "100:A$errorCode"))

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { firstRequestStarted.await() }
                fixture.saveSettings(AppSettings(telegramToken = "200:B$errorCode"))
                allowLateFailure.complete(Unit)
                eventually {
                    assertEquals("200:B$errorCode", sessionToken(currentSession(fixture.poller)))
                    coVerify(atLeast = 1) {
                        fixture.telegram.getUpdatesForToken("200:B$errorCode", -1, 0)
                    }
                }
                verify(exactly = 1) { fixture.agent.resetSession() }
                delay(100.milliseconds)
                assertEquals("200:B$errorCode", sessionToken(currentSession(fixture.poller)))
                verify(exactly = 1) { fixture.agent.resetSession() }
                assertFalse(fixture.barrier.isSwitching)
            } finally {
                allowLateFailure.complete(Unit)
                fixture.poller.closeAndJoin()
            }
        }
    }

    @Test
    fun `failed authentication reset blocks replacement until serialized retry succeeds`() = runBlocking {
        listOf(
            401 to null,
            403 to Job().apply { cancel() },
        ).forEach { (errorCode, initialReset) ->
            val initialResetStarted = CompletableDeferred<Unit>()
            val retryResetStarted = CompletableDeferred<Unit>()
            val allowRetryReset = Job()
            val resetCount = AtomicInteger()
            val fixture = fixture()
            fixture.updates.saveLastUpdateId("100", 10)
            coEvery { fixture.telegram.getUpdatesForToken("100:A$errorCode", 11, 30) } returns
                    GetUpdatesResponse(ok = false, errorCode = errorCode, description = "Unauthorized")
            coEvery { fixture.telegram.getUpdatesForToken("200:B$errorCode", -1, 0) } returns
                    GetUpdatesResponse(ok = true)
            every { fixture.agent.resetSession() } answers {
                if (resetCount.getAndIncrement() == 0) {
                    initialResetStarted.complete(Unit)
                    initialReset
                } else {
                    retryResetStarted.complete(Unit)
                    allowRetryReset
                }
            }
            fixture.saveSettings(AppSettings(telegramToken = "100:A$errorCode"))

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { initialResetStarted.await() }
                eventually {
                    assertNull(currentSessionOrNull(fixture.poller))
                    assertTrue(fixture.barrier.isSwitching)
                }

                fixture.saveSettings(AppSettings(telegramToken = "200:B$errorCode"))
                withTimeout(2.seconds) { retryResetStarted.await() }
                assertTrue(fixture.barrier.isSwitching)
                assertNull(currentSessionOrNull(fixture.poller))
                coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:B$errorCode", -1, 0) }
                val blockedRequest = async { fixture.barrier.runWhenReady { "admitted" } }
                delay(100.milliseconds)
                assertFalse(blockedRequest.isCompleted)

                allowRetryReset.complete()
                eventually {
                    assertEquals("200:B$errorCode", sessionToken(currentSession(fixture.poller)))
                    coVerify(atLeast = 1) {
                        fixture.telegram.getUpdatesForToken("200:B$errorCode", -1, 0)
                    }
                    assertFalse(fixture.barrier.isSwitching)
                }
                assertEquals("admitted", withTimeout(2.seconds) { blockedRequest.await() })
                verify(exactly = 2) { fixture.agent.resetSession() }
            } finally {
                allowRetryReset.complete()
                fixture.poller.closeAndJoin()
            }
        }
    }

    @Test
    fun `close cancels hanging authentication reset and releases barrier`() = runBlocking {
        val resetStarted = CompletableDeferred<Unit>()
        val resetJob = Job()
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns
                GetUpdatesResponse(ok = false, errorCode = 401, description = "Unauthorized")
        every { fixture.agent.resetSession() } answers {
            resetStarted.complete(Unit)
            resetJob
        }
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { resetStarted.await() }
            eventually {
                assertNull(currentSessionOrNull(fixture.poller))
                assertTrue(fixture.barrier.isSwitching)
            }

            fixture.poller.requestStop()
            withTimeout(2.seconds) { fixture.poller.awaitStopped() }

            assertTrue(resetJob.isCancelled)
            assertNull(currentSessionOrNull(fixture.poller))
            assertFalse(fixture.barrier.isSwitching)
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) }
        } finally {
            resetJob.cancel()
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `token rotation waits for active owner and never reenters agent`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val agentStarted = CompletableDeferred<Unit>()
        val releaseAgent = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:old",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:old", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "in-flight"))),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:new", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "in-flight"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken(any(), "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("in-flight") } coAnswers {
            agentStarted.complete(Unit)
            withContext(NonCancellable) { releaseAgent.await() }
            "late"
        }
        coEvery {
            fixture.telegram.sendMessageForToken("100:new", "123", "late", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { agentStarted.await() }
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:new",
                    ai = AISettings(agentEnabled = true, agentChatId = "123"),
                ),
            )
            delay(100.milliseconds)
            assertNull(currentSessionOrNull(fixture.poller))
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:new", any(), any()) }

            releaseAgent.complete(Unit)
            eventually {
                assertEquals("100:new", sessionToken(currentSession(fixture.poller)))
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("in-flight") }
                coVerify {
                    fixture.telegram.sendMessageForToken("100:new", "123", "late", ReplyParameters(1))
                }
            }
        } finally {
            releaseAgent.complete(Unit)
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `token switch while full update waits on barrier drops old batch without feedback`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        val blockingAgentStarted = CompletableDeferred<Unit>()
        val allowBlockingAgent = CompletableDeferred<Unit>()
        val barrierInstalled = CompletableDeferred<Unit>()
        val availabilityChecks = AtomicInteger()
        var switchGeneration = Long.MIN_VALUE
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:A",
                ai = AISettings(
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        every { fixture.agent.isAiFeatureEnabled(any()) } answers {
            if (availabilityChecks.incrementAndGet() == 11) {
                switchGeneration = fixture.barrier.beginExternalSwitch()
                barrierInstalled.complete(Unit)
            }
            true
        }
        val batch = (11L..22L).map { updateId ->
            Update(
                updateId,
                message = authorizedMessage(
                    updateId,
                    chat,
                    text = if (updateId == 11L) "block" else "queued-$updateId",
                ),
            )
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns
                GetUpdatesResponse(ok = true, result = batch)
        coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            blockingAgentStarted.complete(Unit)
            allowBlockingAgent.await()
            ""
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { blockingAgentStarted.await() }
            withTimeout(2.seconds) { barrierInstalled.await() }
            assertTrue(fixture.barrier.isSwitching)

            fixture.saveSettings(
                AppSettings(
                    telegramToken = "200:B",
                    ai = AISettings(
                        geminiApiKey = "test-key",
                        agentEnabled = true,
                        agentChatId = "123",
                    ),
                ),
            )
            eventually {
                assertEquals("200:B", sessionToken(currentSession(fixture.poller)))
            }
            fixture.barrier.complete(switchGeneration)
            delay(100.milliseconds)

            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 0) {
                fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
            }
        } finally {
            allowBlockingAgent.complete(Unit)
            if (switchGeneration != Long.MIN_VALUE) {
                fixture.barrier.complete(switchGeneration)
            }
            fixture.poller.closeAndJoin()
        }
    }

    @Test
    fun `requestStop rejects late active session work and await methods reach quiescence`() = runBlocking {
        val fixture = fixture()
        val requestStarted = CompletableDeferred<Unit>()
        val releaseLateResponse = CompletableDeferred<Unit>()
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:old",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:old", 11, 30) } coAnswers {
            requestStarted.complete(Unit)
            withContext(NonCancellable) { releaseLateResponse.await() }
            GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message = authorizedMessage(1, chat, text = "too-late"))),
            )
        }

        fixture.poller.start()
        withTimeout(2.seconds) { requestStarted.await() }
        val session = currentSession(fixture.poller)
        fixture.poller.requestStop()
        val stopped = async { fixture.poller.awaitStopped() }
        delay(100.milliseconds)
        assertFalse(stopped.isCompleted)
        assertNull(currentSessionOrNull(fixture.poller))
        assertTrue(sessionJob(session).isCancelled)

        fixture.saveSettings(
            AppSettings(
                telegramToken = "200:new",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        releaseLateResponse.complete(Unit)
        withTimeout(2.seconds) { stopped.await() }
        withTimeout(2.seconds) { fixture.poller.closeAndJoin() }
        withTimeout(2.seconds) { fixture.poller.awaitStopped() }

        assertEquals(10, fixture.updates.getData("100").lastUpdateId)
        coVerify(exactly = 0) { fixture.agent.sendMessage("too-late") }
        coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:new", any(), any()) }
    }
}
