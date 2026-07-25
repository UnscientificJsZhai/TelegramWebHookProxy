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
     * 发送文本消息并获取回复。
     *
     * @param text 要发送的文本消息；允许为空字符串，具体处理方式由实现决定。
     * @return AI 的回复文本；未生成可返回内容时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或当前会话不可用，且具体实现选择以异常报告时抛出。
     */
    open suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空，元素的 MIME 类型必须与具体实现支持的格式匹配。
     * @return AI 的回复文本；未生成可返回内容时返回空字符串。
     * @throws IllegalStateException 当服务已关闭或当前会话不可用，且具体实现选择以异常报告时抛出。
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
