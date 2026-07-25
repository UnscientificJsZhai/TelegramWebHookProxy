package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSnapshot
import io.ktor.client.statement.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

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
