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

    @Test
    fun testModelCommandPersistsCanonicalModelNameAfterSuccessfulSwitch() = runTest {
        val chatId = "123456"
        every { agentService.switchModel("gemini-test") } returns completedJob()
        every { agentService.currentModel } returns "models/gemini-test"
        every { settingsRepository.saveSettings(any()) } returns Unit
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns telegramOkResponse()

        messagePoller.handleCommand(chatId, "/model gemini-test", 100L)

        verify {
            settingsRepository.saveSettings(
                match { it.ai?.selectedModel == "models/gemini-test" },
            )
        }
        coVerify {
            telegramService.sendMessage(
                chatId,
                "已切换模型并重置会话，待处理消息已清空：models/gemini-test",
                any(),
            )
        }
    }

    @Test
    fun testResetCommandDoesNotPersistSelectedModel() = runTest {
        val chatId = "123456"
        every { agentService.resetSession() } returns completedJob()
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns telegramOkResponse()

        messagePoller.handleCommand(chatId, "/reset", 100L)

        verify(exactly = 0) { settingsRepository.saveSettings(any()) }
    }

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

    @Test
    fun testSuccessfulAiReplyUpdatesLastReplyTime() = runTest {
        val chatId = "123456"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage("Hello AI") } returns "Hello Human"
        coEvery { telegramService.sendMessage(chatId, "Hello Human", any()) } returns telegramOkResponse()

        messagePoller.handleAiMessage(chatId, "Hello AI", 100L)

        assert(getLastAiReplyAtMillis() != null)
    }

    @Test
    fun testBlankAiReplyDoesNotUpdateLastReplyTime() = runTest {
        val chatId = "123456"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { agentService.sendMessage("Hello AI") } returns ""

        messagePoller.handleAiMessage(chatId, "Hello AI", 100L)

        assert(getLastAiReplyAtMillis() == null)
    }

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
