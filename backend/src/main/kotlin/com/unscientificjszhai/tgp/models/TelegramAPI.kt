package com.unscientificjszhai.tgp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatInfo(
    val id: String,
    val title: String,
    val type: String,
)

@Serializable
data class ReplyParameters(
    @SerialName("message_id") val messageId: Long,
    @SerialName("chat_id") val chatId: String? = null,
    @SerialName("allow_sending_without_reply") val allowSendingWithoutReply: Boolean? = null,
    val quote: String? = null,
    @SerialName("quote_parse_mode") val quoteParseMode: String? = null,
    @SerialName("quote_entities") val quoteEntities: List<MessageEntity>? = null,
    @SerialName("quote_position") val quotePosition: Int? = null,
)

@Serializable
data class MessageEntity(
    val type: String,
    val offset: Int,
    val length: Int,
    val url: String? = null,
    val user: User? = null,
    val language: String? = null,
    @SerialName("custom_emoji_id") val customEmojiId: String? = null,
)

@Serializable
data class User(
    val id: Long,
    @SerialName("is_bot") val isBot: Boolean,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String? = null,
    val username: String? = null,
    @SerialName("language_code") val languageCode: String? = null,
    @SerialName("is_premium") val isPremium: Boolean? = null,
    @SerialName("added_to_attachment_menu") val addedToAttachmentMenu: Boolean? = null,
    @SerialName("can_join_groups") val canJoinGroups: Boolean? = null,
    @SerialName("can_read_all_group_messages") val canReadAllGroupMessages: Boolean? = null,
    @SerialName("supports_inline_queries") val supportsInlineQueries: Boolean? = null,
    @SerialName("can_connect_to_business") val canConnectToBusiness: Boolean? = null,
    @SerialName("has_main_web_app") val hasMainWebApp: Boolean? = null,
)

@Serializable
data class SendTelegramMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("reply_parameters") val replyParameters: ReplyParameters? = null,
)

@Serializable
data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<Update> = emptyList(),
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)

@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("channel_post") val channelPost: Message? = null,
    @SerialName("my_chat_member") val myChatMember: ChatMemberUpdated? = null,
)

@Serializable
data class ChatMemberUpdated(
    val chat: Chat,
)

@Serializable
data class Voice(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    val duration: Int,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

@Serializable
data class FileResponse(
    val ok: Boolean,
    val result: TelegramFile? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)

@Serializable
data class TelegramFile(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("file_path") val filePath: String? = null,
)

@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    val text: String? = null,
    val voice: Voice? = null,
    val caption: String? = null,
)

@Serializable
data class ChatActionRequest(
    @SerialName("chat_id") val chatId: String,
    val action: String,
)

@Serializable
data class BotCommand(
    val command: String,
    val description: String,
)

@Serializable
data class SetMyCommandsRequest(
    val commands: List<BotCommand>,
)

@Serializable
data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)
