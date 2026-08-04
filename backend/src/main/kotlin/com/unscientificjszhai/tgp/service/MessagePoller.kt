package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.repository.botIdFromTelegramToken
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.http.isSuccess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * 后台轮询 Telegram 更新，并将授权聊天的消息依次交给 AI 代理处理。
 *
 * 每个有效 token 生命周期拥有唯一的轮询会话。会话捕获 token、bot 标识、token 代次、队列、
 * 子作用域和上下文清理计时；token 更换、清空或代次变化时会先取消旧会话的在途及排队任务，
 * 再创建新会话。旧会话永远不会确认排队完成或推进偏移量。
 *
 * @constructor 创建消息轮询服务。
 * @param parentScope 持有轮询服务的父协程作用域；取消该作用域会停止内部轮询任务。
 * @param telegramService 与 Telegram Bot API 通信的服务。
 * @param agentService 处理文本和媒体消息的 AI 代理服务。
 * @param settingsRepository 提供机器人与 AI 设置的仓储。
 * @param updatesRepository 持久化按机器人隔离的聊天信息和已完成更新标识的仓储。
 * @param modelSwitchBarrier 与设置仓储及代理服务共享的启动和模型切换屏障。
 */
@Singleton
class MessagePoller @Inject constructor(
    parentScope: CoroutineScope,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
) : AutoCloseable {
    /**
     * 为未接入依赖注入的既有调用方创建轮询服务。
     *
     * 此兼容构造器复用 [settingsRepository] 持有的屏障，不会创建独立屏障。新代码应显式传入应用共享的
     * [ModelSwitchBarrier]。
     *
     * @param parentScope 持有轮询服务的父协程作用域；取消该作用域会停止内部轮询任务。
     * @param telegramService 与 Telegram Bot API 通信的服务。
     * @param agentService 处理文本和媒体消息的 AI 代理服务。
     * @param settingsRepository 提供机器人与 AI 设置的仓储，且必须持有应用共享的屏障。
     * @param updatesRepository 持久化按机器人隔离的聊天信息和已完成更新标识的仓储。
     */
    @Deprecated("请显式传入与 SettingsRepository 共享的 ModelSwitchBarrier。")
    constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsRepository,
        updatesRepository,
        settingsRepository.modelSwitchBarrier,
    )

    private val logger = LoggerFactory.getLogger(MessagePoller::class.java)
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private val lifecycleLock = Any()
    private val sessionLock = ReentrantLock()

    @Volatile
    private var closed = false

    private var settingsJob: Job? = null
    private var currentSession: PollingSession? = null
    private var processingTimeout: Duration = 10.minutes

    /**
     * 使用指定单条消息处理时限创建仅供测试使用的轮询服务。
     *
     * @param parentScope 持有轮询服务的父协程作用域。
     * @param telegramService 与 Telegram Bot API 通信的服务。
     * @param agentService 处理文本和媒体消息的 AI 代理服务。
     * @param settingsRepository 提供机器人与 AI 设置的仓储。
     * @param updatesRepository 持久化按机器人隔离的状态的仓储。
     * @param modelSwitchBarrier 与设置仓储及代理服务共享的启动和模型切换屏障。
     * @param processingTimeout 单条排队消息允许的最长处理时长；必须大于零。
     */
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        modelSwitchBarrier: ModelSwitchBarrier,
        processingTimeout: Duration,
    ) : this(parentScope, telegramService, agentService, settingsRepository, updatesRepository, modelSwitchBarrier) {
        require(processingTimeout.isPositive()) { "processingTimeout must be positive." }
        this.processingTimeout = processingTimeout
    }

    /**
     * 使用指定单条消息处理时限创建兼容的测试轮询服务。
     *
     * 此兼容构造器复用 [settingsRepository] 持有的屏障，不会创建独立屏障；新的测试应显式传入共享屏障。
     *
     * @param parentScope 持有轮询服务的父协程作用域。
     * @param telegramService 与 Telegram Bot API 通信的服务。
     * @param agentService 处理文本和媒体消息的 AI 代理服务。
     * @param settingsRepository 提供机器人与 AI 设置的仓储，且必须持有共享屏障。
     * @param updatesRepository 持久化按机器人隔离的状态的仓储。
     * @param processingTimeout 单条排队消息允许的最长处理时长；必须大于零。
     */
    @Deprecated("新的测试应显式传入与 SettingsRepository 共享的 ModelSwitchBarrier。")
    internal constructor(
        parentScope: CoroutineScope,
        telegramService: TelegramService,
        agentService: AgentService,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        processingTimeout: Duration,
    ) : this(
        parentScope,
        telegramService,
        agentService,
        settingsRepository,
        updatesRepository,
        settingsRepository.modelSwitchBarrier,
        processingTimeout,
    )

    private class PollingSession(
        val token: String,
        val botId: String,
        val generation: Long,
        val scope: CoroutineScope,
        val updateChannel: Channel<QueuedUpdate>,
        var pollJob: Job? = null,
        var consumerJob: Job? = null,
        var lastAiReplyAtMillis: Long? = null,
    )

    private data class QueuedUpdate(
        val update: Update,
        val entryTime: Long,
        val completion: CompletableDeferred<Unit>,
    )

    /**
     * 启动 token 生命周期监听，并按需创建唯一轮询会话。
     *
     * 此方法不会等待代理初始化或阻塞调用线程。屏障放行后才订阅 token 流并创建会话；关闭或调用协程
     * 已取消时不创建会话。重复调用不会创建额外监听器。token 为空或格式无有效 bot 前缀时不创建会话；
     * 每次 token 实际改变后的代次都会替换会话，即使最终 token 文本恢复为原值。
     */
    fun start() {
        val started = synchronized(lifecycleLock) {
            if (closed || settingsJob != null) {
                false
            } else {
                settingsJob = scope.launch {
                    modelSwitchBarrier.awaitReady()
                    currentCoroutineContext().ensureActive()
                    if (closed) {
                        return@launch
                    }
                    settingsRepository.telegramTokenUpdateFlow.collect { tokenUpdate ->
                        currentCoroutineContext().ensureActive()
                        if (!closed) {
                            replaceSession(tokenUpdate.token, tokenUpdate.generation)
                        }
                    }
                }
                true
            }
        }
        if (started) {
            logger.info("Agent poller observer started.")
        }
    }

    private suspend fun replaceSession(token: String, generation: Long) {
        if (closed) {
            return
        }
        val sameSession = sessionLock.withLock {
            currentSession?.let { it.token == token && it.generation == generation } == true
        }
        if (sameSession) {
            return
        }

        val previous = sessionLock.withLock {
            currentSession.also { currentSession = null }
        }
        if (previous != null) {
            previous.updateChannel.close()
            previous.scope.cancel()
            previous.scope.coroutineContext[Job]?.join()
            // AgentService 的会话是全局的；token 切换时必须显式清除，避免 A 的上下文泄漏给 B。
            if (!awaitSuccessfulAgentReset()) {
                logger.warn("Failed to reset agent session while switching polling session; continuing with new bot session.")
            }
            logger.info("Cancelled polling session for bot {} at generation {}", previous.botId, previous.generation)
        }

        val botId = token.botIdFromTelegramToken() ?: run {
            logger.info("Agent poller paused due to empty or invalid token.")
            return
        }
        val sessionScope = scope + SupervisorJob(scope.coroutineContext[Job])
        val session = PollingSession(
            token = token,
            botId = botId,
            generation = generation,
            scope = sessionScope,
            updateChannel = Channel(capacity = 10),
        )
        val installed = synchronized(lifecycleLock) {
            if (closed) {
                false
            } else {
                sessionLock.withLock {
                    currentSession = session
                }
                true
            }
        }
        if (!installed) {
            sessionScope.cancel()
            return
        }
        session.consumerJob = session.scope.launch { consumeQueue(session) }
        session.pollJob = session.scope.launch { runPolling(session) }
        logger.info("Started polling session for bot {} at generation {}", botId, generation)
    }

    private suspend fun runPolling(session: PollingSession) {
        while (currentCoroutineContext().isActive) {
            try {
                if (!pollOnce(session)) {
                    return
                }
            } catch (_: CancellationException) {
                return
            } catch (e: Exception) {
                if (e is SocketTimeoutException || e.cause is SocketTimeoutException) {
                    logger.warn("Polling timeout for bot {}: {}", session.botId, e.message ?: "Socket timeout expired")
                } else {
                    logger.error("Error during polling for bot ${session.botId}", e)
                    delay(5000.milliseconds)
                }
            }
        }
    }

    private suspend fun pollOnce(session: PollingSession): Boolean {
        if (!isCurrent(session)) {
            return false
        }
        var lastStoredId = readForCurrent(session) {
            updatesRepository.getData(session.botId).lastUpdateId
        } ?: return false
        if (lastStoredId == 0L) {
            val initialResponse = telegramService.getUpdatesForToken(session.token, offset = -1, timeout = 0)
            if (!isCurrent(session)) {
                return false
            }
            if (!initialResponse.ok) {
                throw IllegalStateException(
                    "Failed to initialize lastUpdateId: Telegram API error " +
                            "${initialResponse.errorCode ?: "unknown"}: " +
                            (initialResponse.description ?: "no description"),
                )
            }
            if (initialResponse.result.isNotEmpty()) {
                lastStoredId = initialResponse.result.last().updateId
                if (!saveForCurrent(session) {
                        updatesRepository.saveLastUpdateId(session.botId, lastStoredId)
                    }
                ) {
                    return false
                }
                logger.info("Initialized lastUpdateId for bot {} to {}", session.botId, lastStoredId)
            }
            delay(1000.milliseconds)
            if (!isCurrent(session)) {
                return false
            }
        }

        val response = telegramService.getUpdatesForToken(
            session.token,
            offset = lastStoredId + 1,
            timeout = 30,
        )
        if (!isCurrent(session)) {
            return false
        }
        if (response.ok) {
            val completions = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
            val discoveredChats = LinkedHashMap<String, ChatInfo>()
            for (update in response.result) {
                update.chatInfo()?.let { discoveredChats[it.id] = it }
                try {
                    completions += update.updateId to enqueueUpdate(session, update)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Error handling update ${update.updateId}", e)
                    completions += update.updateId to completedSignal()
                }
            }
            if (discoveredChats.isNotEmpty() && !saveForCurrent(session) {
                    updatesRepository.mergeChats(session.botId, discoveredChats.values)
                }
            ) {
                return false
            }
            for ((updateId, completion) in completions.sortedBy { it.first }) {
                completion.await()
                if (updateId > lastStoredId) {
                    if (!saveForCurrent(session) {
                            updatesRepository.saveLastUpdateId(session.botId, updateId)
                        }
                    ) {
                        return false
                    }
                    lastStoredId = updateId
                }
            }
        }
        delay(1000.milliseconds)
        return isCurrent(session)
    }

    private suspend fun consumeQueue(session: PollingSession) {
        while (currentCoroutineContext().isActive) {
            val queuedUpdate = session.updateChannel.receiveCatching().getOrNull() ?: return
            val deadline = queuedUpdate.entryTime + processingTimeout.inWholeMilliseconds
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
            try {
                withTimeout(remaining.milliseconds) {
                    processUpdate(session, queuedUpdate.update)
                }
            } catch (_: TimeoutCancellationException) {
                handleProcessingTimeout(session, queuedUpdate.update)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Error processing update ${queuedUpdate.update.updateId}", e)
            }

            currentCoroutineContext().ensureActive()
            if (isCurrent(session)) {
                queuedUpdate.completion.complete(Unit)
            }
        }
    }

    private suspend fun processUpdate(session: PollingSession, update: Update) {
        val message = update.message ?: return
        val chatId = message.chat.id.toString()
        when {
            message.text?.startsWith("/") == true -> handleCommand(session, chatId, message.text, message.messageId)
            message.voice != null -> handleVoiceMessage(
                session,
                chatId,
                message.voice,
                message.caption,
                message.messageId
            )

            message.text != null -> handleAiMessage(session, chatId, message.text, message.messageId)
        }
    }

    private suspend fun handleProcessingTimeout(session: PollingSession, update: Update) {
        val message = update.message ?: return
        logger.warn("Update ${update.updateId} processing timed out after 10 minutes.")
        try {
            sendMessageForSession(
                session,
                message.chat.id.toString(),
                "抱歉，该消息处理超时（超过10分钟）。",
                ReplyParameters(messageId = message.messageId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to send timeout notification", e)
        }
    }

    /**
     * 将一条更新加入当前活跃会话的处理队列。
     *
     * 当前没有有效会话时不会产生副作用。队列满时会使用该会话捕获的 token 回复失败提示，
     * 并保持既有的“提示成功后确认更新”语义。
     *
     * @param update 要检查的 Telegram 更新；不含可处理消息时不会入队。
     */
    @Suppress("unused")
    suspend fun handleUpdate(update: Update) {
        activeSession()?.let { enqueueUpdate(it, update) }
    }

    private suspend fun enqueueUpdate(session: PollingSession, update: Update): CompletableDeferred<Unit> {
        if (!isCurrent(session)) {
            return CompletableDeferred()
        }
        val message = update.message ?: return completedSignal()
        if (message.text == null && message.voice == null) {
            return completedSignal()
        }
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return completedSignal()
        val chatId = message.chat.id.toString()
        if (!agentService.isAiFeatureEnabled(aiSettings) || chatId != aiSettings.agentChatId) {
            return completedSignal()
        }

        val completion = CompletableDeferred<Unit>()
        if (session.updateChannel.trySend(QueuedUpdate(update, System.currentTimeMillis(), completion)).isFailure) {
            logger.warn("Update ${update.updateId} rejected: Queue is full.")
            try {
                sendMessageForSession(
                    session,
                    chatId,
                    "抱歉，当前处理队列已满（最多同时排队10条消息），请稍后再试。",
                    ReplyParameters(messageId = message.messageId),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to send queue full notification", e)
            }
            if (isCurrent(session)) {
                completion.complete(Unit)
            }
        }
        return completion
    }

    private fun completedSignal(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { it.complete(Unit) }

    /**
     * 使用当前活跃会话处理 AI 聊天命令。
     *
     * 当前没有有效会话时不会产生副作用。`/reset` 仅在代理重置任务正常完成且会话仍有效时，才清空当前
     * 会话的队列并清除自动清理计时。重置失败时会保留这些状态并发送失败提示，不会影响其他机器人的队列。
     *
     * @param chatId 发送命令的聊天标识，不能为空。
     * @param text 完整命令文本；首个空白分隔字段作为命令。
     * @param messageId 原始 Telegram 消息标识，用于关联回复。
     */
    suspend fun handleCommand(chatId: String, text: String, messageId: Long) {
        activeSession()?.let { handleCommand(it, chatId, text, messageId) }
    }

    private suspend fun handleCommand(session: PollingSession, chatId: String, text: String, messageId: Long) {
        val parts = text.split(Regex("\\s+"), 2)
        when (parts[0]) {
            "/keep" -> {
                if (isCurrent(session)) {
                    session.lastAiReplyAtMillis = System.currentTimeMillis()
                    logger.info("Auto-clean context timer refreshed by keep command in chat {}", chatId)
                }
            }

            "/reset" -> {
                ensureCurrent(session)
                if (!awaitSuccessfulAgentReset()) {
                    ensureCurrent(session)
                    sendMessageForSession(session, chatId, "会话重置失败，请稍后重试。", ReplyParameters(messageId))
                    logger.warn("Session reset failed by command in chat {}", chatId)
                    return
                }
                ensureCurrent(session)
                clearQueue(session)
                ensureCurrent(session)
                session.lastAiReplyAtMillis = null
                sendMessageForSession(session, chatId, "会话已重置，待处理消息已清空。", ReplyParameters(messageId))
                logger.info("Session reset and queue cleared by command in chat {}", chatId)
            }

            "/model" -> {
                if (parts.size > 1) {
                    val requestedModel = parts[1].trim()
                    try {
                        val settingsBeforeSelection = settingsRepository.currentSettingsSnapshot().settings
                        val selectedModel = agentService.availableModels.firstOrNull { model ->
                            model == requestedModel ||
                                    model.removePrefix("models/") == requestedModel.removePrefix("models/")
                        } ?: throw IllegalArgumentException("Unsupported model: $requestedModel")
                        if (!persistSelectedModel(selectedModel, settingsBeforeSelection)) {
                            throw IllegalStateException("AI configuration changed while selecting a model")
                        }
                        if (isCurrent(session)) {
                            session.lastAiReplyAtMillis = null
                        }
                        sendMessageForSession(
                            session,
                            chatId,
                            "已保存模型选择，正在切换模型并重置会话：$selectedModel",
                            ReplyParameters(messageId),
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        sendMessageForSession(
                            session,
                            chatId,
                            "不支持的模型：$requestedModel\n使用 /model 查看可用列表。",
                            ReplyParameters(messageId),
                        )
                    }
                } else {
                    val modelSnapshot = agentService.updateModel()
                    if (modelSnapshot == null) {
                        sendMessageForSession(
                            session,
                            chatId,
                            "获取可用模型列表失败，请稍后重试。",
                            ReplyParameters(messageId)
                        )
                        return
                    }
                    val list = modelSnapshot.availableModels.joinToString("\n") { model ->
                        if (model == modelSnapshot.currentModel) "✅ $model" else "      $model"
                    }
                    sendMessageForSession(
                        session,
                        chatId,
                        "当前可用模型列表：\n$list\n\n使用 `/model <模型名称>` 切换模型。",
                        ReplyParameters(messageId),
                    )
                }
            }
        }
    }

    private fun persistSelectedModel(selectedModel: String, expectedSettings: AppSettings): Boolean {
        var persisted = false
        settingsRepository.updateSettings { settings ->
            val aiSettings = settings.ai ?: return@updateSettings settings
            if (!settings.hasSameModelServiceConfiguration(expectedSettings)) {
                settings
            } else {
                persisted = true
                settings.copy(ai = aiSettings.copy(selectedModel = selectedModel))
            }
        }
        return persisted
    }

    private fun AppSettings.hasSameModelServiceConfiguration(expected: AppSettings): Boolean {
        val currentAi = ai ?: return false
        val expectedAi = expected.ai ?: return false
        return currentAi.provider == expectedAi.provider &&
                currentAi.agentEnabled == expectedAi.agentEnabled &&
                currentAi.selectedModel == expectedAi.selectedModel &&
                proxy == expected.proxy &&
                currentAi.httpToolSettings == expectedAi.httpToolSettings &&
                currentAi.mcpServers == expectedAi.mcpServers &&
                when (currentAi.provider) {
                    AIProvider.GEMINI -> currentAi.geminiApiKey == expectedAi.geminiApiKey
                    AIProvider.OPENAI ->
                        currentAi.openAiApiKey == expectedAi.openAiApiKey &&
                                currentAi.openAiBaseUrl == expectedAi.openAiBaseUrl
                }
    }

    private suspend fun handleAiMessage(session: PollingSession, chatId: String, text: String, messageId: Long) {
        cleanContextIfNeeded(session, chatId)
        try {
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    val reply = agentService.sendMessage(text)
                    typingJob.cancel()
                    if (reply.isNotBlank()) {
                        val response = sendMessageForSession(session, chatId, reply, ReplyParameters(messageId))
                        if (response.status.isSuccess() && isCurrent(session)) {
                            session.lastAiReplyAtMillis = System.currentTimeMillis()
                        }
                    }
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to handle AI message", e)
            sendMessageForSession(session, chatId, "AI 处理消息时出错：${e.message}", ReplyParameters(messageId))
        }
    }

    private suspend fun handleVoiceMessage(
        session: PollingSession,
        chatId: String,
        voice: Voice,
        caption: String?,
        messageId: Long,
    ) {
        cleanContextIfNeeded(session, chatId)
        try {
            sendChatActionForSession(session, chatId, "typing")
            coroutineScope {
                val typingJob = typingJob(session, chatId)
                try {
                    val filePath = telegramService.getFileForToken(session.token, voice.fileId).result?.filePath
                        ?: throw IllegalStateException("Failed to get file path for voice message")
                    ensureCurrent(session)
                    val audioData = telegramService.downloadFileForToken(session.token, filePath)
                    ensureCurrent(session)
                    val reply =
                        agentService.sendMessage(caption, listOf(MediaData(audioData, voice.mimeType ?: "audio/ogg")))
                    typingJob.cancel()
                    if (reply.isNotBlank()) {
                        val response = sendMessageForSession(session, chatId, reply, ReplyParameters(messageId))
                        if (response.status.isSuccess() && isCurrent(session)) {
                            session.lastAiReplyAtMillis = System.currentTimeMillis()
                        }
                    }
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to handle voice message", e)
            sendMessageForSession(session, chatId, "处理语音消息时出错：${e.message}", ReplyParameters(messageId))
        }
    }

    private fun typingJob(session: PollingSession, chatId: String): Job = session.scope.launch {
        while (isActive) {
            delay(4000.milliseconds)
            try {
                sendChatActionForSession(session, chatId, "typing")
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                logger.warn("Failed to send typing action", e)
            }
        }
    }

    private suspend fun cleanContextIfNeeded(session: PollingSession, chatId: String) {
        val aiSettings = settingsRepository.settingsFlow.value.ai ?: return
        val intervalMinutes = aiSettings.autoCleanContextIntervalMinutes
        val lastReplyAt = session.lastAiReplyAtMillis ?: return
        if (intervalMinutes <= 0 || System.currentTimeMillis() - lastReplyAt < intervalMinutes.minutes.inWholeMilliseconds) {
            return
        }
        ensureCurrent(session)
        if (!awaitSuccessfulAgentReset()) {
            ensureCurrent(session)
            logger.warn(
                "Failed to auto-clean AI context after {} minutes without a successful AI reply.",
                intervalMinutes
            )
            return
        }
        ensureCurrent(session)
        session.lastAiReplyAtMillis = null
        if (!aiSettings.silentContextCleanup) {
            sendMessageForSession(
                session,
                chatId,
                "检测到距离上次对话已超过 $intervalMinutes 分钟，已自动清理上下文。",
            )
        }
        logger.info("Auto-cleaned AI context after {} minutes without a successful AI reply.", intervalMinutes)
    }

    /**
     * 等待代理重置结束，并以 [Job.isCancelled] 判定成功。
     *
     * [Job.join] 不会传播任务自身的失败；当前消费者或轮询会话被取消时仍会原样传播其取消，避免旧会话在
     * token 切换后继续执行。代理返回 `null`、抛出普通异常或返回已取消任务都会返回 `false`。
     */
    private suspend fun awaitSuccessfulAgentReset(): Boolean {
        val resetJob = try {
            agentService.resetSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Failed to start agent session reset", e)
            return false
        } ?: return false

        resetJob.join()
        return !resetJob.isCancelled
    }

    private fun clearQueue(session: PollingSession) {
        var count = 0
        while (true) {
            val queuedUpdate = session.updateChannel.tryReceive().getOrNull() ?: break
            queuedUpdate.completion.complete(Unit)
            count++
        }
        if (count > 0) {
            logger.info("Cleared {} pending updates from current polling session due to reset.", count)
        }
    }

    private suspend fun sendMessageForSession(
        session: PollingSession,
        chatId: String,
        text: String,
        replyParameters: ReplyParameters? = null,
    ): TelegramApiResponse {
        ensureCurrent(session)
        return telegramService.sendMessageForToken(session.token, chatId, text, replyParameters)
    }

    private suspend fun sendChatActionForSession(
        session: PollingSession,
        chatId: String,
        action: String,
    ): TelegramApiResponse {
        ensureCurrent(session)
        return telegramService.sendChatActionForToken(session.token, chatId, action)
    }

    private fun activeSession(): PollingSession? = sessionLock.withLock {
        currentSession?.takeIf { !closed && isTokenGenerationCurrent(it) }
    }

    private fun isCurrent(session: PollingSession): Boolean = sessionLock.withLock {
        !closed && currentSession === session && isTokenGenerationCurrent(session)
    }

    private fun ensureCurrent(session: PollingSession) {
        if (!isCurrent(session)) {
            throw CancellationException("Polling session is no longer current.")
        }
    }

    private fun saveForCurrent(session: PollingSession, save: () -> Unit): Boolean =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession !== session || !isTokenGenerationCurrent(session)) {
                    false
                } else {
                    save()
                    true
                }
            }
        }

    private fun <T> readForCurrent(session: PollingSession, read: () -> T): T? =
        settingsRepository.withTelegramTokenLifecycleLock {
            sessionLock.withLock {
                if (currentSession === session && isTokenGenerationCurrent(session)) read() else null
            }
        }

    private fun isTokenGenerationCurrent(session: PollingSession): Boolean =
        settingsRepository.telegramTokenUpdateFlow.value.let { tokenUpdate ->
            tokenUpdate.token == session.token && tokenUpdate.generation == session.generation
        }

    private fun cancelCurrentSession() {
        val session = sessionLock.withLock { currentSession.also { currentSession = null } } ?: return
        session.updateChannel.close()
        session.scope.cancel()
    }

    /**
     * 停止设置监听及当前轮询会话。
     *
     * 关闭会取消当前会话的在途和排队任务，但不会完成旧队列的确认信号或写入其偏移量。
     */
    override fun close() {
        val jobToCancel = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            settingsJob.also { settingsJob = null }
        }
        jobToCancel?.cancel()
        runBlocking { cancelCurrentSession() }
        scope.cancel()
        logger.info("Agent poller stopped.")
    }
}

private fun Update.chatInfo(): ChatInfo? {
    val chat = message?.chat ?: channelPost?.chat ?: myChatMember?.chat ?: return null
    val title = chat.title ?: chat.username ?: "${chat.firstName ?: ""} ${chat.lastName ?: ""}".trim()
    return ChatInfo(id = chat.id.toString(), title = title, type = chat.type)
}
