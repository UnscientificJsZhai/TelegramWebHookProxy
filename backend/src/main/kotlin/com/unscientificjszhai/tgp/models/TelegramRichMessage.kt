package com.unscientificjszhai.tgp.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray

/** 调用方可选择的 Telegram 富消息正文格式。 */
@Serializable
enum class TelegramRichFormat(val wireName: String) {
    @SerialName("markdown") MARKDOWN("markdown"),
    @SerialName("html") HTML("html"),
    @SerialName("blocks") BLOCKS("blocks");

    companion object {
        fun fromWireName(value: String): TelegramRichFormat? = entries.firstOrNull { it.wireName == value }
    }
}

/** 富消息的三种正文互斥；blocks 的具体字段由 Telegram 验证。 */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class InputRichMessage(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val markdown: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val html: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val blocks: JsonArray? = null,
) {
    init {
        require(listOfNotNull(markdown, html, blocks).size == 1) { "富消息必须且只能指定一种正文。" }
    }
}

/** Telegram sendRichMessage 请求；不携带普通消息的 text 或 parse_mode。 */
@Serializable
data class SendTelegramRichMessageRequest(
    @SerialName("chat_id") val chatId: String,
    @SerialName("rich_message") val richMessage: InputRichMessage,
    @SerialName("reply_parameters") val replyParameters: ReplyParameters? = null,
)

/**
 * 已确定的 AI 回复片段。原文范围使用 UTF-16 下标，仅用于恢复及无损降级，不作为富消息长度计量。
 * format 为 null 时直接发送普通消息，否则仅允许 MARKDOWN。
 */
@Serializable
data class TelegramReplyPart(
    val text: String,
    val sourceStart: Int,
    val sourceEnd: Int,
    val format: TelegramRichFormat? = null,
)
