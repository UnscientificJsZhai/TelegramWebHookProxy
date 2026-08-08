package com.unscientificjszhai.tgp.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.*
import com.unscientificjszhai.tgp.service.ai.agent.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.io.path.createTempDirectory
import kotlin.test.*
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
     * 验证 Telegram API 的服务端 description 不会进入轮询日志，即使其中包含 bot URL token canary。
     */
    @Test
    fun `polling API failure logs stable fields without Telegram response description`() = runBlocking {
        val descriptionCanary = "POLL_DESCRIPTION_CANARY"
        val tokenCanary = "POLL_RESPONSE_TOKEN_CANARY"
        val retryWait = CompletableDeferred<Unit>()
        val fixture = fixture(retryDelay = { retryWait.await() })
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:poll-token", 11, 30) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 500,
            description = "$descriptionCanary https://api.telegram.org/bot$tokenCanary/getUpdates",
        )
        val logger = LoggerFactory.getLogger(MessagePoller::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        fixture.saveSettings(AppSettings(telegramToken = "100:poll-token"))
        fixture.poller.start()
        try {
            eventually {
                assertTrue(synchronized(appender) {
                    appender.list.any { it.formattedMessage.contains("API error 500") }
                })
            }
        } finally {
            retryWait.complete(Unit)
            fixture.poller.close()
            logger.detachAppender(appender)
            appender.stop()
        }

        val loggedEvents = synchronized(appender) { appender.list.toList() }
        val messages = loggedEvents.map { it.formattedMessage }
        assertTrue(messages.none { it.contains(descriptionCanary) })
        assertTrue(messages.none { it.contains(tokenCanary) })
        assertTrue(loggedEvents.none { it.throwableProxy != null })
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
        // 该装配不包含 DelegatingAgentService，显式模拟其完成已发布设置代次的生命周期处理。
        barrier.completeSettingsThrough(settings.settingsUpdateFlow.value.switchGeneration)
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
            // B 的设置代次同样由测试装配显式完成；不会影响 token 轮换或认证失败创建的外部代次。
            barrier.completeSettingsThrough(settings.settingsUpdateFlow.value.switchGeneration)

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

    /** 验证 Agent 异常会固定降级为 durable outbox 回复，且不向日志或用户回显底层错误。 */
    @Test
    fun `agent failure becomes durable fallback with the session token`() = runBlocking {
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
            fixture.telegram.sendMessageForToken("100:A", "123", "抱歉，该消息未能处理。", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                coVerify {
                    fixture.telegram.sendMessageForToken(
                        "100:A",
                        "123",
                        "抱歉，该消息未能处理。",
                        ReplyParameters(1),
                    )
                }
                coVerify(exactly = 1) { fixture.agent.sendMessage("failure") }
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 Agent 内部的转写异常同样固定降级，且不向 Telegram 回显提供商或底层错误内容。 */
    @Test
    fun `voice transcription errors return safe feedback`() = runBlocking {
        listOf<Exception>(
            AudioTranscriptionFailedException(IllegalStateException("provider response must stay private")),
            AudioTranscriptionTooLargeException(),
        ).forEach { failure ->
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
                fixture.telegram.sendMessageForToken("100:A", "123", "抱歉，该消息未能处理。", ReplyParameters(1))
            } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

            fixture.poller.start()
            try {
                eventually {
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken(
                            "100:A",
                            "123",
                            "抱歉，该消息未能处理。",
                            ReplyParameters(1),
                        )
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

    /** 验证语音下载的 4xx 或 5xx 会在 durable claim 前重试，不会固化 Agent 回合或发送回复。 */
    @Test
    fun `voice download HTTP failures retry before durable claim`() = runBlocking {
        listOf(HttpStatusCode.BadRequest, HttpStatusCode.InternalServerError).forEach { status ->
            val retryStarted = CompletableDeferred<Duration>()
            val allowRetry = CompletableDeferred<Unit>()
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
            )
            coEvery { fixture.telegram.getFileForToken("100:A", "voice-id") } returns FileResponse(
                ok = true,
                result = TelegramFile("voice-id", "voice-unique-id", filePath = "voices/voice.ogg"),
            )
            coEvery { fixture.telegram.downloadFileForToken("100:A", "voices/voice.ogg") } throws
                    IllegalStateException("Telegram file download failed with HTTP status ${status.value}.")

            fixture.poller.start()
            try {
                assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })

                val botData = fixture.updates.getData("100")
                assertEquals(10, botData.lastUpdateId)
                assertEquals(11, botData.retryCheckpoint?.targetUpdateId)
                assertEquals(1, botData.retryCheckpoint?.retryCount)
                assertTrue(botData.agentTurnJournal.isEmpty())
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
                coVerify(exactly = 0) { fixture.agent.sendMessage(null, any()) }
                coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
                coVerify(exactly = 1) { fixture.telegram.getFileForToken("100:A", "voice-id") }
                coVerify(exactly = 1) { fixture.telegram.downloadFileForToken("100:A", "voices/voice.ogg") }
            } finally {
                allowRetry.complete(Unit)
                fixture.poller.close()
            }
        }
    }

    /** 验证持久化 Agent 回合超时只会静默确认进行中 journal，不会直发或创建 outbox 回复。 */
    @Test
    fun `durable processing timeout silently confirms in progress journal`() = runBlocking {
        val fixture = fixture(processingTimeout = 50.milliseconds)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val repollRequested = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "slow"))),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 12, 30) } coAnswers {
            repollRequested.complete(Unit)
            GetUpdatesResponse(ok = true)
        }
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("slow") } coAnswers {
            delay(1.seconds)
            "late"
        }
        fixture.poller.start()
        try {
            eventually {
                coVerify(exactly = 0) {
                    fixture.telegram.sendMessageForToken(
                        "100:A",
                        "123",
                        "抱歉，该消息处理超时（超过10分钟）。",
                        any(),
                    )
                }
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("slow") }
            }
            withTimeout(3.seconds) { repollRequested.await() }
            coVerify(exactly = 1) { fixture.agent.sendMessage("slow") }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证排队等待时耗尽时限、尚未 claim 的消息会在授权仍有效时提示一次并确认偏移量。 */
    @Test
    fun `pre claim queue timeout sends feedback and confirms authorized update`() = runBlocking {
        val fixture = fixture(processingTimeout = 50.milliseconds)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, text = "block")),
                Update(12, message = authorizedMessage(2, chat, text = "timed out in queue")),
            ),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            delay(1.seconds)
            "late"
        }
        coEvery {
            fixture.telegram.sendMessageForToken(
                "100:A",
                "123",
                "抱歉，该消息处理超时（超过10分钟）。",
                ReplyParameters(2),
            )
        } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(12, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("block") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("timed out in queue") }
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken(
                        "100:A",
                        "123",
                        "抱歉，该消息处理超时（超过10分钟）。",
                        ReplyParameters(2),
                    )
                }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证尚未 claim 的排队超时消息在票据失效后既不提示也不会因缺少 journal 永久重试。 */
    @Test
    fun `pre claim queue timeout silently confirms stale ticket`() = runBlocking {
        val fixture = fixture(processingTimeout = 50.milliseconds)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val agentStarted = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, text = "block")),
                Update(12, message = authorizedMessage(2, chat, text = "stale timeout")),
            ),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:A", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            agentStarted.complete(Unit)
            delay(1.seconds)
            "late"
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { agentStarted.await() }
            fixture.saveSettings(
                AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "456")),
            )

            eventually {
                assertEquals(12, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("block") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("stale timeout") }
                coVerify(exactly = 0) {
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
        fixture.updates.saveLastUpdateId("100", 99)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 100, 30) } coAnswers {
            firstBatchRequested.complete(Unit)
            allowFirstBatch.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(110, message = authorizedMessage(110, chat, text = "prefix")),
                    Update(111, message = authorizedMessage(111, chat, text = "rejected")),
                    Update(112, message = authorizedMessage(112, chat, text = "suffix")),
                ),
            )
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 113, 30) } coAnswers {
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
                assertEquals(112, fixture.updates.getData("100").lastUpdateId)
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
     * 验证等待中的旧聊天工作在授权切换后会静默确认：文本、语音和命令都不得在新聊天身份下产生副作用，
     * 但其连续偏移量仍会推进。
     */
    @Test
    fun `queued work is silently confirmed after agent chat changes`() = runBlocking {
        val fixture = fixture()
        val alice = Chat(id = 123L, type = "private", firstName = "Alice")
        val agentStarted = CompletableDeferred<Unit>()
        val releaseAgent = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, alice, text = "block")),
                Update(12, message = authorizedMessage(2, alice, text = "stale text")),
                Update(13, message = authorizedMessage(3, alice, voice = Voice("stale-voice", "unique", duration = 1))),
                Update(14, message = authorizedMessage(4, alice, text = "/reset")),
                Update(15, message = authorizedMessage(5, alice, text = "/model stale-model")),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 16, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            agentStarted.complete(Unit)
            releaseAgent.await()
            ""
        }
        every { fixture.agent.availableModels } returns listOf("stale-model")

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { agentStarted.await() }
            eventually { verify(atLeast = 5) { fixture.agent.isAiFeatureEnabled(any()) } }

            fixture.saveSettings(
                AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "456")),
            )
            releaseAgent.complete(Unit)

            eventually {
                assertEquals(15, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("block") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("stale text") }
                coVerify(exactly = 1) { fixture.agent.sendMessage("block", emptyList()) }
                coVerify(exactly = 0) { fixture.telegram.getFileForToken(any(), "stale-voice") }
                coVerify(exactly = 0) { fixture.telegram.downloadFileForToken(any(), any()) }
                verify(exactly = 0) { fixture.agent.resetSession() }
                verify(exactly = 0) { fixture.agent.availableModels }
                coVerify(exactly = 1) { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") }
                coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
            }
        } finally {
            releaseAgent.complete(Unit)
            fixture.poller.close()
        }
    }

    /** 验证 `A → B → A` 恢复同一聊天标识时，旧票据仍因 generation 不同而被丢弃。 */
    @Test
    fun `queued work remains rejected when agent chat changes away and back`() = runBlocking {
        val fixture = fixture()
        val alice = Chat(id = 123L, type = "private", firstName = "Alice")
        val agentStarted = CompletableDeferred<Unit>()
        val releaseAgent = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, alice, text = "block")),
                Update(12, message = authorizedMessage(2, alice, text = "stale after restore")),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 13, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            agentStarted.complete(Unit)
            releaseAgent.await()
            ""
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { agentStarted.await() }
            eventually { verify(atLeast = 2) { fixture.agent.isAiFeatureEnabled(any()) } }
            fixture.saveSettings(
                AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "456")),
            )
            fixture.saveSettings(
                AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
            )
            releaseAgent.complete(Unit)

            eventually {
                assertEquals(12, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("block") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("stale after restore") }
            }
        } finally {
            releaseAgent.complete(Unit)
            fixture.poller.close()
        }
    }

    /**
     * 验证模型选择不会通过复制带有语义非法历史代理的设置而静默删除该代理配置。
     */
    @Test
    fun `model selection does not overwrite an unresolved historical proxy`() = runBlocking {
        val configFile = tempDirectory.resolve("historical-proxy-model.json")
        val originalContent =
            """
            {"telegramToken":"100:token","proxy":{"host":"proxy.example.com","port":70000,"type":"HTTP"},"ai":{"geminiApiKey":"test-key","agentEnabled":true,"agentChatId":"123","selectedModel":""}}
            """.trimIndent()
        configFile.writeText(originalContent)
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(configFile, barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("historical-proxy-model-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        every { agent.availableModels } returns listOf("model")
        every { agent.isAiFeatureEnabled(any()) } returns true
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
            poller.handleCommandForTesting(
                Update(1, message = authorizedMessage(1, Chat(123L, "private"), text = "/model model")),
            )

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
                agentChatId = "123",
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
            fixture.barrier.completeSettingsThrough(fixture.settings.settingsUpdateFlow.value.switchGeneration)
            listOf("models/gemini-old")
        }

        fixture.poller.start()
        try {
            eventually {
                coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) }
            }
            fixture.poller.handleCommandForTesting(
                Update(1, message = authorizedMessage(1, Chat(123L, "private"), text = "/model models/gemini-old")),
            )

            assertEquals(AIProvider.OPENAI, fixture.settings.settingsFlow.value.ai?.provider)
            assertEquals("", fixture.settings.settingsFlow.value.ai?.selectedModel)
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证 `/model` 在票据复验后、仓储写入前发生设置竞争时使用 generation CAS，不会覆盖并发设置。
     */
    @Test
    fun `model selection generation CAS rejects a change after ticket validation`() = runBlocking {
        val fixture = fixture()
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123", selectedModel = ""),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        every { fixture.agent.availableModels } returns listOf("chosen-model")
        fixture.poller.beforeModelSelectionPersistForTesting = {
            fixture.settings.updateSettings { current ->
                current.copy(
                    ai = current.ai!!.copy(autoCleanContextIntervalMinutes = 17),
                )
            }
            fixture.barrier.completeSettingsThrough(fixture.settings.settingsUpdateFlow.value.switchGeneration)
        }

        fixture.poller.start()
        try {
            eventually {
                coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) }
            }
            fixture.poller.handleCommandForTesting(
                Update(1, message = authorizedMessage(1, Chat(123L, "private"), text = "/model chosen-model")),
            )

            assertEquals("", fixture.settings.settingsFlow.value.ai?.selectedModel)
            assertEquals(17, fixture.settings.settingsFlow.value.ai?.autoCleanContextIntervalMinutes)
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
        } finally {
            fixture.poller.beforeModelSelectionPersistForTesting = null
            fixture.poller.close()
        }
    }

    /** 验证长 `/model` 列表先与偏移量同次持久化，再由 outbox 分块投递。 */
    @Test
    fun `long model list command is durably queued before chunk delivery`() = runBlocking {
        val fixture = fixture()
        val firstSendStarted = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.agent.updateModel() } returns ModelSnapshot(
            currentModel = "model-0",
            availableModels = List(200) { index -> "model-$index-${"x".repeat(30)}" },
        )
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", any(), any()) } coAnswers {
            firstSendStarted.complete(Unit)
            withContext(NonCancellable) { releaseFirstSend.await() }
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } }
            fixture.poller.handleCommandForTesting(
                Update(11, message = authorizedMessage(1, Chat(123L, "private"), text = "/model")),
            )
            withTimeout(2.seconds) { firstSendStarted.await() }
            val pending = fixture.updates.getPendingTelegramReplies("100").single()
            assertEquals(11, fixture.updates.getData("100").lastUpdateId)
            assertTrue(pending.text.length > 4096)
            assertEquals(0, pending.nextChunkStart)

            releaseFirstSend.complete(Unit)
            eventually(6.seconds) {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(atLeast = 2) { fixture.telegram.sendMessageForToken("100:token", "123", any(), any()) }
            }
        } finally {
            releaseFirstSend.complete(Unit)
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

    /** 验证 FINAL 已落盘但 outbox/offset 首次提交失败后，重投只提交 FINAL，绝不再次进入 Agent。 */
    @Test
    fun `final agent turn retries offset commit without reentering agent`() = runBlocking {
        val file = tempDirectory.resolve("retry-final-agent-turn.json")
        var rejectFirstCompletion = true
        val updates = UpdatesRepository(file) { state ->
            val bot = state.bots["100"]
            if (
                rejectFirstCompletion &&
                bot?.lastUpdateId == 11L &&
                bot.pendingTelegramReplies.any { it.updateId == 11L } &&
                bot.agentTurnJournal.any { it.updateId == 11L && it.reply == "reply" }
            ) {
                rejectFirstCompletion = false
                throw IOException("injected completeAgentUpdate failure")
            }
        }
        val fixture = fixture(retryDelay = {}, updatesOverride = updates)
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
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "once"))),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("once") } returns "reply"
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "reply", ReplyParameters(1)) } returns
                TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                assertFalse(rejectFirstCompletion)
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                fixture.updates.getPendingTelegramReplies("100").single().let { reply ->
                    assertEquals(11, reply.updateId)
                    assertEquals("reply", reply.text)
                    assertEquals(ReplyParameters(1), reply.replyParameters)
                }
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("once") }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证正常 FINAL 连续写失败留下孤儿 IN_PROGRESS，恢复后静默确认且绝不重放 Agent 或创建 outbox。 */
    @Test
    fun `failed final journal writes silently confirm orphan without reentering agent`() = runBlocking {
        val file = tempDirectory.resolve("failed-final-agent-turn.json")
        var rejectFinalWrites = 2
        val updates = UpdatesRepository(file) { state ->
            val entry = state.bots["100"]?.agentTurnJournal?.singleOrNull()
            if (entry?.status?.name == "FINAL" && rejectFinalWrites > 0) {
                rejectFinalWrites -= 1
                throw IOException("injected FINAL journal write failure")
            }
        }
        val fixture = fixture(retryDelay = {}, updatesOverride = updates)
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
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "once-final"))),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("once-final") } returns "normal-reply"
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，该消息未能处理。", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(0, rejectFinalWrites)
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("once-final") }
                coVerify(exactly = 0) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，该消息未能处理。", ReplyParameters(1))
                }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /**
     * 验证深层输入引发的 StackOverflowError 不会遗留等待 completion 的批次。
     *
     * 消费者必须把当前 work 退回 Retry、只重启一次；重投会看到 durable IN_PROGRESS 并静默确认，绝不
     * 第二次调用 Agent 或创建失败回复。
     */
    @Test
    fun `stack overflow retries queue once then silently settles durable turn`() = runBlocking {
        val fixture = fixture(retryDelay = {})
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
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "overflow"))),
        ) andThen GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "overflow"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("overflow") } throws StackOverflowError("injected deeply nested JSON")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("overflow") }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 非白名单 Error 必须摘除会话，不能保留无人消费的 channel 让 polling completion 永久等待。 */
    @Test
    fun `fatal queue consumer error terminates session and rejects later offers`() = runBlocking {
        val fixture = fixture(retryDelay = {})
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
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "fatal"))),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("fatal") } throws Error("injected fatal queue failure")

        fixture.poller.start()
        try {
            eventually {
                assertNull(currentSessionOrNull(fixture.poller))
                coVerify(exactly = 1) { fixture.agent.sendMessage("fatal") }
            }
            fixture.poller.enqueueUpdateForTesting(
                Update(
                    12,
                    message = authorizedMessage(2, chat, text = "must-not-queue")
                )
            )
            delay(100)
            coVerify(exactly = 1) { fixture.agent.sendMessage("fatal") }
            coVerify(exactly = 0) { fixture.agent.sendMessage("must-not-queue") }
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证低 update 的 FINAL 提交失败会阻断同批较高 update，避免其越过未提交回合推进 offset。 */
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
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
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
            fixture.poller.close()
        }
    }

    /** 验证重启后无本地 owner 的 IN_PROGRESS 只会静默确认偏移量，不能自动重放 Agent 或创建回复。 */
    @Test
    fun `restarted stale in progress turn is silently confirmed without agent replay`() = runBlocking {
        val file = tempDirectory.resolve("stale-agent-turn.json")
        UpdatesRepository(file).apply {
            saveLastUpdateId("100", 10)
            assertEquals(AgentTurnClaim.CLAIMED, claimAgentTurn("100", 11, "123", ReplyParameters(1)))
        }
        val fixture = fixture(updatesOverride = UpdatesRepository(file))
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
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
            fixture.poller.close()
        }
    }

    /** 验证 AI 已禁用或密钥缺失时仍会优先静默确认遗留 IN_PROGRESS，绝不重放 Agent 或创建 outbox。 */
    @Test
    fun `unavailable agent still silently confirms existing in progress journal without replay`() = runBlocking {
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
            // 不使用 fixture.saveSettings：该便利方法会为 Gemini 测试配置补入密钥，无法覆盖缺密钥路径。
            fixture.settings.saveSettings(settings)
            fixture.barrier.complete(fixture.barrier.latestPendingGeneration())
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
                fixture.poller.close()
            }
        }
    }

    /** 验证 AI 被禁用后仍优先调和已有 FINAL，不会把它当作普通确认而丢失 outbox。 */
    @Test
    fun `disabled agent still commits existing final journal through outbox`() = runBlocking {
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
            fixture.poller.close()
        }
    }

    /** 验证已有语音 FINAL 不依赖过期的 Telegram 文件下载，重启后可直接完成 outbox/offset。 */
    @Test
    fun `existing voice final commits without redownloading expired input`() = runBlocking {
        val file = tempDirectory.resolve("existing-voice-final.json")
        UpdatesRepository(file).apply {
            saveLastUpdateId("100", 10)
            assertEquals(AgentTurnClaim.CLAIMED, claimAgentTurn("100", 11, "123", ReplyParameters(1)))
            assertNotNull(finalizeAgentTurn("100", 11, "voice-reply"))
        }
        val fixture = fixture(updatesOverride = UpdatesRepository(file))
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(
                    11,
                    message = authorizedMessage(1, chat, voice = Voice("expired-voice", "unique", duration = 1))
                ),
            ),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "voice-reply", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 0) { fixture.telegram.getFileForToken(any(), any()) }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any(), any()) }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证同进程 token 轮换会等待仍活跃的 owner 退出，后续会话只降级其 IN_PROGRESS 而不抢占重跑。 */
    @Test
    fun `token rotation does not take over an active agent turn owner`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val agentStarted = CompletableDeferred<Unit>()
        val releaseAgent = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:old",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
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
                    ai = AISettings(agentEnabled = true, agentChatId = "123")
                )
            )
            delay(100)
            assertNull(currentSessionOrNull(fixture.poller))
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:new", any(), any()) }

            releaseAgent.complete(Unit)
            eventually {
                assertEquals("100:new", sessionToken(currentSession(fixture.poller)))
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("in-flight") }
                coVerify {
                    fixture.telegram.sendMessageForToken(
                        "100:new",
                        "123",
                        "late",
                        ReplyParameters(1),
                    )
                }
            }
        } finally {
            releaseAgent.complete(Unit)
            fixture.poller.close()
        }
    }

    /** 验证 journal claim 原子写失败时，即使更新已入队也不会调用 Agent。 */
    @Test
    fun `agent turn claim write failure never enters agent`() = runBlocking {
        val writeAttempted = CompletableDeferred<Unit>()
        val updates = UpdatesRepository(tempDirectory.resolve("failed-poller-agent-claim.json")) {
            writeAttempted.complete(Unit)
            throw IOException("injected agent journal write failure")
        }
        val fixture = fixture(updatesOverride = updates)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", -1, 0) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 1, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()

        fixture.poller.start()
        try {
            eventually { currentSession(fixture.poller) }
            fixture.poller.handleUpdate(Update(1, message = authorizedMessage(1, chat, text = "never")))
            withTimeout(2.seconds) { writeAttempted.await() }
            delay(100)
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            assertEquals(0, fixture.updates.getData("100").lastUpdateId)
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证语音文件下载在 claim 前失败时不进入 Agent，也不写入进行中账本。 */
    @Test
    fun `voice download failure before claim leaves no agent turn`() = runBlocking {
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
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, voice = Voice("voice-id", "unique", duration = 1))),
            ),
        )
        coEvery { fixture.telegram.getFileForToken("100:token", "voice-id") } throws IOException("download unavailable")

        fixture.poller.start()
        try {
            eventually { coVerify(exactly = 1) { fixture.telegram.getFileForToken("100:token", "voice-id") } }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any(), any()) }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
        } finally {
            fixture.poller.close()
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
     * 验证替换候选初始化取消后，屏障释放不会让轮询器回退到旧 Agent 确认授权更新；后续配置恢复时同一
     * 更新只会被处理一次。
     */
    @Test
    fun `cancelled delegating replacement keeps authorized update uncommitted until recovery`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("delegating-cancelled-settings.json"), barrier)
        val skills = SkillRepository.forTesting(tempDirectory.resolve("delegating-cancelled-skills.json"))
        val updates = UpdatesRepository(tempDirectory.resolve("delegating-cancelled-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val componentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val failedComponent = mockk<AgentComponent>()
        val recoveredComponent = mockk<AgentComponent>()
        val oldAgent = mockk<OpenAIAgentService>()
        val failedAgent = mockk<OpenAIAgentService>()
        val recoveredAgent = mockk<OpenAIAgentService>()
        val firstPollStarted = CompletableDeferred<Unit>()
        val allowUpdate = CompletableDeferred<Unit>()
        val retryPollStarted = CompletableDeferred<Unit>()
        val allowRecoveredPoll = CompletableDeferred<Unit>()
        val failedCreated = CompletableDeferred<Unit>()
        val recoveredCreated = CompletableDeferred<Unit>()
        val delegatingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val oldAi = AISettings(
            provider = AIProvider.OPENAI,
            openAiApiKey = "old-key",
            openAiBaseUrl = "https://old.example/v1",
            agentEnabled = true,
            agentChatId = "123",
        )
        val failedAi = oldAi.copy(
            openAiApiKey = "failed-key",
            openAiBaseUrl = "https://failed.example/v1",
        )
        val recoveredAi = oldAi.copy(
            openAiApiKey = "recovered-key",
            openAiBaseUrl = "https://recovered.example/v1",
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        val update = Update(
            11,
            message = authorizedMessage(messageId = 1, chat = chat, text = "retry-after-recovery"),
        )
        updates.saveLastUpdateId("100", 10)
        settings.saveSettings(AppSettings(telegramToken = "100:token", ai = oldAi))
        var componentCreationCount = 0
        every { componentFactory.create() } answers {
            when (componentCreationCount++) {
                0 -> oldComponent
                1 -> {
                    failedCreated.complete(Unit)
                    failedComponent
                }

                else -> {
                    recoveredCreated.complete(Unit)
                    recoveredComponent
                }
            }
        }
        every { oldComponent.openAIAgentService } returns oldAgent
        every { failedComponent.openAIAgentService } returns failedAgent
        every { recoveredComponent.openAIAgentService } returns recoveredAgent
        every { oldAgent.initializationJob() } returns null
        every { failedAgent.initializationJob() } returns Job().apply { cancel() }
        every { recoveredAgent.initializationJob() } returns null
        every { oldAgent.close() } returns Job().apply { complete() }
        every { failedAgent.close() } returns Job().apply { complete() }
        every { recoveredAgent.close() } returns Job().apply { complete() }
        every { oldAgent.isAiFeatureEnabled(any()) } returns true
        every { recoveredAgent.isAiFeatureEnabled(any()) } returns true
        coEvery { recoveredAgent.sendMessage("retry-after-recovery", any()) } returns ""
        var pollCount = 0
        coEvery { telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            if (pollCount++ == 0) {
                firstPollStarted.complete(Unit)
                allowUpdate.await()
            } else {
                retryPollStarted.complete(Unit)
                allowRecoveredPoll.await()
            }
            GetUpdatesResponse(ok = true, result = listOf(update))
        }
        coEvery { telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)

        val delegatingAgent = DelegatingAgentService(componentFactory, settings, skills, barrier, delegatingScope)
        val poller = MessagePoller(
            parentScope,
            telegram,
            delegatingAgent,
            settings,
            updates,
            barrier,
            processingTimeout = 10.minutes,
            retryDelay = {},
        )
        poller.start()
        try {
            eventually { assertTrue(delegatingAgent.isAiFeatureEnabled(oldAi)) }
            withTimeout(2.seconds) { firstPollStarted.await() }
            settings.updateSettings { current -> current.copy(ai = failedAi) }
            withTimeout(2.seconds) { failedCreated.await() }
            withTimeout(2.seconds) { while (barrier.isSwitching) delay(10) }

            allowUpdate.complete(Unit)
            withTimeout(2.seconds) { retryPollStarted.await() }
            assertEquals(10, updates.getData("100").lastUpdateId)
            assertTrue(updates.getData("100").agentTurnJournal.isEmpty())
            coVerify(exactly = 0) { oldAgent.sendMessage(any(), any()) }
            coVerify(exactly = 0) { failedAgent.sendMessage(any(), any()) }

            settings.updateSettings { current -> current.copy(ai = recoveredAi) }
            withTimeout(2.seconds) { recoveredCreated.await() }
            withTimeout(2.seconds) { while (barrier.isSwitching) delay(10) }
            allowRecoveredPoll.complete(Unit)

            eventually {
                assertEquals(11, updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { recoveredAgent.sendMessage("retry-after-recovery", any()) }
                coVerify(exactly = 0) { oldAgent.sendMessage(any(), any()) }
                coVerify(exactly = 0) { failedAgent.sendMessage(any(), any()) }
            }
        } finally {
            allowUpdate.complete(Unit)
            allowRecoveredPoll.complete(Unit)
            poller.close()
            delegatingAgent.close().join()
            delegatingScope.cancel()
            delegatingScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证 durable 回合只使用一次 ready-agent 准入，不会在模拟切换开始后再次进入委派屏障。
     *
     * 包装器在 [AgentService.withReadyService] 已取得真实委派服务的底层 Agent 后启动外部切换；它在旧的
     * [AgentService.sendMessage] 路径则先启动同一切换、再委派给真实服务。旧实现会让外层授权屏障等待内层
     * 委派准入形成循环；当前实现直接发送已取得的底层 Agent，并在切换解除前完成 FINAL、outbox 和 offset。
     */
    @Test
    fun `model switch waits for the admitted durable agent turn to finalize`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("durable-turn-switch-settings.json"), barrier)
        val skills = SkillRepository.forTesting(tempDirectory.resolve("durable-turn-switch-skills.json"))
        val finalPersisted = CompletableDeferred<Unit>()
        val outboxPersisted = CompletableDeferred<Unit>()
        val updates = UpdatesRepository(tempDirectory.resolve("durable-turn-switch-updates.json")) { state ->
            val botData = state.bots["100"] ?: return@UpdatesRepository
            if (botData.agentTurnJournal.any { it.updateId == 11L && it.status.name == "FINAL" }) {
                finalPersisted.complete(Unit)
            }
            if (botData.pendingTelegramReplies.any { it.updateId == 11L && it.text == "old agent reply" }) {
                outboxPersisted.complete(Unit)
            }
        }
        val telegram = mockk<TelegramService>(relaxed = true)
        val componentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val oldAgent = mockk<OpenAIAgentService>()
        val oldAgentSendStarted = CompletableDeferred<Unit>()
        val releaseOldAgentSend = CompletableDeferred<Unit>()
        val delegatingScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val wrapperScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val oldAi = AISettings(
            provider = AIProvider.OPENAI,
            openAiApiKey = "old-key",
            openAiBaseUrl = "https://old.example/v1",
            agentEnabled = true,
            agentChatId = "123",
        )
        updates.saveLastUpdateId("100", 10)
        settings.updateSettings { AppSettings(telegramToken = "100:token", ai = oldAi) }
        every { componentFactory.create() } returns oldComponent
        every { oldComponent.openAIAgentService } returns oldAgent
        every { oldAgent.initializationJob() } returns null
        every { oldAgent.close() } returns Job().apply { complete() }
        every { oldAgent.isAiFeatureEnabled(any()) } returns true
        coEvery { oldAgent.sendMessage("barrier protected", any()) } coAnswers {
            oldAgentSendStarted.complete(Unit)
            releaseOldAgentSend.await()
            "old agent reply"
        }
        coEvery { telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(
                    11,
                    message = authorizedMessage(
                        messageId = 1,
                        chat = Chat(id = 123L, type = "private", firstName = "Authorized"),
                        text = "barrier protected",
                    ),
                ),
            ),
        )
        coEvery { telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery {
            telegram.sendMessageForToken("100:token", "123", "old agent reply", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":false}""")

        val delegatingAgent = DelegatingAgentService(componentFactory, settings, skills, barrier, delegatingScope)
        val agent = SwitchInjectingAgentService(delegatingAgent, barrier, wrapperScope)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        poller.start()
        try {
            eventually { assertTrue(delegatingAgent.isAiFeatureEnabled(oldAi)) }
            withTimeout(2.seconds) { agent.switchStarted.await() }
            withTimeout(2.seconds) { oldAgentSendStarted.await() }
            withTimeout(2.seconds) {
                while (!barrier.isSwitching) {
                    delay(10.milliseconds)
                }
            }
            delay(100.milliseconds)
            assertFalse(agent.switchCompleted.isCompleted, "switch must wait for the admitted ready-agent scope")
            assertFalse(finalPersisted.isCompleted)
            assertEquals(10, updates.getData("100").lastUpdateId)

            releaseOldAgentSend.complete(Unit)
            withTimeout(5.seconds) { agent.switchCompleted.await() }
            withTimeout(5.seconds) { finalPersisted.await() }
            withTimeout(5.seconds) { outboxPersisted.await() }
            eventually {
                assertEquals(11, updates.getData("100").lastUpdateId)
                assertTrue(
                    updates.getData("100").pendingTelegramReplies.any {
                        it.updateId == 11L && it.text == "old agent reply"
                    },
                )
                assertTrue(updates.getData("100").agentTurnJournal.none { it.updateId == 11L })
                coVerify(exactly = 1) { oldAgent.sendMessage("barrier protected", any()) }
            }
        } finally {
            releaseOldAgentSend.complete(Unit)
            poller.close()
            delegatingAgent.close().join()
            delegatingScope.cancel()
            delegatingScope.coroutineContext[Job]?.join()
            wrapperScope.cancel()
            wrapperScope.coroutineContext[Job]?.join()
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

    /** 验证聊天发现写入失败只是辅助缓存故障，仍会等待授权回合完成并确认对应 offset。 */
    @Test
    fun `discovery write failure does not block authorized completion or offset`() = runBlocking {
        val file = tempDirectory.resolve("discovery-write-failure.json")
        val updates = UpdatesRepository(file) { candidate ->
            if (candidate.bots["100"]?.chats?.isNotEmpty() == true) {
                throw IOException("injected chat discovery failure")
            }
        }
        val fixture = fixture(updatesOverride = updates)
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(
                    11,
                    message = authorizedMessage(1, chat, text = "complete despite discovery failure")
                )
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.agent.sendMessage("complete despite discovery failure") } returns ""

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("complete despite discovery failure") }
            }
            assertTrue(fixture.updates.getChats("100").isEmpty())
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证发现缓存写入的取消会离开轮询，而不会被当作可忽略的普通存储故障。 */
    @Test
    fun `discovery cancellation stops polling without confirming its offset`() = runBlocking {
        val file = tempDirectory.resolve("discovery-cancellation.json")
        UpdatesRepository(file).saveLastUpdateId("100", 10)
        val updates = UpdatesRepository(file) { candidate ->
            if (candidate.bots["100"]?.chats?.isNotEmpty() == true) {
                throw CancellationException("injected discovery cancellation")
            }
        }
        val fixture = fixture(updatesOverride = updates)
        val group = Chat(id = -100L, type = "group", title = "Cancellation group")
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, group, text = "cancel discovery"))),
        )

        fixture.poller.start()
        try {
            eventually { coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } }
            delay(100)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertTrue(fixture.updates.getChats("100").isEmpty())
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证发现写入前 token 代次失效时，该次轮询返回 Stopped 而不确认旧会话的偏移量。 */
    @Test
    fun `stale discovery save stops the current poll attempt`() = runBlocking {
        val fixture = fixture()
        val firstPollingRequest = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:old", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:old", 11, 30) } coAnswers {
            firstPollingRequest.complete(Unit)
            neverCompletes.await()
            GetUpdatesResponse(ok = true)
        } andThen GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "rotate during discovery"))),
        )

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { firstPollingRequest.await() }
            val oldSession = currentSession(fixture.poller)
            sessionPollJob(oldSession).cancel()
            sessionPollJob(oldSession).join()
            var tokenRotated = false
            every { fixture.agent.isAiFeatureEnabled(any()) } answers {
                if (!tokenRotated) {
                    tokenRotated = true
                    fixture.settings.saveSettings(
                        AppSettings(
                            telegramToken = "200:new",
                            ai = AISettings(geminiApiKey = "test-key", agentEnabled = true, agentChatId = "123"),
                        ),
                    )
                }
                true
            }

            val attempt = pollOnceForTesting(fixture.poller, oldSession)

            assertEquals("Stopped", attempt.javaClass.simpleName)
            assertTrue(tokenRotated)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertTrue(fixture.updates.getChats("100").isEmpty())
            coVerify(exactly = 2) { fixture.telegram.getUpdatesForToken("100:old", 11, 30) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证普通 Confirmed 更新的偏移量提交失败后会退避并从相同偏移量重新拉取。 */
    @Test
    fun `confirmed offset save failure retries the update`() = runBlocking {
        val file = tempDirectory.resolve("confirmed-offset-save-failure.json")
        var rejectFirstOffsetSave = true
        val updates = UpdatesRepository(file) { candidate ->
            if (rejectFirstOffsetSave && candidate.bots["100"]?.lastUpdateId == 11L) {
                rejectFirstOffsetSave = false
                throw IOException("injected confirmed offset save failure")
            }
        }
        val retryStarted = CompletableDeferred<Duration>()
        val allowRetry = CompletableDeferred<Unit>()
        val fixture = fixture(
            updatesOverride = updates,
            retryDelay = { duration ->
                retryStarted.complete(duration)
                allowRetry.await()
            },
        )
        val group = Chat(id = -100L, type = "group", title = "Confirmed group")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, group, text = "retry confirmed offset"))),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })
            assertFalse(rejectFirstOffsetSave)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertEquals(listOf("-100"), fixture.updates.getChats("100").map { it.id })
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }

            allowRetry.complete(Unit)
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                coVerify(atLeast = 2) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
            }
        } finally {
            allowRetry.complete(Unit)
            fixture.poller.close()
        }
    }

    /** 验证未授权群消息仍可作为发现缓存保存，但绝不会进入 Agent、下载或命令副作用。 */
    @Test
    fun `untrusted group discovery never invokes agent`() = runBlocking {
        val fixture = fixture()
        val group = Chat(id = -100L, type = "group", title = "Untrusted group")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, group, text = "untrusted group message"))),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertEquals(listOf("-100"), fixture.updates.getChats("100").map { it.id })
            }
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.agent.sendMessage(null, any()) }
            coVerify(exactly = 0) { fixture.telegram.getFileForToken(any(), any()) }
            verify(exactly = 0) { fixture.agent.resetSession() }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证同批 A-B-A 发现以最后一次观察为准，避免暂存去重反转 LRU 驱逐对象。 */
    @Test
    fun `repeated chat discovery in one batch keeps its final LRU observation order`() = runBlocking {
        val fixture = fixture()
        val chatA = Chat(id = 1_000L, type = "group", title = "A")
        val chatB = Chat(id = 1_001L, type = "group", title = "B")
        val chatC = ChatInfo("1_002", "C", "group")
        val olderChats = (1..62).map { index -> ChatInfo("old-$index", "old-$index", "group") }
        fixture.updates.mergeChats("100", listOf(ChatInfo("1000", "A", "group")) + olderChats)
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(11, chatA, text = "first A")),
                Update(12, message = authorizedMessage(12, chatB, text = "B")),
                Update(13, message = authorizedMessage(13, chatA, text = "final A")),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 14, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            eventually { assertEquals(13, fixture.updates.getData("100").lastUpdateId) }
            // Make the original 62 records newer than both A and B. A subsequent admission must evict B, whose
            // last observation precedes the final A observation in the Telegram batch.
            fixture.updates.mergeChats("100", olderChats)
            fixture.updates.mergeChats("100", listOf(chatC))

            val chatIds = fixture.updates.getChats("100").map { it.id }.toSet()
            assertTrue("1000" in chatIds)
            assertFalse("1001" in chatIds)
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
        val commandReplySent = CompletableDeferred<Unit>()
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
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } coAnswers {
            commandReplySent.complete(Unit)
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

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

            fixture.poller.handleCommandForTesting(
                Update(103, message = authorizedMessage(99, chat, text = "/reset")),
            )

            assertEquals(lastReplyAt, session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply {
                isAccessible = true
            }.get(session))
            assertNotNull(sessionQueue(session).tryReceive().getOrNull())
            withTimeout(2.seconds) { commandReplySent.await() }
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
        val commandReplySent = CompletableDeferred<Unit>()
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
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } coAnswers {
            commandReplySent.complete(Unit)
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        try {
            eventually { coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } }
            fixture.poller.enqueueUpdateForTesting(Update(101, message = authorizedMessage(1, chat, text = "block")))
            withTimeout(2.seconds) { processingStarted.await() }
            fixture.poller.enqueueUpdateForTesting(Update(102, message = authorizedMessage(2, chat, text = "queued")))
            val session = currentSession(fixture.poller)
            session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply { isAccessible = true }.set(session, 1234L)

            fixture.poller.handleCommandForTesting(
                Update(103, message = authorizedMessage(99, chat, text = "/reset")),
            )

            assertNull(session.javaClass.getDeclaredField("lastAiReplyAtMillis").apply { isAccessible = true }
                .get(session))
            assertTrue(sessionQueue(session).tryReceive().isFailure)
            withTimeout(2.seconds) { commandReplySent.await() }
            coVerify {
                fixture.telegram.sendMessageForToken("100:token", "123", "会话已重置，待处理消息已清空。", any())
            }
        } finally {
            keepProcessing.cancel()
            fixture.poller.close()
        }
    }

    /** 验证 `/reset` 清队列时不会吞掉已持久化的 FINAL，而是先将其写入 outbox 与偏移量。 */
    @Test
    fun `reset queue clearing completes queued durable final`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val processingStarted = CompletableDeferred<Unit>()
        val keepProcessing = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 9)
        assertEquals(AgentTurnClaim.CLAIMED, fixture.updates.claimAgentTurn("100", 11, "123", ReplyParameters(2)))
        assertNotNull(fixture.updates.finalizeAgentTurn("100", 11, "saved-final"))
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            processingStarted.complete(Unit)
            keepProcessing.await()
            ""
        }
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "saved-final", ReplyParameters(2)) } returns
                TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually { currentSession(fixture.poller) }
            fixture.poller.enqueueUpdateForTesting(Update(10, message = authorizedMessage(1, chat, text = "block")))
            withTimeout(2.seconds) { processingStarted.await() }
            fixture.poller.enqueueUpdateForTesting(
                Update(
                    11,
                    message = authorizedMessage(2, chat, text = "must-not-run")
                )
            )

            fixture.poller.handleCommandForTesting(Update(12, message = authorizedMessage(3, chat, text = "/reset")))

            assertEquals(12, fixture.updates.getData("100").lastUpdateId)
            fixture.updates.getPendingTelegramReplies("100").first { it.updateId == 11L }.let { pending ->
                assertEquals("saved-final", pending.text)
                assertEquals(ReplyParameters(2), pending.replyParameters)
            }
            coVerify(exactly = 0) { fixture.agent.sendMessage("must-not-run") }
        } finally {
            keepProcessing.cancel()
            fixture.poller.close()
        }
    }

    /** 验证最大安全持久化 offset 仍会请求 Long.MAX_VALUE，而不会有符号回绕。 */
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
        } finally {
            holdRequest.cancel()
            fixture.poller.close()
        }
    }

    /** 验证初始化响应中的不可持久化更新不会推进 offset 或留下重试检查点。 */
    @Test
    fun `initial response rejects Long MAX update without writing state`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val holdRetry = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = {
                retryStarted.complete(Unit)
                holdRetry.await()
            },
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", -1, 0) } returns
                GetUpdatesResponse(ok = true, result = listOf(Update(Long.MAX_VALUE)))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            assertEquals(0, fixture.updates.getData("100").lastUpdateId)
            assertNull(fixture.updates.getData("100").retryCheckpoint)
            assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
            assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", 1, 30) }
        } finally {
            holdRetry.cancel()
            fixture.poller.close()
        }
    }

    /** 验证常规长轮询响应中的不可持久化更新不会创建检查点、账本或负 offset 请求。 */
    @Test
    fun `normal response rejects Long MAX update without checkpoint or overflow request`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val holdRetry = CompletableDeferred<Unit>()
        val fixture = fixture(
            retryDelay = {
                retryStarted.complete(Unit)
                holdRetry.await()
            },
        )
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns
                GetUpdatesResponse(ok = true, result = listOf(Update(Long.MAX_VALUE)))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertNull(fixture.updates.getData("100").retryCheckpoint)
            assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
            assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", Long.MIN_VALUE, 30) }
        } finally {
            holdRetry.cancel()
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
     * 验证认证失败后的悬挂 Agent reset 会被关闭根 scope 取消；关闭后不会重建会话，并且待处理的外部屏障会释放。
     */
    @Test
    fun `close cancels a hanging authentication failure reset without rebuilding its session`() = runBlocking {
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

            fixture.poller.close()
            withTimeout(2.seconds) { fixture.poller.awaitStopped() }

            assertTrue(resetJob.isCancelled)
            assertNull(currentSessionOrNull(fixture.poller))
            assertFalse(fixture.barrier.isSwitching)
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:A", 11, 30) }
        } finally {
            resetJob.cancel()
            fixture.poller.close()
        }
    }

    /**
     * 验证关闭根 scope 会取消 token 轮换中悬挂的 Agent reset；关闭不必等待该 reset 自行返回，也绝不能在取消后
     * 安装新会话。
     */
    @Test
    fun `close cancels a hanging token rotation agent reset`() = runBlocking {
        val resetStarted = CompletableDeferred<Unit>()
        val resetJob = Job()
        val fixture = fixture()
        every { fixture.agent.resetSession() } answers {
            resetStarted.complete(Unit)
            resetJob
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("200:B", any(), any()) } returns GetUpdatesResponse(ok = true)
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            eventually { assertEquals("100:A", sessionToken(currentSession(fixture.poller))) }
            fixture.saveSettings(AppSettings(telegramToken = "200:B"))
            withTimeout(2.seconds) { resetStarted.await() }
            assertNull(currentSessionOrNull(fixture.poller))

            fixture.poller.close()
            withTimeout(2.seconds) { fixture.poller.awaitStopped() }

            assertTrue(resetJob.isCancelled)
            assertNull(currentSessionOrNull(fixture.poller))
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:B", any(), any()) }
        } finally {
            resetJob.cancel()
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

                // 不使用 fixture.saveSettings：B 的 settings 代次在此处单独完成，认证外部代次必须保持封闭。
                fixture.settings.saveSettings(AppSettings(telegramToken = "200:B"))
                fixture.barrier.completeSettingsThrough(fixture.settings.settingsUpdateFlow.value.switchGeneration)
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
     * 验证普通 token 轮换的 Agent 重置失败时，新 Bot 不会开始轮询或处理其授权用户消息；后续实际 token
     * 代次可重试并恢复。空任务、已取消任务和同步异常必须采用同一 fail-closed 语义。
     */
    @Test
    fun `failed token rotation reset blocks the new bot until a later token generation recovers`() = runBlocking {
        data class ResetFailure(
            val name: String,
            val createResetJob: () -> Job?,
        )

        listOf(
            ResetFailure("null") { null },
            ResetFailure("cancelled") { Job().apply { cancel() } },
            ResetFailure("synchronous exception") { throw IllegalStateException("injected reset failure") },
        ).forEach { failure ->
            val fixture = fixture()
            val chatA = Chat(id = 111L, type = "private", firstName = "A")
            val chatB = Chat(id = 222L, type = "private", firstName = "B")
            val resetAttempted = CompletableDeferred<Unit>()
            var resetCount = 0
            every { fixture.agent.resetSession() } answers {
                if (resetCount++ == 0) {
                    resetAttempted.complete(Unit)
                    failure.createResetJob()
                } else {
                    Job().apply { complete() }
                }
            }
            coEvery { fixture.telegram.getUpdatesForToken("100:A", any(), any()) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.getUpdatesForToken("200:B", any(), any()) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.getUpdatesForToken("300:C", any(), any()) } returns GetUpdatesResponse(ok = true)
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:A",
                    ai = AISettings(agentEnabled = true, agentChatId = chatA.id.toString()),
                ),
            )

            fixture.poller.start()
            try {
                eventually {
                    assertEquals("100:A", sessionToken(currentSession(fixture.poller)))
                    coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("100:A", any(), any()) }
                }

                fixture.saveSettings(
                    AppSettings(
                        telegramToken = "200:B",
                        ai = AISettings(agentEnabled = true, agentChatId = chatB.id.toString()),
                    ),
                )
                withTimeout(2.seconds) { resetAttempted.await() }
                eventually {
                    assertNull(currentSessionOrNull(fixture.poller), failure.name)
                    assertTrue(fixture.barrier.isSwitching, failure.name)
                    coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:B", any(), any()) }
                }
                val blockedRequest = async { fixture.barrier.runWhenReady { "admitted" } }
                delay(100)
                assertFalse(blockedRequest.isCompleted, failure.name)

                fixture.poller.handleUpdate(
                    Update(11, message = authorizedMessage(11, chatB, text = "B must stay blocked")),
                )
                coVerify(exactly = 0) { fixture.agent.sendMessage("B must stay blocked") }

                fixture.saveSettings(
                    AppSettings(
                        telegramToken = "300:C",
                        ai = AISettings(agentEnabled = true, agentChatId = "333"),
                    ),
                )
                eventually {
                    assertEquals("300:C", sessionToken(currentSession(fixture.poller)))
                    coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("300:C", any(), any()) }
                    assertFalse(fixture.barrier.isSwitching)
                }
                assertEquals("admitted", withTimeout(2.seconds) { blockedRequest.await() }, failure.name)
                verify(exactly = 2) { fixture.agent.resetSession() }
            } finally {
                fixture.poller.close()
            }
        }
    }

    /**
     * 验证 B 的 Agent 重置期间发布 C 时，不会短暂安装或轮询已经过期的 B 代次。
     */
    @Test
    fun `token rotation converges directly to C when B becomes stale during its reset`() = runBlocking {
        val fixture = fixture()
        val resetStarted = CompletableDeferred<Unit>()
        val resetJob = Job()
        val chatA = Chat(id = 111L, type = "private", firstName = "A")
        val chatB = Chat(id = 222L, type = "private", firstName = "B")
        every { fixture.agent.resetSession() } answers {
            resetStarted.complete(Unit)
            resetJob
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("200:B", any(), any()) } returns GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.getUpdatesForToken("300:C", any(), any()) } returns GetUpdatesResponse(ok = true)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:A",
                ai = AISettings(agentEnabled = true, agentChatId = chatA.id.toString()),
            ),
        )

        fixture.poller.start()
        try {
            eventually { assertEquals("100:A", sessionToken(currentSession(fixture.poller))) }
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "200:B",
                    ai = AISettings(agentEnabled = true, agentChatId = chatB.id.toString()),
                ),
            )
            withTimeout(2.seconds) { resetStarted.await() }
            eventually {
                assertNull(currentSessionOrNull(fixture.poller))
                assertTrue(fixture.barrier.isSwitching)
            }

            fixture.saveSettings(
                AppSettings(
                    telegramToken = "300:C",
                    ai = AISettings(agentEnabled = true, agentChatId = "333"),
                ),
            )
            resetJob.complete()

            eventually {
                assertEquals("300:C", sessionToken(currentSession(fixture.poller)))
                coVerify(atLeast = 1) { fixture.telegram.getUpdatesForToken("300:C", any(), any()) }
                coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:B", any(), any()) }
                assertFalse(fixture.barrier.isSwitching)
            }
            verify(exactly = 1) { fixture.agent.resetSession() }
        } finally {
            resetJob.complete()
            fixture.poller.close()
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

    /** 验证长 durable 回复依次发送所有片段，且仅首片段携带原消息引用。 */
    @Test
    fun `outbox delivers long reply chunks in order with reply parameters only on first chunk`() = runBlocking {
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
                ReplyParameters(1)
            )
        } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "b", null) } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerifyOrder {
                    fixture.telegram.sendMessageForToken("100:token", "123", "a".repeat(4096), ReplyParameters(1))
                    fixture.telegram.sendMessageForToken("100:token", "123", "b", null)
                }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证后续片段被 Telegram 拒绝时只重试该片段，不会跳过或重新发送首片段。 */
    @Test
    fun `outbox retries a rejected middle chunk without skipping it`() = runBlocking {
        val fixture = fixture()
        val source = "a".repeat(4096) + "b"
        fixture.updates.completeAgentUpdate("100", 11, PendingTelegramReply(11, "123", source, ReplyParameters(1)))
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken(
                "100:token",
                "123",
                "a".repeat(4096),
                ReplyParameters(1)
            )
        } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "b", null) } returns
                TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "a".repeat(4096), ReplyParameters(1))
                }
                coVerify(exactly = 2) { fixture.telegram.sendMessageForToken("100:token", "123", "b", null) }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证首片段的 HTTP `400` 或响应正文 `error_code:400` 会先清除引用并重投原文，再按通常阈值进入回退。 */
    @Test
    fun `permanent rejection retries the quoted first chunk without reply parameters before fallback`() = runBlocking {
        val rejectionCases = listOf(
            "http 400" to TelegramApiResponse(HttpStatusCode.BadRequest, "not-json"),
            "body error code 400" to TelegramApiResponse(
                HttpStatusCode.InternalServerError,
                """{"ok":false,"error_code":400}""",
            ),
        )
        rejectionCases.forEach { (_, rejection) ->
            val fixture = fixture()
            fixture.updates.completeAgentUpdate(
                "100",
                11,
                PendingTelegramReply(11, "123", "original", ReplyParameters(1)),
            )
            fixture.saveSettings(AppSettings(telegramToken = "100:token"))
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
            coEvery {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
            } returns rejection
            coEvery {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
            } returns rejection andThen rejection
            coEvery {
                fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
            } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

            fixture.poller.start()
            try {
                eventually {
                    val pending = fixture.updates.getPendingTelegramReplies("100").single()
                    assertEquals(0, pending.nextChunkStart)
                    assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                    assertNull(pending.replyParameters)
                    assertEquals(0, pending.deliveryAttempts)
                    assertEquals(0, pending.permanentRejectionCount)
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                    }
                }
                eventually(4.seconds) {
                    val pending = fixture.updates.getPendingTelegramReplies("100").single()
                    assertEquals(0, pending.nextChunkStart)
                    assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                    assertNull(pending.replyParameters)
                    assertEquals(1, pending.deliveryAttempts)
                    assertEquals(1, pending.permanentRejectionCount)
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                    }
                }
                eventually(5.seconds) {
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                    }
                    coVerify(exactly = 2) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                    }
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
                    }
                    coVerifyOrder {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                        fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
                    }
                }
            } finally {
                fixture.poller.close()
            }
        }
    }

    /** 验证中间原文片段即使保留历史引用参数，回退成功后也会终止整条长回复而不发送尾部。 */
    @Test
    fun `accepted middle fallback terminates the long reply before its tail and delivers the next update`() =
        runBlocking {
            val fixture = fixture()
            val first = "a".repeat(4096)
            val middle = "b".repeat(4096)
            val tail = "c"
            fixture.updates.completeAgentUpdate(
                "100",
                11,
                PendingTelegramReply(11, "123", first + middle + tail, ReplyParameters(1))
            )
            fixture.updates.completeAgentUpdate("100", 12, PendingTelegramReply(12, "123", "next"))
            fixture.saveSettings(AppSettings(telegramToken = "100:token"))
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 13, 30) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.sendMessageForToken("100:token", "123", first, ReplyParameters(1)) } returns
                    TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            coEvery { fixture.telegram.sendMessageForToken("100:token", "123", middle, null) } returns
                    TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                    TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")
            coEvery {
                fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
            } returns TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "next", null) } returns
                    TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

            fixture.poller.start()
            try {
                eventually(4.seconds) {
                    val pending = fixture.updates.getPendingTelegramReplies("100").single { it.updateId == 11L }
                    assertEquals(4096, pending.nextChunkStart)
                    assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                    assertEquals(ReplyParameters(1), pending.replyParameters)
                    assertEquals(1, pending.deliveryAttempts)
                    assertEquals(1, pending.permanentRejectionCount)
                }
                eventually(6.seconds) {
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                    coVerify(exactly = 2) { fixture.telegram.sendMessageForToken("100:token", "123", middle, null) }
                    coVerify(exactly = 1) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
                    }
                    coVerify(exactly = 0) { fixture.telegram.sendMessageForToken("100:token", "123", tail, null) }
                    coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:token", "123", "next", null) }
                }
            } finally {
                fixture.poller.close()
            }
        }

    /** 验证中间回退消息第三次失败后会终止整条长回复，而不会发送尾部且不会阻塞下一更新。 */
    @Test
    fun `exhausted middle fallback terminates the long reply before its tail and delivers the next update`() =
        runBlocking {
            val fixture = fixture()
            val first = "a".repeat(4096)
            val middle = "b".repeat(4096)
            val tail = "c"
            fixture.updates.completeAgentUpdate(
                "100",
                11,
                PendingTelegramReply(11, "123", first + middle + tail, ReplyParameters(1))
            )
            fixture.updates.completeAgentUpdate("100", 12, PendingTelegramReply(12, "123", "next"))
            fixture.saveSettings(AppSettings(telegramToken = "100:token"))
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 13, 30) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.sendMessageForToken("100:token", "123", first, ReplyParameters(1)) } returns
                    TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            coEvery { fixture.telegram.sendMessageForToken("100:token", "123", middle, null) } returns
                    TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                    TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")
            coEvery {
                fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
            } returns TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                    TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                    TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")
            coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "next", null) } returns
                    TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

            fixture.poller.start()
            try {
                eventually(8.seconds) {
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                    coVerify(exactly = 3) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
                    }
                    coVerify(exactly = 0) { fixture.telegram.sendMessageForToken("100:token", "123", tail, null) }
                    coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:token", "123", "next", null) }
                }
            } finally {
                fixture.poller.close()
            }
        }

    /** 验证清除首片段引用的条件替换无法持久化时，旧快照保持不变且不会提前发送无引用原文。 */
    @Test
    fun `failed quoted first chunk replacement retains the old snapshot without an extra send`() = runBlocking {
        val file = tempDirectory.resolve("outbox-quoted-replacement-gate.json")
        val updates = UpdatesRepository(file) { state ->
            val candidate = state.bots["100"]?.pendingTelegramReplies?.singleOrNull()
            if (
                candidate?.deliveryStage == TelegramReplyDeliveryStage.ORIGINAL &&
                candidate.nextChunkStart == 0 &&
                candidate.replyParameters == null &&
                candidate.deliveryAttempts == 0 &&
                candidate.permanentRejectionCount == 0
            ) {
                throw IOException("injected quoted replacement persistence failure")
            }
        }
        val fixture = fixture(updatesOverride = updates)
        updates.completeAgentUpdate("100", 11, PendingTelegramReply(11, "123", "original", ReplyParameters(1)))
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                val pending = updates.getPendingTelegramReplies("100").single()
                assertEquals(ReplyParameters(1), pending.replyParameters)
                assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                assertEquals(0, pending.nextChunkStart)
                assertEquals(1, pending.deliveryAttempts)
                assertEquals(0, pending.permanentRejectionCount)
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                }
            }
            delay(200)
            assertEquals(ReplyParameters(1), updates.getPendingTelegramReplies("100").single().replyParameters)
            coVerify(exactly = 1) {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
            }
            coVerify(exactly = 0) { fixture.telegram.sendMessageForToken("100:token", "123", "original", null) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 HTTP `429` 即使正文矛盾地声明 `error_code:400` 也不属于永久拒绝。 */
    @Test
    fun `outbox treats Telegram 429 as retryable instead of fallback`() = runBlocking {
        val fixture = fixture()
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "123", "original", ReplyParameters(1)),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.TooManyRequests, """{"ok":false,"error_code":400}""") andThen
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually {
                val pending = fixture.updates.getPendingTelegramReplies("100").single()
                assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                assertEquals(1, pending.deliveryAttempts)
                assertEquals(0, pending.permanentRejectionCount)
            }
            eventually(4.seconds) {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 2) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                }
                coVerify(exactly = 0) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                }
                coVerify(exactly = 0) {
                    fixture.telegram.sendMessageForToken(
                        "100:token",
                        "123",
                        "抱歉，上一条回复未能发送。",
                        null,
                    )
                }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 `429`、`5xx`、无效响应与网络异常不会清除引用或切换为回退消息。 */
    @Test
    fun `retryable outbox failures retain quoted original without fallback`() = runBlocking {
        data class RetryableFailure(
            val name: String,
            val response: TelegramApiResponse? = null,
            val exception: Exception? = null,
        )

        listOf(
            RetryableFailure("429", TelegramApiResponse(HttpStatusCode.TooManyRequests, """{"ok":false}""")),
            RetryableFailure("5xx", TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")),
            RetryableFailure("malformed body", TelegramApiResponse(HttpStatusCode.OK, "not-json")),
            RetryableFailure("network", exception = SocketTimeoutException("injected timeout")),
        ).forEach { retryableFailure ->
            val fixture = fixture()
            fixture.updates.completeAgentUpdate(
                "100",
                11,
                PendingTelegramReply(11, "123", "original", ReplyParameters(1)),
            )
            fixture.saveSettings(AppSettings(telegramToken = "100:token"))
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)
            coEvery {
                fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
            } coAnswers {
                retryableFailure.exception?.let { throw it } ?: checkNotNull(retryableFailure.response)
            } andThen
                    TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

            fixture.poller.start()
            try {
                eventually {
                    val pending = fixture.updates.getPendingTelegramReplies("100").single()
                    assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                    assertEquals(ReplyParameters(1), pending.replyParameters)
                    assertEquals(1, pending.deliveryAttempts)
                    assertEquals(0, pending.permanentRejectionCount)
                }
                eventually(4.seconds) {
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                    coVerify(exactly = 2) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                    }
                    coVerify(exactly = 0) {
                        fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                    }
                    coVerify(exactly = 0) {
                        fixture.telegram.sendMessageForToken(
                            "100:token",
                            "123",
                            "抱歉，上一条回复未能发送。",
                            null,
                        )
                    }
                }
            } finally {
                fixture.poller.close()
            }
        }
    }

    /** 验证回退消息三次未被接受后会丢弃毒消息并继续投递下一项 outbox 回复。 */
    @Test
    fun `exhausted fallback no longer blocks later outbox reply`() = runBlocking {
        val fixture = fixture()
        fixture.updates.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "123", "original", ReplyParameters(1)),
        )
        fixture.updates.completeAgentUpdate("100", 12, PendingTelegramReply(12, "123", "next"))
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 13, 30) } returns GetUpdatesResponse(ok = true)
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
        } returns TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
        } returns TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
        } returns TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""") andThen
                TelegramApiResponse(HttpStatusCode.BadRequest, """{"ok":false}""")
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "next", null) } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        fixture.poller.start()
        try {
            eventually(8.seconds) {
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "original", ReplyParameters(1))
                }
                coVerify(exactly = 2) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "original", null)
                }
                coVerify(exactly = 3) {
                    fixture.telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
                }
                coVerify(exactly = 1) { fixture.telegram.sendMessageForToken("100:token", "123", "next", null) }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证重启后已登记三次的中间回退消息会删除整条长回复，不会发送原文尾部且下一项仍可投递。 */
    @Test
    fun `restarted exhausted middle fallback terminates the whole reply before sending its tail`() = runBlocking {
        val file = tempDirectory.resolve("restarted-exhausted-fallback.json")
        val source = "a".repeat(4096) + "b"
        val initialRepository = UpdatesRepository(file)
        initialRepository.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(
                updateId = 11,
                chatId = "123",
                text = source,
                nextChunkStart = 4096,
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
                deliveryAttempts = 3,
            ),
        )
        initialRepository.completeAgentUpdate("100", 12, PendingTelegramReply(12, "123", "next"))
        val updates = UpdatesRepository(file)
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("restarted-exhausted-fallback-settings.json"), barrier)
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        settings.saveSettings(AppSettings(telegramToken = "100:token"))
        barrier.complete(barrier.latestPendingGeneration())
        coEvery { telegram.getUpdatesForToken("100:token", 13, 30) } returns GetUpdatesResponse(ok = true)
        coEvery { telegram.sendMessageForToken("100:token", "123", "b", null) } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        coEvery { telegram.sendMessageForToken("100:token", "123", "next", null) } returns
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")

        poller.start()
        try {
            eventually {
                assertTrue(updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 0) {
                    telegram.sendMessageForToken("100:token", "123", "抱歉，上一条回复未能发送。", null)
                }
                coVerify(exactly = 0) { telegram.sendMessageForToken("100:token", "123", "b", null) }
                coVerify(exactly = 1) { telegram.sendMessageForToken("100:token", "123", "next", null) }
            }
        } finally {
            poller.close()
        }
    }

    /** 验证已轮换 token 的迟到永久拒绝不能更新当前 outbox 的原文阶段或连续拒绝计数。 */
    @Test
    fun `late old token permanent rejection cannot advance outbox delivery state`() = runBlocking {
        listOf(
            TelegramApiResponse(HttpStatusCode.BadRequest, "not-json"),
            TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false,"error_code":400}"""),
        ).forEach { oldResponse ->
            val oldSendStarted = CompletableDeferred<Unit>()
            val releaseOldResponse = CompletableDeferred<Unit>()
            val newSendStarted = CompletableDeferred<Unit>()
            val releaseNewResponse = CompletableDeferred<Unit>()
            val fixture = fixture()
            fixture.updates.completeAgentUpdate(
                "100",
                11,
                PendingTelegramReply(11, "123", "original", ReplyParameters(1)),
            )
            fixture.saveSettings(AppSettings(telegramToken = "100:old"))
            coEvery { fixture.telegram.getUpdatesForToken("100:old", 12, 30) } returns GetUpdatesResponse(ok = true)
            coEvery { fixture.telegram.getUpdatesForToken("100:new", 12, 30) } returns GetUpdatesResponse(ok = true)
            coEvery {
                fixture.telegram.sendMessageForToken("100:old", "123", "original", ReplyParameters(1))
            } coAnswers {
                oldSendStarted.complete(Unit)
                withContext(NonCancellable) { releaseOldResponse.await() }
                oldResponse
            }
            coEvery {
                fixture.telegram.sendMessageForToken("100:new", "123", "original", ReplyParameters(1))
            } coAnswers {
                newSendStarted.complete(Unit)
                withContext(NonCancellable) { releaseNewResponse.await() }
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            }

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { oldSendStarted.await() }
                fixture.saveSettings(AppSettings(telegramToken = "100:new"))
                releaseOldResponse.complete(Unit)
                withTimeout(2.seconds) { newSendStarted.await() }

                eventually {
                    val pending = fixture.updates.getPendingTelegramReplies("100").single()
                    assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                    assertEquals(0, pending.permanentRejectionCount)
                    coVerify(exactly = 0) {
                        fixture.telegram.sendMessageForToken(
                            "100:new",
                            "123",
                            "抱歉，上一条回复未能发送。",
                            null,
                        )
                    }
                }
            } finally {
                releaseOldResponse.complete(Unit)
                releaseNewResponse.complete(Unit)
                fixture.poller.close()
            }
        }
    }

    /** 验证投递次数的持久化失败时不会向 Telegram 发送尚未安全登记的 outbox 回复。 */
    @Test
    fun `outbox write failure before delivery does not send Telegram request`() = runBlocking {
        val file = tempDirectory.resolve("failed-outbox-delivery-state.json")
        UpdatesRepository(file).completeAgentUpdate("100", 11, PendingTelegramReply(11, "123", "reply"))
        val writeAttempted = CompletableDeferred<Unit>()
        val updates = UpdatesRepository(file) {
            writeAttempted.complete(Unit)
            throw IOException("injected delivery state write failure")
        }
        val barrier = ModelSwitchBarrier()
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("failed-outbox-settings.json"), barrier)
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        settings.saveSettings(AppSettings(telegramToken = "100:token"))
        barrier.complete(barrier.latestPendingGeneration())
        coEvery { telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)

        poller.start()
        try {
            withTimeout(2.seconds) { writeAttempted.await() }
            coVerify(exactly = 0) { telegram.sendMessageForToken("100:token", "123", "reply", null) }
            assertEquals(0, updates.getPendingTelegramReplies("100").single().deliveryAttempts)
        } finally {
            poller.close()
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
                val pending = fixture.updates.getPendingTelegramReplies("100").single()
                assertEquals(11, pending.updateId)
                assertEquals("123", pending.chatId)
                assertEquals("reply", pending.text)
                assertEquals(ReplyParameters(1), pending.replyParameters)
                assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
                assertEquals(0, pending.permanentRejectionCount)
                assertTrue(pending.deliveryAttempts >= 1)
                coVerify(exactly = 1) { fixture.agent.sendMessage("public-durable") }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 outbox 绝不允许写入源更新偏移量尚未持久化确认的记录。 */
    @Test
    fun `outbox rejects reply whose update offset is not confirmed`() = runBlocking {
        val fixture = fixture()
        assertFailsWith<IllegalArgumentException> {
            fixture.updates.updateData("100") { current ->
                current.copy(pendingTelegramReplies = listOf(PendingTelegramReply(11, "123", "must not send")))
            }
        }
        assertTrue(fixture.updates.getData("100").pendingTelegramReplies.isEmpty())
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

    /** 验证本地失败先持久化检查点；重启后的唯一请求 offset 仍为该目标且不会再次初始化。 */
    @Test
    fun `persisted retry checkpoint survives restart and remains the polling offset source`() = runBlocking {
        val file = tempDirectory.resolve("persisted-retry-checkpoint.json")
        val firstRetry = CompletableDeferred<Unit>()
        val first = fixture(
            retryDelay = {
                firstRetry.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
            updatesOverride = UpdatesRepository(file),
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        first.updates.saveLastUpdateId("100", 10)
        first.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { first.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "retry"))),
        )
        every { first.agent.isAiFeatureEnabled(any()) } throws IllegalStateException("temporarily unavailable")

        first.poller.start()
        try {
            withTimeout(2.seconds) { firstRetry.await() }
            assertEquals(
                RetryCheckpoint(11, first.updates.getData("100").retryCheckpoint?.firstRetryAtMillis ?: -1, 1),
                first.updates.getData("100").retryCheckpoint
            )
            assertEquals(10, first.updates.getData("100").lastUpdateId)
        } finally {
            first.poller.close()
        }

        val secondRequest = CompletableDeferred<Unit>()
        val holdSecondRetry = CompletableDeferred<Unit>()
        val second = fixture(
            retryDelay = { holdSecondRetry.await() },
            updatesOverride = UpdatesRepository(file),
        )
        second.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { second.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            secondRequest.complete(Unit)
            GetUpdatesResponse(ok = true)
        }

        second.poller.start()
        try {
            withTimeout(2.seconds) { secondRequest.await() }
            coVerify(exactly = 0) { second.telegram.getUpdatesForToken("100:token", -1, 0) }
            assertEquals(11, second.updates.getData("100").retryCheckpoint?.targetUpdateId)
            assertTrue((second.updates.getData("100").retryCheckpoint?.retryCount ?: 0) >= 2)
        } finally {
            holdSecondRetry.complete(Unit)
            second.poller.close()
        }
    }

    /** 验证 Telegram 首个可用更新高于重试目标时只审计并跳过目标，下一轮才请求 target 加一。 */
    @Test
    fun `expired retry checkpoint gap advances only target before next polling round`() = runBlocking {
        val nextRoundStarted = CompletableDeferred<Unit>()
        val releaseNextRound = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", 10)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            fixture.updates.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(15, message = authorizedMessage(1, chat, text = "must-not-run"))),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 12, 30) } coAnswers {
            nextRoundStarted.complete(Unit)
            releaseNextRound.await()
            GetUpdatesResponse(ok = true)
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { nextRoundStarted.await() }
            assertEquals(11, fixture.updates.getData("100").lastUpdateId)
            assertNull(fixture.updates.getData("100").retryCheckpoint)
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
        } finally {
            releaseNextRound.complete(Unit)
            fixture.poller.close()
        }
    }

    /** 验证 gap 判定前先调和 FINAL 和孤立 IN_PROGRESS，绝不跳过 durable outbox 或重放 Agent。 */
    @Test
    fun `retry gap reconciles durable journal before it can skip target`() = runBlocking {
        listOf("final", "in-progress").forEach { mode ->
            val file = tempDirectory.resolve("retry-gap-durable-$mode.json")
            UpdatesRepository(file).apply {
                saveLastUpdateId("100", 10)
                assertEquals(AgentTurnClaim.CLAIMED, claimAgentTurn("100", 11, "123", ReplyParameters(1)))
                if (mode == "final") {
                    assertNotNull(finalizeAgentTurn("100", 11, "saved"))
                }
                assertEquals(
                    RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
                    recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
                )
            }
            val nextRoundStarted = CompletableDeferred<Unit>()
            val releaseNextRound = CompletableDeferred<Unit>()
            val fixture = fixture(updatesOverride = UpdatesRepository(file))
            val chat = Chat(id = 123L, type = "private", firstName = "Test")
            fixture.saveSettings(AppSettings(telegramToken = "100:$mode"))
            coEvery { fixture.telegram.getUpdatesForToken("100:$mode", 11, 30) } returns GetUpdatesResponse(
                ok = true,
                result = listOf(Update(15, message = authorizedMessage(1, chat, text = "must-not-run"))),
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:$mode", 12, 30) } coAnswers {
                nextRoundStarted.complete(Unit)
                releaseNextRound.await()
                GetUpdatesResponse(ok = true)
            }
            if (mode == "final") {
                coEvery {
                    fixture.telegram.sendMessageForToken("100:$mode", "123", "saved", ReplyParameters(1))
                } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")
            }

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { nextRoundStarted.await() }
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertNull(fixture.updates.getData("100").retryCheckpoint)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                if (mode == "final") {
                    assertEquals("saved", fixture.updates.getPendingTelegramReplies("100").single().text)
                } else {
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            } finally {
                releaseNextRound.complete(Unit)
                fixture.poller.close()
            }
        }
    }

    /** 验证仍有本地 Agent owner 的 durable IN_PROGRESS 不会被 gap 静默跳过。 */
    @Test
    fun `retry gap retains checkpoint while durable turn still has local owner`() = runBlocking {
        val pollStarted = CompletableDeferred<Unit>()
        val releaseGapResponse = CompletableDeferred<Unit>()
        val agentStarted = CompletableDeferred<Unit>()
        val retryStarted = CompletableDeferred<Unit>()
        val fixture = fixture(retryDelay = {
            retryStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        })
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            pollStarted.complete(Unit)
            releaseGapResponse.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(Update(15, message = authorizedMessage(15, chat, text = "must-not-run"))),
            )
        }
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("live") } coAnswers {
            agentStarted.complete(Unit)
            CompletableDeferred<String>().await()
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { pollStarted.await() }
            fixture.poller.handleUpdate(Update(11, message = authorizedMessage(1, chat, text = "live")))
            withTimeout(2.seconds) { agentStarted.await() }
            assertEquals(
                RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
                fixture.updates.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
            )
            releaseGapResponse.complete(Unit)
            withTimeout(2.seconds) { retryStarted.await() }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertEquals(11, fixture.updates.getData("100").retryCheckpoint?.targetUpdateId)
            assertTrue((fixture.updates.getData("100").retryCheckpoint?.retryCount ?: 0) >= 2)
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", 12, 30) }
            coVerify(exactly = 1) { fixture.agent.sendMessage("live") }
        } finally {
            releaseGapResponse.complete(Unit)
            fixture.poller.close()
        }
    }

    /** 验证检查点原子写失败仍会唤醒等待中的消费者，恢复后同一 offset 可再次完成。 */
    @Test
    fun `checkpoint write failure resumes waiting consumer and recovers same update`() = runBlocking {
        val file = tempDirectory.resolve("retry-checkpoint-write-failure-resume.json")
        var rejectCheckpointWrite = true
        val updates = UpdatesRepository(file) { state ->
            if (rejectCheckpointWrite && state.bots["100"]?.retryCheckpoint?.targetUpdateId == 11L) {
                rejectCheckpointWrite = false
                throw IOException("injected retry checkpoint write failure")
            }
        }
        val fixture = fixture(retryDelay = {}, updatesOverride = updates)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returnsMany listOf(
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(11, message = authorizedMessage(1, chat, voice = Voice("voice-id", "unique", duration = 1))),
                ),
            ),
            GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message = authorizedMessage(1, chat, text = "recovered")))
            ),
        )
        coEvery { fixture.telegram.getFileForToken("100:token", "voice-id") } throws IOException("voice unavailable")
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("recovered") } returns ""

        fixture.poller.start()
        try {
            eventually {
                assertFalse(rejectCheckpointWrite)
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertNull(fixture.updates.getData("100").retryCheckpoint)
                coVerify(exactly = 1) { fixture.agent.sendMessage("recovered") }
                coVerify(atLeast = 2) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
            }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 `lastUpdateId` 为零但已有检查点时，轮询仍从检查点而非 `-1` 初始化请求开始。 */
    @Test
    fun `checkpoint suppresses initial minus one request even when last offset is zero`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val fixture = fixture(retryDelay = {
            retryStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        })
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(1, 100, 1)),
            fixture.updates.recordRetryCheckpoint("100", 1, expectedTargetUpdateId = null, nowMillis = 100),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:zero"))
        coEvery { fixture.telegram.getUpdatesForToken("100:zero", 1, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:zero", 1, 30) }
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:zero", -1, 0) }
            assertEquals(0, fixture.updates.getData("100").lastUpdateId)
            assertEquals(1, fixture.updates.getData("100").retryCheckpoint?.targetUpdateId)
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证比检查点更早的响应不会成为 gap 证据，也不会执行同批更高更新的副作用。 */
    @Test
    fun `earlier response keeps retry checkpoint and does not execute higher update`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val fixture = fixture(retryDelay = {
            retryStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        })
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            fixture.updates.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(10, message = authorizedMessage(10, chat, text = "earlier")),
                Update(15, message = authorizedMessage(15, chat, text = "must-not-run")),
            ),
        )

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertEquals(11, fixture.updates.getData("100").retryCheckpoint?.targetUpdateId)
            assertTrue((fixture.updates.getData("100").retryCheckpoint?.retryCount ?: 0) >= 2)
            coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", 12, 30) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证 gap 的原子提交失败不会丢失检查点或推进 offset；重启后仍从原目标请求。 */
    @Test
    fun `failed retry gap commit retains checkpoint and restart requests original target`() = runBlocking {
        val file = tempDirectory.resolve("retry-gap-commit-failure.json")
        var rejectGapCommit = true
        val updates = UpdatesRepository(file) { state ->
            val bot = state.bots["100"]
            if (rejectGapCommit && bot?.lastUpdateId == 11L && bot.retryCheckpoint == null) {
                rejectGapCommit = false
                throw IOException("injected retry gap commit failure")
            }
        }
        updates.saveLastUpdateId("100", 10)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            updates.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        val retryStarted = CompletableDeferred<Unit>()
        val first = fixture(
            retryDelay = {
                retryStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
            updatesOverride = updates,
        )
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        first.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { first.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(15, message = authorizedMessage(15, chat, text = "must-not-run"))),
        )

        first.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            assertFalse(rejectGapCommit)
            assertEquals(10, first.updates.getData("100").lastUpdateId)
            assertEquals(11, first.updates.getData("100").retryCheckpoint?.targetUpdateId)
        } finally {
            first.poller.close()
        }

        val restartedRequest = CompletableDeferred<Unit>()
        val holdRestart = CompletableDeferred<Unit>()
        val restarted = fixture(
            retryDelay = { holdRestart.await() },
            updatesOverride = UpdatesRepository(file),
        )
        restarted.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { restarted.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            restartedRequest.complete(Unit)
            GetUpdatesResponse(ok = true)
        }
        restarted.poller.start()
        try {
            withTimeout(2.seconds) { restartedRequest.await() }
            coVerify(exactly = 0) { restarted.telegram.getUpdatesForToken("100:token", -1, 0) }
        } finally {
            holdRestart.complete(Unit)
            restarted.poller.close()
        }
    }

    /** 验证即使检查点年龄和次数都极大，空响应也不是 gap 证据，状态必须原样保留。 */
    @Test
    fun `empty response does not skip an old saturated retry checkpoint`() = runBlocking {
        val retryStarted = CompletableDeferred<Unit>()
        val file = tempDirectory.resolve("old-saturated-retry-checkpoint.json")
        file.writeText(
            com.unscientificjszhai.tgp.utils.ConfigJson.encodeToString(
                com.unscientificjszhai.tgp.repository.BotUpdatesData(
                    bots = mapOf(
                        "100" to com.unscientificjszhai.tgp.repository.UpdatesData(
                            lastUpdateId = 10,
                            retryCheckpoint = RetryCheckpoint(11, 0, Long.MAX_VALUE),
                        ),
                    ),
                ),
            ),
        )
        val fixture = fixture(
            retryDelay = {
                retryStarted.complete(Unit)
                CompletableDeferred<Unit>().await()
            },
            updatesOverride = UpdatesRepository(file),
        )
        fixture.saveSettings(AppSettings(telegramToken = "100:token"))
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(ok = true)

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { retryStarted.await() }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertEquals(RetryCheckpoint(11, 0, Long.MAX_VALUE), fixture.updates.getData("100").retryCheckpoint)
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:token", 12, 30) }
        } finally {
            fixture.poller.close()
        }
    }

    /** 验证检查点按 bot 隔离，旧 token 代次的检查点不会改变新 bot 会话的 offset。 */
    @Test
    fun `retry checkpoint stays isolated across bot and token generation`() = runBlocking {
        val oldPollStarted = CompletableDeferred<Unit>()
        val holdOldPoll = CompletableDeferred<Unit>()
        val newPollStarted = CompletableDeferred<Unit>()
        val holdNewPoll = CompletableDeferred<Unit>()
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", 10)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            fixture.updates.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        fixture.updates.saveLastUpdateId("200", 20)
        fixture.saveSettings(AppSettings(telegramToken = "100:old"))
        coEvery { fixture.telegram.getUpdatesForToken("100:old", 11, 30) } coAnswers {
            oldPollStarted.complete(Unit)
            holdOldPoll.await()
            GetUpdatesResponse(ok = true)
        }
        coEvery { fixture.telegram.getUpdatesForToken("200:new", 21, 30) } coAnswers {
            newPollStarted.complete(Unit)
            holdNewPoll.await()
            GetUpdatesResponse(ok = true)
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { oldPollStarted.await() }
            fixture.saveSettings(AppSettings(telegramToken = "200:new"))
            withTimeout(2.seconds) { newPollStarted.await() }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            assertEquals(11, fixture.updates.getData("100").retryCheckpoint?.targetUpdateId)
            assertEquals(20, fixture.updates.getData("200").lastUpdateId)
            assertNull(fixture.updates.getData("200").retryCheckpoint)
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("200:new", 11, 30) }
        } finally {
            holdOldPoll.complete(Unit)
            holdNewPoll.complete(Unit)
            fixture.poller.close()
        }
    }

    private fun fixture(
        processingTimeout: Duration = 10.minutes,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
        updatesOverride: UpdatesRepository? = null,
    ): Fixture {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsRepository.forTesting(tempDirectory.resolve("settings-${System.nanoTime()}.json"), barrier)
        val updates = updatesOverride ?: UpdatesRepository(tempDirectory.resolve("updates-${System.nanoTime()}.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        every { agent.isAiFeatureEnabled(any()) } returns true
        every { agent.resetSession() } returns Job().apply { complete() }
        // 生产路径直接调用媒体重载；既有文本测试仍可只 stub 文本便利重载。
        coEvery { agent.sendMessage(any(), any()) } coAnswers {
            firstArg<String?>()?.let { agent.sendMessage(it) } ?: ""
        }
        coEvery { agent.withReadyService<Any?>(any()) } coAnswers {
            firstArg<suspend (AgentService) -> Any?>().invoke(agent)
        }
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
        // 只能完成本次设置写入创建的屏障；认证或 token 轮换中的外部代次必须继续保持封闭。
        barrier.completeSettingsThrough(this.settings.settingsUpdateFlow.value.switchGeneration)
    }

    private suspend fun eventually(timeout: Duration = 3.seconds, assertion: () -> Unit) {
        withTimeout(timeout) {
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
        fixture.updates.saveLastUpdateId("100", 99)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:A", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 100, 30) } coAnswers {
            firstBatchRequested.complete(Unit)
            allowFirstBatch.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(110, message = authorizedMessage(110, chat, text = "prefix")),
                    Update(120, message = authorizedMessage(120, chat, text = "rejected")),
                    Update(121, message = authorizedMessage(121, chat, text = "suffix")),
                ),
            )
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 120, 30) } coAnswers {
            if (++retryFetchCount == 1) {
                GetUpdatesResponse(ok = true)
            } else {
                GetUpdatesResponse(
                    ok = true,
                    result = listOf(
                        Update(120, message = authorizedMessage(120, chat, text = "rejected")),
                        Update(121, message = authorizedMessage(121, chat, text = "suffix")),
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
                assertEquals(110, fixture.updates.getData("100").lastUpdateId)
                coVerify(exactly = 1) { fixture.agent.sendMessage("prefix") }
                coVerify(exactly = 0) { fixture.agent.sendMessage("suffix") }
            }
            assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })
            coVerify(exactly = 0) { fixture.telegram.getUpdatesForToken("100:A", 120, 30) }

            allowRetry.complete(Unit)
            eventually {
                assertEquals(121, fixture.updates.getData("100").lastUpdateId)
                coVerify(atLeast = 2) { fixture.telegram.getUpdatesForToken("100:A", 120, 30) }
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

    private fun sessionPollJob(session: Any): Job = assertNotNull(
        session.javaClass.getDeclaredField("pollJob").apply { isAccessible = true }.get(session) as Job?,
    )

    private suspend fun pollOnceForTesting(poller: MessagePoller, session: Any): Any =
        suspendCoroutine { continuation ->
            val method = MessagePoller::class.java.declaredMethods.single { it.name == "pollOnce" }.apply {
                isAccessible = true
            }
            val returned = try {
                if (method.parameterCount == 2) {
                    method.invoke(poller, session, continuation)
                } else {
                    method.invoke(poller, session, null, continuation)
                }
            } catch (e: InvocationTargetException) {
                continuation.resumeWithException(checkNotNull(e.cause))
                return@suspendCoroutine
            }
            if (returned !== COROUTINE_SUSPENDED) {
                continuation.resume(returned)
            }
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

    /** 在真实委派服务的两种调用路径前注入同一外部切换，用于复现嵌套屏障循环。 */
    private class SwitchInjectingAgentService(
        private val delegate: AgentService,
        private val barrier: ModelSwitchBarrier,
        private val switchScope: CoroutineScope,
    ) : AgentService() {
        val switchStarted = CompletableDeferred<Unit>()
        val switchCompleted = CompletableDeferred<Unit>()

        override val currentModel: String
            get() = delegate.currentModel

        override val availableModels: List<String>
            get() = delegate.availableModels

        override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean = delegate.isAiFeatureEnabled(aiSettings)

        override fun switchModel(modelName: String): Job? = delegate.switchModel(modelName)

        override suspend fun updateModel(): ModelSnapshot? = delegate.updateModel()

        override fun resetSession(): Job? = delegate.resetSession()

        override fun initializationJob(): Job? = delegate.initializationJob()

        override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
            beginInjectedSwitch()
            return delegate.sendMessage(text, mediaData)
        }

        override fun close(): Job? = delegate.close()

        override suspend fun <T> withReadyService(block: suspend (AgentService) -> T): T =
            delegate.withReadyService { readyAgent ->
                beginInjectedSwitch()
                block(readyAgent)
            }

        private fun beginInjectedSwitch() {
            check(!switchStarted.isCompleted) { "The test wrapper only supports one injected switch." }
            val generation = barrier.beginExternalSwitch()
            switchStarted.complete(Unit)
            switchScope.launch {
                try {
                    barrier.awaitInFlightRequests()
                } finally {
                    barrier.complete(generation)
                    switchCompleted.complete(Unit)
                }
            }
        }
    }
}
