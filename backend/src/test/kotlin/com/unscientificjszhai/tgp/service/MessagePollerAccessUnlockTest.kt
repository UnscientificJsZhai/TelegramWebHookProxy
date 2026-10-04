package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilitySnapshot
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilityState
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class MessagePollerAccessUnlockTest : MessagePollerFacadeTestSupport() {
    @Test
    fun `blocked AI retains durable backlog while later batches unlock once`() = runBlocking {
        val fixture = fixture()
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:test",
                ai = AISettings(agentEnabled = true, agentChatId = "42")
            )
        )
        fixture.updates.saveLastUpdateId("100", 100)
        every { fixture.agent.availability } returns MutableStateFlow(
            AgentAvailabilitySnapshot(
                AgentAvailabilityState.BLOCKED,
                1,
                0
            )
        )
        val ordinary = (101L..120L).map { Update(it, authorizedMessage(it, Chat(42, "private"), "waiting")) }
        val unlock = Update(121, authorizedMessage(121, Chat(42, "private"), "/access_unlock"))
        val controlPolls = AtomicInteger()
        coEvery { fixture.telegram.getUpdatesForToken("100:test", 101, 30) } returns GetUpdatesResponse(
            true,
            ordinary.take(10)
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:test", 111, 0) } returns GetUpdatesResponse(
            true,
            ordinary.drop(10)
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:test", 121, 0) } returns GetUpdatesResponse(
            true,
            listOf(unlock)
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:test", 122, 0) } coAnswers {
            controlPolls.incrementAndGet()
            // 即使 Telegram 重放已消费的命令，也不重新创建文件。
            GetUpdatesResponse(true, listOf(unlock))
        }
        fixture.poller.start()
        try {
            eventually(8.seconds) { assertTrue(fixture.settings.accessControlOverride.isPresent()) }
            fixture.settings.accessControlOverride.delete()
            val before = controlPolls.get()
            eventually(5.seconds) { assertTrue(controlPolls.get() > before) }
            assertFalse(fixture.settings.accessControlOverride.isPresent())
            assertEquals(100, fixture.updates.getData("100").lastUpdateId)
            assertEquals(21, fixture.updates.getData("100").receivedUpdates.size)
            val reloaded = com.unscientificjszhai.tgp.repository.UpdatesRepository(
                tempDirectory.listFiles()!!.single { it.name.startsWith("updates-") })
            assertEquals(121, reloaded.getData("100").receivedThroughId)
            assertEquals(listOf(121L), reloaded.getData("100").consumedAccessUnlockIds)
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
        } finally {
            fixture.poller.requestStop(); fixture.poller.awaitStopped()
        }
    }

    @Test
    fun `model invocation stalled or switch barrier stalled does not stop control intake`() = runBlocking {
        for (switching in listOf(false, true)) {
            val fixture = fixture()
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:test",
                    ai = AISettings(agentEnabled = true, agentChatId = "42")
                )
            )
            fixture.updates.saveLastUpdateId("100", 100)
            coEvery { fixture.agent.sendMessage(any(), any()) } coAnswers { awaitCancellation() }
            if (switching) fixture.barrier.beginSwitch()
            coEvery { fixture.telegram.getUpdatesForToken("100:test", 101, 30) } returns GetUpdatesResponse(
                true, listOf(
                    Update(101, authorizedMessage(101, Chat(42, "private"), "stalled")),
                )
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:test", 102, 0) } returns GetUpdatesResponse(
                true, listOf(
                    Update(102, authorizedMessage(102, Chat(42, "private"), "/access_unlock")),
                )
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:test", 103, 0) } returns GetUpdatesResponse(true)
            fixture.settings.accessControlOverride.delete()
            fixture.poller.start()
            try {
                eventually(5.seconds) { assertTrue(fixture.settings.accessControlOverride.isPresent()) }
            } finally {
                fixture.poller.requestStop(); fixture.poller.awaitStopped(); fixture.settings.accessControlOverride.delete()
            }
        }
    }
}
