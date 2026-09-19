package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.TelegramReplyPart
import com.unscientificjszhai.tgp.models.TelegramRichFormat
import com.unscientificjszhai.tgp.utils.TelegramTextChunks

/** 计划保存的是完整原文的连续分区；禁止恢复时遗漏、重复或切断 Unicode 代理对。 */
internal fun validateTelegramDeliveryPlan(source: String?, plan: List<TelegramReplyPart>?) {
    if (plan == null) return
    require(!source.isNullOrBlank() && plan.isNotEmpty()) { "投递计划必须对应非空回复。" }
    var cursor = 0
    for ((text, sourceStart, sourceEnd, format) in plan) {
        require(sourceStart == cursor && sourceEnd > cursor && sourceEnd <= source.length) {
            "投递计划必须连续覆盖原文。"
        }
        require(text.isNotEmpty() && (format == null || format == TelegramRichFormat.MARKDOWN))
        require(sourceEnd == source.length || !(source[sourceEnd - 1].isHighSurrogate() && source[sourceEnd].isLowSurrogate()))
        if (format == null) {
            require(text == source.substring(sourceStart, sourceEnd) && text.length <= 4096)
        }
        cursor = sourceEnd
    }
    require(cursor == source.length) { "投递计划必须覆盖全部原文。" }
}

internal fun PendingTelegramReply.currentPart(): TelegramReplyPart? = deliveryPlan?.get(nextPartIndex)

internal fun PendingTelegramReply.currentPartSource(): String = currentPart()?.let {
    text.substring(it.sourceStart, it.sourceEnd)
} ?: text

internal fun PendingTelegramReply.isRichDelivery(): Boolean =
    deliveryStage == TelegramReplyDeliveryStage.ORIGINAL && currentPart()?.format == TelegramRichFormat.MARKDOWN

internal fun PendingTelegramReply.isFirstOriginalPart(): Boolean =
    deliveryStage == TelegramReplyDeliveryStage.ORIGINAL && if (deliveryPlan == null) nextChunkStart == 0 else nextPartIndex == 0

internal fun PendingTelegramReply.originalDeliveryText(): String = when (deliveryStage) {
    TelegramReplyDeliveryStage.ORIGINAL -> currentPart()?.text ?: TelegramTextChunks.chunkAt(text, nextChunkStart)
    TelegramReplyDeliveryStage.PLAIN_FALLBACK -> TelegramTextChunks.chunkAt(currentPartSource(), plainFallbackStart)
    TelegramReplyDeliveryStage.FALLBACK -> error("固定失败提示不是原文投递。")
}
