package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerVoiceFailureTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `permanent voice failures are confirmed and following text reaches the agent`() = runBlocking {
        for (failure in listOf("message-size", "metadata-size", "get-file", "download", "download-size")) {
            val fixture = fixture()
            fixture.updates.saveLastUpdateId("100", 10)
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:token",
                    ai = AISettings(agentEnabled = true, agentChatId = "123")
                )
            )
            val chat = Chat(123, "private", firstName = "Test")
            val voice = Voice(
                "voice", "unique", 1,
                fileSize = if (failure == "message-size") 21 * 1024 * 1024L else null
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
                true, listOf(
                    Update(11, authorizedMessage(1, chat, voice = voice)),
                    Update(12, authorizedMessage(2, chat, text = "following"))
                )
            )
            val finished = CompletableDeferred<Unit>()
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 13, 30) } coAnswers {
                finished.complete(Unit)
                awaitCancellation()
            }
            coEvery { fixture.telegram.getFileForToken("100:token", "voice") } returns
                    if (failure == "get-file") FileResponse(false, errorCode = 400)
                    else FileResponse(
                        true, TelegramFile(
                            "voice",
                            "unique",
                            fileSize = if (failure == "metadata-size") 21 * 1024 * 1024L else null,
                            filePath = "voice.ogg"
                        )
                    )
            coEvery { fixture.telegram.downloadFileForToken("100:token", "voice.ogg") } throws
                    if (failure == "download") TelegramFileDownloadRejectedException() else TelegramPayloadTooLargeException()
            coEvery {
                fixture.telegram.sendMessageForToken(
                    any(),
                    any(),
                    any(),
                    any()
                )
            } coAnswers { awaitCancellation() }
            coEvery { fixture.telegram.sendChatActionForToken(any(), any(), any()) } returns mockk()
            coEvery { fixture.agent.sendMessage("following") } returns "reply"
            fixture.poller.start()
            withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
                withTimeout(5.seconds) { finished.await() }
                val state = fixture.updates.getData("100")
                assertEquals(12, state.lastUpdateId, failure)
                assertNull(state.retryCheckpoint, failure)
                assertTrue(
                    state.pendingTelegramReplies.any { it.updateId == 11L && it.text.contains("无法下载") },
                    failure
                )
                coVerify(exactly = 1) { fixture.agent.sendMessage("following") }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any(), match { it.isNotEmpty() }) }
                if (failure == "message-size") coVerify(exactly = 0) { fixture.telegram.getFileForToken(any(), any()) }
                if (failure in listOf("message-size", "metadata-size", "get-file"))
                    coVerify(exactly = 0) { fixture.telegram.downloadFileForToken(any(), any()) }
            }
        }
    }

    @Test
    fun `temporary voice failures keep retry checkpoint and do not confirm later text`() = runBlocking {
        for (failure in listOf("rate-limit", "server", "network")) {
            val retryStarted = CompletableDeferred<Unit>()
            val fixture = fixture(retryDelay = { retryStarted.complete(Unit); awaitCancellation() })
            fixture.updates.saveLastUpdateId("100", 10)
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:token",
                    ai = AISettings(agentEnabled = true, agentChatId = "123")
                )
            )
            val chat = Chat(123, "private", firstName = "Test")
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
                true, listOf(
                    Update(11, authorizedMessage(1, chat, voice = Voice("voice", "unique", 1))),
                    Update(12, authorizedMessage(2, chat, text = "following"))
                )
            )
            if (failure == "network") coEvery { fixture.telegram.getFileForToken(any(), any()) } throws IOException()
            else coEvery { fixture.telegram.getFileForToken(any(), any()) } returns
                    FileResponse(false, errorCode = if (failure == "rate-limit") 429 else 500)
            fixture.poller.start()
            withTestCleanup(cleanup = { fixture.poller.closeAndJoin() }) {
                withTimeout(5.seconds) { retryStarted.await() }
                assertEquals(10, fixture.updates.getData("100").lastUpdateId)
                assertEquals(11, fixture.updates.getData("100").retryCheckpoint?.targetUpdateId)
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 0) { fixture.agent.sendMessage(any<String>()) }
            }
        }
    }
}
