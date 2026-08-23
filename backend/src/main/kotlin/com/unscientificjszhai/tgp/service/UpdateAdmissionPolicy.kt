package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Message
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.Update
import com.unscientificjszhai.tgp.repository.AgentTurnJournalStatus
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilityState
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger

/** 一条更新在当前轮询批次中的接纳结果。 */
internal sealed interface UpdateAdmission {
    data object Confirmed : UpdateAdmission

    /**
     * 已进入当前会话队列的更新。
     *
     * @property completion 消费者写入最终处理要求的完成信号。
     */
    data class Enqueued(val completion: CompletableDeferred<UpdateCompletion>) : UpdateAdmission
    data object Retry : UpdateAdmission

    /**
     * 已持久化检查点、需要等待 Agent 可用性变化的更新。
     *
     * @property observedSequence 已观察到的 Agent 可用性事件序列。
     * @property observedSettingsVersion 已观察到的设置代次。
     */
    data class WaitingForAgent(
        val observedSequence: Long,
        val observedSettingsVersion: Long,
    ) : UpdateAdmission
}

/** 屏障放行后的本地接纳判定；队满通知必须在屏障外发送。 */
private sealed interface BarrierAdmission {
    data object Confirmed : BarrierAdmission
    data object Retry : BarrierAdmission

    /**
     * 屏障内观察到 Agent 尚未就绪的结果。
     *
     * @property observedSequence 已观察到的 Agent 可用性事件序列。
     * @property observedSettingsVersion 已观察到的设置代次。
     */
    data class WaitingForAgent(
        val observedSequence: Long,
        val observedSettingsVersion: Long,
    ) : BarrierAdmission

    /**
     * 屏障内成功入队的结果。
     *
     * @property completion 消费者写入最终处理要求的完成信号。
     */
    data class Enqueued(val completion: CompletableDeferred<UpdateCompletion>) : BarrierAdmission

    /**
     * 队列已满但消息仍获授权的结果。
     *
     * @property authorization 原消息携带的授权事实。
     * @property ticket 更新通过准入时捕获的设置票据。
     */
    data class QueueFull(
        val authorization: AuthorizedMessageContext,
        val ticket: AdmissionTicket,
    ) : BarrierAdmission
}

/**
 * 决定 Telegram 更新是否可以进入当前会话，并集中执行所有设置票据与模型屏障复核。
 *
 * 此类不缓存任何可变设置；每次副作用都重新读取仓储快照并验证 generation、私聊、chat 与 sender。
 *
 * @param runtime 提供当前会话复核与线性化队列操作的共享运行时。
 * @param telegramService 发送准入反馈与聊天动作的 Telegram 服务。
 * @param agentService 提供 Agent 就绪状态、可用性检查和 ready 准入的服务。
 * @param settingsChangeCoordinator 提供最新 AI 设置与设置代次的协调器。
 * @param updatesRepository 查询 durable Agent journal 的仓储。
 * @param modelSwitchBarrier 协调模型切换与副作用准入的共享屏障。
 * @param logger 记录准入与反馈结果的日志器。
 */
internal class UpdateAdmissionPolicy(
    private val runtime: MessagePollingRuntime,
    private val telegramService: TelegramService,
    private val agentService: AgentService,
    private val settingsChangeCoordinator: SettingsChangeCoordinator,
    private val updatesRepository: UpdatesRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    private val logger: Logger,
) {
    /**
     * 判断一条 Telegram 更新是否确认、入队、重试或等待 Agent。
     *
     * @param session 收到更新的当前轮询会话。
     * @param update 待接纳的 Telegram 更新。
     * @param expectedRetryCheckpointTarget 提交该更新时必须匹配的重试检查点目标。
     * @return 当前轮询批次应采取的接纳结果。
     */
    suspend fun enqueueUpdate(
        session: PollingSession,
        update: Update,
        expectedRetryCheckpointTarget: Long? = null,
    ): UpdateAdmission {
        if (!runtime.isCurrent(session)) {
            return UpdateAdmission.Confirmed
        }
        val message = update.message ?: return UpdateAdmission.Confirmed
        if (message.text == null && message.voice == null) {
            return UpdateAdmission.Confirmed
        }
        val authorization = authorizationFor(message)
        val existingTurn = try {
            updatesRepository.findAgentTurn(session.botId, update.updateId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Unable to read durable Agent journal for update {}; preserving its offset; category={}",
                update.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateAdmission.Retry
        }
        if (existingTurn != null) {
            val completion = CompletableDeferred<UpdateCompletion>()
            val work = when (existingTurn.status) {
                AgentTurnJournalStatus.FINAL -> QueuedWork.DurableFinal(
                    update = update,
                    entryTime = System.currentTimeMillis(),
                    completion = completion,
                    expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                    entry = existingTurn,
                )

                AgentTurnJournalStatus.IN_PROGRESS -> QueuedWork.DurableInProgress(
                    update = update,
                    entryTime = System.currentTimeMillis(),
                    completion = completion,
                    expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                    entry = existingTurn,
                )
            }
            return when (runtime.offerUpdateForCurrent(session, work)) {
                QueueOfferResult.ENQUEUED -> UpdateAdmission.Enqueued(completion)
                QueueOfferResult.FULL -> UpdateAdmission.Retry
                QueueOfferResult.NOT_CURRENT -> UpdateAdmission.Confirmed
            }
        }

        val admission = modelSwitchBarrier.runWhenReady {
            if (!runtime.isCurrent(session)) {
                return@runWhenReady BarrierAdmission.Confirmed
            }

            val snapshot = settingsChangeCoordinator.currentSettingsSnapshot()
            val aiSettings = snapshot.settings.ai
                ?: return@runWhenReady BarrierAdmission.Confirmed
            if (
                !aiSettings.agentEnabled ||
                aiSettings.requiredApiKey().isBlank() ||
                !authorization.matches(aiSettings)
            ) {
                return@runWhenReady BarrierAdmission.Confirmed
            }

            val availabilityBeforeCheck = agentService.availability.value
            if (availabilityBeforeCheck.state.isAgentRecoveryWaitState()) {
                return@runWhenReady BarrierAdmission.WaitingForAgent(
                    observedSequence = availabilityBeforeCheck.sequence,
                    observedSettingsVersion = snapshot.generation,
                )
            }

            val aiAvailable = try {
                agentService.isAiFeatureEnabled(aiSettings)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "AI availability check failed for update {}; preserving its offset for retry; category={}",
                    update.updateId,
                    SafeLogging.failureCategory(e).wireName,
                )
                return@runWhenReady BarrierAdmission.Retry
            }
            if (!aiAvailable) {
                val unavailable = agentService.availability.value
                if (unavailable.state.isAgentRecoveryWaitState()) {
                    return@runWhenReady BarrierAdmission.WaitingForAgent(
                        observedSequence = unavailable.sequence,
                        observedSettingsVersion = snapshot.generation,
                    )
                }
                logger.warn(
                    "AI remains unavailable after the model switch barrier for update {}; preserving its offset for retry.",
                    update.updateId,
                )
                return@runWhenReady BarrierAdmission.Retry
            }

            val ticket = AdmissionTicket(
                agentChatId = aiSettings.agentChatId,
                generation = snapshot.generation,
            )
            val completion = CompletableDeferred<UpdateCompletion>()
            when (
                runtime.offerUpdateForCurrent(
                    session,
                    QueuedWork.Authorized(
                        update = update,
                        entryTime = System.currentTimeMillis(),
                        completion = completion,
                        expectedRetryCheckpointTarget = expectedRetryCheckpointTarget,
                        ticket = ticket,
                    ),
                )
            ) {
                QueueOfferResult.ENQUEUED -> BarrierAdmission.Enqueued(completion)
                QueueOfferResult.FULL -> BarrierAdmission.QueueFull(authorization, ticket)
                QueueOfferResult.NOT_CURRENT -> BarrierAdmission.Confirmed
            }
        }
        return when (admission) {
            BarrierAdmission.Confirmed -> UpdateAdmission.Confirmed
            BarrierAdmission.Retry -> UpdateAdmission.Retry
            is BarrierAdmission.WaitingForAgent -> UpdateAdmission.WaitingForAgent(
                observedSequence = admission.observedSequence,
                observedSettingsVersion = admission.observedSettingsVersion,
            )

            is BarrierAdmission.Enqueued -> UpdateAdmission.Enqueued(admission.completion)
            is BarrierAdmission.QueueFull -> notifyQueueFull(
                session,
                update.updateId,
                admission.authorization,
                admission.ticket,
            )
        }
    }

    /**
     * 在屏障外发送队满提示；未被 Telegram 接受时保留该更新的偏移量。
     *
     * @param session 收到队满更新的当前轮询会话。
     * @param updateId 被拒绝入队的 Telegram 更新标识。
     * @param authorization 原消息携带的授权事实。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @return 通知已被接受或授权失效时为确认，否则要求重试。
     */
    private suspend fun notifyQueueFull(
        session: PollingSession,
        updateId: Long,
        authorization: AuthorizedMessageContext,
        ticket: AdmissionTicket,
    ): UpdateAdmission {
        logger.warn("Update {} rejected: queue is full.", updateId)
        val notification = try {
            sendAuthorizedMessage(
                session,
                ticket,
                authorization,
                "抱歉，当前处理队列已满（最多同时排队10条消息），请稍后再试。",
                ReplyParameters(messageId = authorization.messageId),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Queue full notification request failed for update {}; category={}",
                updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return UpdateAdmission.Retry
        }
        if (notification is AuthorizedEffect.Confirmed) {
            return UpdateAdmission.Confirmed
        }
        val notificationAccepted =
            (notification as AuthorizedEffect.Executed).value?.isTelegramAccepted() == true
        if (notificationAccepted) {
            logger.info("Queue full notification accepted for update {}; confirming update.", updateId)
            return UpdateAdmission.Confirmed
        }
        logger.warn("Queue full notification was not accepted for update {}; preserving offset for retry.", updateId)
        return UpdateAdmission.Retry
    }

    /**
     * 在已由 ready service 准入的 durable 回合中复核授权，不重复进入模型屏障。
     *
     * @param session durable 回合所属的轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @return 当前设置、消息身份和会话仍全部有效时返回 `true`。
     */
    fun isDurableAgentTurnAuthorized(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
    ): Boolean {
        val snapshot = settingsChangeCoordinator.currentSettingsSnapshot()
        val aiSettings = snapshot.settings.ai
        return snapshot.generation == ticket.generation &&
                aiSettings != null &&
                aiSettings.agentEnabled &&
                aiSettings.requiredApiKey().isNotBlank() &&
                aiSettings.agentChatId == ticket.agentChatId &&
                authorization.matches(aiSettings) &&
                runtime.isCurrent(session)
    }

    /**
     * 在共享模型屏障内同时复核票据、设置和会话后执行副作用。
     *
     * @param session 副作用所属的轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @param action 授权有效时以最新设置执行的副作用。
     * @return 授权失效的确认结果，或副作用的执行结果。
     */
    suspend fun <T> runWhenAuthorized(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        action: suspend (AppSettings) -> T,
    ): AuthorizedEffect<T> = modelSwitchBarrier.runWhenReady {
        val snapshot = settingsChangeCoordinator.currentSettingsSnapshot()
        val aiSettings = snapshot.settings.ai
        if (
            snapshot.generation != ticket.generation ||
            aiSettings == null ||
            !aiSettings.agentEnabled ||
            aiSettings.requiredApiKey().isBlank() ||
            aiSettings.agentChatId != ticket.agentChatId ||
            !authorization.matches(aiSettings) ||
            !runtime.isCurrent(session)
        ) {
            AuthorizedEffect.Confirmed
        } else {
            AuthorizedEffect.Executed(action(snapshot.settings))
        }
    }

    /**
     * 在委派服务的单次 ready 准入内复核授权并直接操作底层 Agent。
     *
     * @param session 副作用所属的轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @param action 授权有效时使用已就绪底层 Agent 执行的副作用。
     * @return 授权失效的确认结果，或副作用的执行结果。
     */
    suspend fun <T> runWhenAuthorizedWithReadyAgent(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        action: suspend (AgentService) -> T,
    ): AuthorizedEffect<T> = agentService.withReadyService { readyAgent ->
        val snapshot = settingsChangeCoordinator.currentSettingsSnapshot()
        val aiSettings = snapshot.settings.ai
        if (
            snapshot.generation != ticket.generation ||
            aiSettings == null ||
            !aiSettings.agentEnabled ||
            aiSettings.requiredApiKey().isBlank() ||
            aiSettings.agentChatId != ticket.agentChatId ||
            !authorization.matches(aiSettings) ||
            !runtime.isCurrent(session)
        ) {
            AuthorizedEffect.Confirmed
        } else {
            AuthorizedEffect.Executed(action(readyAgent))
        }
    }

    /**
     * 在授权仍有效时发送 Telegram 文本消息。
     *
     * @param session 消息所属的轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @param text 要发送的文本。
     * @param replyParameters 可选的 Telegram 回复参数。
     * @return 授权失效的确认结果，或 Telegram API 响应。
     */
    suspend fun sendAuthorizedMessage(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        text: String,
        replyParameters: ReplyParameters? = null,
    ): AuthorizedEffect<TelegramApiResponse?> = runWhenAuthorized(session, ticket, authorization) {
        telegramService.sendMessageForToken(session.token, authorization.chatId, text, replyParameters)
    }

    /**
     * 在授权仍有效时发送 Telegram 聊天动作。
     *
     * @param session 动作所属的轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @param action Telegram 聊天动作名称。
     * @return 授权失效的确认结果，或 Telegram API 响应。
     */
    suspend fun sendAuthorizedChatAction(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        action: String,
    ): AuthorizedEffect<TelegramApiResponse?> = runWhenAuthorized(session, ticket, authorization) {
        telegramService.sendChatActionForToken(session.token, authorization.chatId, action)
    }

    /**
     * 从 Telegram 消息提取后续副作用需要复核的授权事实。
     *
     * @param message 提供聊天、发送者与消息标识的 Telegram 消息。
     * @return 对消息身份的不可变授权上下文。
     */
    fun authorizationFor(message: Message): AuthorizedMessageContext = AuthorizedMessageContext(
        chatId = message.chat.id.toString(),
        chatType = message.chat.type,
        fromId = message.from?.id?.toString(),
        messageId = message.messageId,
    )

    private fun AISettings.requiredApiKey(): String = when (provider) {
        AIProvider.GEMINI -> geminiApiKey
        AIProvider.OPENAI -> openAiApiKey
    }

    private fun TelegramApiResponse.isTelegramAccepted(): Boolean =
        status.isSuccess() && try {
            JsonStructureLimits.parseToJsonElement(Json, body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }
}

/**
 * 判断 Agent 可用性状态是否要求轮询器等待恢复事件。
 *
 * @receiver 待分类的 Agent 可用性状态。
 * @return 初始化、已安排重试或阻塞状态返回 `true`，其余状态返回 `false`。
 */
internal fun AgentAvailabilityState.isAgentRecoveryWaitState(): Boolean = when (this) {
    AgentAvailabilityState.INITIALIZING,
    AgentAvailabilityState.RETRY_SCHEDULED,
    AgentAvailabilityState.BLOCKED,
        -> true

    AgentAvailabilityState.DISABLED,
    AgentAvailabilityState.READY,
    AgentAvailabilityState.CLOSED,
        -> false
}
