package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 后台机器人轮询服务，负责监听 Telegram 消息并执行指令或调用 AI。
 */
class AgentPoller(
    private val telegramService: TelegramService,
    private val geminiAgentService: GeminiAgentService,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(AgentPoller::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    /**
     * 启动轮询。
     */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                try {
                    poll()
                } catch (_: CancellationException) {
                    break
                } catch (e: Exception) {
                    if (e is SocketTimeoutException || e.cause is SocketTimeoutException) {
                        logger.warn("Polling timeout: ${e.message ?: "Socket timeout expired"}")
                    } else {
                        logger.error("Error during polling", e)
                        delay(5000.milliseconds) // 发生错误时等待 5 秒
                    }
                }
            }
        }
        logger.info("Agent poller started.")
    }

    private suspend fun poll() {
        var lastStoredId = updatesRepository.lastUpdateId

        if (lastStoredId == 0L) {
            // 第一次运行，先获取最新的 update_id 以避免处理历史消息
            try {
                val initialResponse = telegramService.getUpdates(offset = -1, timeout = 0)
                if (initialResponse.ok && initialResponse.result.isNotEmpty()) {
                    lastStoredId = initialResponse.result.last().update_id
                    updatesRepository.saveLastUpdateId(lastStoredId)
                    logger.info("Initialized lastUpdateId to $lastStoredId")
                }
            } catch (e: Exception) {
                logger.warn("Failed to initialize lastUpdateId", e)
            }
            delay(1000.milliseconds)
        }

        val offset = lastStoredId + 1
        val response = telegramService.getUpdates(offset = offset, timeout = 30)

        if (response.ok) {
            var lastId = lastStoredId
            val currentChats = updatesRepository.chatsFlow.value.associateBy { it.id }.toMutableMap()
            var chatsUpdated = false

            for (update in response.result) {
                val chat = update.message?.chat ?: update.channel_post?.chat ?: update.my_chat_member?.chat

                if (chat != null) {
                    val title = chat.title ?: chat.username ?: "${chat.first_name ?: ""} ${chat.last_name ?: ""}".trim()

                    val chatInfo = ChatInfo(
                        id = chat.id.toString(), title = title, type = chat.type
                    )

                    if (currentChats[chatInfo.id] != chatInfo) {
                        currentChats[chatInfo.id] = chatInfo
                        chatsUpdated = true
                    }
                }

                try {
                    handleUpdate(update)
                } catch (e: Exception) {
                    logger.error("Error handling update ${update.update_id}", e)
                }
                lastId = update.update_id
            }

            if (chatsUpdated) {
                updatesRepository.saveChats(currentChats.values.toList())
            }
            if (lastId > lastStoredId) {
                updatesRepository.saveLastUpdateId(lastId)
            }
        }
        delay(1000.milliseconds)
    }

    internal suspend fun handleUpdate(update: Update) {
        val message = update.message ?: return
        val text = message.text ?: return
        val chatId = message.chat.id.toString()
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return

        if (!aiSettings.agentEnabled || aiSettings.geminiApiKey.isBlank()) return

        if (chatId == aiSettings.agentChatId) {
            if (text.startsWith("/")) {
                handleCommand(chatId, text, message.message_id)
            } else {
                handleAiMessage(chatId, text, message.message_id)
            }
        }
    }

    internal suspend fun handleCommand(chatId: String, text: String, messageId: Long) {
        val parts = text.split(Regex("\\s+"), 2)
        val command = parts[0]

        when (command) {
            "/reset" -> {
                geminiAgentService.resetSession()
                telegramService.sendMessage(chatId, "会话已重置", ReplyParameters(messageId))
                logger.info("Session reset by command in chat $chatId")
            }

            "/model" -> {
                if (parts.size > 1) {
                    val requestedModel = parts[1].trim()
                    try {
                        geminiAgentService.switchModel(requestedModel)
                        telegramService.sendMessage(
                            chatId, "已切换模型并重置会话：$requestedModel", ReplyParameters(messageId)
                        )
                    } catch (_: Exception) {
                        telegramService.sendMessage(
                            chatId,
                            "不支持的模型：$requestedModel\n使用 /model 查看可用列表。",
                            ReplyParameters(messageId)
                        )
                    }
                } else {
                    geminiAgentService.updateModel()
                    val current = geminiAgentService.currentModel
                    val available = geminiAgentService.availableModels
                    val list = available.joinToString("\n") { model ->
                        if (model == current) "✅ $model" else "    $model"
                    }
                    telegramService.sendMessage(
                        chatId,
                        "当前可用模型列表：\n$list\n\n使用 `/model <模型名称>` 切换模型。",
                        ReplyParameters(messageId)
                    )
                }
            }
            // 可以添加更多指令
        }
    }

    internal suspend fun handleAiMessage(chatId: String, text: String, messageId: Long) {
        try {
            telegramService.sendChatAction(chatId, "typing")
        } catch (e: Exception) {
            logger.warn("Failed to send initial typing action", e)
        }

        coroutineScope {
            val typingJob = launch {
                while (isActive) {
                    delay(4000.milliseconds)
                    try {
                        telegramService.sendChatAction(chatId, "typing")
                    } catch (e: Exception) {
                        logger.warn("Failed to send typing action", e)
                    }
                }
            }

            try {
                val reply = geminiAgentService.sendMessage(text)
                typingJob.cancel()
                if (reply.isNotBlank()) {
                    telegramService.sendMessage(
                        chatId, reply, ReplyParameters(message_id = messageId)
                    )
                }
            } catch (e: Exception) {
                typingJob.cancel()
                logger.error("Failed to handle AI message", e)
                telegramService.sendMessage(
                    chatId, "AI 处理消息时出错：${e.message}", ReplyParameters(messageId)
                )
            }
        }
    }

    override fun close() {
        job?.cancel()
        job = null
        logger.info("Agent poller stopped.")
    }
}
