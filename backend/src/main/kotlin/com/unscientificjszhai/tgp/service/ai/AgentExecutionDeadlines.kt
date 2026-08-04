package com.unscientificjszhai.tgp.service.ai

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Agent 初始化和定时回合的总体执行时限。
 *
 * 这些时限约束完整工作流，而不是单个网络请求：MCP 时限包含连接锁等待、全部服务器连接和工具发现；
 * 候选初始化时限包含首轮会话重置和模型发现；定时任务时限包含预消费、Agent 回合和 Telegram 投递。
 * 所有时限必须为有限正值，避免错误配置重新引入无界等待。
 *
 * @property mcpBatch MCP 连接批次的总体时限，默认 60 秒。
 * @property candidateInitialization 候选 Agent 初始化的总体时限，默认 90 秒。
 * @property scheduledTurn 单次已准入定时任务的总体时限，默认 5 分钟。
 */
@PublishedApi
internal data class AgentExecutionDeadlines(
    val mcpBatch: Duration = 60.seconds,
    val candidateInitialization: Duration = 90.seconds,
    val scheduledTurn: Duration = 5.minutes,
) {
    init {
        require(mcpBatch.isFinite() && mcpBatch.isPositive()) { "MCP 批次时限必须是有限正值。" }
        require(candidateInitialization.isFinite() && candidateInitialization.isPositive()) {
            "候选 Agent 初始化时限必须是有限正值。"
        }
        require(scheduledTurn.isFinite() && scheduledTurn.isPositive()) { "定时任务时限必须是有限正值。" }
    }
}
