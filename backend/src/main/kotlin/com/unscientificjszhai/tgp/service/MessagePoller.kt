package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * 后台轮询 Telegram 更新，并将授权聊天的消息依次交给 AI 代理处理。
 *
 * 调用 [start] 后服务持续观察机器人令牌并管理轮询协程；调用 [close] 会停止所有内部协程。
 * 单条消息自入队起最多处理十分钟，队列满时会直接回复失败提示。更新的偏移量仅在对应更新结束
 * 队列处理后保存；关闭时内存队列及在途任务会被丢弃，未保存偏移量的更新会在下次轮询时由
 * Telegram 重投。
 *
 * @constructor 创建消息轮询服务。
 * @param parentScope 持有轮询和队列消费者的父协程作用域；取消该作用域会停止内部协程。
 * @param telegramService 与 Telegram Bot API 通信的服务。
 * @param agentService 处理文本和媒体消息的 AI 代理服务。
 * @param settingsRepository 提供机器人与 AI 设置的仓储。
 * @param updatesRepository 持久化聊天信息和已完成队列处理的更新标识的仓储。
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

    // 消息队列，容量为 10，存储更新内容、入队时间戳及处理完成信号。
    private val updateChannel = Channel<QueuedUpdate>(10)

    private data class QueuedUpdate(
        val update: Update,
        val entryTime: Long,
        val completion: CompletableDeferred<Unit>,
    )

    /**
     * 启动设置监听、消息队列消费者和按需轮询。
     *
     * 重复调用不会创建额外协程；机器人令牌为空时轮询保持暂停，令牌变更时会自动重启轮询。
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
            val initialResponse = telegramService.getUpdates(offset = -1, timeout = 0)
            if (!initialResponse.ok) {
                throw IllegalStateException(
                    "Failed to initialize lastUpdateId: Telegram API error " +
                            "${initialResponse.errorCode ?: "unknown"}: " +
                            (initialResponse.description ?: "no description"),
                )
            }
            if (initialResponse.result.isNotEmpty()) {
                lastStoredId = initialResponse.result.last().updateId
                updatesRepository.saveLastUpdateId(lastStoredId)
                logger.info("Initialized lastUpdateId to $lastStoredId")
            }
            delay(1000.milliseconds)
        }

        val offset = lastStoredId + 1
        val response = telegramService.getUpdates(offset = offset, timeout = 30)

        if (response.ok) {
            val completions = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
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
                    completions += update.updateId to enqueueUpdate(update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error handling update ${update.updateId}", e)
                    completions += update.updateId to completedSignal()
                }
            }

            if (chatsUpdated) {
                updatesRepository.saveChats(currentChats.values.toList())
            }

            for ((updateId, completion) in completions.sortedBy { it.first }) {
                completion.await()
                if (updateId > lastStoredId) {
                    updatesRepository.saveLastUpdateId(updateId)
                    lastStoredId = updateId
                }
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
            updateChannel.receiveAsFlow().collect { queuedUpdate ->
                val update = queuedUpdate.update
                val deadline = queuedUpdate.entryTime + 10.minutes.inWholeMilliseconds
                val now = System.currentTimeMillis()
                val remaining = (deadline - now).coerceAtLeast(0)

                try {
                    // 在剩余时间内处理消息
                    withTimeout(remaining.milliseconds) {
                        processUpdate(update)
                    }
                } catch (_: TimeoutCancellationException) {
                    handleProcessingTimeout(update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error processing update ${update.updateId}", e)
                }

                currentCoroutineContext().ensureActive()
                queuedUpdate.completion.complete(Unit)
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

    /**
     * 接收单条 Telegram 更新，并在满足 AI 设置时将其加入处理队列。
     *
     * 仅处理包含文本或语音消息、AI 已启用且聊天标识等于配置值的更新；队列已满时会向该消息
     * 发送失败提示而不入队。
     *
     * @param update 要检查的 Telegram 更新，不能为空；不含可处理消息时不会产生副作用。
     */
    @Suppress("unused")
    suspend fun handleUpdate(update: Update) {
        enqueueUpdate(update)
    }

    private suspend fun enqueueUpdate(update: Update): CompletableDeferred<Unit> {
        val message = update.message ?: return completedSignal()
        val text = message.text
        val voice = message.voice
        if (text == null && voice == null) return completedSignal()

        val chatId = message.chat.id.toString()
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return completedSignal()

        if (!aiSettings.agentEnabled) return completedSignal()

        if (chatId != aiSettings.agentChatId) return completedSignal()

        val completion = CompletableDeferred<Unit>()
        // 尝试入队，如果不成功（队列满）则直接回复失败
        val result = updateChannel.trySend(
            QueuedUpdate(update, System.currentTimeMillis(), completion),
        )
        if (result.isFailure) {
            logger.warn("Update ${update.updateId} rejected: Queue is full.")
            try {
                telegramService.sendMessage(
                    chatId,
                    "抱歉，当前处理队列已满（最多同时排队10条消息），请稍后再试。",
                    ReplyParameters(messageId = message.messageId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to send queue full notification", e)
            }
            completion.complete(Unit)
        }

        return completion
    }

    private fun completedSignal(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }

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

    /**
     * 下载语音文件并将其作为媒体消息交给 AI 代理处理。
     *
     * 处理期间会持续发送“正在输入”状态；AI 的非空回复会作为对原消息的回复发送。下载、代理
     * 调用或发送回复失败时会向聊天发送错误提示。
     *
     * @param chatId 接收回复的聊天标识，不能为空。
     * @param voice 要处理的 Telegram 语音文件，必须包含有效的文件标识。
     * @param caption 语音消息的可选说明文字；没有说明时为 `null`。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
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
        while (true) {
            val queuedUpdate = updateChannel.tryReceive().getOrNull() ?: break
            queuedUpdate.completion.complete(Unit)
            count++
        }
        if (count > 0) {
            logger.info("Cleared $count pending updates from queue due to reset/model switch.")
        }
    }

    /**
     * 处理 AI 聊天中的机器人命令。
     *
     * 支持 `/keep`、`/reset` 和 `/model`；命令可能重置会话、清空待处理队列或持久化模型选择，
     * 并会向聊天发送相应反馈。首个空白分隔字段以外的命令会被忽略。
     *
     * @param chatId 发送命令的聊天标识，不能为空。
     * @param text 完整命令文本；首个空白分隔字段作为命令，其余内容作为命令参数。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
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
                        val selectedModel = agentService.availableModels.firstOrNull { model ->
                            model == requestedModel ||
                                    model.removePrefix("models/") == requestedModel.removePrefix("models/")
                        } ?: throw IllegalArgumentException("Unsupported model: $requestedModel")
                        persistSelectedModel(selectedModel)
                        lastAiReplyAtMillis = null
                        telegramService.sendMessage(
                            chatId,
                            "已保存模型选择，正在切换模型并重置会话：$selectedModel",
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
                    val modelSnapshot = agentService.updateModel()
                    if (modelSnapshot == null) {
                        telegramService.sendMessage(
                            chatId,
                            "获取可用模型列表失败，请稍后重试。",
                            ReplyParameters(messageId),
                        )
                        return
                    }
                    val current = modelSnapshot.currentModel
                    val available = modelSnapshot.availableModels
                    val list = available.joinToString("\n") { model ->
                        if (model == current) "✅ $model" else "      $model"
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

    /**
     * 持久化用户选择的模型，由代理服务从设置流中应用。
     *
     * @param selectedModel 要保存的规范模型名称，不能为空；与当前选择相同时不写入设置。
     */
    private fun persistSelectedModel(selectedModel: String) {
        val settings = settingsRepository.settingsFlow.value
        val aiSettings = settings.ai ?: return
        if (aiSettings.selectedModel != selectedModel) {
            settingsRepository.saveSettings(
                settings.copy(ai = aiSettings.copy(selectedModel = selectedModel)),
            )
        }
    }

    /**
     * 将文本消息交给 AI 代理，并将非空回复发送回原聊天。
     *
     * 处理期间会持续发送“正在输入”状态；代理调用或发送回复失败时会向聊天发送错误提示。
     *
     * @param chatId 接收回复的聊天标识，不能为空。
     * @param text 要发送给 AI 的文本，不能为空。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
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

    /**
     * 停止设置监听、轮询和队列消费者。
     *
     * 此方法可重复调用；已取消的协程不会再次取消。
     */
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
