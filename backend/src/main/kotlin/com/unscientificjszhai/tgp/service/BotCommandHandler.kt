package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.repository.SettingsGenerationMismatchException
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger

/**
 * 处理已通过 [UpdateAdmissionPolicy] 取得不可变票据的 Bot 命令。
 *
 * @param admissionPolicy 在每个命令副作用前复核消息授权的策略。
 * @param cleanupCoordinator 管理 Agent 上下文重置和清理计时的协调器。
 * @param outboxWorker 持久化命令回复的 outbox worker。
 * @param agentService 提供模型查询与上下文重置的 Agent 服务。
 * @param settingsRepository 持久化模型选择的设置仓储。
 * @param logger 记录命令处理结果的日志器。
 */
internal class BotCommandHandler(
    private val admissionPolicy: UpdateAdmissionPolicy,
    private val cleanupCoordinator: ContextCleanupCoordinator,
    private val outboxWorker: TelegramReplyOutboxWorker,
    private val agentService: AgentService,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger,
) {
    /** 在测试中于模型选择持久化前执行的竞争注入点。 */
    @Volatile
    internal var beforeModelSelectionPersistForTesting: (() -> Unit)? = null

    /** 在测试中于刷新可用模型前执行的竞争注入点。 */
    @Volatile
    internal var beforeModelRefreshForTesting: (() -> Unit)? = null

    /**
     * 处理一条已授权 Bot 命令。
     *
     * `/reset` 的队列清理由 [AgentTurnProcessor] 以回调注入，避免命令与处理器形成对象环。
     *
     * @param session 命令所属的当前轮询会话。
     * @param ticket 命令通过准入时捕获的设置票据。
     * @param authorization 原命令消息携带的授权事实。
     * @param updateId 命令对应的 Telegram 更新标识。
     * @param expectedRetryCheckpointTarget 提交命令结果时必须匹配的重试检查点目标。
     * @param text 命令原始文本。
     * @param clearQueue 重置成功后清理当前会话队列的回调。
     * @return 命令处理完成后对轮询 offset 的处理要求。
     */
    suspend fun handle(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
        updateId: Long,
        expectedRetryCheckpointTarget: Long?,
        text: String,
        clearQueue: suspend () -> Unit,
    ): UpdateCompletion {
        val parts = text.split(Regex("\\s+"), 2)
        return when (parts[0]) {
            "/keep" -> {
                when (
                    admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                        cleanupCoordinator.refreshTimer(session)
                        logger.info("Auto-clean context timer refreshed by keep command for bot {}", session.botId)
                    }
                ) {
                    AuthorizedEffect.Confirmed -> UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> UpdateCompletion.Confirmed
                }
            }

            "/reset" -> {
                val reset = when (
                    val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                        cleanupCoordinator.awaitSuccessfulAgentReset()
                    }
                ) {
                    AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> result.value
                }
                if (!reset) {
                    logger.warn("Session reset failed by command for bot {}", session.botId)
                    return outboxWorker.persistAuthorizedReply(
                        session,
                        ticket,
                        authorization,
                        updateId,
                        expectedRetryCheckpointTarget,
                        "会话重置失败，请稍后重试。",
                    )
                }
                when (admissionPolicy.runWhenAuthorized(session, ticket, authorization) { clearQueue() }) {
                    AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> Unit
                }
                when (
                    admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                        cleanupCoordinator.clearTimer(session)
                    }
                ) {
                    AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                    is AuthorizedEffect.Executed -> Unit
                }
                logger.info("Session reset and queue cleared by command for bot {}", session.botId)
                outboxWorker.persistAuthorizedReply(
                    session,
                    ticket,
                    authorization,
                    updateId,
                    expectedRetryCheckpointTarget,
                    "会话已重置，待处理消息已清空。",
                )
            }

            "/model" -> {
                if (parts.size > 1) {
                    val requestedModel = parts[1].trim()
                    try {
                        val selectedModel = when (
                            val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                                agentService.availableModels.firstOrNull { model ->
                                    model == requestedModel ||
                                            model.removePrefix("models/") == requestedModel.removePrefix("models/")
                                }
                            }
                        ) {
                            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                            is AuthorizedEffect.Executed -> result.value
                        } ?: throw IllegalArgumentException("Unsupported model: $requestedModel")
                        val update = when (
                            val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) {
                                beforeModelSelectionPersistForTesting?.invoke()
                                persistSelectedModel(selectedModel, ticket.generation)
                            }
                        ) {
                            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                            is AuthorizedEffect.Executed -> result.value
                        }
                        val successorAi = update.current.settings.ai ?: return UpdateCompletion.Confirmed
                        val successorTicket = AdmissionTicket(successorAi.agentChatId, update.current.generation)
                        when (
                            admissionPolicy.runWhenAuthorized(session, successorTicket, authorization) {
                                cleanupCoordinator.clearTimer(session)
                            }
                        ) {
                            AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                            is AuthorizedEffect.Executed -> Unit
                        }
                        outboxWorker.persistAuthorizedReply(
                            session,
                            successorTicket,
                            authorization,
                            updateId,
                            expectedRetryCheckpointTarget,
                            "已保存模型选择，正在切换模型并重置会话：$selectedModel",
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SettingsGenerationMismatchException) {
                        UpdateCompletion.Confirmed
                    } catch (_: Exception) {
                        outboxWorker.persistAuthorizedReply(
                            session,
                            ticket,
                            authorization,
                            updateId,
                            expectedRetryCheckpointTarget,
                            "不支持的模型：$requestedModel\n使用 /model 查看可用列表。",
                        )
                    }
                } else {
                    val modelSnapshot = when (
                        val result = admissionPolicy.runWhenAuthorizedWithReadyAgent(
                            session,
                            ticket,
                            authorization,
                        ) { readyAgent ->
                            beforeModelRefreshForTesting?.invoke()
                            readyAgent.updateModel()
                        }
                    ) {
                        AuthorizedEffect.Confirmed -> return UpdateCompletion.Confirmed
                        is AuthorizedEffect.Executed -> result.value
                    }
                    if (modelSnapshot == null) {
                        return outboxWorker.persistAuthorizedReply(
                            session,
                            ticket,
                            authorization,
                            updateId,
                            expectedRetryCheckpointTarget,
                            "获取可用模型列表失败，请稍后重试。",
                        )
                    }
                    val list = modelSnapshot.availableModels.joinToString("\n") { model ->
                        if (model == modelSnapshot.currentModel) "✅ $model" else "      $model"
                    }
                    outboxWorker.persistAuthorizedReply(
                        session,
                        ticket,
                        authorization,
                        updateId,
                        expectedRetryCheckpointTarget,
                        "当前可用模型列表：\n$list\n\n使用 `/model <模型名称>` 切换模型。",
                    )
                }
            }

            else -> UpdateCompletion.Confirmed
        }
    }

    /**
     * 以设置代次 CAS 持久化选中的模型。
     *
     * @param selectedModel 已验证可用的模型名称。
     * @param expectedGeneration 必须仍匹配的设置代次。
     * @return 设置仓储提交的新旧快照。
     */
    private fun persistSelectedModel(selectedModel: String, expectedGeneration: Long) =
        settingsRepository.updateSettings(expectedGeneration = expectedGeneration) { settings ->
            val aiSettings = checkNotNull(settings.ai) { "AI configuration is unavailable." }
            settings.copy(ai = aiSettings.copy(selectedModel = selectedModel))
        }
}
