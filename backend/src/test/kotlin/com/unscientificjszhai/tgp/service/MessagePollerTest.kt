package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.FileResponse
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.Message
import com.unscientificjszhai.tgp.models.TelegramFile
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.models.Voice
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 消息轮询会话隔离与 token 生命周期的测试设计。
 */
class MessagePollerTest {
    private val tempDirectory = createTempDirectory("message-poller-test").toFile()
    private val parentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUp() {
        parentScope.cancel()
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证已启动轮询后被 StateFlow 合并的 `A → 空 → A` 仍会取消旧长轮询并创建新会话。
     */
    @Test
    fun `rapid token restoration after start cancels old long poll and starts a new generation`() = runBlocking {
        val fixture = fixture()
        val oldPollStarted = CompletableDeferred<Unit>()
        val oldPollCancelled = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        fixture.settings.saveSettings(AppSettings(telegramToken = "100:A"))
        fixture.updates.saveLastUpdateId("100", 7)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 8, 30) } coAnswers {
            oldPollStarted.complete(Unit)
            try {
                neverCompletes.await()
                GetUpdatesResponse(ok = true)
            } finally {
                oldPollCancelled.complete(Unit)
            }
        } andThen GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { oldPollStarted.await() }
            fixture.settings.saveSettings(AppSettings(telegramToken = ""))
            fixture.settings.saveSettings(AppSettings(telegramToken = "100:A"))
            eventually {
                assert(oldPollCancelled.isCompleted)
                coVerify(atLeast = 2) { fixture.telegram.getUpdatesForToken("100:A", 8, 30) }
            }
            assertEquals(3, fixture.settings.telegramTokenUpdateFlow.value.generation)
            verify(atLeast = 1) { fixture.agent.resetSession() }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证 A 与 B 切换时分别读取各自的偏移量，切回 A 后继续使用 A 的状态。
     */
    @Test
    fun `bot switches keep each bot offset isolated`() = runBlocking {
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", 4)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 5, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(20)),
        )
        coEvery { fixture.telegram.getUpdatesForToken("200:B", 21, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("100:A-rotated", 5, 30) } returns GetUpdatesResponse(ok = true)

        fixture.settings.saveSettings(AppSettings(telegramToken = "100:A"))
        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:A", 5, 30) } }
            fixture.settings.saveSettings(AppSettings(telegramToken = "200:B"))
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", 21, 30) } }
            fixture.settings.saveSettings(AppSettings(telegramToken = "100:A-rotated"))
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:A-rotated", 5, 30) } }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证 token 切换会取消在途工作，并且旧会话不确认其更新偏移量。
     */
    @Test
    fun `switching token does not acknowledge old in flight update`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val processingStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.settings.saveSettings(
            AppSettings(
                telegramToken = "100:A",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = Message(1, chat, text = "work")),
                Update(12, message = Message(2, chat, text = "queued")),
            ),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("work") } coAnswers {
            processingStarted.complete(Unit)
            neverCompletes.await()
            "unreachable"
        }
        coEvery { fixture.telegram.getUpdatesForToken("200:B", any(), any()) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { processingStarted.await() }
            fixture.settings.saveSettings(
                AppSettings(
                    telegramToken = "200:B",
                    ai = AISettings(agentEnabled = true, agentChatId = "123"),
                ),
            )
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", any(), any()) } }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 0) { fixture.agent.sendMessage("queued") }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证偏移量写入与 token 切换共享线性化边界，切换不会在旧写入中途生效。
     */
    @Test
    fun `token change waits for an in progress offset commit`() = runBlocking {
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("linear-settings.json"), ModelSwitchBarrier())
        val offsetWriteEntered = CompletableDeferred<Unit>()
        val allowOffsetWrite = CountDownLatch(1)
        val updates = UpdatesRepository(tempDirectory.resolve("linear-updates.json")) { state ->
            if (state.bots["100"]?.lastUpdateId == 11L && !offsetWriteEntered.isCompleted) {
                offsetWriteEntered.complete(Unit)
                allowOffsetWrite.await()
            }
        }
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates)
        updates.saveLastUpdateId("100", 10)
        settings.saveSettings(AppSettings(telegramToken = "100:A"))
        coEvery { telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11)),
        )
        coEvery { telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
        coEvery { telegram.getUpdatesForToken("200:B", 1, 30) } returns GetUpdatesResponse(ok = true)

        poller.start()
        try {
            withTimeout(2.seconds) { offsetWriteEntered.await() }
            val switch = async(Dispatchers.Default) {
                settings.saveSettings(AppSettings(telegramToken = "200:B"))
            }
            delay(100)
            assertFalse(switch.isCompleted)

            allowOffsetWrite.countDown()
            withTimeout(2.seconds) { switch.await() }

            assertEquals(11, updates.getData("100").lastUpdateId)
            assertEquals("200:B", settings.telegramTokenUpdateFlow.value.token)
        } finally {
            allowOffsetWrite.countDown()
            poller.close()
        }
    }

    /**
     * 验证轮询、聊天动作、正常回复和语音文件访问均固定使用会话 token。
     */
    @Test
    fun `all processed telegram calls use the session token`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.settings.saveSettings(
            AppSettings(
                telegramToken = "100:captured-token",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:captured-token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = Message(1, chat, text = "hello")),
                Update(
                    12,
                    message = Message(
                        2,
                        chat,
                        voice = Voice("voice-id", "voice-unique-id", duration = 1),
                    ),
                ),
            ),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:captured-token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("hello") } returns "reply"
        coEvery { fixture.telegram.getFileForToken("100:captured-token", "voice-id") } returns FileResponse(
            ok = true,
            result = TelegramFile("voice-id", "voice-unique-id", filePath = "voices/voice.ogg"),
        )
        coEvery { fixture.telegram.downloadFileForToken("100:captured-token", "voices/voice.ogg") } returns byteArrayOf(
            1
        )
        coEvery { fixture.agent.sendMessage(null, any()) } returns ""
        coEvery { fixture.telegram.sendMessageForToken("100:captured-token", "123", "reply", any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, "")

        fixture.poller.start()
        try {
            eventually {
                coVerify { fixture.telegram.getUpdatesForToken("100:captured-token", 11, 30) }
                coVerify { fixture.telegram.sendChatActionForToken("100:captured-token", "123", "typing") }
                coVerify { fixture.telegram.sendMessageForToken("100:captured-token", "123", "reply", any()) }
                coVerify { fixture.telegram.getFileForToken("100:captured-token", "voice-id") }
                coVerify { fixture.telegram.downloadFileForToken("100:captured-token", "voices/voice.ogg") }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证 AI 处理错误的反馈固定使用触发该会话的 token。
     */
    @Test
    fun `processing error feedback uses the session token`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.settings.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = Message(1, chat, text = "failure"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("failure") } throws IllegalStateException("agent failure")
        coEvery {
            fixture.telegram.sendMessageForToken("100:A", "123", match { it.startsWith("AI 处理消息时出错") }, any())
        } returns mockk()

        fixture.poller.start()
        try {
            eventually {
                coVerify {
                    fixture.telegram.sendMessageForToken(
                        "100:A",
                        "123",
                        match { it.startsWith("AI 处理消息时出错") },
                        any(),
                    )
                }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证处理超时的反馈固定使用触发该会话的 token。
     */
    @Test
    fun `processing timeout feedback uses the session token`() = runBlocking {
        val fixture = fixture(processingTimeout = 50.milliseconds)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.settings.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = Message(1, chat, text = "slow"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("slow") } coAnswers {
            delay(1.seconds)
            "late"
        }
        coEvery {
            fixture.telegram.sendMessageForToken("100:A", "123", "抱歉，该消息处理超时（超过10分钟）。", any())
        } returns mockk()

        fixture.poller.start()
        try {
            eventually {
                coVerify {
                    fixture.telegram.sendMessageForToken(
                        "100:A",
                        "123",
                        "抱歉，该消息处理超时（超过10分钟）。",
                        any(),
                    )
                }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证队列满提示使用旧会话 token，切换后不会改用新机器人的 token。
     */
    @Test
    fun `queue full feedback remains bound to the old session token`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val processingStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.settings.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("200:B", 1, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            processingStarted.complete(Unit)
            neverCompletes.await()
            "unreachable"
        }
        coEvery {
            fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
        } returns mockk()

        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } }
            fixture.poller.handleUpdate(Update(101, message = Message(101, chat, text = "block")))
            withTimeout(2.seconds) { processingStarted.await() }
            (102L..112L).forEach { updateId ->
                fixture.poller.handleUpdate(Update(updateId, message = Message(updateId, chat, text = "queued")))
            }
            eventually {
                coVerify {
                    fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
                }
            }

            fixture.settings.saveSettings(
                AppSettings(
                    telegramToken = "200:B",
                    ai = AISettings(agentEnabled = true, agentChatId = "123")
                )
            )
            coVerify(exactly = 0) {
                fixture.telegram.sendMessageForToken("200:B", "123", match { it.contains("处理队列已满") }, any())
            }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证模型选择不会通过复制带有未知历史代理的设置而静默删除该代理配置。
     */
    @Test
    fun `model selection does not overwrite an unresolved historical proxy`() = runBlocking {
        val configFile = tempDirectory.resolve("historical-proxy-model.json")
        val originalContent =
            """
            {"telegramToken":"100:token","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"},"ai":{"agentEnabled":true,"selectedModel":""}}
            """.trimIndent()
        configFile.writeText(originalContent)
        val settings = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
        val updates = UpdatesRepository(tempDirectory.resolve("historical-proxy-model-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates)
        every { agent.availableModels } returns listOf("model")
        coEvery { telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery {
            telegram.sendMessageForToken(
                any(),
                any(),
                any(),
                any()
            )
        } returns TelegramApiResponse(HttpStatusCode.OK, "")

        poller.start()
        try {
            eventually { coVerify(atLeast = 1) { telegram.getUpdatesForToken("100:token", any(), any()) } }
            poller.handleCommand("123", "/model model", 1)

            assertEquals("", settings.settingsFlow.value.ai?.selectedModel)
            assertTrue(settings.hasHistoricalInvalidProxy)
            assertEquals(originalContent, configFile.readText())
        } finally {
            poller.close()
        }
    }

    private fun fixture(processingTimeout: Duration? = null): Fixture {
        val settings = SettingsRepository.forTesting(
            tempDirectory.resolve("settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier()
        )
        val updates = UpdatesRepository(tempDirectory.resolve("updates-${System.nanoTime()}.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        return Fixture(
            settings = settings,
            updates = updates,
            telegram = telegram,
            agent = agent,
            poller = processingTimeout?.let { timeout ->
                MessagePoller(parentScope, telegram, agent, settings, updates, timeout)
            } ?: MessagePoller(parentScope, telegram, agent, settings, updates),
        )
    }

    private suspend fun eventually(assertion: () -> Unit) {
        withTimeout(3.seconds) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    delay(20)
                }
            }
        }
    }

    private data class Fixture(
        val settings: SettingsRepository,
        val updates: UpdatesRepository,
        val telegram: TelegramService,
        val agent: AgentService,
        val poller: MessagePoller,
    )
}
