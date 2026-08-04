package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 设置默认聊天标识的请求体。
 *
 * @property chatId 要保存为默认值的 Telegram 聊天标识；空字符串会清除默认聊天。
 */
@Suppress("unused")
@Serializable
data class SetChatIdRequest(
    val chatId: String,
)
