package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
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
        coEvery { telegramService.sendMessage(chatId, match { it.startsWith("AI 处理消息时出错") }, any()) } returns mockk()

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
}
