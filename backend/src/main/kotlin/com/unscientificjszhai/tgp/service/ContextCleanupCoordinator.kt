package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.CancellationException
import org.slf4j.Logger
import kotlin.time.Duration.Companion.minutes

/**
 * 一次自动上下文清理的授权内结果。
 *
 * @property intervalMinutes 触发清理的无回复间隔分钟数。
 * @property silent 是否禁止发送清理完成通知。
 * @property resetSucceeded Agent 上下文是否重置成功。
 */
private data class ContextCleanupResult(
    val intervalMinutes: Int,
    val silent: Boolean,
    val resetSucceeded: Boolean,
)

/**
 * 管理 Agent 上下文重置和会话清理计时，不创建独立协程作用域。
 *
 * @param runtime 提供当前会话复核的共享轮询运行时。
 * @param admissionPolicy 在重置和通知前复核授权的准入策略。
 * @param agentService 执行 Agent 会话重置的服务。
 * @param logger 记录清理结果的日志器。
 */
internal class ContextCleanupCoordinator(
    private val runtime: MessagePollingRuntime,
    private val admissionPolicy: UpdateAdmissionPolicy,
    private val agentService: AgentService,
    private val logger: Logger,
) {
    /**
     * 在每次清理、重置和通知前复核票据。
     *
     * @param session 正在处理消息的轮询会话。
     * @param ticket 更新通过准入时捕获的设置票据。
     * @param authorization 原消息携带的授权事实。
     * @return 授权仍有效且可以继续处理时返回 `true`；授权已失效时返回 `false`。
     */
    suspend fun cleanContextIfNeeded(
        session: PollingSession,
        ticket: AdmissionTicket,
        authorization: AuthorizedMessageContext,
    ): Boolean {
        val outcome = when (
            val result = admissionPolicy.runWhenAuthorized(session, ticket, authorization) { settings ->
                val aiSettings = checkNotNull(settings.ai)
                val intervalMinutes = aiSettings.autoCleanContextIntervalMinutes
                val lastReplyAt = session.lastAiReplyAtMillis
                if (
                    lastReplyAt == null ||
                    intervalMinutes <= 0 ||
                    System.currentTimeMillis() - lastReplyAt < intervalMinutes.minutes.inWholeMilliseconds
                ) {
                    null
                } else {
                    ContextCleanupResult(
                        intervalMinutes,
                        aiSettings.silentContextCleanup,
                        awaitSuccessfulAgentReset(),
                    )
                }
            }
        ) {
            AuthorizedEffect.Confirmed -> return false
            is AuthorizedEffect.Executed -> result.value
        } ?: return true

        if (!outcome.resetSucceeded) {
            logger.warn(
                "Failed to auto-clean AI context after {} minutes without a successful AI reply.",
                outcome.intervalMinutes,
            )
            return true
        }
        when (admissionPolicy.runWhenAuthorized(session, ticket, authorization) { clearTimer(session) }) {
            AuthorizedEffect.Confirmed -> return false
            is AuthorizedEffect.Executed -> Unit
        }
        if (!outcome.silent) {
            when (
                admissionPolicy.sendAuthorizedMessage(
                    session,
                    ticket,
                    authorization,
                    "检测到距离上次对话已超过 ${outcome.intervalMinutes} 分钟，已自动清理上下文。",
                )
            ) {
                AuthorizedEffect.Confirmed -> return false
                is AuthorizedEffect.Executed -> Unit
            }
        }
        logger.info("Auto-cleaned AI context after {} minutes without a successful AI reply.", outcome.intervalMinutes)
        return true
    }

    /**
     * 等待代理重置结束，并以 `Job.isCancelled` 判定成功。
     *
     * @return 重置任务存在、完成且未被取消时返回 `true`。
     * @throws CancellationException 调用协程在等待期间被取消时原样抛出。
     */
    suspend fun awaitSuccessfulAgentReset(): Boolean {
        val resetJob = try {
            agentService.resetSession()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Failed to start agent session reset; category={}",
                SafeLogging.failureCategory(e).wireName,
            )
            return false
        } ?: return false

        try {
            resetJob.join()
        } catch (e: CancellationException) {
            resetJob.cancel(e)
            throw e
        }
        return !resetJob.isCancelled
    }

    /**
     * 将会话的清理计时刷新为当前时间。
     *
     * @param session 需要刷新计时的轮询会话。
     */
    fun refreshTimer(session: PollingSession) {
        session.lastAiReplyAtMillis = System.currentTimeMillis()
    }

    /**
     * 清除会话的上下文清理计时。
     *
     * @param session 需要清除计时的轮询会话。
     */
    fun clearTimer(session: PollingSession) {
        session.lastAiReplyAtMillis = null
    }

    /**
     * 在会话仍为当前会话时记录一次成功回复。
     *
     * @param session 产生成功回复的轮询会话。
     */
    fun recordSuccessfulReply(session: PollingSession) {
        if (runtime.isCurrent(session)) {
            refreshTimer(session)
        }
    }
}
