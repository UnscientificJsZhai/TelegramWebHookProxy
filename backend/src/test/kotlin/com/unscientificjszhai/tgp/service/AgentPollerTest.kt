package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.GeminiAgentService
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AgentPollerTest {

    private lateinit var telegramService: TelegramService
    private lateinit var geminiAgentService: GeminiAgentService
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var updatesRepository: UpdatesRepository
    private lateinit var agentPoller: AgentPoller

    @BeforeTest
    fun setup() {
        telegramService = mockk()
        geminiAgentService = mockk()
        settingsRepository = mockk()
        updatesRepository = mockk()

        agentPoller = AgentPoller(
            telegramService, geminiAgentService, settingsRepository, updatesRepository
        )
    }

    @Test
    fun testHandleAiMessage() = runTest {
        val chatId = "123456"
        val messageId = 100L
        val userMessage = "Hello AI"
        val aiReply = "Hello Human"

        coEvery { telegramService.sendChatAction(chatId, "typing") } returns mockk()
        coEvery { geminiAgentService.sendMessage(userMessage) } returns aiReply
        coEvery { telegramService.sendMessage(chatId, aiReply, any()) } returns mockk()

        agentPoller.handleAiMessage(chatId, userMessage, messageId)

        coVerify { telegramService.sendChatAction(chatId, "typing") }
        coVerify { geminiAgentService.sendMessage(userMessage) }
        coVerify {
            telegramService.sendMessage(
                chatId, aiReply, match { it.messageId == messageId })
        }
    }

    @Test
    fun testHandleCommandReset() = runTest {
        val chatId = "123456"
        val messageId = 100L
        val command = "/reset"

        coEvery { geminiAgentService.resetSession() } just Runs
        coEvery { telegramService.sendMessage(chatId, any(), any()) } returns mockk()

        agentPoller.handleCommand(chatId, command, messageId)

        coVerify { geminiAgentService.resetSession() }
        coVerify {
            telegramService.sendMessage(
                chatId, "会话已重置", match { it.messageId == messageId })
        }
    }
}
