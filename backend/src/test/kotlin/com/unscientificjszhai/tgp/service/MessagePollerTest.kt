package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.FileResponse
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.Message
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.TelegramFile
import com.unscientificjszhai.tgp.models.TelegramResponseParameters
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.models.User
import com.unscientificjszhai.tgp.models.Voice
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.AudioTranscriptionFailedException
import com.unscientificjszhai.tgp.service.ai.agent.AudioTranscriptionTooLargeException
import com.unscientificjszhai.tgp.service.ai.agent.DelegatingAgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import com.unscientificjszhai.tgp.di.AgentComponent
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))
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
            fixture.saveSettings(AppSettings(telegramToken = ""))
            fixture.saveSettings(AppSettings(telegramToken = "100:A"))
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

        fixture.saveSettings(AppSettings(telegramToken = "100:A"))
        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:A", 5, 30) } }
            fixture.saveSettings(AppSettings(telegramToken = "200:B"))
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", 21, 30) } }
            fixture.saveSettings(AppSettings(telegramToken = "100:A-rotated"))
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
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:A",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, text = "work")),
                Update(12, message = authorizedMessage(2, chat, text = "queued")),
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
            fixture.saveSettings(
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
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("linear-settings.json"), barrier)
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
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
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
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:captured-token",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:captured-token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, text = "hello")),
                Update(
                    12,
                    message = authorizedMessage(
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
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "failure"))),
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
     * 验证转写失败和超大语音会返回稳定提示，且不向 Telegram 回显提供商或底层错误内容。
     */
    @Test
    fun `voice transcription errors return safe feedback`() = runBlocking {
        listOf<Exception>(
            AudioTranscriptionFailedException(IllegalStateException("provider response must stay private")),
            AudioTranscriptionTooLargeException(),
        ).zip(
            listOf(
                "语音转写失败，请稍后重试。",
                "语音文件过大，最大支持 24 MiB，请发送更短的语音消息。",
            ),
        ).forEach { (failure, expectedReply) ->
            val fixture = fixture()
            val chat = Chat(id = 123L, type = "private", firstName = "Test")
            fixture.updates.saveLastUpdateId("100", 10)
            fixture.saveSettings(
                AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(
                        11,
                        message = authorizedMessage(
                            1,
                            chat,
                            voice = Voice("voice-id", "voice-unique-id", duration = 1),
                        ),
                    ),
                ),
            ) andThen GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
            coEvery { fixture.telegram.getFileForToken("100:A", "voice-id") } returns FileResponse(
                ok = true,
                result = TelegramFile("voice-id", "voice-unique-id", filePath = "voices/voice.ogg"),
            )
            coEvery { fixture.telegram.downloadFileForToken("100:A", "voices/voice.ogg") } returns byteArrayOf(1)
            coEvery { fixture.agent.sendMessage(null, any()) } throws failure
            coEvery {
                fixture.telegram.sendMessageForToken("100:A", "123", expectedReply, any())
            } returns mockk()

            fixture.poller.start()
            try {
                eventually {
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken("100:A", "123", expectedReply, any())
                    }
                    coVerify(exactly = 0) {
                        fixture.telegram.sendMessageForToken(
                            "100:A",
                            "123",
                            match { it.contains("provider response must stay private") },
                            any(),
                        )
                    }
                }
            } finally {
                fixture.poller.close()
            }
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
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "slow"))),
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
        fixture.saveSettings(
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
        } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } }
            fixture.poller.enqueueUpdateForTesting(Update(101, message = authorizedMessage(101, chat, text = "block")))
            withTimeout(2.seconds) { processingStarted.await() }
            (102L..112L).forEach { updateId ->
                fixture.poller.enqueueUpdateForTesting(
                    Update(
                        updateId,
                        message = authorizedMessage(updateId, chat, text = "queued")
                    )
                )
            }
            eventually {
                coVerify {
                    fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
                }
            }

            fixture.saveSettings(
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
     * 验证通知抛错、非成功状态、`ok:false` 或空/畸形正文都会保留被拒绝更新及后缀以便重试。
     */
    @Test
    fun `unaccepted queue full feedback retries rejected update after committing admitted prefix`() = runBlocking {
        for (notificationResponse in listOf(
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":false}"""),
            TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":true}"""),
            TelegramApiResponse(HttpStatusCode.OK, ""),
            TelegramApiResponse(HttpStatusCode.OK, "not-json"),
        )) {
            assertUnacceptedQueueFeedbackRetries(notificationResponse)
        }
        assertUnacceptedQueueFeedbackRetries(notificationFailure = IllegalStateException("notification unavailable"))
    }

    /** 验证仅 HTTP `2xx` 且 API `ok:true` 的队满提示才会确认被拒绝更新和其后缀。 */
    @Test
    fun `accepted queue full feedback confirms rejected update and suffix`() = runBlocking {
        val firstBatchRequested = CompletableDeferred<Unit>()
        val allowFirstBatch = CompletableDeferred<Unit>()
        val blockStarted = CompletableDeferred<Unit>()
        val allowBlockToFinish = CompletableDeferred<Unit>()
        val nextPollRequested = CompletableDeferred<Unit>()
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } coAnswers {
            firstBatchRequested.complete(Unit)
            allowFirstBatch.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(11, message = authorizedMessage(11, chat, text = "prefix")),
                    Update(12, message = authorizedMessage(12, chat, text = "rejected")),
                    Update(13, message = authorizedMessage(13, chat, text = "suffix")),
                ),
            )
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 14, 30) } coAnswers {
            nextPollRequested.complete(Unit)
            GetUpdatesResponse(ok = true)
        }
        coEvery { fixture.agent.sendMessage(any()) } returns ""
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            blockStarted.complete(Unit)
            allowBlockToFinish.await()
            ""
        }
        coEvery {
            fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
        } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { firstBatchRequested.await() }
            fixture.poller.enqueueUpdateForTesting(Update(100, message = authorizedMessage(100, chat, text = "block")))
            withTimeout(2.seconds) { blockStarted.await() }
            (101L..109L).forEach { updateId ->
                fixture.poller.enqueueUpdateForTesting(
                    Update(
                        updateId,
                        message = authorizedMessage(updateId, chat, text = "queued-$updateId")
                    )
                )
            }
            allowFirstBatch.complete(Unit)
            eventually {
                coVerify(exactly = 2) {
                    fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
                }
            }

            allowBlockToFinish.complete(Unit)
            eventually {
                assertEquals(13, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("prefix") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("rejected") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("suffix") }
            }
            withTimeout(3.seconds) { nextPollRequested.await() }
        } finally {
            allowFirstBatch.complete(Unit)
            allowBlockToFinish.complete(Unit)
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
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(configFile, barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("historical-proxy-model-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
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

    /**
     * 验证旧代理的模型列表不会在提供方并发切换后污染最新设置。
     */
    @Test
    fun `model selection rechecks provider configuration inside repository lock`() = runBlocking {
        val fixture = fixture()
        val geminiSettings = AppSettings(
            telegramToken = "100:token",
            ai = AISettings(
                provider = AIProvider.GEMINI,
                geminiApiKey = "gemini-key",
                agentEnabled = true,
            ),
        )
        fixture.saveSettings(geminiSettings)
        coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, "")
        every { fixture.agent.availableModels } answers {
            fixture.settings.updateSettings { current ->
                current.copy(
                    ai = current.ai!!.copy(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "openai-key",
                    ),
                )
            }
            listOf("models/gemini-old")
        }

        fixture.poller.start()
        try {
            eventually {
                coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) }
            }
            fixture.poller.handleCommand("123", "/model models/gemini-old", 1)

            assertEquals(AIProvider.OPENAI, fixture.settings.settingsFlow.value.ai?.provider)
            assertEquals("", fixture.settings.settingsFlow.value.ai?.selectedModel)
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证启动仅在共享初始就绪屏障放行后才订阅 token 流。
     *
     * 有效初始 AI 配置尚在就绪时，不会创建轮询会话或发起 Telegram 请求。
     */
    @Test
    fun `start waits for the shared initial readiness barrier before polling`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("initial-readiness-settings.json"), barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("initial-readiness-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        every { agent.isAiFeatureEnabled(any()) } returns true
        settings.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "valid-key",
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        barrier.complete(barrier.latestPendingGeneration())
        val initialGeneration = barrier.beginSwitch()
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        coEvery { telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)

        poller.start()
        try {
            delay(100)
            coVerify(exactly = 0) { telegram.getUpdatesForToken(any(), any(), any()) }

            barrier.complete(initialGeneration)
            eventually {
                coVerify(atLeast = 1) { telegram.getUpdatesForToken("100:token", any(), any()) }
            }
        } finally {
            barrier.complete(initialGeneration)
            poller.close()
        }
    }

    /**
     * 验证设置仓储、委派服务和轮询器共享的初始屏障会阻止有效配置在候选 Agent 就绪前轮询。
     */
    @Test
    fun `delegating initial readiness blocks polling until the candidate agent is ready`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("shared-readiness-settings.json"), barrier)
        val skills = SkillRepository.forTesting(tempDirectory.resolve("shared-readiness-skills.json"))
        val updates = UpdatesRepository(tempDirectory.resolve("shared-readiness-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val componentFactory = mockk<AgentComponent.Factory>()
        val component = mockk<AgentComponent>()
        val candidateAgent = mockk<OpenAIAgentService>()
        val candidateCreated = CompletableDeferred<Unit>()
        val readiness = Job()
        val delegatingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        updates.saveLastUpdateId("100", 10)
        settings.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "valid-key",
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        every { componentFactory.create() } answers {
            candidateCreated.complete(Unit)
            component
        }
        every { component.openAIAgentService } returns candidateAgent
        every { candidateAgent.initializationJob() } returns readiness
        every { candidateAgent.close() } returns Job().apply { complete() }
        coEvery { telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(ok = true)

        val delegatingAgent = DelegatingAgentService(componentFactory, settings, skills, barrier, delegatingScope)
        val poller = MessagePoller(parentScope, telegram, delegatingAgent, settings, updates, barrier)
        poller.start()
        try {
            withTimeout(5.seconds) { candidateCreated.await() }
            delay(100)
            coVerify(exactly = 0) { telegram.getUpdatesForToken(any(), any(), any()) }
            assertEquals(10, updates.getData("100").lastUpdateId)

            readiness.complete()
            eventually {
                coVerify(atLeast = 1) { telegram.getUpdatesForToken("100:token", 11, 30) }
            }
        } finally {
            readiness.complete()
            poller.close()
            delegatingAgent.close().join()
            delegatingScope.cancel()
            delegatingScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证提供商凭据或基础地址轮换已写入设置、但候选委派代理尚未发布时，轮询器不会以旧代理的
     * `isAiFeatureEnabled` 结果确认更新；候选就绪后同一更新只会被处理和提交一次。
     */
    @Test
    fun `delegating rotation keeps an update pending until the replacement agent is published`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("delegating-rotation-settings.json"), barrier)
        val skills = SkillRepository.forTesting(tempDirectory.resolve("delegating-rotation-skills.json"))
        val updates = UpdatesRepository(tempDirectory.resolve("delegating-rotation-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val componentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val replacementComponent = mockk<AgentComponent>()
        val oldAgent = mockk<OpenAIAgentService>()
        val replacementAgent = mockk<OpenAIAgentService>()
        val firstPollStarted = CompletableDeferred<Unit>()
        val allowUpdate = CompletableDeferred<Unit>()
        val replacementCreated = CompletableDeferred<Unit>()
        val replacementReady = Job()
        val delegatingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val oldAi = AISettings(
            provider = AIProvider.OPENAI,
            openAiApiKey = "old-key",
            openAiBaseUrl = "https://old.example/v1",
            agentEnabled = true,
            agentChatId = "123",
        )
        val replacementAi = oldAi.copy(
            openAiApiKey = "replacement-key",
            openAiBaseUrl = "https://replacement.example/v1",
        )
        updates.saveLastUpdateId("100", 10)
        settings.saveSettings(AppSettings(telegramToken = "100:token", ai = oldAi))
        var componentCreationCount = 0
        every { componentFactory.create() } answers {
            if (componentCreationCount++ == 0) {
                oldComponent
            } else {
                replacementCreated.complete(Unit)
                replacementComponent
            }
        }
        every { oldComponent.openAIAgentService } returns oldAgent
        every { replacementComponent.openAIAgentService } returns replacementAgent
        every { oldAgent.initializationJob() } returns Job().apply { complete() }
        every { replacementAgent.initializationJob() } returns replacementReady
        every { oldAgent.close() } returns Job().apply { complete() }
        every { replacementAgent.close() } returns Job().apply { complete() }
        every { oldAgent.isAiFeatureEnabled(any()) } returns true
        every { replacementAgent.isAiFeatureEnabled(any()) } returns true
        coEvery { replacementAgent.sendMessage("rotated", any()) } returns ""
        coEvery { telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            firstPollStarted.complete(Unit)
            allowUpdate.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(
                        11,
                        message = authorizedMessage(
                            messageId = 1,
                            chat = Chat(id = 123L, type = "private", firstName = "Authorized"),
                            text = "rotated",
                        ),
                    ),
                ),
            )
        }
        coEvery { telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)

        val delegatingAgent = DelegatingAgentService(componentFactory, settings, skills, barrier, delegatingScope)
        val poller = MessagePoller(parentScope, telegram, delegatingAgent, settings, updates, barrier)
        poller.start()
        try {
            eventually { assertTrue(delegatingAgent.isAiFeatureEnabled(oldAi)) }
            withTimeout(2.seconds) { firstPollStarted.await() }
            settings.updateSettings { current -> current.copy(ai = replacementAi) }
            withTimeout(2.seconds) { replacementCreated.await() }

            allowUpdate.complete(Unit)
            delay(100)
            assertEquals(10, updates.getData("100").lastUpdateId)
            coVerify(exactly = 0) { oldAgent.sendMessage(any(), any()) }
            coVerify(exactly = 0) { replacementAgent.sendMessage(any(), any()) }

            replacementReady.complete()
            eventually {
                assertEquals(11, updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { replacementAgent.sendMessage("rotated", any()) }
                coVerify(exactly = 0) { oldAgent.sendMessage(any(), any()) }
            }
        } finally {
            allowUpdate.complete(Unit)
            replacementReady.complete()
            poller.close()
            delegatingAgent.close().join()
            delegatingScope.cancel()
            delegatingScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证缺少提供商密钥时不会将更新发送给不可用的代理。
     *
     * 屏障会正常放行，使轮询器能按禁用 AI 的语义跳过该更新而不发送错误回复。
     */
    @Test
    fun `missing initial API key does not dispatch messages to an unavailable agent`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("missing-key-settings.json"), barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("missing-key-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        updates.saveLastUpdateId("100", 10)
        settings.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        barrier.complete(barrier.latestPendingGeneration())
        coEvery { telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "hello"))),
        ) andThen GetUpdatesResponse(ok = true)

        poller.start()
        try {
            eventually {
                assertEquals(11, updates.getData("100").lastUpdateId)
            }
            verify(exactly = 0) { agent.isAiFeatureEnabled(any()) }
            coVerify(exactly = 0) { agent.sendMessage("hello") }
            coVerify(exactly = 0) { telegram.sendMessageForToken(any(), any(), any(), any()) }
        } finally {
            poller.close()
        }
    }

    /** 验证显式禁用 Agent 时确认更新，但不检查暂时不可用的代理。 */
    @Test
    fun `disabled agent confirms an authorized update without checking availability`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = false, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "disabled"))),
        ) andThen GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            eventually { assertEquals(11, fixture.updates.getData("100").lastUpdateId) }
            verify(exactly = 0) { fixture.agent.isAiFeatureEnabled(any()) }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证更新在切换屏障内等待时不会被旧设置确认；放行后必须重新读取已发布的 AI 设置并处理同一更新。
     */
    @Test
    fun `admission waits for the barrier then rereads published AI settings`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        val updateRequestStarted = CompletableDeferred<Unit>()
        val allowUpdate = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            updateRequestStarted.complete(Unit)
            allowUpdate.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message = authorizedMessage(1, chat, text = "after-switch"))),
            )
        }
        coEvery { fixture.agent.sendMessage("after-switch") } returns ""

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { updateRequestStarted.await() }
            fixture.barrier.beginSwitch()
            allowUpdate.complete(Unit)

            delay(100)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }

            fixture.settings.updateSettings { current ->
                current.copy(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "published-key",
                        agentEnabled = true,
                        agentChatId = "123",
                    ),
                )
            }
            fixture.barrier.completeThrough(fixture.barrier.latestPendingGeneration())

            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("after-switch") }
            }
        } finally {
            allowUpdate.complete(Unit)
            fixture.barrier.completeThrough(fixture.barrier.latestPendingGeneration())
            fixture.poller.close()
        }
    }

    /**
     * 验证已稳定发布的有效设置若代理仍不可用，会保留相同偏移量重试而不会进入 Agent 调用路径。
     */
    @Test
    fun `stable unavailable agent retries an authorized update without confirming its offset`() = runBlocking {
        val retryStarted = CompletableDeferred<Duration>()
        val allowRetry = CompletableDeferred<Unit>()
        val fixture = fixture(retryDelay = { delayDuration ->
            retryStarted.complete(delayDuration)
            allowRetry.await()
        })
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "configured-key",
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        every { fixture.agent.isAiFeatureEnabled(any()) } returns false
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "retry-me"))),
        )

        fixture.poller.start()
        try {
            assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            verify(atLeast = 1) { fixture.agent.isAiFeatureEnabled(any()) }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
        } finally {
            allowRetry.complete(Unit)
            fixture.poller.close()
        }
    }

    /**
     * 验证队列已满的更新在切换屏障关闭时不会提前通知或确认；屏障放行后若通知未被 Telegram 接受，
     * 轮询必须保留相同偏移量重试。
     */
    @Test
    fun `queue full feedback waits for the barrier and retries when Telegram rejects it`() = runBlocking {
        val retryStarted = CompletableDeferred<Duration>()
        val allowRetry = CompletableDeferred<Unit>()
        val fixture = fixture(retryDelay = { delayDuration ->
            retryStarted.complete(delayDuration)
            allowRetry.await()
        })
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        val pollRequestStarted = CompletableDeferred<Unit>()
        val allowPollUpdate = CompletableDeferred<Unit>()
        val blockingAgentStarted = CompletableDeferred<Unit>()
        val allowBlockingAgent = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            pollRequestStarted.complete(Unit)
            allowPollUpdate.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message = authorizedMessage(11, chat, text = "full"))),
            )
        }
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            blockingAgentStarted.complete(Unit)
            allowBlockingAgent.await()
            ""
        }
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", match { it.contains("处理队列已满") }, any())
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":true}""")

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { pollRequestStarted.await() }
            fixture.poller.enqueueUpdateForTesting(Update(100, message = authorizedMessage(100, chat, text = "block")))
            withTimeout(2.seconds) { blockingAgentStarted.await() }
            (101L..110L).forEach { updateId ->
                fixture.poller.enqueueUpdateForTesting(
                    Update(
                        updateId,
                        message = authorizedMessage(updateId, chat, text = "queued")
                    )
                )
            }

            val switchGeneration = fixture.barrier.beginSwitch()
            allowPollUpdate.complete(Unit)
            delay(100)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 0) {
                fixture.telegram.sendMessageForToken("100:token", "123", match { it.contains("处理队列已满") }, any())
            }

            fixture.barrier.complete(switchGeneration)
            eventually {
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken(
                        "100:token",
                        "123",
                        match { it.contains("处理队列已满") },
                        any(),
                    )
                }
            }
            assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
        } finally {
            allowPollUpdate.complete(Unit)
            allowBlockingAgent.complete(Unit)
            allowRetry.complete(Unit)
            fixture.poller.close()
        }
    }

    /**
     * 验证 token 在屏障等待期间切换时，旧会话即使其队列已满也不会推进旧偏移量或发送旧 bot 的队满提示。
     */
    @Test
    fun `token switch during barrier wait drops the old full queue without acknowledgement or feedback`() =
        runBlocking {
            val fixture = fixture()
            val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
            val pollRequestStarted = CompletableDeferred<Unit>()
            val allowPollUpdate = CompletableDeferred<Unit>()
            val blockingAgentStarted = CompletableDeferred<Unit>()
            val allowBlockingAgent = CompletableDeferred<Unit>()
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
            coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } coAnswers {
                pollRequestStarted.complete(Unit)
                allowPollUpdate.await()
                GetUpdatesResponse(
                    ok = true,
                    result = listOf(Update(11, message = authorizedMessage(11, chat, text = "old-full"))),
                )
            }
            coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.agent.sendMessage("block") } coAnswers {
                blockingAgentStarted.complete(Unit)
                allowBlockingAgent.await()
                ""
            }

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { pollRequestStarted.await() }
                fixture.poller.enqueueUpdateForTesting(
                    Update(
                        100,
                        message = authorizedMessage(100, chat, text = "block")
                    )
                )
                withTimeout(2.seconds) { blockingAgentStarted.await() }
                (101L..110L).forEach { updateId ->
                    fixture.poller.enqueueUpdateForTesting(
                        Update(
                            updateId,
                            message = authorizedMessage(updateId, chat, text = "queued")
                        )
                    )
                }

                val switchGeneration = fixture.barrier.beginSwitch()
                allowPollUpdate.complete(Unit)
                delay(100)
                coVerify(exactly = 0) {
                    fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
                }

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
                delay(100)

                assertEquals(10, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 0) {
                    fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
                }
            } finally {
                allowPollUpdate.complete(Unit)
                allowBlockingAgent.complete(Unit)
                fixture.barrier.completeThrough(fixture.barrier.latestPendingGeneration())
                fixture.poller.close()
            }
        }

    /**
     * 验证仅由授权用户发出的私聊更新才能进入 AI、语音下载或命令处理路径。
     *
     * 群组和超级群组即使发送者标识恰好等于授权标识也必须被拒绝；缺少发送者或发送者不匹配的私聊
     * 也必须被拒绝。
     */
    @Test
    fun `only authorized private updates can trigger agent processing`() = runBlocking {
        val fixture = fixture()
        val privateChat = Chat(id = 123L, type = "private", firstName = "Authorized")
        val groupChat = Chat(id = 123L, type = "group", title = "Group")
        val supergroupChat = Chat(id = 123L, type = "supergroup", title = "Supergroup")
        val untrustedUpdates = listOf(
            Update(101, message = authorizedMessage(1, groupChat, text = "group text")),
            Update(102, message = authorizedMessage(2, supergroupChat, text = "supergroup text")),
            Update(103, message = authorizedMessage(3, privateChat, text = "missing sender", from = null)),
            Update(
                104,
                message = authorizedMessage(
                    4,
                    privateChat,
                    text = "different sender",
                    from = User(id = 456L, isBot = false, firstName = "Other"),
                ),
            ),
            Update(
                105,
                message = authorizedMessage(
                    5,
                    groupChat,
                    voice = Voice("group-voice", "group-voice-unique", duration = 1),
                ),
            ),
            Update(106, message = authorizedMessage(6, groupChat, text = "/reset")),
            Update(107, message = authorizedMessage(7, groupChat, text = "/model model")),
        )
        fixture.updates.saveLastUpdateId("100", 100)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123"),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 101, 30) } returns GetUpdatesResponse(
            ok = true,
            result = untrustedUpdates,
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 108, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            eventually {
                assertEquals(107, fixture.updates.getData("100").lastUpdateId)
            }

            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.agent.sendMessage(null, any()) }
            verify(exactly = 0) { fixture.agent.resetSession() }
            verify(exactly = 0) { fixture.agent.availableModels }
            verify(exactly = 0) { fixture.agent.switchModel(any()) }
            coVerify(exactly = 0) { fixture.telegram.getFileForToken(any(), any()) }
            coVerify(exactly = 0) { fixture.telegram.downloadFileForToken(any(), any()) }
            coVerify(exactly = 0) { fixture.telegram.sendChatActionForToken(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
            assertEquals("", fixture.settings.settingsFlow.value.ai?.selectedModel)
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证等待初始就绪屏障期间关闭服务不会在随后放行时创建会话。
     */
    @Test
    fun `close while waiting for readiness never creates a polling session`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("close-race-settings.json"), barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("close-race-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val generation = barrier.beginSwitch()
        settings.saveSettings(AppSettings(telegramToken = "100:token"))
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)

        poller.start()
        poller.close()
        barrier.complete(generation)
        delay(100)

        coVerify(exactly = 0) { telegram.getUpdatesForToken(any(), any(), any()) }
    }

    /**
     * 验证 `/reset` 的候选会话任务取消时只发送失败提示，不清空当前队列或自动清理计时。
     */
    @Test
    fun `reset command keeps queue and timer when reset job is cancelled`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val processingStarted = CompletableDeferred<Unit>()
        val keepProcessing = CompletableDeferred<Unit>()
        val cancelledReset = Job().apply { cancel() }
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            processingStarted.complete(Unit)
            keepProcessing.await()
            ""
        }
        every { fixture.agent.resetSession() } returns cancelledReset
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, "")

        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } }
            fixture.poller.enqueueUpdateForTesting(Update(101, message = authorizedMessage(1, chat, text = "block")))
            withTimeout(2.seconds) { processingStarted.await() }
            fixture.poller.enqueueUpdateForTesting(Update(102, message = authorizedMessage(2, chat, text = "queued")))
            val session = currentSession(fixture.poller)
            val lastReplyAt = 1234L
            session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply { isAccessible = true }
                .set(session, lastReplyAt)

            fixture.poller.handleCommand("123", "/reset", 99)

            assertEquals(lastReplyAt, session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply {
                isAccessible = true
            }.get(session))
            assertNotNull(sessionQueue(session).tryReceive().getOrNull())
            coVerify {
                fixture.telegram.sendMessageForToken("100:token", "123", "会话重置失败，请稍后重试。", any())
            }
            coVerify(exactly = 0) {
                fixture.telegram.sendMessageForToken("100:token", "123", "会话已重置，待处理消息已清空。", any())
            }
        } finally {
            keepProcessing.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证 `/reset` 仅在任务正常完成且会话仍有效时清空队列、清除计时并通知成功。
     */
    @Test
    fun `reset command clears queue and timer only after successful reset job`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val processingStarted = CompletableDeferred<Unit>()
        val keepProcessing = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            processingStarted.complete(Unit)
            keepProcessing.await()
            ""
        }
        every { fixture.agent.resetSession() } returns Job().apply { complete() }
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, "")

        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } }
            fixture.poller.enqueueUpdateForTesting(Update(101, message = authorizedMessage(1, chat, text = "block")))
            withTimeout(2.seconds) { processingStarted.await() }
            fixture.poller.enqueueUpdateForTesting(Update(102, message = authorizedMessage(2, chat, text = "queued")))
            val session = currentSession(fixture.poller)
            session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply { isAccessible = true }.set(session, 1234L)

            fixture.poller.handleCommand("123", "/reset", 99)

            assertNull(session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply { isAccessible = true }
                .get(session))
            assertTrue(sessionQueue(session).tryReceive().isFailure)
            coVerify {
                fixture.telegram.sendMessageForToken("100:token", "123", "会话已重置，待处理消息已清空。", any())
            }
        } finally {
            keepProcessing.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证失败响应使用一次退避，遵循 `retry_after`、指数增长和成功后的计数重置规则。
     */
    @Test
    fun `polling failures use retry after exponential backoff and reset after success`() = runBlocking {
        val observedDelays = mutableListOf<Duration>()
        val sixthDelayObserved = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = { duration ->
                synchronized(observedDelays) {
                    observedDelays += duration
                    if (observedDelays.size == 6) {
                        sixthDelayObserved.complete(Unit)
                    }
                }
            },
            retryJitter = { localBackoff -> localBackoff / 2 },
        )
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 429,
            parameters = TelegramResponseParameters(retryAfter = 3),
        ) andThen GetUpdatesResponse(
            ok = false,
            errorCode = 429,
        ) andThen GetUpdatesResponse(
            ok = false,
            errorCode = 429,
            parameters = TelegramResponseParameters(retryAfter = 0),
        ) andThen GetUpdatesResponse(
            ok = false,
            errorCode = 409,
            description = "Conflict: terminated by other getUpdates request",
        ) andThen GetUpdatesResponse(
            ok = false,
            errorCode = 500,
        ) andThen GetUpdatesResponse(ok = true) andThen GetUpdatesResponse(
            ok = false,
            errorCode = 500,
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(3.seconds) { sixthDelayObserved.await() }
            assertEquals(
                listOf(
                    3500.milliseconds,
                    3.seconds,
                    6.seconds,
                    12.seconds,
                    24.seconds,
                    1500.milliseconds,
                ),
                synchronized(observedDelays) { observedDelays.take(6) },
            )
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证连续可重试失败的本地指数退避饱和在 60 秒，而不会无限增长。
     */
    @Test
    fun `polling failure backoff caps at sixty seconds`() = runBlocking {
        val observedDelays = mutableListOf<Duration>()
        val seventhDelayStarted = CompletableDeferred<Unit>()
        val keepSeventhDelay = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = { duration ->
                observedDelays += duration
                if (observedDelays.size == 7) {
                    seventhDelayStarted.complete(Unit)
                    keepSeventhDelay.await()
                }
            },
        )
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 500,
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { seventhDelayStarted.await() }
            assertEquals(
                listOf(1.seconds, 2.seconds, 4.seconds, 8.seconds, 16.seconds, 32.seconds, 60.seconds),
                observedDelays,
            )
        } finally {
            keepSeventhDelay.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证网络异常进入与 API 失败相同的可取消退避路径，且不推进偏移量。
     */
    @Test
    fun `network polling failures retry without changing the offset`() = runBlocking {
        val observedDelay = CompletableDeferred<Duration>()
        val keepRetrying = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = { duration ->
                observedDelay.complete(duration)
                keepRetrying.await()
            },
        )
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } throws SocketTimeoutException("timeout")
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            assertEquals(1.seconds, withTimeout(2.seconds) { observedDelay.await() })
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) }
        } finally {
            keepRetrying.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证初始化请求失败不会写入偏移量，并在下一轮仍使用初始化请求而非正常长轮询。
     */
    @Test
    fun `failed initial polling retries initialization without changing the offset`() = runBlocking {
        val retryDelays = mutableListOf<Duration>()
        val normalPollStarted = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = { duration -> retryDelays += duration },
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", -1, 0) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 500,
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 1, 30) } coAnswers {
            normalPollStarted.complete(Unit)
            neverCompletes.await()
            GetUpdatesResponse(ok = true)
        }
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(3.seconds) { normalPollStarted.await() }
            coVerify(exactly = 2) { fixture.telegram.getUpdatesForToken("100:A", -1, 0) }
            assertEquals(listOf(1.seconds), retryDelays)
            assertEquals(0, fixture.updates.getData("100").lastUpdateId)
        } finally {
            neverCompletes.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证 token 切换会取消旧会话的失败退避，且不会由旧 token 发起额外请求。
     */
    @Test
    fun `token switch cancels old polling backoff before another old request`() = runBlocking {
        val backoffStarted = CompletableDeferred<Unit>()
        val backoffCancelled = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = {
                backoffStarted.complete(Unit)
                try {
                    neverCompletes.await()
                } finally {
                    backoffCancelled.complete(Unit)
                }
            },
        )
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 500,
        )
        coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { backoffStarted.await() }
            fixture.saveSettings(AppSettings(telegramToken = "200:B"))

            withTimeout(2.seconds) { backoffCancelled.await() }
            eventually {
                coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) }
                coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", -1, 0) }
            }
        } finally {
            neverCompletes.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证关闭服务会取消正在等待的失败退避，且不再发起轮询。
     */
    @Test
    fun `close cancels polling backoff without issuing another request`() = runBlocking {
        val backoffStarted = CompletableDeferred<Unit>()
        val backoffCancelled = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = {
                backoffStarted.complete(Unit)
                try {
                    neverCompletes.await()
                } finally {
                    backoffCancelled.complete(Unit)
                }
            },
        )
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 500,
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        withTimeout(2.seconds) { backoffStarted.await() }
        fixture.poller.close()
        withTimeout(2.seconds) { backoffCancelled.await() }
        coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) }
        neverCompletes.cancel()
    }

    /**
     * 验证 401/403 会仅移除当前会话、关闭其队列、重置 Agent，并允许后续 token 建立新会话。
     */
    @Test
    fun `authentication failures reset only their current session and later token can poll`() = runBlocking {
        listOf(401, 403).forEach { errorCode ->
            val authenticationRequestStarted = CompletableDeferred<Unit>()
            val allowAuthenticationFailure = CompletableDeferred<Unit>()
            val fixture = fixture()
            fixture.updates.saveLastUpdateId("100", 10)
            coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } coAnswers {
                authenticationRequestStarted.complete(Unit)
                allowAuthenticationFailure.await()
                GetUpdatesResponse(ok = false, errorCode = errorCode, description = "Unauthorized")
            }
            coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
            every { fixture.agent.resetSession() } returns Job().apply { complete() }
            fixture.saveSettings(AppSettings(telegramToken = "100:A"))

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { authenticationRequestStarted.await() }
                val failedSession = currentSession(fixture.poller)
                allowAuthenticationFailure.complete(Unit)

                eventually { assertNull(currentSessionOrNull(fixture.poller)) }
                assertTrue(sessionQueue(failedSession).trySend(mockk()).isFailure)
                assertTrue(sessionJob(failedSession).isCancelled)
                verify(exactly = 1) { fixture.agent.resetSession() }

                fixture.saveSettings(AppSettings(telegramToken = "200:B"))
                eventually {
                    coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", -1, 0) }
                    assertEquals("200:B", sessionToken(currentSession(fixture.poller)))
                }
            } finally {
                allowAuthenticationFailure.complete(Unit)
                fixture.poller.close()
            }
        }
    }

    /**
     * 验证认证失败建立的屏障会持续到 Agent 重置收尾，期间新的代理请求不能越过该代次。
     */
    @Test
    fun `authentication reset keeps the barrier closed until the reset job finishes`() = runBlocking {
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
            assertTrue(fixture.barrier.isSwitching)

            val admitted = async { fixture.barrier.runWhenReady { "admitted" } }
            delay(100)
            assertFalse(admitted.isCompleted)

            resetJob.complete()
            assertEquals("admitted", withTimeout(2.seconds) { admitted.await() })
            eventually { assertFalse(fixture.barrier.isSwitching) }
        } finally {
            resetJob.complete()
            fixture.poller.close()
        }
    }

    /**
     * 验证 401/403 的初次认证重置返回空值或取消时，B 会话会在安装前重试且始终保持 fail-closed。
     */
    @Test
    fun `failed or cancelled authentication reset blocks B until its retry succeeds`() = runBlocking {
        listOf(
            401 to null,
            403 to Job().apply { cancel() },
        ).forEach { (errorCode, initialReset) ->
            val initialResetStarted = CompletableDeferred<Unit>()
            val retryResetStarted = CompletableDeferred<Unit>()
            val allowRetryReset = Job()
            var resetCount = 0
            val fixture = fixture()
            fixture.updates.saveLastUpdateId("100", 10)
            coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns
                    GetUpdatesResponse(ok = false, errorCode = errorCode, description = "Unauthorized")
            coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
            every { fixture.agent.resetSession() } answers {
                if (resetCount++ == 0) {
                    initialResetStarted.complete(Unit)
                    initialReset
                } else {
                    retryResetStarted.complete(Unit)
                    allowRetryReset
                }
            }
            fixture.saveSettings(AppSettings(telegramToken = "100:A"))

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { initialResetStarted.await() }
                eventually {
                    assertNull(currentSessionOrNull(fixture.poller))
                    assertTrue(fixture.barrier.isSwitching)
                }

                // 不使用 fixture.saveSettings：它为旧测试便利会完成最新任意代次，不能触碰认证外部代次。
                fixture.settings.saveSettings(AppSettings(telegramToken = "200:B"))
                withTimeout(2.seconds) { retryResetStarted.await() }
                assertTrue(fixture.barrier.isSwitching)
                assertNull(currentSessionOrNull(fixture.poller))
                coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:B", -1, 0) }
                val blockedRequest = async { fixture.barrier.runWhenReady { "admitted" } }
                delay(100)
                assertFalse(blockedRequest.isCompleted)

                allowRetryReset.complete()
                eventually {
                    assertEquals("200:B", sessionToken(currentSession(fixture.poller)))
                    coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", -1, 0) }
                    assertFalse(fixture.barrier.isSwitching)
                }
                assertEquals("admitted", withTimeout(2.seconds) { blockedRequest.await() })
                verify(exactly = 2) { fixture.agent.resetSession() }
            } finally {
                allowRetryReset.complete()
                fixture.poller.close()
            }
        }
    }

    /**
     * 验证 A 会话的迟到认证失败在 B 已接管后不会重置 Agent，也不会影响 B 会话。
     */
    @Test
    fun `late authentication failure from an expired session leaves the replacement session untouched`() = runBlocking {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val allowLateFailure = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } coAnswers {
            firstRequestStarted.complete(Unit)
            withContext(NonCancellable) { allowLateFailure.await() }
            GetUpdatesResponse(ok = false, errorCode = 401, description = "Late Unauthorized")
        }
        coEvery { fixture.telegram.getUpdatesForToken("200:B", -1, 0) } returns GetUpdatesResponse(ok = true)
        every { fixture.agent.resetSession() } returns Job().apply { complete() }
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { firstRequestStarted.await() }
            fixture.saveSettings(AppSettings(telegramToken = "200:B"))
            // token 生命周期已经发布为 B；旧会话的不可取消网络返回仍在等待，模拟迟到 401。
            allowLateFailure.complete(Unit)
            eventually {
                assertEquals("200:B", sessionToken(currentSession(fixture.poller)))
                coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("200:B", -1, 0) }
            }
            verify(exactly = 1) { fixture.agent.resetSession() }

            delay(100)
            assertEquals("200:B", sessionToken(currentSession(fixture.poller)))
            verify(exactly = 1) { fixture.agent.resetSession() }
            assertFalse(fixture.barrier.isSwitching)
        } finally {
            allowLateFailure.complete(Unit)
            fixture.poller.close()
        }
    }

    /** 验证 Telegram 拒绝正常 Agent 回复时只重投已持久化 outbox，不会重跑 Agent。 */
    @Test
    fun `outbox retries rejected reply without reexecuting agent`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "durable"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("durable") } returns "reply"
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "reply", any()) } returns
                TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":true}""") andThen
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("durable") }
                coVerify(exactly = 2) { fixture.telegram.sendMessageForToken("100:token", "123", "reply", any()) }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证公开 handleUpdate 与轮询入口一样把成功的 Agent 回复写入 durable outbox。 */
    @Test
    fun `public handle update uses durable outbox when Telegram rejects reply`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("public-durable") } returns "reply"
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "reply", any()) } returns
                TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually { currentSession(fixture.poller) }
            fixture.poller.handleUpdate(Update(11, message = authorizedMessage(1, chat, text = "public-durable")))

            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertEquals(
                    listOf(PendingTelegramReply(11, "123", "reply", ReplyParameters(1))),
                    fixture.updates.getPendingTelegramReplies("100")
                )
                coVerify(exactly = 1) { fixture.agent.sendMessage("public-durable") }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 outbox 绝不在源更新偏移量被持久化确认前发送。 */
    @Test
    fun `outbox skips reply whose update offset is not confirmed`() = runBlocking {
        val fixture = fixture()
        fixture.updates.updateData("100") { current ->
            current.copy(pendingTelegramReplies = listOf(PendingTelegramReply(11, "123", "must not send")))
        }
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 1, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            delay(100)
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证旧 token 的迟到成功不能删除 outbox，轮换后的同 bot 会话才可确认投递。 */
    @Test
    fun `late old token outbox success is retained for rotated token`() = runBlocking {
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
        } finally {
            releaseOldSend.complete(Unit)
            releaseNewSend.complete(Unit)
            fixture.poller.close()
        }
    }

    private fun fixture(
        processingTimeout: Duration = 10.minutes,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
    ): Fixture {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("settings-${System.nanoTime()}.json"), barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("updates-${System.nanoTime()}.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        every { agent.isAiFeatureEnabled(any()) } returns true
        return Fixture(
            barrier = barrier,
            settings = settings,
            updates = updates,
            telegram = telegram,
            agent = agent,
            poller = MessagePoller(
                parentScope,
                telegram,
                agent,
                settings,
                updates,
                barrier,
                processingTimeout,
                retryDelay,
                retryJitter,
            ),
        )
    }

    private fun Fixture.saveSettings(settings: AppSettings) {
        // 既有轮询测试以 `agentEnabled` 表示可用的默认 Gemini 测试配置；缺少密钥的行为由专门用例使用
        // SettingsRepository 直接设置，以免该便利方法掩盖禁用分支。
        val enabledTestSettings = settings.copy(
            ai = settings.ai?.let { aiSettings ->
                if (
                    aiSettings.agentEnabled &&
                    aiSettings.provider == AIProvider.GEMINI &&
                    aiSettings.geminiApiKey.isBlank()
                ) {
                    aiSettings.copy(geminiApiKey = "test-key")
                } else {
                    aiSettings
                }
            },
        )
        this.settings.saveSettings(enabledTestSettings)
        barrier.complete(barrier.latestPendingGeneration())
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

    private suspend fun assertUnacceptedQueueFeedbackRetries(
        notificationResponse: TelegramApiResponse? = null,
        notificationFailure: Exception? = null,
    ) {
        require((notificationResponse == null) != (notificationFailure == null))
        val firstBatchRequested = CompletableDeferred<Unit>()
        val allowFirstBatch = CompletableDeferred<Unit>()
        val blockStarted = CompletableDeferred<Unit>()
        val allowBlockToFinish = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<Duration>()
        val allowRetry = CompletableDeferred<Unit>()
        var retryFetchCount = 0
        val fixture = fixture(
            retryDelay = { delayDuration ->
                retryStarted.complete(delayDuration)
                allowRetry.await()
            },
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } coAnswers {
            firstBatchRequested.complete(Unit)
            allowFirstBatch.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(11, message = authorizedMessage(11, chat, text = "prefix")),
                    Update(20, message = authorizedMessage(20, chat, text = "rejected")),
                    Update(21, message = authorizedMessage(21, chat, text = "suffix")),
                ),
            )
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 20, 30) } coAnswers {
            if (++retryFetchCount == 1) {
                GetUpdatesResponse(ok = true)
            } else {
                GetUpdatesResponse(
                    ok = true,
                    result = listOf(
                        Update(20, message = authorizedMessage(20, chat, text = "rejected")),
                        Update(21, message = authorizedMessage(21, chat, text = "suffix")),
                    ),
                )
            }
        }
        coEvery { fixture.agent.sendMessage(any()) } returns ""
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            blockStarted.complete(Unit)
            allowBlockToFinish.await()
            ""
        }
        if (notificationFailure != null) {
            coEvery {
                fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
            } throws notificationFailure
        } else {
            coEvery {
                fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
            } returns requireNotNull(notificationResponse)
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { firstBatchRequested.await() }
            fixture.poller.enqueueUpdateForTesting(Update(100, message = authorizedMessage(100, chat, text = "block")))
            withTimeout(2.seconds) { blockStarted.await() }
            (101L..109L).forEach { updateId ->
                fixture.poller.enqueueUpdateForTesting(
                    Update(
                        updateId,
                        message = authorizedMessage(updateId, chat, text = "queued-$updateId")
                    )
                )
            }
            allowFirstBatch.complete(Unit)
            eventually {
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken("100:A", "123", match { it.contains("处理队列已满") }, any())
                }
            }

            allowBlockToFinish.complete(Unit)
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("prefix") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("suffix") }
            }
            assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:A", 20, 30) }

            allowRetry.complete(Unit)
            eventually {
                assertEquals(21, fixture.updates.getData("100").lastUpdateId)
                coVerify(atLeast = 2) { fixture.telegram.getUpdatesForToken("100:A", 20, 30) }
                coVerify(exactly = 1) { fixture.agent.sendMessage("prefix") }
                coVerify(exactly = 1) { fixture.agent.sendMessage("rejected") }
                coVerify(exactly = 1) { fixture.agent.sendMessage("suffix") }
            }
        } finally {
            allowFirstBatch.complete(Unit)
            allowBlockToFinish.complete(Unit)
            allowRetry.complete(Unit)
            fixture.poller.close()
        }
    }

    private fun currentSession(poller: MessagePoller): Any = assertNotNull(
        MessagePoller::class.java.getDeclaredField("currentSession").apply { isAccessible = true }.get(poller),
    )

    private fun currentSessionOrNull(poller: MessagePoller): Any? =
        MessagePoller::class.java.getDeclaredField("currentSession").apply { isAccessible = true }.get(poller)

    private fun sessionToken(session: Any): String = session.javaClass.getDeclaredField("token").apply {
        isAccessible = true
    }.get(session) as String

    private fun sessionJob(session: Any): Job = session.javaClass.getDeclaredField("scope").apply {
        isAccessible = true
    }.get(session).let { scope ->
        (scope as CoroutineScope).coroutineContext[Job]!!
    }

    @Suppress("UNCHECKED_CAST")
    private fun sessionQueue(session: Any): Channel<Any> = session.javaClass.getDeclaredField("updateChannel").apply {
        isAccessible = true
    }.get(session) as Channel<Any>

    /** 创建默认由该私聊用户本人发送的授权 Telegram 消息。 */
    private fun authorizedMessage(
        messageId: Long,
        chat: Chat,
        text: String? = null,
        voice: Voice? = null,
        caption: String? = null,
        from: User? = User(id = chat.id, isBot = false, firstName = chat.firstName ?: "Authorized"),
    ): Message = Message(messageId, chat, text, voice, caption, from)

    private data class Fixture(
        val barrier: ModelSwitchBarrier,
        val settings: SettingsRepository,
        val updates: UpdatesRepository,
        val telegram: TelegramService,
        val agent: AgentService,
        val poller: MessagePoller,
    )
}
