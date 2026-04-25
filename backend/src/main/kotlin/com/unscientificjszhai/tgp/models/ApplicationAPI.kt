package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 设置默认聊天ID请求体。
 *
 * @property chatId 要设置的聊天ID。
 */
@Serializable
data class SetChatIdRequest(
    val chatId: String,
)
