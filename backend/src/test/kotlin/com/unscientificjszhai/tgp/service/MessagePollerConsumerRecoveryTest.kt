package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.Update
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerConsumerRecoveryTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `second stack overflow terminates session before retry and settles queued work`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(123L, "private")
        val pollStarted = CompletableDeferred<Unit>()
        val secondTurnStarted = CompletableDeferred<Unit>()
        val releaseSecondFailure = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            pollStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("first-overflow") } throws StackOverflowError("first injected failure")
        coEvery { fixture.agent.sendMessage("second-overflow") } coAnswers {
            secondTurnStarted.complete(Unit)
            releaseSecondFailure.await()
            throw StackOverflowError("second injected failure")
        }

        fixture.poller.start()
        withTestCleanup(cleanup = {
            releaseSecondFailure.complete(Unit)
            fixture.poller.closeAndJoin()
        }) {
            withTimeout(5.seconds) { pollStarted.await() }
            val session = currentSession(fixture.poller)
            val firstConsumer = assertNotNull(session.consumerJob)
            val first = assertIs<UpdateAdmission.Enqueued>(
                fixture.poller.enqueueForTesting(Update(11, message = authorizedMessage(1, chat, "first-overflow"))),
            )
            assertEquals(UpdateCompletion.Retry, withTimeout(5.seconds) { first.completion.await() })
            eventually {
                assertTrue(session.consumerRestartedAfterError)
                assertTrue(assertNotNull(session.consumerJob).isActive)
                assertTrue(session.consumerJob !== firstConsumer)
            }

            val second = assertIs<UpdateAdmission.Enqueued>(
                fixture.poller.enqueueForTesting(Update(12, message = authorizedMessage(2, chat, "second-overflow"))),
            )
            withTimeout(5.seconds) { secondTurnStarted.await() }
            val queued = assertIs<UpdateAdmission.Enqueued>(
                fixture.poller.enqueueForTesting(Update(13, message = authorizedMessage(3, chat, "healthy"))),
            )
            val cancelledBeforeRetry = CompletableDeferred<Boolean>()
            second.completion.invokeOnCompletion {
                cancelledBeforeRetry.complete(sessionJob(session).isCancelled)
            }
            releaseSecondFailure.complete(Unit)

            assertEquals(UpdateCompletion.Retry, withTimeout(5.seconds) { second.completion.await() })
            assertTrue(withTimeout(5.seconds) { cancelledBeforeRetry.await() })
            assertEquals(UpdateCompletion.Retry, withTimeout(5.seconds) { queued.completion.await() })
            withTimeout(5.seconds) { sessionJob(session).join() }
            assertNull(currentSessionOrNull(fixture.poller))
            assertTrue(assertNotNull(session.pollJob).isCancelled)
            assertEquals(
                UpdateAdmission.Confirmed, fixture.poller.enqueueForTesting(
                    Update(14, message = authorizedMessage(4, chat, "late-message")),
                )
            )
            coVerify(exactly = 1) { fixture.agent.sendMessage("first-overflow") }
            coVerify(exactly = 1) { fixture.agent.sendMessage("second-overflow") }
            coVerify(exactly = 0) { fixture.agent.sendMessage("healthy") }
            coVerify(exactly = 0) { fixture.agent.sendMessage("late-message") }
        }
    }

    private suspend fun MessagePoller.enqueueForTesting(update: Update): UpdateAdmission {
        val session = currentSessionOrNull(this) ?: return UpdateAdmission.Confirmed
        val policy = MessagePoller::class.java.getDeclaredField("admissionPolicy").apply { isAccessible = true }
            .get(this) as UpdateAdmissionPolicy
        return policy.enqueueUpdate(session, update)
    }
}
