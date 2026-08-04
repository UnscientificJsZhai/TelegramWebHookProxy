package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.SkillBrief
import kotlinx.coroutines.Job

/**
 * 一次模型列表刷新得到的一致性快照。
 *
 * @property currentModel 刷新完成后当前实际使用的模型名称。
 * @property availableModels 刷新完成后可供选择的模型名称列表；列表可为空。
 */
data class ModelSnapshot(
    val currentModel: String,
    val availableModels: List<String>,
)

internal const val MAX_TOOL_CALL_ROUNDS = 10

internal class ToolCallLimitExceededException : IllegalStateException(
    "工具调用轮次超过上限（$MAX_TOOL_CALL_ROUNDS 轮）。",
)

/**
 * 表示 OpenAI 代理的一次回合未能完成，因而其会话历史未被提交。
 *
 * 调用方可以在之后重试相同任务；已由本次回合触发的外部工具副作用无法撤销，因此重试具有至少一次
 * 语义。
 *
 * @param message 对未完成原因的稳定说明，不包含提供商返回的敏感内容。
 * @param cause 导致回合未完成的底层异常；没有底层异常时为 `null`。
 */
class AgentTurnFailedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 确保本轮工具调用没有超过上限，避免模型持续请求工具而无法生成最终回复。
 *
 * @param toolCallRounds 已完成的工具调用轮次数；达到 [MAX_TOOL_CALL_ROUNDS] 时抛出异常。
 * @throws ToolCallLimitExceededException 当 [toolCallRounds] 不小于允许上限时抛出。
 */
internal fun ensureToolCallRoundIsAllowed(toolCallRounds: Int) {
    if (toolCallRounds >= MAX_TOOL_CALL_ROUNDS) {
        throw ToolCallLimitExceededException()
    }
}

/**
 * 定义 AI 对话服务的模型管理、会话管理与消息交互契约。
 *
 * 实现类应在 [close] 返回的任务完成后释放所持有的资源；关闭后的服务不应再用于发送消息。
 */
abstract class AgentService {
    /**
     * 获取当前会话实际使用的模型名称。
     */
    abstract val currentModel: String

    /**
     * 获取当前可供选择的模型名称列表。
     *
     * 列表可为空；模型列表通常由 [updateModel] 刷新。
     */
    abstract val availableModels: List<String>

    /**
     * 判断给定设置是否足以启用此服务对应的 AI 功能。
     *
     * @param aiSettings 要检查的 AI 设置。
     * @return 设置启用了该服务支持的提供商且包含所需凭据时返回 `true`，否则返回 `false`。
     */
    abstract fun isAiFeatureEnabled(aiSettings: AISettings): Boolean

    /**
     * 切换当前会话使用的模型。
     *
     * 模型切换会重置会话；调用方可等待返回任务完成后再依赖新模型对应的会话状态。
     *
     * @param modelName 要切换到的模型名称，必须存在于 [availableModels]。
     * @return 已开始切换时返回异步重置会话的任务；模型未改变或服务不可用时返回 `null`。
     * @throws IllegalArgumentException 当 [modelName] 不在 [availableModels] 中时抛出。
     */
    abstract fun switchModel(modelName: String): Job?

    /**
     * 从提供商刷新可用模型列表，并在当前模型失效时选择实现定义的回退模型。
     *
     * @return 获取成功后的模型快照；刷新失败或结果已过期时返回 `null`。
     */
    abstract suspend fun updateModel(): ModelSnapshot?

    /**
     * 异步重置当前会话，清空历史记录并重新应用系统提示词。
     *
     * @return 已开始重置时返回其异步任务；服务不可用或无需重置时返回 `null`。
     */
    abstract fun resetSession(): Job?

    /**
     * 获取服务创建时必须完成的初始化任务。
     *
     * 调用方可等待该任务后再发布服务实例；任务被取消表示该实例的初始化任务未完成。实现可将可选
     * 依赖（例如单个 MCP 服务器）的连接错误降级处理，此类错误不会单独使任务取消。没有额外初始化
     * 步骤的服务返回 `null`。
     *
     * @return 创建时的初始化任务；无需等待额外初始化时返回 `null`。
     */
    open fun initializationJob(): Job? = null

    /**
     * 发送文本消息并获取回复。
     *
     * 正常返回表示当前实现定义的代理回合已完成；模型返回以 `Error:` 开头的文本仍是正常的模型回复，
     * 而非失败信号。OpenAI 实现会将未完成且未提交历史的回合报告为 [AgentTurnFailedException]；其他
     * 提供商可保留其原有异常语义。取消会原样向上传播。
     *
     * @param text 要发送的文本消息；允许为空字符串，具体处理方式由实现决定。
     * @return AI 的回复文本；未生成可返回内容时返回空字符串。
     * @throws AgentTurnFailedException 当 OpenAI 实现无法完成本次回合且未提交其会话历史时抛出。
     * @throws IllegalStateException 当服务已关闭或当前会话不可用，且具体实现选择以异常报告时抛出。
     * @throws Exception 当非 OpenAI 提供商以其原有语义报告失败时抛出。
     * @throws kotlinx.coroutines.CancellationException 当调用协程或底层 I/O 被取消时原样抛出。
     */
    open suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * 正常返回表示当前实现定义的代理回合已完成；模型返回以 `Error:` 开头的文本仍是正常的模型回复，
     * 而非失败信号。OpenAI 实现会将未完成且未提交历史的回合报告为 [AgentTurnFailedException]；其他
     * 提供商可保留其原有异常语义。取消会原样向上传播。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空，元素的 MIME 类型必须与具体实现支持的格式匹配。
     * @return AI 的回复文本；未生成可返回内容时返回空字符串。
     * @throws AgentTurnFailedException 当 OpenAI 实现无法完成本次回合且未提交其会话历史时抛出。
     * @throws IllegalStateException 当服务已关闭或当前会话不可用，且具体实现选择以异常报告时抛出。
     * @throws Exception 当非 OpenAI 提供商以其原有语义报告失败时抛出。
     * @throws kotlinx.coroutines.CancellationException 当调用协程或底层 I/O 被取消时原样抛出。
     */
    abstract suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String

    /**
     * 异步关闭服务并释放资源。
     *
     * @return 有异步清理工作时返回对应任务；无需清理时返回 `null`。调用方可等待该任务作为服务重建屏障。
     */
    open fun close(): Job? = null

    /**
     * 生成注入到系统提示词中的技能说明。
     *
     * @param skills 要说明的技能摘要列表；列表可为空。
     * @return 包含技能标识和描述的英文提示词；当 [skills] 为空时返回空字符串。
     */
    protected fun getSkillPrompt(skills: List<SkillBrief>): String {
        return if (skills.isNotEmpty()) {
            "Before doing anything, first try calling the read_skill tool to confirm the correct process. Available Skills:\n" + skills.joinToString(
                "\n"
            ) { "- ID: ${it.id}, Description: ${it.description}" } + "\n\n"
        } else {
            ""
        }
    }
}

/**
 * 等待代理服务完成创建时的就绪步骤。
 *
 * 此函数等待 [AgentService.initializationJob]；初始化任务被取消时返回 `false`，没有初始化任务的服务
 * 立即返回 `true`。实现已降级处理的可选依赖错误不属于此失败条件。调用方可据此在不关闭旧服务的
 * 前提下决定是否发布替代实例。
 *
 * @receiver 要检查的代理服务。
 * @return 初始化成功且服务可发布时返回 `true`；初始化任务被取消时返回 `false`。
 */
internal suspend fun AgentService.awaitReady(): Boolean {
    val job = initializationJob() ?: return true
    job.join()
    return !job.isCancelled
}
