package com.unscientificjszhai.tgp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 已保存的 Telegram 聊天摘要。
 *
 * @property id 聊天标识的字符串表示。
 * @property title 聊天显示标题。
 * @property type Telegram 聊天类型。
 */
@Serializable
data class ChatInfo(
    val id: String,
    val title: String,
    val type: String,
)

/**
 * 发送消息时指定的回复参数。
 *
 * @property messageId 要回复的消息标识。
 * @property chatId 被回复消息所属的聊天标识；`null` 表示使用发送目标聊天。
 * @property allowSendingWithoutReply 是否允许原消息不存在时仍发送；`null` 表示由 Telegram 使用默认行为。
 * @property quote 要引用的文本；`null` 表示不指定引用文本。
 * @property quoteParseMode 解析 [quote] 的格式；`null` 表示不指定解析格式。
 * @property quoteEntities [quote] 中的格式实体；`null` 表示不显式指定实体。
 * @property quotePosition [quote] 在原消息中的起始位置，按 UTF-16 代码单元计数；`null` 表示不指定位置。
 */
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

/**
 * Telegram 消息文本中一段格式化内容的范围和附加数据。
 *
 * @property type 实体类型，例如加粗、链接或代码。
 * @property offset 实体在文本中的起始偏移量，按 UTF-16 代码单元计数，且不得为负数。
 * @property length 实体长度，按 UTF-16 代码单元计数，且不得为负数。
 * @property url 链接实体对应的 URL；非链接实体为 `null`。
 * @property user 文本提及实体对应的用户；非文本提及实体为 `null`。
 * @property language 代码块指定的编程语言；未指定时为 `null`。
 * @property customEmojiId 自定义表情实体的唯一标识；非自定义表情实体为 `null`。
 */
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

/**
 * Telegram 用户或机器人信息。
 *
 * @property id 用户唯一标识。
 * @property isBot 是否为机器人账户。
 * @property firstName 用户名字；不得为空。
 * @property lastName 用户姓氏；未提供时为 `null`。
 * @property username 用户名；未设置时为 `null`。
 * @property languageCode 用户客户端语言代码；未提供时为 `null`。
 * @property isPremium 是否为 Telegram Premium 用户；未提供时为 `null`。
 * @property addedToAttachmentMenu 是否已添加到附件菜单；未提供时为 `null`。
 * @property canJoinGroups 机器人是否允许被加入群组；未提供时为 `null`。
 * @property canReadAllGroupMessages 机器人是否具有读取全部群消息的权限；未提供时为 `null`。
 * @property supportsInlineQueries 机器人是否支持内联查询；未提供时为 `null`。
 * @property canConnectToBusiness 机器人是否可连接到商业账户；未提供时为 `null`。
 * @property hasMainWebApp 机器人是否已配置主 Web 应用；未提供时为 `null`。
 */
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

/**
 * 调用 Telegram `sendMessage` 接口的请求体。
 *
 * @property chatId 接收消息的聊天标识；不得为空。
 * @property text 要发送的文本；不得为空。
 * @property replyParameters 回复目标和引用设置；`null` 表示不以回复方式发送。
 */
@Serializable
data class SendTelegramMessageRequest(
    @SerialName("chat_id") val chatId: String,
    val text: String,
    @SerialName("reply_parameters") val replyParameters: ReplyParameters? = null,
)

/**
 * Telegram `getUpdates` 接口的响应体。
 *
 * @property ok 请求是否成功；为 `false` 时可查看 [errorCode] 和 [description]。
 * @property result 按 Telegram 返回顺序排列的更新列表；请求失败或没有更新时为空列表。
 * @property errorCode 请求失败时的 Telegram 错误码；成功时为 `null`。
 * @property description 请求失败时的错误说明；成功或未提供说明时为 `null`。
 */
@Serializable
data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<Update> = emptyList(),
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)

/**
 * Telegram 推送的一项更新。
 *
 * @property updateId 更新的唯一标识，用于推进轮询偏移量。
 * @property message 普通消息更新；非普通消息时为 `null`。
 * @property channelPost 频道消息更新；非频道消息时为 `null`。
 * @property myChatMember 机器人自身聊天成员状态更新；非该类型更新时为 `null`。
 */
@Serializable
data class Update(
    @SerialName("update_id") val updateId: Long,
    val message: Message? = null,
    @SerialName("channel_post") val channelPost: Message? = null,
    @SerialName("my_chat_member") val myChatMember: ChatMemberUpdated? = null,
)

/**
 * 机器人在聊天中的成员状态更新。
 *
 * @property chat 成员状态发生变化的聊天。
 */
@Serializable
data class ChatMemberUpdated(
    val chat: Chat,
)

/**
 * Telegram 语音消息的文件信息。
 *
 * @property fileId 用于下载或复用文件的标识。
 * @property fileUniqueId 文件的稳定唯一标识，不能用于下载。
 * @property duration 语音时长，单位为秒，且不得为负数。
 * @property mimeType 文件 MIME 类型；Telegram 未提供时为 `null`。
 * @property fileSize 文件大小，单位为字节；Telegram 未提供时为 `null`。
 */
@Serializable
data class Voice(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    val duration: Int,
    @SerialName("mime_type") val mimeType: String? = null,
    @SerialName("file_size") val fileSize: Long? = null,
)

/**
 * Telegram 文件查询接口的响应体。
 *
 * @property ok 请求是否成功；为 `false` 时可查看 [errorCode] 和 [description]。
 * @property result 查询成功时的文件信息；请求失败或未返回文件时为 `null`。
 * @property errorCode 请求失败时的 Telegram 错误码；成功时为 `null`。
 * @property description 请求失败时的错误说明；成功或未提供说明时为 `null`。
 */
@Serializable
data class FileResponse(
    val ok: Boolean,
    val result: TelegramFile? = null,
    @SerialName("error_code") val errorCode: Int? = null,
    val description: String? = null,
)

/**
 * Telegram 文件的元数据。
 *
 * @property fileId 用于下载或复用文件的标识。
 * @property fileUniqueId 文件的稳定唯一标识，不能用于下载。
 * @property fileSize 文件大小，单位为字节；Telegram 未提供时为 `null`。
 * @property filePath 用于下载文件的相对路径；Telegram 未提供时为 `null`。
 */
@Serializable
data class TelegramFile(
    @SerialName("file_id") val fileId: String,
    @SerialName("file_unique_id") val fileUniqueId: String,
    @SerialName("file_size") val fileSize: Long? = null,
    @SerialName("file_path") val filePath: String? = null,
)

/**
 * Telegram 聊天中的一条消息。
 *
 * @property messageId 聊天内唯一的消息标识。
 * @property chat 消息所属的聊天。
 * @property text 文本消息内容；非文本消息或未提供时为 `null`。
 * @property voice 语音消息文件信息；非语音消息时为 `null`。
 * @property caption 媒体消息的说明文字；未提供时为 `null`。
 */
@Serializable
data class Message(
    @SerialName("message_id") val messageId: Long,
    val chat: Chat,
    val text: String? = null,
    val voice: Voice? = null,
    val caption: String? = null,
)

/**
 * 调用 Telegram `sendChatAction` 接口的请求体。
 *
 * @property chatId 要显示动作状态的聊天标识；不得为空。
 * @property action Telegram 支持的聊天动作名称；不得为空。
 */
@Serializable
data class ChatActionRequest(
    @SerialName("chat_id") val chatId: String,
    val action: String,
)

/**
 * Telegram 机器人命令定义。
 *
 * @property command 命令名称，不含命令前缀 `/`；必须由 `1..32` 个小写英文字母、数字或下划线组成。
 * @property description 命令向用户展示的说明；长度必须在 `1..256` 个字符之间。
 */
@Serializable
data class BotCommand(
    val command: String,
    val description: String,
)

/**
 * 设置 Telegram 机器人命令列表的请求体。
 *
 * @property commands 要设置的命令列表；最多包含 `100` 个元素，空列表会清空机器人命令。
 */
@Serializable
data class SetMyCommandsRequest(
    val commands: List<BotCommand>,
)

/**
 * Telegram 聊天信息。
 *
 * @property id 聊天唯一标识。
 * @property type 聊天类型，例如私聊、群组或频道。
 * @property title 聊天标题；私聊或 Telegram 未提供标题时为 `null`。
 * @property username 聊天公开用户名；未设置时为 `null`。
 * @property firstName 私聊对象的名字；非私聊或未提供时为 `null`。
 * @property lastName 私聊对象的姓氏；非私聊或未提供时为 `null`。
 */
@Serializable
data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
)
