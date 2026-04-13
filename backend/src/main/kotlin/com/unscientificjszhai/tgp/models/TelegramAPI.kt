package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable


@Serializable
data class ChatInfo(
    val id: String, val title: String, val type: String
)

@Serializable
data class ReplyParameters(
    val message_id: Long,
    val chat_id: String? = null,
    val allow_sending_without_reply: Boolean? = null,
    val quote: String? = null,
    val quote_parse_mode: String? = null,
    val quote_entities: List<MessageEntity>? = null,
    val quote_position: Int? = null
)

@Serializable
data class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val user: User? = null,
    val language: String? = null,
    val custom_emoji_id: String? = null
)

@Serializable
data class User(
    val id: Long,
    val is_bot: Boolean,
    val first_name: String,
    val last_name: String? = null,
    val username: String? = null,
    val language_code: String? = null,
    val is_premium: Boolean? = null,
    val added_to_attachment_menu: Boolean? = null,
    val can_join_groups: Boolean? = null,
    val can_read_all_group_messages: Boolean? = null,
    val supports_inline_queries: Boolean? = null,
    val can_connect_to_business: Boolean? = null,
    val has_main_web_app: Boolean? = null
)

@Serializable
data class SendTelegramMessageRequest(
    val chat_id: String,
    val text: String,
    val reply_parameters: ReplyParameters? = null
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
    val message_id: Long,
    val chat: Chat,
    val text: String? = null
)

@Serializable
data class ChatActionRequest(
    val chat_id: String,
    val action: String
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
