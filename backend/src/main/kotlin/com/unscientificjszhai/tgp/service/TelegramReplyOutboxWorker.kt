package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.repository.MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS
import com.unscientificjszhai.tgp.repository.PendingTelegramReply
import com.unscientificjszhai.tgp.repository.RetryCheckpointCommitResult
import com.unscientificjszhai.tgp.repository.TelegramReplyDeliveryStage
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.TelegramTextChunks
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.Logger
import kotlin.time.Duration.Companion.seconds

private const val TELEGRAM_REPLY_FALLBACK_MESSAGE = "抱歉，上一条回复未能发送。"

/**
 * 按持久化顺序投递 Telegram 回复；worker 始终运行在所属 [PollingSession.scope]。
 *
 * @param runtime 提供当前会话复核和线性化仓储操作的共享运行时。
 * @param telegramService 发送已持久化回复的 Telegram 服务。
 * @param updatesRepository 读取并推进回复 outbox 的仓储。
 * @param admissionPolicy 在创建回复时复核原消息授权的策略。
 * @param logger 记录投递与恢复结果的日志器。
 */
internal class TelegramReplyOutboxWorker(
    private val runtime: MessagePollingRuntime,
    private val telegramService: TelegramService,
    private val updatesRepository: UpdatesRepository,
    private val admissionPolicy: UpdateAdmissionPolicy,
    private val logger: Logger,
) {
    /**
     * 持续投递当前会话的待发送回复，直至会话被取消或信号通道关闭。
     *
     * @param session 拥有 outbox worker 与唤醒信号的轮询会话。
     */
    suspend fun run(session: PollingSession) {
        while (currentCoroutineContext().isActive) {
            when (deliverNextPendingReply(session)) {
                OutboxDelivery.DELIVERED -> Unit
                OutboxDelivery.EMPTY -> {
                    if (session.outboxSignal.receiveCatching().getOrNull() == null) {
                        return
                    }
                }

                OutboxDelivery.RETRY -> {
                    withTimeoutOrNull(1.seconds) {
                        session.outboxSignal.receiveCatching().getOrNull()
                    }
                }
            }
        }
    }

    /**
     * 每次网络请求前先持久化投递次数；只有 Telegram HTTP 和正文均成功后才在线性化门内推进 outbox。
     *
     * @param session 当前 outbox 所属的轮询会话。
     * @return 本轮已推进、outbox 为空或需要重试的结果。
     */
    private suspend fun deliverNextPendingReply(session: PollingSession): OutboxDelivery {
        val pendingReply = try {
            runtime.readForCurrent(session) {
                updatesRepository.getPendingTelegramReplies(session.botId).firstOrNull()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to read Telegram outbox for bot {}; retrying later; category={}",
                session.botId,
                SafeLogging.failureCategory(e).wireName,
            )
            return OutboxDelivery.RETRY
        } ?: return if (runtime.isCurrent(session)) OutboxDelivery.EMPTY else OutboxDelivery.RETRY

        var replyToSend: PendingTelegramReply? = null
        val deliveryPrepared = try {
            runtime.saveForCurrent(session) {
                replyToSend =
                    updatesRepository.preparePendingTelegramReplyDelivery(session.botId, pendingReply.updateId)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to persist Telegram outbox delivery attempt for update {}; not sending it; category={}",
                pendingReply.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            return OutboxDelivery.RETRY
        }
        if (!deliveryPrepared) {
            return OutboxDelivery.RETRY
        }
        val reply = replyToSend ?: return OutboxDelivery.DELIVERED

        val response = try {
            runtime.ensureCurrent(session)
            telegramService.sendMessageForToken(
                session.token,
                reply.chatId,
                reply.deliveryText(),
                reply.deliveryReplyParameters(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Telegram outbox send failed for update {} of bot {}; retrying later; category={}",
                reply.updateId,
                session.botId,
                SafeLogging.failureCategory(e).wireName,
            )
            null
        }
        if (response?.isTelegramAccepted() != true) {
            if (
                reply.deliveryStage == TelegramReplyDeliveryStage.FALLBACK &&
                reply.deliveryAttempts >= MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS
            ) {
                val exhaustedRemoved = try {
                    runtime.saveForCurrent(session) {
                        updatesRepository.discardExhaustedPendingTelegramReplyFallback(session.botId, reply)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Failed to discard exhausted Telegram fallback chunk for reply {}; retaining it; category={}",
                        reply.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    false
                }
                if (exhaustedRemoved) {
                    logger.warn(
                        "Telegram fallback reply {} of bot {} was rejected three times; terminating that reply.",
                        reply.updateId,
                        session.botId,
                    )
                    return OutboxDelivery.DELIVERED
                }
                return OutboxDelivery.RETRY
            }
            if (reply.deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
                val replacement = when {
                    response?.isPermanentTelegramRejection() == true &&
                            reply.shouldRetryFirstChunkWithoutReplyParameters() ->
                        reply.withoutFirstChunkReplyParameters()

                    response?.isPermanentTelegramRejection() == true -> reply.afterPermanentTelegramRejection()
                    else -> reply.afterRetryableTelegramFailure()
                }
                if (replacement == reply) {
                    logger.warn(
                        "Telegram did not accept outbox reply chunk for update {} of bot {}; retrying later.",
                        reply.updateId,
                        session.botId,
                    )
                    return OutboxDelivery.RETRY
                }
                var replaced = false
                val replacementPersisted = try {
                    runtime.saveForCurrent(session) {
                        replaced = updatesRepository.replacePendingTelegramReply(session.botId, reply, replacement)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error(
                        "Failed to persist Telegram outbox delivery state for reply {}; retrying later; category={}",
                        reply.updateId,
                        SafeLogging.failureCategory(e).wireName,
                    )
                    false
                }
                if (!replacementPersisted || !replaced) {
                    return OutboxDelivery.RETRY
                }
                if (replacement.deliveryStage == TelegramReplyDeliveryStage.FALLBACK) {
                    logger.warn(
                        "Telegram permanently rejected outbox reply {} of bot {} twice; sending fallback next.",
                        reply.updateId,
                        session.botId,
                    )
                } else if (
                    response?.isPermanentTelegramRejection() == true &&
                    reply.shouldRetryFirstChunkWithoutReplyParameters()
                ) {
                    logger.warn(
                        "Telegram permanently rejected the quoted first chunk for outbox reply {} of bot {}; retrying original without reply parameters.",
                        reply.updateId,
                        session.botId,
                    )
                } else if (replacement.permanentRejectionCount == 0) {
                    logger.warn(
                        "Telegram retryable failure reset permanent rejection count for outbox reply {} of bot {}.",
                        reply.updateId,
                        session.botId,
                    )
                } else {
                    logger.warn(
                        "Telegram permanently rejected outbox reply {} of bot {}; retaining original for one final retry.",
                        reply.updateId,
                        session.botId,
                    )
                }
            } else {
                logger.warn(
                    "Telegram did not accept outbox reply for update {} of bot {}; retrying later.",
                    reply.updateId,
                    session.botId,
                )
            }
            return OutboxDelivery.RETRY
        }

        val removed = try {
            runtime.saveForCurrent(session) {
                updatesRepository.advancePendingTelegramReplyDelivery(session.botId, reply)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(
                "Failed to persist delivered Telegram outbox reply chunk {}; retaining it; category={}",
                reply.updateId,
                SafeLogging.failureCategory(e).wireName,
            )
            false
        }
        return if (removed) OutboxDelivery.DELIVERED else OutboxDelivery.RETRY
    }

    /**
     * 在仍持有匹配授权票据时把回复和源更新偏移量原子写入 outbox。
     *
     * @param session 回复所属的当前轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @param updateId 回复对应的 Telegram 更新标识。
     * @param expectedRetryCheckpointTarget 提交 offset 时必须匹配的重试检查点目标。
     * @param text 要持久化并发送的回复文本。
     * @return 持久化完成后对轮询 offset 的处理要求。
     */
    suspend fun persistAuthorizedReply(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
        text: String,
    ): UpdateCompletion {
        val committed = when (
            val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                runtime.writeForCurrent(session) {
                    updatesRepository.completeAgentUpdateAtRetryCheckpoint(
                        botId = session.botId,
                        updateId = updateId,
                        reply = PendingTelegramReply(
                            updateId = updateId,
                            chatId = authorization.chatId,
                            text = text,
                            replyParameters = ReplyParameters(authorization.messageId),
                        ),
                        expectedRetryTarget = expectedRetryCheckpointTarget,
                    )
                }
            }
        ) {
            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
            is AuthorizedEffect.Executed -> result.value
        }
        if (committed == RetryCheckpointCommitResult.Committed) {
            signal(session.botId)
            return UpdateCompletion.Persisted
        }
        return if (runtime.isCurrent(session)) UpdateCompletion.Retry else UpdateCompletion.Confirmed
    }

    /**
     * 唤醒指定 Bot 当前会话的 outbox worker。
     *
     * @param botId 存在待发送回复的 Bot 标识。
     */
    fun signal(botId: String) {
        runtime.signalOutboxForBot(botId)
    }

    private fun TelegramApiResponse.isTelegramAccepted(): Boolean =
        status.isSuccess() && try {
            JsonStructureLimits.parseToJsonElement(Json, body).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true
        } catch (_: Exception) {
            false
        }

    private fun TelegramApiResponse.isPermanentTelegramRejection(): Boolean {
        if (status.value == 429) return false
        if (status.value in 400..499) return true
        return try {
            JsonStructureLimits.parseToJsonElement(Json, body)
                .jsonObject["error_code"]
                ?.jsonPrimitive
                ?.intOrNull
                ?.let { it in 400..499 && it != 429 }
                ?: false
        } catch (_: Exception) {
            false
        }
    }

    private fun PendingTelegramReply.afterPermanentTelegramRejection(): PendingTelegramReply {
        check(deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
            "only original replies can record permanent rejections."
        }
        val rejectionCount = permanentRejectionCount + 1
        return if (rejectionCount < 2) {
            copy(permanentRejectionCount = rejectionCount)
        } else {
            copy(
                replyParameters = null,
                deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
                deliveryAttempts = 0,
                permanentRejectionCount = 0,
            )
        }
    }

    private fun PendingTelegramReply.shouldRetryFirstChunkWithoutReplyParameters(): Boolean =
        deliveryStage == TelegramReplyDeliveryStage.ORIGINAL &&
                nextChunkStart == 0 &&
                replyParameters != null

    private fun PendingTelegramReply.withoutFirstChunkReplyParameters(): PendingTelegramReply {
        check(shouldRetryFirstChunkWithoutReplyParameters()) {
            "only an original first chunk with reply parameters can retry without them."
        }
        return copy(
            replyParameters = null,
            deliveryAttempts = 0,
            permanentRejectionCount = 0,
        )
    }

    private fun PendingTelegramReply.afterRetryableTelegramFailure(): PendingTelegramReply {
        check(deliveryStage == TelegramReplyDeliveryStage.ORIGINAL) {
            "only original replies can reset permanent rejections."
        }
        return if (permanentRejectionCount == 0) this else copy(permanentRejectionCount = 0)
    }

    private fun PendingTelegramReply.deliveryText(): String = when (deliveryStage) {
        TelegramReplyDeliveryStage.ORIGINAL -> TelegramTextChunks.chunkAt(text, nextChunkStart)
        TelegramReplyDeliveryStage.FALLBACK -> TELEGRAM_REPLY_FALLBACK_MESSAGE
    }

    private fun PendingTelegramReply.deliveryReplyParameters(): ReplyParameters? =
        replyParameters.takeIf {
            deliveryStage == TelegramReplyDeliveryStage.ORIGINAL && nextChunkStart == 0
        }

    private enum class OutboxDelivery {
        DELIVERED,
        EMPTY,
        RETRY,
    }
}
