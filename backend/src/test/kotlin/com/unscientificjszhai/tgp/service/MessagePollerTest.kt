package com.unscientificjszhai.tgp.service

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.GetUpdatesResponse
import com.unscientificjszhai.tgp.models.Message
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSnapshot
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.slf4j.LoggerFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 消息轮询服务的命令、AI 回复和上下文清理行为测试设计。
 */
class MessagePollerTest {

    private lateinit var telegramService: TelegramService
    private lateinit var agentService: AgentService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var updatesRepository: UpdatesRepository
    private lateinit var messagePoller: MessagePoller
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>

    @BeforeTest
    fun setup() {
        telegramService = mockk()
        agentService = mockk()
        settingsRepository = mockk()
        updatesRepository = mockk()
        settingsFlow = MutableStateFlow(AppSettings(ai = AISettings(agentEnabled = true, agentChatId = "123456")))
        every { settingsRepository.settingsFlow } returns settingsFlow

        messagePoller = MessagePoller(
            CoroutineScope(kotlin.coroutines.EmptyCoroutineContext),
            telegramService, agentService, settingsRepository, updatesRepository
        )
    }

    /**
     * 验证文本 AI 消息处理的设计。
     *
     * 验证代理回复会被发送为对原消息的回复。
     */
    @Test
    fun testHandleAiMessage() = runTest {
        val chatId = "123456"
        val messageId = 100L
        val userMessage = "Hello AI"
        val aiReply = "Hello Human"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage(userMessage) } returns aiReply
        coEvery { telegramService.sendMessage(chatId, aiReply, any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, userMessage, messageId)

        coVerify { telegramService.sendChatAction(chatId, "typing") }
        coVerify { agentService.sendMessage(userMessage) }
        coVerify {
            telegramService.sendMessage(
                chatId, aiReply, match { it.messageId == messageId })
        }
    }

    /**
     * 验证 `/keep` 命令更新上下文计时的设计。
     *
     * 验证命令只刷新最后回复时间而不发送消息。
     */
    @Test
    fun testKeepCommandUpdatesLastReplyTimeWithoutReplying() = runTest {
        val chatId = "123456"
        val beforeKeep = System.currentTimeMillis()

        messagePoller.handleCommand(chatId, "/keep", 100L)

        val lastReplyAt = getLastAiReplyAtMillis()
        assert(lastReplyAt != null)
        assert(lastReplyAt!! >= beforeKeep)
        coVerify(exactly = 0) { telegramService.sendMessage(any(), any(), any()) }
        coVerify(exactly = 0) { agentService.resetSession() }
        coVerify(exactly = 0) { agentService.sendMessage(any<String>()) }
    }

    /**
     * 验证 `/model` 查询命令刷新模型列表的设计。
     *
     * 验证返回内容使用最新模型快照而非旧缓存。
     */
    @Test
    fun testModelCommandUsesRefreshedModelList() = runTest {
        val chatId = "123456"
        coEvery { agentService.updateModel() } returns ModelSnapshot(
            currentModel = "fresh-model",
            availableModels = listOf("fresh-model", "another-model"),
        )
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns telegramOkResponse()

        messagePoller.handleCommand(chatId, "/model", 100L)

        coVerifyOrder {
            agentService.updateModel()
            telegramService.sendMessage(
                chatId,
                "当前可用模型列表：\n✅ fresh-model\n      another-model\n\n使用 `/model <模型名称>` 切换模型。",
                any(),
            )
        }
    }

    /**
     * 验证 `/model` 刷新失败时的反馈设计。
     *
     * 验证刷新失败会提示错误且不会回退展示缓存模型。
     */
    @Test
    fun testModelCommandReportsRefreshFailureWithoutUsingCachedModels() = runTest {
        val chatId = "123456"
        coEvery { agentService.updateModel() } returns null
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns telegramOkResponse()

        messagePoller.handleCommand(chatId, "/model", 100L)

        coVerify {
            telegramService.sendMessage(
                chatId,
                "获取可用模型列表失败，请稍后重试。",
                any(),
            )
        }
        verify(exactly = 0) { agentService.currentModel }
        verify(exactly = 0) { agentService.availableModels }
    }

    /**
     * 验证 `/model` 选择模型的持久化设计。
     *
     * 验证规范模型名称会保存，并由设置流触发后续模型切换。
     */
    @Test
    fun testModelCommandPersistsCanonicalModelNameAndLetsSettingsFlowSwitchIt() = runTest {
        val chatId = "123456"
        every { agentService.availableModels } returns listOf("models/gemini-test")
        every { settingsRepository.saveSettings(any()) } returns Unit
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns telegramOkResponse()

        messagePoller.handleCommand(chatId, "/model gemini-test", 100L)

        verify {
            settingsRepository.saveSettings(
                match { it.ai?.selectedModel == "models/gemini-test" },
            )
        }
        verify(exactly = 0) { agentService.switchModel(any()) }
        coVerify {
            telegramService.sendMessage(
                chatId,
                "已保存模型选择，正在切换模型并重置会话：models/gemini-test",
                any(),
            )
        }
    }

    /**
     * 验证 `/reset` 命令的模型选择保留设计。
     *
     * 验证重置会话不会修改已保存的模型选择。
     */
    @Test
    fun testResetCommandDoesNotPersistSelectedModel() = runTest {
        val chatId = "123456"
        every { agentService.resetSession() } returns completedJob()
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns telegramOkResponse()

        messagePoller.handleCommand(chatId, "/reset", 100L)

        verify(exactly = 0) { settingsRepository.saveSettings(any()) }
    }

    /**
     * 验证关闭自动清理时的会话保留设计。
     *
     * 验证处理消息不会重置当前会话。
     */
    @Test
    fun testAutoCleanDisabledDoesNotResetSession() = runTest {
        val chatId = "123456"
        val userMessage = "Hello AI"
        setLastAiReplyAtMillis(System.currentTimeMillis() - 120_000)

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage(userMessage) } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, userMessage, 100L)

        coVerify(exactly = 0) { agentService.resetSession() }
        coVerify { agentService.sendMessage(userMessage) }
    }

    /**
     * 验证 `/keep` 命令延长自动清理窗口的设计。
     *
     * 验证命令执行后在清理窗口内处理消息不会重置会话。
     */
    @Test
    fun testKeepCommandExtendsAutoCleanWindow() = runTest {
        val chatId = "123456"
        val userMessage = "Hello AI"
        settingsFlow.value = AppSettings(
            ai = AISettings(
                agentEnabled = true,
                agentChatId = chatId,
                autoCleanContextIntervalMinutes = 5,
            ),
        )
        setLastAiReplyAtMillis(System.currentTimeMillis() - 360_000)

        messagePoller.handleCommand(chatId, "/keep", 100L)

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage(userMessage) } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, userMessage, 101L)

        coVerify(exactly = 0) { agentService.resetSession() }
        coVerify(exactly = 0) {
            telegramService.sendMessage(chatId, match { it.contains("自动清理上下文") }, null)
        }
        coVerify { agentService.sendMessage(userMessage) }
    }

    /**
     * 验证自动清理期限未到时的会话保留设计。
     *
     * 验证未超过配置间隔不会触发会话重置。
     */
    @Test
    fun testAutoCleanNotExpiredDoesNotResetSession() = runTest {
        val chatId = "123456"
        val userMessage = "Hello AI"
        settingsFlow.value = AppSettings(
            ai = AISettings(
                agentEnabled = true,
                agentChatId = chatId,
                autoCleanContextIntervalMinutes = 60,
            ),
        )
        setLastAiReplyAtMillis(System.currentTimeMillis() - 30_000)

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage(userMessage) } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, userMessage, 100L)

        coVerify(exactly = 0) { agentService.resetSession() }
        coVerify(exactly = 0) {
            telegramService.sendMessage(chatId, match { it.contains("自动清理上下文") }, null)
        }
        coVerify { agentService.sendMessage(userMessage) }
    }

    /**
     * 验证自动清理到期后的处理顺序设计。
     *
     * 验证先重置会话并发送通知，再将消息交给 AI 处理。
     */
    @Test
    fun testAutoCleanExpiredSendsNoticeThenProcessesMessage() = runTest {
        val chatId = "123456"
        val userMessage = "Hello AI"
        settingsFlow.value = AppSettings(
            ai = AISettings(
                agentEnabled = true,
                agentChatId = chatId,
                autoCleanContextIntervalMinutes = 1,
            ),
        )
        setLastAiReplyAtMillis(System.currentTimeMillis() - 120_000)

        coEvery { agentService.resetSession() } returns Job().also { it.complete() }
        coEvery { telegramService.sendMessage(chatId, match { it.contains("自动清理上下文") }) } returns mockk()
        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage(userMessage) } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, userMessage, 100L)

        coVerifyOrder {
            agentService.resetSession()
            telegramService.sendMessage(chatId, match { it.contains("自动清理上下文") })
            agentService.sendMessage(userMessage)
        }
    }

    /**
     * 验证静默自动清理的通知设计。
     *
     * 验证会话会重置但不会发送自动清理提示。
     */
    @Test
    fun testSilentAutoCleanDoesNotSendNotice() = runTest {
        val chatId = "123456"
        val userMessage = "Hello AI"
        settingsFlow.value = AppSettings(
            ai = AISettings(
                agentEnabled = true,
                agentChatId = chatId,
                autoCleanContextIntervalMinutes = 1,
                silentContextCleanup = true,
            ),
        )
        setLastAiReplyAtMillis(System.currentTimeMillis() - 120_000)

        coEvery { agentService.resetSession() } returns Job().also { it.complete() }
        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage(userMessage) } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, userMessage, 100L)

        coVerify { agentService.resetSession() }
        coVerify(exactly = 0) {
            telegramService.sendMessage(chatId, match { it.contains("自动清理上下文") }, null)
        }
        coVerify { agentService.sendMessage(userMessage) }
    }

    /**
     * 验证成功发送 AI 回复后的计时更新设计。
     *
     * 验证仅在回复发送成功后更新最后回复时间。
     */
    @Test
    fun testSuccessfulAiReplyUpdatesLastReplyTime() = runTest {
        val chatId = "123456"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage("Hello AI") } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, "Hello AI", 100L)

        assert(getLastAiReplyAtMillis() != null)
    }

    /**
     * 验证空 AI 回复的计时处理设计。
     *
     * 验证空回复不会更新最后回复时间。
     */
    @Test
    fun testBlankAiReplyDoesNotUpdateLastReplyTime() = runTest {
        val chatId = "123456"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage("Hello AI") } returns ""

        messagePoller.handleAiMessage(chatId, "Hello AI", 100L)

        assert(getLastAiReplyAtMillis() == null)
    }

    /**
     * 验证 AI 回复发送失败时的计时处理设计。
     *
     * 验证发送异常不会更新最后回复时间。
     */
    @Test
    fun testSendFailureDoesNotUpdateLastReplyTime() = runTest {
        val chatId = "123456"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage("Hello AI") } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } throws RuntimeException("send failed")
        coEvery {
            telegramService.sendMessage(
                chatId,
                match { it.startsWith("AI 处理消息时出错") },
                any()
            )
        } returns mockk()

        messagePoller.handleAiMessage(chatId, "Hello AI", 100L)

        assert(getLastAiReplyAtMillis() == null)
    }

    /**
     * 验证首次初始化请求抛异常时不会开始常规轮询。
     *
     * 验证初始化失败不会保存偏移量，也不会以 `offset = 1` 重放历史消息。
     */
    @Test
    fun testInitialPollingExceptionDoesNotStartRegularPollingOrSaveOffset() = runBlocking {
        val regularPollCalled = CompletableDeferred<Unit>()
        prepareInitialPolling()
        coEvery { telegramService.getUpdates(-1, 0) } throws IllegalStateException("initialization failed")
        coEvery { telegramService.getUpdates(1, 30) } coAnswers {
            regularPollCalled.complete(Unit)
            GetUpdatesResponse(ok = true)
        }

        messagePoller.use { poller ->
            poller.start()
            eventually {
                coVerify(exactly = 1) { telegramService.getUpdates(-1, 0) }
            }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(2.seconds) { regularPollCalled.await() }
            }
            coVerify(exactly = 0) { telegramService.getUpdates(1, 30) }
            verify(exactly = 0) { updatesRepository.saveLastUpdateId(any()) }
        }
    }

    /**
     * 验证首次初始化返回 Telegram 错误时不会开始常规轮询。
     *
     * 验证 Telegram `ok = false` 响应不会保存偏移量，也不会以 `offset = 1` 重放历史消息。
     */
    @Test
    fun testFailedInitialPollingResponseDoesNotStartRegularPollingOrSaveOffset() = runBlocking {
        val regularPollCalled = CompletableDeferred<Unit>()
        val logger = LoggerFactory.getLogger(MessagePoller::class.java) as Logger
        val logAppender = ListAppender<ILoggingEvent>().also { it.start() }
        prepareInitialPolling()
        coEvery { telegramService.getUpdates(-1, 0) } returns GetUpdatesResponse(
            ok = false,
            errorCode = 401,
            description = "Unauthorized",
        )
        coEvery { telegramService.getUpdates(1, 30) } coAnswers {
            regularPollCalled.complete(Unit)
            GetUpdatesResponse(ok = true)
        }
        logger.addAppender(logAppender)

        messagePoller.start()
        try {
            eventually {
                coVerify(exactly = 1) { telegramService.getUpdates(-1, 0) }
            }
            eventually {
                assertTrue(
                    logAppender.list.any { event ->
                        event.throwableProxy?.message?.contains("401") == true &&
                                event.throwableProxy?.message?.contains("Unauthorized") == true
                    },
                )
            }
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(2.seconds) { regularPollCalled.await() }
            }
            coVerify(exactly = 0) { telegramService.getUpdates(1, 30) }
            verify(exactly = 0) { updatesRepository.saveLastUpdateId(any()) }
        } finally {
            messagePoller.close()
            logger.detachAppender(logAppender)
            logAppender.stop()
        }
    }

    /**
     * 验证首次初始化失败后会在重试成功前保持停止常规轮询。
     *
     * 验证下一次轮询会再次执行初始化；只有成功保存最新更新标识后，才会按其后的偏移量长轮询。
     */
    @Test
    fun testInitialPollingRetriesInitializationBeforeStartingRegularPolling() = runBlocking {
        val initializedUpdateId = 99L
        prepareInitialPolling()
        coEvery { telegramService.getUpdates(-1, 0) } throws IllegalStateException("initialization failed") andThen
                GetUpdatesResponse(ok = true, result = listOf(Update(updateId = initializedUpdateId)))
        coEvery { telegramService.getUpdates(initializedUpdateId + 1, 30) } returns GetUpdatesResponse(ok = true)

        messagePoller.use { poller ->
            poller.start()
            eventually(timeout = 8.seconds) {
                coVerify(exactly = 2) { telegramService.getUpdates(-1, 0) }
                verify(exactly = 1) { updatesRepository.saveLastUpdateId(initializedUpdateId) }
                coVerify(atLeast = 1) { telegramService.getUpdates(initializedUpdateId + 1, 30) }
            }
            coVerifyOrder {
                telegramService.getUpdates(-1, 0)
                telegramService.getUpdates(-1, 0)
                updatesRepository.saveLastUpdateId(initializedUpdateId)
                telegramService.getUpdates(initializedUpdateId + 1, 30)
            }
            coVerify(exactly = 0) { telegramService.getUpdates(1, 30) }
        }
    }

    /**
     * 验证轮询仅在队列处理结束后保存更新偏移量。
     *
     * 验证在队列消费者完成前不保存偏移量，并按更新标识而非 Telegram 返回列表的顺序逐条保存。
     */
    @Test
    fun testPollSavesOffsetsAfterQueueProcessingInUpdateIdOrder() = runBlocking(
        Dispatchers.Default.limitedParallelism(1),
    ) {
        val processingStarted = CompletableDeferred<Unit>()
        val allowProcessingToFinish = CompletableDeferred<Unit>()
        val chat = Chat(id = 123456, type = "private", firstName = "Test")
        val pendingUpdate = Update(
            updateId = 11,
            message = Message(messageId = 100, chat = chat, text = "pending"),
        )

        preparePolling(chat)
        coEvery { telegramService.getUpdates(11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(updateId = 12), pendingUpdate),
        )
        coEvery { telegramService.sendChatAction("123456", "typing") } returns telegramOkResponse()
        coEvery { agentService.sendMessage("pending") } coAnswers {
            processingStarted.complete(Unit)
            allowProcessingToFinish.await()
            "reply"
        }
        coEvery { telegramService.sendMessage("123456", "reply", any()) } returns telegramOkResponse()

        messagePoller.use {
            messagePoller.start()
            withTimeout(2.seconds) { processingStarted.await() }
            verify(exactly = 0) { updatesRepository.saveLastUpdateId(any()) }

            allowProcessingToFinish.complete(Unit)
            eventually {
                verify(exactly = 1) { updatesRepository.saveLastUpdateId(11) }
                verify(exactly = 1) { updatesRepository.saveLastUpdateId(12) }
            }
            verifyOrder {
                updatesRepository.saveLastUpdateId(11)
                updatesRepository.saveLastUpdateId(12)
            }
        }
    }

    /**
     * 验证关闭期间不会确认正在处理的更新。
     *
     * 验证消费者取消后，等待中的 AI 调用被取消且没有写入对应更新偏移量。
     */
    @Test
    fun testClosingPollerDoesNotSaveInFlightUpdateOffset() = runBlocking(
        Dispatchers.Default.limitedParallelism(1),
    ) {
        val processingStarted = CompletableDeferred<Unit>()
        val processingCancelled = CompletableDeferred<Unit>()
        val neverCompletes = CompletableDeferred<Unit>()
        val chat = Chat(id = 123456, type = "private", firstName = "Test")
        val pendingUpdate = Update(
            updateId = 11,
            message = Message(messageId = 100, chat = chat, text = "pending"),
        )

        preparePolling(chat)
        coEvery { telegramService.getUpdates(11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(pendingUpdate),
        )
        coEvery { telegramService.sendChatAction("123456", "typing") } returns telegramOkResponse()
        coEvery { agentService.sendMessage("pending") } coAnswers {
            processingStarted.complete(Unit)
            try {
                neverCompletes.await()
                "reply"
            } finally {
                processingCancelled.complete(Unit)
            }
        }

        messagePoller.use {
            messagePoller.start()
            withTimeout(2.seconds) { processingStarted.await() }

            messagePoller.close()

            withTimeout(2.seconds) { processingCancelled.await() }
            verify(exactly = 0) { updatesRepository.saveLastUpdateId(any()) }
        }
    }

    /**
     * 验证业务异常处理完既有反馈后仍会确认更新。
     *
     * 验证 AI 调用失败时，消费者在发送错误提示后推进偏移量，避免 Telegram 重复投递该更新。
     */
    @Test
    fun testPollSavesOffsetAfterBusinessFailureFeedback() = runBlocking(
        Dispatchers.Default.limitedParallelism(1),
    ) {
        val chat = Chat(id = 123456, type = "private", firstName = "Test")
        val failedUpdate = Update(
            updateId = 11,
            message = Message(messageId = 100, chat = chat, text = "failure"),
        )

        preparePolling(chat)
        coEvery { telegramService.getUpdates(11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(failedUpdate),
        )
        coEvery { telegramService.sendChatAction("123456", "typing") } returns telegramOkResponse()
        coEvery { agentService.sendMessage("failure") } throws IllegalStateException("agent failure")
        coEvery {
            telegramService.sendMessage(
                "123456",
                match { it.startsWith("AI 处理消息时出错") },
                any(),
            )
        } returns telegramOkResponse()

        messagePoller.use {
            messagePoller.start()
            eventually {
                verify(exactly = 1) { updatesRepository.saveLastUpdateId(11) }
            }
            coVerify {
                telegramService.sendMessage(
                    "123456",
                    match { it.startsWith("AI 处理消息时出错") },
                    any(),
                )
            }
        }
    }

    private fun preparePolling(chat: Chat) {
        settingsFlow.value = settingsFlow.value.copy(telegramToken = "test-token")
        messagePoller = MessagePoller(
            CoroutineScope(Dispatchers.Default),
            telegramService,
            agentService,
            settingsRepository,
            updatesRepository,
        )
        every { updatesRepository.lastUpdateId } returns 10L
        every { updatesRepository.chatsFlow } returns MutableStateFlow(
            listOf(ChatInfo(id = chat.id.toString(), title = chat.firstName!!, type = chat.type)),
        )
        every { updatesRepository.saveChats(any()) } returns Unit
        every { updatesRepository.saveLastUpdateId(any()) } returns Unit
    }

    private fun prepareInitialPolling() {
        var lastUpdateId = 0L
        settingsFlow.value = settingsFlow.value.copy(telegramToken = "test-token")
        messagePoller = MessagePoller(
            CoroutineScope(Dispatchers.Default),
            telegramService,
            agentService,
            settingsRepository,
            updatesRepository,
        )
        every { updatesRepository.lastUpdateId } answers { lastUpdateId }
        every { updatesRepository.chatsFlow } returns MutableStateFlow(emptyList())
        every { updatesRepository.saveChats(any()) } returns Unit
        every { updatesRepository.saveLastUpdateId(any()) } answers {
            lastUpdateId = firstArg()
        }
    }

    private suspend fun eventually(timeout: kotlin.time.Duration = 2.seconds, assertion: () -> Unit) {
        withTimeout(timeout) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    delay(10.milliseconds)
                }
            }
        }
    }

    private fun setLastAiReplyAtMillis(value: Long?) {
        lastAiReplyAtMillisField().set(messagePoller, value)
    }

    private fun getLastAiReplyAtMillis(): Long? =
        lastAiReplyAtMillisField().get(messagePoller) as Long?

    private fun lastAiReplyAtMillisField() =
        MessagePoller::class.java.getDeclaredField("lastAiReplyAtMillis").also {
            it.isAccessible = true
        }

    private fun telegramOkResponse(): HttpResponse =
        mockk {
            every { status } returns HttpStatusCode.OK
        }

    private fun completedJob(): Job = Job().also { it.complete() }
}
