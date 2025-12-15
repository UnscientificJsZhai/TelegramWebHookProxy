package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable


@Serializable
data class ChatInfo(
    val id: String, val title: String, val type: String
)

@Serializable
data class SendTelegramMessageRequest(
    val chat_id: String, val text: String
)

@Serializable
data class GetUpdatesResponse(
    val ok: Boolean, val result: List<Update>
)

@Serializable
data class Update(
    val update_id: Long,
    val message: Message? = null,
    val channel_post: Message? = null,
    val my_chat_member: ChatMemberUpdated? = null
)

@Serializable
data class ChatMemberUpdated(
    val chat: Chat
)

@Serializable
data class Message(
    val chat: Chat
)

@Serializable
data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    val first_name: String? = null,
    val last_name: String? = null
)
