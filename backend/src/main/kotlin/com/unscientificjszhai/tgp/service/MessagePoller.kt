package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.models.Voice
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import io.ktor.http.isSuccess
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * 后台机器人轮询服务，负责监听 Telegram 消息并执行指令或调用 AI。
 */
@Singleton
class MessagePoller @Inject constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(MessagePoller::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private var job: Job? = null
    private var consumerJob: Job? = null
    private var settingsJob: Job? = null
    private var currentToken: String? = null
    private var lastAiReplyAtMillis: Long? = null

    // 消息队列，容量为 10，存储更新内容及入队时间戳
    private val updateChannel = Channel<Pair<Update, Long>>(10)

    /**
     * 启动轮询监听。
     */
    fun start() {
        if (settingsJob != null) return
        startQueueConsumer()

        settingsJob = settingsRepository.settingsFlow
            .onEach { settings ->
                val newToken = settings.telegramToken
                if (newToken.isBlank()) {
                    if (job != null) {
                        job?.cancel()
                        job = null
                        logger.info("Agent poller paused due to empty token.")
                    }
                } else if (newToken != currentToken) {
                    currentToken = newToken
                    restartPolling()
                }
            }.launchIn(scope)

        logger.info("Agent poller observer started.")
    }

    private fun restartPolling() {
        job?.cancel()
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
        logger.info("Agent poller started/restarted.")
    }

    private suspend fun poll() {
        var lastStoredId = updatesRepository.lastUpdateId

        if (lastStoredId == 0L) {
            // 第一次运行，先获取最新的 updateId 以避免处理历史消息
            try {
                val initialResponse = telegramService.getUpdates(offset = -1, timeout = 0)
                if (initialResponse.ok && initialResponse.result.isNotEmpty()) {
                    lastStoredId = initialResponse.result.last().updateId
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
                val chat = update.message?.chat ?: update.channelPost?.chat ?: update.myChatMember?.chat

                if (chat != null) {
                    val title = chat.title ?: chat.username ?: "${chat.firstName ?: ""} ${chat.lastName ?: ""}".trim()

                    val chatInfo = ChatInfo(
                        id = chat.id.toString(),
                        title = title,
                        type = chat.type,
                    )

                    if (currentChats[chatInfo.id] != chatInfo) {
                        currentChats[chatInfo.id] = chatInfo
                        chatsUpdated = true
                    }
                }

                try {
                    handleUpdate(update)
                } catch (e: Exception) {
                    logger.error("Error handling update ${update.updateId}", e)
                }
                lastId = update.updateId
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

    /**
     * 启动队列消费者，按顺序处理消息。
     */
    private fun startQueueConsumer() {
        if (consumerJob != null) return
        consumerJob = scope.launch {
            updateChannel.receiveAsFlow().collect { (update, entryTime) ->
                val deadline = entryTime + 10.minutes.inWholeMilliseconds
                val now = System.currentTimeMillis()
                val remaining = (deadline - now).coerceAtLeast(0)

                try {
                    // 在剩余时间内处理消息
                    withTimeout(remaining.milliseconds) {
                        processUpdate(update)
                    }
                } catch (_: TimeoutCancellationException) {
                    handleProcessingTimeout(update)
                } catch (e: Exception) {
                    logger.error("Error processing update ${update.updateId}", e)
                }
            }
        }
    }

    /**
     * 实际处理更新的逻辑。
     */
    private suspend fun processUpdate(update: Update) {
        val message = update.message ?: return
        val text = message.text
        val voice = message.voice
        val chatId = message.chat.id.toString()

        if (text != null && text.startsWith("/")) {
            handleCommand(chatId, text, message.messageId)
        } else if (voice != null) {
            handleVoiceMessage(chatId, voice, message.caption, message.messageId)
        } else if (text != null) {
            handleAiMessage(chatId, text, message.messageId)
        }
    }

    /**
     * 处理超时的回调。
     */
    private suspend fun handleProcessingTimeout(update: Update) {
        val message = update.message ?: return
        val chatId = message.chat.id.toString()
        logger.warn("Update ${update.updateId} processing timed out after 10 minutes.")
        try {
            telegramService.sendMessage(
                chatId,
                "抱歉，该消息处理超时（超过10分钟）。",
                ReplyParameters(messageId = message.messageId),
            )
        } catch (e: Exception) {
            logger.warn("Failed to send timeout notification", e)
        }
    }

    suspend fun handleUpdate(update: Update) {
        val message = update.message ?: return
        val text = message.text
        val voice = message.voice
        if (text == null && voice == null) return

        val chatId = message.chat.id.toString()
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return

        if (!aiSettings.agentEnabled) return

        if (chatId == aiSettings.agentChatId) {
            // 尝试入队，如果不成功（队列满）则直接回复失败
            val result = updateChannel.trySend(update to System.currentTimeMillis())
            if (result.isFailure) {
                logger.warn("Update ${update.updateId} rejected: Queue is full.")
                try {
                    telegramService.sendMessage(
                        chatId,
                        "抱歉，当前处理队列已满（最多同时排队10条消息），请稍后再试。",
                        ReplyParameters(messageId = message.messageId),
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to send queue full notification", e)
                }
            }
        }
    }

    /**
     * 发送“输入中”事件的任务。
     *
     * @param chatId 聊天ID。
     */
    private fun CoroutineScope.typingJob(chatId: String) = launch {
        while (isActive) {
            delay(4000.milliseconds)
            try {
                telegramService.sendChatAction(chatId, "typing")
            } catch (_: CancellationException) {
                logger.debug("Failed to send typing action. Job cancelled")
            } catch (e: Exception) {
                logger.warn("Failed to send typing action", e)
            }
        }
    }

    suspend fun handleVoiceMessage(
        chatId: String,
        voice: Voice,
        caption: String?,
        messageId: Long,
    ) {
        cleanContextIfNeeded(chatId)

        try {
            telegramService.sendChatAction(chatId, "typing")
        } catch (e: Exception) {
            logger.warn("Failed to send initial typing action", e)
        }

        coroutineScope {
            val typingJob = typingJob(chatId)

            try {
                // 1. 获取文件路径
                val fileResponse = telegramService.getFile(voice.fileId)
                val filePath = fileResponse.result?.filePath
                    ?: throw IllegalStateException("Failed to get file path for voice message")

                // 2. 下载文件数据
                val audioData = telegramService.downloadFile(filePath)

                // 3. 构建媒体数据
                val mimeType = voice.mimeType ?: "audio/ogg"
                val mediaData = MediaData(audioData, mimeType)

                // 4. 发送给 AI
                val reply = agentService.sendMessage(caption, listOf(mediaData))

                typingJob.cancel()
                if (reply.isNotBlank()) {
                    val response = telegramService.sendMessage(
                        chatId,
                        reply,
                        ReplyParameters(messageId = messageId),
                    )
                    if (response.status.isSuccess()) {
                        lastAiReplyAtMillis = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                typingJob.cancel()
                logger.error("Failed to handle voice message", e)
                telegramService.sendMessage(
                    chatId,
                    "处理语音消息时出错：${e.message}",
                    ReplyParameters(messageId),
                )
            }
        }
    }

    /**
     * 清除队列中所有待处理的消息。
     */
    private fun clearQueue() {
        var count = 0
        while (updateChannel.tryReceive().isSuccess) {
            count++
        }
        if (count > 0) {
            logger.info("Cleared $count pending updates from queue due to reset/model switch.")
        }
    }

    suspend fun handleCommand(
        chatId: String,
        text: String,
        messageId: Long,
    ) {
        val parts = text.split(Regex("\\s+"), 2)
        val command = parts[0]

        when (command) {
            "/keep" -> {
                lastAiReplyAtMillis = System.currentTimeMillis()
                logger.info("Auto-clean context timer refreshed by keep command in chat $chatId")
            }

            "/reset" -> {
                clearQueue()
                agentService.resetSession()?.join()
                lastAiReplyAtMillis = null
                telegramService.sendMessage(chatId, "会话已重置，待处理消息已清空。", ReplyParameters(messageId))
                logger.info("Session reset and queue cleared by command in chat $chatId")
            }

            "/model" -> {
                if (parts.size > 1) {
                    val requestedModel = parts[1].trim()
                    try {
                        clearQueue()
                        agentService.switchModel(requestedModel)?.join()
                        lastAiReplyAtMillis = null
                        telegramService.sendMessage(
                            chatId,
                            "已切换模型并重置会话，待处理消息已清空：$requestedModel",
                            ReplyParameters(messageId),
                        )
                    } catch (_: Exception) {
                        telegramService.sendMessage(
                            chatId,
                            "不支持的模型：$requestedModel\n使用 /model 查看可用列表。",
                            ReplyParameters(messageId),
                        )
                    }
                } else {
                    agentService.updateModel()
                    val current = agentService.currentModel
                    val available = agentService.availableModels
                    val list = available.joinToString("\n") { model ->
                        if (model == current) "✅ $model" else "    $model"
                    }
                    telegramService.sendMessage(
                        chatId,
                        "当前可用模型列表：\n$list\n\n使用 `/model <模型名称>` 切换模型。",
                        ReplyParameters(messageId),
                    )
                }
            }
            // 可以添加更多指令
        }
    }

    suspend fun handleAiMessage(
        chatId: String,
        text: String,
        messageId: Long,
    ) {
        cleanContextIfNeeded(chatId)

        try {
            telegramService.sendChatAction(chatId, "typing")
        } catch (e: Exception) {
            logger.warn("Failed to send initial typing action", e)
        }

        coroutineScope {
            val typingJob = typingJob(chatId)

            try {
                val reply = agentService.sendMessage(text)
                typingJob.cancel()
                if (reply.isNotBlank()) {
                    val response = telegramService.sendMessage(
                        chatId,
                        reply,
                        ReplyParameters(messageId = messageId),
                    )
                    if (response.status.isSuccess()) {
                        lastAiReplyAtMillis = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                typingJob.cancel()
                logger.error("Failed to handle AI message", e)
                telegramService.sendMessage(
                    chatId,
                    "AI 处理消息时出错：${e.message}",
                    ReplyParameters(messageId),
                )
            }
        }
    }

    private suspend fun cleanContextIfNeeded(chatId: String) {
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return
        val intervalMinutes = aiSettings.autoCleanContextIntervalMinutes
        val lastReplyAt = lastAiReplyAtMillis ?: return
        if (intervalMinutes <= 0) return

        val elapsedMillis = System.currentTimeMillis() - lastReplyAt
        if (elapsedMillis < intervalMinutes.minutes.inWholeMilliseconds) return

        try {
            agentService.resetSession()?.join()
            lastAiReplyAtMillis = null
            if (!aiSettings.silentContextCleanup) {
                telegramService.sendMessage(
                    chatId,
                    "检测到距离上次对话已超过 $intervalMinutes 分钟，已自动清理上下文。",
                )
            }
            logger.info("Auto-cleaned AI context after $intervalMinutes minutes without a successful AI reply.")
        } catch (e: Exception) {
            logger.warn("Failed to auto-clean AI context", e)
        }
    }

    override fun close() {
        settingsJob?.cancel()
        settingsJob = null
        job?.cancel()
        job = null
        consumerJob?.cancel()
        consumerJob = null
        logger.info("Agent poller stopped.")
    }
}
