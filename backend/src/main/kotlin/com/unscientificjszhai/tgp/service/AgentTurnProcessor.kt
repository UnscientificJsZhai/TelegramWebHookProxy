package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.Message
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.repository.AgentTurnClaim
import com.unscientificjszhai.tgp.repository.AgentTurnJournalEntry
import com.unscientificjszhai.tgp.repository.AgentTurnJournalStatus
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.RetryCheckpointCommitResult
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.MAX_AGENT_TEXT_BYTES
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import java.nio.charset.StandardCharsets
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val AGENT_TURN_FAILURE_REPLY = "抱歉，该消息未能处理。"

/**
 * 串行消费单个 PollingSession 的工作，并执行不可重放的 Agent 回合状态机。
 *
 * 此类不创建 scope 或锁；所有任务都挂在 session.scope，owner 表只借用 runtime 的唯一 session lock。
 *
 * @param runtime 提供当前会话复核与唯一会话锁的共享运行时。
 * @param telegramService 获取 Telegram 语音文件的服务。
 * @param agentService 执行已准入 Agent 回合的服务。
 * @param updatesRepository 持久化 Agent journal、offset 与回复 outbox 的仓储。
 * @param admissionPolicy 在每个外部副作用前复核授权的策略。
 * @param cleanupCoordinator 管理 Agent 上下文重置与清理计时的协调器。
 * @param outboxWorker 原子提交并唤醒 Telegram 回复 outbox 的 worker。
 * @param commandHandler 处理已授权 Bot 命令的协作者。
 * @param logger 记录处理与恢复结果的日志器。
 * @param processingTimeout 单项队列工作的最大处理时间。
 */
internal class AgentTurnProcessor(
    private val runtime: MessagePollingRuntime,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val updatesRepository: UpdatesRepository,
    private val admissionPolicy: UpdateAdmissionPolicy,
    private val cleanupCoordinator: ContextCleanupCoordinator,
    private val outboxWorker: TelegramReplyOutboxWorker,
    private val commandHandler: BotCommandHandler,
    private val logger: Logger,
    processingTimeout: Duration,
) {
    private val agentTurnOwners = mutableMapOf<AgentTurnKey, CompletableDeferred<Unit>>()

    /** 单项队列工作的最大处理时间。 */
    var processingTimeout: Duration = processingTimeout
        set(value) {
            require(value.isPositive()) { "processingTimeout must be positive." }
            field = value
        }

    /**
     * 启动当前会话唯一的队列消费者，并在 [StackOverflowError] 后至多恢复一次。
     *
     * @param session 将拥有消费者任务的当前轮询会话。
     */
    fun start(session: PollingSession) {
        val consumer = session.scope.launch(CoroutineExceptionHandler { _, cause ->
            logger.error(
                "Queue consumer exited after completing retry signals for bot {}; type={}",
                session.botId,
                cause::class.qualifiedName,
            )
        }) { consumeQueue(session) }
        session.consumerJob = consumer
        consumer.invokeOnCompletion { cause ->
            if (cause == null || cause is CancellationException) {
                return@invokeOnCompletion
            }
            if (!isRecoverableQueueConsumerFailure(cause)) {
                terminateFatalQueueConsumerSession(session, consumer)
                logger.error(
                    "Queue consumer stopped with a fatal error for bot {}; the session was terminated and will not restart; type={}",
                    session.botId,
                    cause::class.qualifiedName,
                )
                return@invokeOnCompletion
            }
            session.scope.launch {
                val shouldRestart = runtime.withSessionLock {
                    if (
                        runtime.closed ||
                        runtime.currentSession !== session ||
                        session.consumerJob !== consumer ||
                        session.consumerRestartedAfterError
                    ) {
                        false
                    } else {
                        session.consumerRestartedAfterError = true
                        true
                    }
                }
                if (shouldRestart && runtime.isCurrent(session)) {
                    logger.warn(
                        "Queue consumer hit a recoverable error for bot {}; restarting it once after queue retry completion.",
                        session.botId,
                    )
                    start(session)
                }
            }
        }
    }

    private fun isRecoverableQueueConsumerFailure(cause: Throwable): Boolean = cause is StackOverflowError

    /**
     * 在 fatal error 后原子摘除当前会话，防止 polling 继续向无人消费的队列投递。
     *
     * @param session 消费者发生 fatal error 的轮询会话。
     * @param consumer 已失败的消费者任务。
     */
    private fun terminateFatalQueueConsumerSession(session: PollingSession, consumer: Job) {
        val shouldTerminate = runtime.withSessionLock {
            if (
                runtime.closed ||
                runtime.currentSession !== session ||
                session.consumerJob !== consumer
            ) {
                false
            } else {
                runtime.currentSession = null
                session.updateChannel.close()
                session.consumerResume.close()
                session.outboxSignal.close()
                true
            }
        }
        if (shouldTerminate) {
            drainQueuedUpdatesAsRetry(session)
            session.pollJob?.cancel(CancellationException("Queue consumer stopped after fatal error."))
            session.scope.cancel(CancellationException("Queue consumer stopped after fatal error."))
        }
    }

    /**
     * 串行消费会话队列，并在所有退出路径把当前与排队工作结算为 [UpdateCompletion.Retry]。
     *
     * 普通 [Exception] 仅影响当前业务工作。
     *
     * @param session 提供工作队列和消费者控制信号的轮询会话。
     */
    private suspend fun consumeQueue(session: PollingSession) {
        var currentWork: QueuedWork? = null
        try {
            while (currentCoroutineContext().isActive) {
                val queuedWork = session.updateChannel.receiveCatching().getOrNull() ?: return
                currentWork = queuedWork
                val deadline = queuedWork.entryTime + processingTimeout.inWholeMilliseconds
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                val completion = try {
                    withTimeout(remaining.milliseconds) {
                        processQueuedWork(session, queuedWork)
                    }
                } catch (_: TimeoutCancellationException) {
                    handleProcessingTimeout(session, queuedWork)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Error processing update {}; category={}",
                        queuedWork.update.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    UpdateCompletion.Retry
                }

                currentCoroutineContext().ensureActive()
                if (runtime.isCurrent(session)) {
                    queuedWork.completion.complete(completion)
                    currentWork = null
                    if (completion == UpdateCompletion.Retry) {
                        drainQueuedUpdatesAsRetry(session)
                        session.consumerResume.receiveCatching().getOrNull() ?: return
                    }
                }
            }
        } finally {
            currentWork?.completion?.complete(UpdateCompletion.Retry)
            drainQueuedUpdatesAsRetry(session)
        }
    }

    private fun drainQueuedUpdatesAsRetry(session: PollingSession) {
        while (true) {
            val queued = session.updateChannel.tryReceive().getOrNull() ?: return
            queued.completion.complete(UpdateCompletion.Retry)
        }
    }

    private suspend fun processQueuedWork(
        session: PollingSession,
        work: QueuedWork,
    ): UpdateCompletion = when (work) {
        is QueuedWork.DurableFinal -> completeFinalAgentTurn(
            session,
            work.entry,
            work.expectedRetryCheckpointTarget,
        )

        is QueuedWork.DurableInProgress -> confirmDurableInProgressTurn(
            session,
            work.entry,
            work.expectedRetryCheckpointTarget,
        )

        is QueuedWork.Authorized -> processAuthorizedUpdate(
            session,
            work.update,
            work.ticket,
            work.expectedRetryCheckpointTarget,
        )
    }

    private suspend fun processAuthorizedUpdate(
        session: PollingSession,
        update: com.unscientificjszhai.tgp.models.Update,
        ticket: AdmissionTicket,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val message = update.message ?: return UpdateCompletion.Confirmed
        val authorization = admissionPolicy.authorizationFor(message)
        return when {
            message.text?.startsWith("/") == true -> commandHandler.handle(
                session,
                ticket,
                authorization,
                update.updateId,
                expectedRetryCheckpointTarget,
                message.text,
                clearQueue = { clearQueue(session) },
            )

            message.voice != null -> completeVoiceAgentUpdate(
                session,
                ticket,
                update.updateId,
                authorization,
                message,
                expectedRetryCheckpointTarget,
            )

            message.text != null -> completeTextAgentUpdate(
                session,
                ticket,
                update.updateId,
                authorization,
                message,
                expectedRetryCheckpointTarget,
            )

            else -> UpdateCompletion.Confirmed
        }
    }

    private suspend fun handleProcessingTimeout(
        session: PollingSession,
        work: QueuedWork,
    ): UpdateCompletion = when (work) {
        is QueuedWork.DurableFinal -> completeFinalAgentTurn(
            session,
            work.entry,
            work.expectedRetryCheckpointTarget,
        )

        is QueuedWork.DurableInProgress -> confirmDurableInProgressTurn(
            session,
            work.entry,
            work.expectedRetryCheckpointTarget,
        )

        is QueuedWork.Authorized -> handleAuthorizedProcessingTimeout(session, work)
    }

    private suspend fun handleAuthorizedProcessingTimeout(
        session: PollingSession,
        work: QueuedWork.Authorized,
    ): UpdateCompletion {
        val update = work.update
        val message = update.message ?: return UpdateCompletion.Retry
        val authorization = admissionPolicy.authorizationFor(message)
        if (
            !message.text.orEmpty().startsWith("/") &&
            (message.text != null || message.voice != null)
        ) {
            return finalizeTimedOutDurableAgentTurn(
                session,
                work.ticket,
                authorization,
                update.updateId,
                work.expectedRetryCheckpointTarget,
            )
        }
        logger.warn("Non-durable update {} processing timed out.", update.updateId)
        return sendAuthorizedTimeoutNotification(
            session,
            work.ticket,
            authorization,
            update.updateId,
            work.expectedRetryCheckpointTarget,
        )
    }

    private suspend fun finalizeTimedOutDurableAgentTurn(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion = try {
        val entry = withContext(NonCancellable) {
            updatesRepository.getData(session.botId).agentTurnJournal.singleOrNull { it.updateId == updateId }
        } ?: return sendAuthorizedTimeoutNotification(
            session,
            ticket,
            authorization,
            updateId,
            expectedRetryCheckpointTarget,
        )
        when (entry.status) {
            AgentTurnJournalStatus.FINAL -> completeFinalAgentTurn(
                session,
                entry,
                expectedRetryCheckpointTarget,
            )

            AgentTurnJournalStatus.IN_PROGRESS -> confirmDurableInProgressTurn(
                session,
                entry,
                expectedRetryCheckpointTarget,
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn(
            "Timed out Agent turn {} could not finalize safely; category={}",
            updateId,
            SafeLogging.failureCategory(e).wireName,
        )
        UpdateCompletion.Retry
    }

    private suspend fun sendAuthorizedTimeoutNotification(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion = outboxWorker.persistAuthorizedReply(
        session,
        ticket,
        authorization,
        updateId,
        expectedRetryCheckpointTarget,
        "抱歉，该消息处理超时（超过10分钟）。",
    )

    private suspend fun completeTextAgentUpdate(
        session: PollingSession,
        ticket: AdmissionTicket,
        updateId: Long,
        authorization: AuthorizedMessageContext,
        message: Message,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val text = checkNotNull(message.text)
        if (!isWithinAgentTextLimit(text)) {
            logger.warn("Text input for update {} exceeds the local pre-claim limit.", updateId)
            return UpdateCompletion.Retry
        }
        return try {
            if (!cleanupCoordinator.cleanContextIfNeeded(session, ticket, authorization)) {
                return UpdateCompletion.Confirmed
            }
            when (admissionPolicy.sendAuthorizedChatAction(session, ticket, authorization, "typing")) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> Unit
            }
            coroutineScope {
                val typingJob = typingJob(session, ticket, authorization)
                try {
                    runDurableAgentTurn(
                        session = session,
                        ticket = ticket,
                        updateId = updateId,
                        authorization = authorization,
                        chatId = authorization.chatId,
                        replyParameters = ReplyParameters(messageId = message.messageId),
                        expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                        request = DurableAgentRequest.Text(text),
                    )
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Text update {} could not reach durable Agent claim; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    private suspend fun completeVoiceAgentUpdate(
        session: PollingSession,
        ticket: AdmissionTicket,
        updateId: Long,
        authorization: AuthorizedMessageContext,
        message: Message,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val voice = checkNotNull(message.voice)
        if (!isWithinAgentTextLimit(message.caption)) {
            logger.warn("Voice caption for update {} exceeds the local pre-claim limit.", updateId)
            return UpdateCompletion.Retry
        }
        val audioData = try {
            val fileResponse = when (
                val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                    telegramService.getFileForToken(session.token, voice.fileId)
                }
            ) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> result.value
            }
            val filePath = fileResponse.result?.filePath
                ?: throw IllegalStateException("Failed to get file path for voice message")
            when (
                val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                    telegramService.downloadFileForToken(session.token, filePath)
                }
            ) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> result.value
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Voice input for update {} was unavailable before Agent claim; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateCompletion.Retry
        }
        if (audioData.isEmpty()) {
            logger.warn("Voice input for update {} was empty before Agent claim.", updateId)
            return UpdateCompletion.Retry
        }
        return try {
            if (!cleanupCoordinator.cleanContextIfNeeded(session, ticket, authorization)) {
                return UpdateCompletion.Confirmed
            }
            when (admissionPolicy.sendAuthorizedChatAction(session, ticket, authorization, "typing")) {
                AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                is AuthorizedEffect.Executed -> Unit
            }
            coroutineScope {
                val typingJob = typingJob(session, ticket, authorization)
                try {
                    runDurableAgentTurn(
                        session = session,
                        ticket = ticket,
                        updateId = updateId,
                        authorization = authorization,
                        chatId = authorization.chatId,
                        replyParameters = ReplyParameters(messageId = message.messageId),
                        expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                        request = DurableAgentRequest.Voice(
                            message.caption,
                            listOf(MediaData(audioData, voice.mimeType ?: "audio/ogg")),
                        ),
                    )
                } finally {
                    typingJob.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Voice update {} could not reach durable Agent claim; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    private suspend fun runDurableAgentTurn(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters,
        expectedRetryCheckpointTarget: Long?,
        request: DurableAgentRequest,
    ): UpdateCompletion = agentService.withReadyService { readyAgent ->
        runDurableAgentTurnWithReadyService(
            session = session,
            ticket = ticket,
            authorization = authorization,
            updateId = updateId,
            chatId = chatId,
            replyParameters = replyParameters,
            expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
            readyAgent = readyAgent,
            request = request,
        )
    }

    /**
     * 在单次 ready-agent 准入内完成 claim、一次 Agent 调用、终态持久化和提交。
     *
     * @param session 当前轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @param updateId 触发 Agent 回合的 Telegram 更新标识。
     * @param chatId 回复目标聊天标识。
     * @param replyParameters Telegram 回复参数。
     * @param expectedRetryCheckpointTarget 提交 offset 时必须匹配的重试检查点目标。
     * @param readyAgent 已通过委派服务 ready 屏障的底层 Agent。
     * @param request 要执行且不可重放的 Agent 请求。
     * @return durable 回合完成后对轮询 offset 的处理要求。
     */
    private suspend fun runDurableAgentTurnWithReadyService(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        chatId: String,
        replyParameters: ReplyParameters,
        expectedRetryCheckpointTarget: Long?,
        readyAgent: AgentService,
        request: DurableAgentRequest,
    ): UpdateCompletion {
        val key = AgentTurnKey(session.botId, updateId)
        val owner = acquireAgentTurnOwner(key) ?: return UpdateCompletion.Retry
        try {
            if (!admissionPolicy.isDurableAgentTurnAuthorized(session, ticket, authorization)) {
                return UpdateCompletion.Confirmed
            }
            val claim = withContext(NonCancellable) {
                updatesRepository.claimAgentTurn(session.botId, updateId, chatId, replyParameters)
            }
            return when (claim) {
                AgentTurnClaim.CLAIMED -> {
                    val finalized = try {
                        val reply = request.sendWith(readyAgent).takeIf { it.isNotBlank() }
                        withContext(NonCancellable) {
                            updatesRepository.finalizeAgentTurn(session.botId, updateId, reply)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn(
                            "Agent turn {} failed after durable claim; category={}",
                            updateId,
                            SafeLogging.failureCategory(e).wireName,
                        )
                        withContext(NonCancellable) {
                            updatesRepository.failInProgressAgentTurn(
                                session.botId,
                                updateId,
                                AGENT_TURN_FAILURE_REPLY,
                            )
                        }
                    }
                    finalized?.let {
                        completeFinalAgentTurn(session, it, expectedRetryCheckpointTarget)
                    } ?: UpdateCompletion.Retry
                }

                is AgentTurnClaim.FINAL -> completeFinalAgentTurn(
                    session,
                    claim.entry,
                    expectedRetryCheckpointTarget,
                )

                is AgentTurnClaim.InProgress -> UpdateCompletion.Retry
                AgentTurnClaim.AlreadyConfirmed -> UpdateCompletion.Confirmed
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Durable Agent journal operation failed for update {}; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateCompletion.Retry
        } finally {
            releaseAgentTurnOwner(key, owner)
        }
    }

    /**
     * 静默确认没有本地 owner 的 durable `IN_PROGRESS` 回合，绝不重新进入 Agent。
     *
     * @param session 回合所属的当前轮询会话。
     * @param entry 已持久化的 `IN_PROGRESS` journal 条目。
     * @param expectedRetryCheckpointTarget 提交 offset 时必须匹配的重试检查点目标。
     * @return journal 调和后对轮询 offset 的处理要求。
     */
    suspend fun confirmDurableInProgressTurn(
        session: PollingSession,
        entry: AgentTurnJournalEntry,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val key = AgentTurnKey(session.botId, entry.updateId)
        val hasActiveOwner = runtime.withSessionLock { agentTurnOwners[key]?.isCompleted == false }
        if (hasActiveOwner) {
            return UpdateCompletion.Retry
        }
        return try {
            val confirmed = withContext(NonCancellable) {
                updatesRepository.confirmInProgressAgentTurnWithoutReply(
                    session.botId,
                    entry.updateId,
                    expectedRetryCheckpointTarget,
                )
            }
            if (confirmed) UpdateCompletion.Persisted else UpdateCompletion.Retry
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "In-progress Agent turn {} could not be silently confirmed; category={}",
                entry.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    /**
     * 为 durable `FINAL` 回合提交 outbox 与 offset，并在成功后尽力清理 journal。
     *
     * 本方法绝不重新调用 Agent。
     *
     * @param session 回合所属的当前轮询会话。
     * @param entry 已持久化的 `FINAL` journal 条目。
     * @param expectedRetryCheckpointTarget 提交 offset 时必须匹配的重试检查点目标。
     * @return 终态提交后对轮询 offset 的处理要求。
     */
    suspend fun completeFinalAgentTurn(
        session: PollingSession,
        entry: AgentTurnJournalEntry,
        expectedRetryCheckpointTarget: Long?,
    ): UpdateCompletion {
        val reply = entry.reply?.let {
            PendingTelegramReply(entry.updateId, entry.chatId, it, entry.replyParameters)
        }
        return try {
            val committed = withContext(NonCancellable) {
                updatesRepository.completeAgentUpdateAtRetryCheckpoint(
                    session.botId,
                    entry.updateId,
                    reply,
                    expectedRetryCheckpointTarget,
                )
            }
            if (committed != RetryCheckpointCommitResult.Committed) {
                return UpdateCompletion.Retry
            }
            if (reply != null) {
                outboxWorker.signal(session.botId)
                cleanupCoordinator.recordSuccessfulReply(session)
            }
            try {
                withContext(NonCancellable) {
                    updatesRepository.cleanupConfirmedAgentTurns(session.botId)
                }
            } catch (e: Exception) {
                logger.warn(
                    "Confirmed Agent journal cleanup deferred for update {}; category={}",
                    entry.updateId,
                    SafeLogging.failureCategory(e).wireName,
                )
            }
            UpdateCompletion.Persisted
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "FINAL Agent turn {} could not commit offset/outbox; category={}",
                entry.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            UpdateCompletion.Retry
        }
    }

    private fun acquireAgentTurnOwner(key: AgentTurnKey): CompletableDeferred<Unit>? =
        runtime.withSessionLock {
            agentTurnOwners[key]?.takeIf { !it.isCompleted }?.let { return@withSessionLock null }
            CompletableDeferred<Unit>().also { agentTurnOwners[key] = it }
        }

    private fun releaseAgentTurnOwner(key: AgentTurnKey, owner: CompletableDeferred<Unit>) {
        runtime.withSessionLock {
            owner.complete(Unit)
            if (agentTurnOwners[key] === owner) {
                agentTurnOwners.remove(key)
            }
        }
    }

    private fun isWithinAgentTextLimit(text: String?): Boolean =
        (text ?: "").toByteArray(StandardCharsets.UTF_8).size <= MAX_AGENT_TEXT_BYTES

    private fun typingJob(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
    ): Job = session.scope.launch {
        while (isActive) {
            delay(4000.milliseconds)
            try {
                when (admissionPolicy.sendAuthorizedChatAction(session, ticket, authorization, "typing")) {
                    AuthorizedEffect.Confirmed -> return@launch
                    is AuthorizedEffect.Executed -> Unit
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Exception) {
                logger.warn(
                    "Failed to send typing action; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
            }
        }
    }

    private suspend fun clearQueue(session: PollingSession) {
        var count = 0
        while (true) {
            val queuedWork = session.updateChannel.tryReceive().getOrNull() ?: break
            val completion = when (queuedWork) {
                is QueuedWork.Authorized -> UpdateCompletion.Confirmed
                is QueuedWork.DurableFinal -> completeFinalAgentTurn(
                    session,
                    queuedWork.entry,
                    queuedWork.expectedRetryCheckpointTarget,
                )

                is QueuedWork.DurableInProgress -> confirmDurableInProgressTurn(
                    session,
                    queuedWork.entry,
                    queuedWork.expectedRetryCheckpointTarget,
                )
            }
            queuedWork.completion.complete(completion)
            count++
        }
        if (count > 0) {
            logger.info("Cleared {} pending updates from current polling session due to reset.", count)
        }
    }

    private sealed interface DurableAgentRequest {
        /**
         * 纯文本 Agent 请求。
         *
         * @property text 发送给 Agent 的文本。
         */
        data class Text(val text: String) : DurableAgentRequest

        /**
         * 携带语音媒体的 Agent 请求。
         *
         * @property caption Telegram 语音消息的可选说明文字。
         * @property mediaData 已下载并准备发送给 Agent 的媒体列表。
         */
        data class Voice(
            val caption: String?,
            val mediaData: List<MediaData>,
        ) : DurableAgentRequest

        /**
         * 使用已就绪 Agent 执行请求。
         *
         * @param readyAgent 已通过 ready 屏障的底层 Agent。
         * @return Agent 生成的回复文本。
         */
        suspend fun sendWith(readyAgent: AgentService): String = when (this) {
            is Text -> readyAgent.sendMessage(text, emptyList())
            is Voice -> readyAgent.sendMessage(caption, mediaData)
        }
    }
}
