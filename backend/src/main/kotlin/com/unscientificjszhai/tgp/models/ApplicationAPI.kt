package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 发送信息请求体。
 *
 * @property chatId 要将消息发送到的聊天ID。如果为`null`，则会发送给当前已选择的聊天。
 * @property text 消息正文。
 */
@Serializable
data class SendMessageRequest(val chatId: String?, val text: String)

/**
 * 设置默认聊天ID请求体。
 *
 * @property chatId 要设置的聊天ID。
 */
@Serializable
data class SetChatIdRequest(val chatId: String)