package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.SkillBrief
import kotlinx.coroutines.Job

/**
 * 一次模型列表刷新得到的一致性快照。
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
 */
internal fun ensureToolCallRoundIsAllowed(toolCallRounds: Int) {
    if (toolCallRounds >= MAX_TOOL_CALL_ROUNDS) {
        throw ToolCallLimitExceededException()
    }
}

/**
 * AI 代理服务基类，抽象了 AI 对话的核心逻辑。
 */
abstract class AgentService {
    /**
     * 当前会话使用的模型。
     */
    abstract val currentModel: String

    /**
     * 可选的模型列表。
     */
    abstract val availableModels: List<String>

    /**
     * 判断 AI 功能是否启用。
     */
    abstract fun isAiFeatureEnabled(aiSettings: AISettings): Boolean

    /**
     * 切换当前会话使用的模型。
     *
     * @param modelName 模型名称。
     */
    abstract fun switchModel(modelName: String): Job?

    /**
     * 更新可用模型列表。
     *
     * @return 获取成功后的模型快照；刷新失败或结果已过期时返回 null。
     */
    abstract suspend fun updateModel(): ModelSnapshot?

    /**
     * 重置当前会话，清空历史记录并重新应用系统提示词。
     */
    abstract fun resetSession(): Job?

    /**
     * 发送文本消息并获取回复。
     *
     * @param text 消息内容。
     * @return AI 的回复文本。
     */
    open suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * @param text 配文或指令内容（可选）。
     * @param mediaData 包含媒体数据的列表。
     * @return AI 的回复文本。
     */
    abstract suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String

    /**
     * 关闭服务，释放资源。
     *
     * @return 异步清理任务；调用方可等待该任务作为服务重建屏障。
     */
    open fun close(): Job? = null

    /**
     * 获取注入到系统提示词中的 Skill 的描述。
     *
     * @param skills Skill列表。
     * @return 拼接后的提示词。如果 [skills] 为空，则返回空白字符串。
     */
    protected fun getSkillPrompt(skills: List<SkillBrief>):String {
        return if (skills.isNotEmpty()) {
            "Before doing anything, first try calling the read_skill tool to confirm the correct process. Available Skills:\n" + skills.joinToString(
                "\n"
            ) { "- ID: ${it.id}, Description: ${it.description}" } + "\n\n"
        } else {
            ""
        }
    }
}
