package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import kotlinx.coroutines.Job

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
     */
    abstract fun updateModel()

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
     */
    open fun close() {}
}
