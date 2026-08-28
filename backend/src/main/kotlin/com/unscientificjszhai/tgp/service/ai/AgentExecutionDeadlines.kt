package com.unscientificjszhai.tgp.service.ai

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Agent 初始化和定时回合的总体执行时限。
 *
 * 这些时限约束完整工作流，而不是单个网络请求：MCP 时限包含连接锁等待、全部服务器连接和工具发现；
 * 候选初始化时限包含首轮会话重置和模型发现；模型发现时限包含其全部分页读取；定时任务时限包含预消费、
 * Agent 回合和 Telegram 投递。SDK 的阻塞网络 I/O 未必会响应协程取消，因此模型发现时限不能强制中断
 * 正在进行的单次请求；它会阻止之后的分页和模型快照提交。
 * 所有时限必须为有限正值，避免错误配置重新引入无界等待。
 *
 * @property mcpBatch MCP 连接批次的总体时限，默认 60 秒。
 * @property candidateInitialization 候选 Agent 初始化的总体时限，默认 90 秒。
 * @property geminiModelDiscovery 单次 Gemini 模型发现（含全部分页）的总体时限，默认 30 秒。
 * @property scheduledTurn 单次已准入定时任务的总体时限，默认 5 分钟。
 */
@PublishedApi
internal data class AgentExecutionDeadlines(
    val mcpBatch: Duration = 60.seconds,
    val candidateInitialization: Duration = 90.seconds,
    val geminiModelDiscovery: Duration = 30.seconds,
    val scheduledTurn: Duration = 5.minutes,
) {
    init {
        require(mcpBatch.isFinite() && mcpBatch.isPositive()) { "MCP 批次时限必须是有限正值。" }
        require(candidateInitialization.isFinite() && candidateInitialization.isPositive()) {
            "候选 Agent 初始化时限必须是有限正值。"
        }
        require(geminiModelDiscovery.isFinite() && geminiModelDiscovery.isPositive()) {
            "Gemini 模型发现时限必须是有限正值。"
        }
        require(scheduledTurn.isFinite() && scheduledTurn.isPositive()) { "定时任务时限必须是有限正值。" }
    }
}
