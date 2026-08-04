package com.unscientificjszhai.tgp.utils

import java.text.BreakIterator
import java.util.Locale

/** Telegram `sendMessage` 单段文本允许的最大 UTF-16 code unit 数。 */
internal const val MAX_TELEGRAM_MESSAGE_TEXT_LENGTH = 4096

/**
 * 将纯文本划分为可由 Telegram `sendMessage` 接收的连续片段。
 *
 * 分块不会修改输入，也不会在 Unicode 代理对中间截断。优先采用 JDK 的扩展字符边界，遇到超过上限的单一
 * 字素簇时才退回到 code point 边界；因此所有片段按顺序拼接后始终与输入完全相等。
 */
internal object TelegramTextChunks {
    /**
     * 返回 [text] 的稳定分块快照。
     *
     * 空字符串和长度不超过 Telegram 上限的字符串均保留为单个原始片段，避免改变既有调用方语义。
     */
    fun split(text: String): List<String> {
        if (text.length <= MAX_TELEGRAM_MESSAGE_TEXT_LENGTH) {
            return listOf(text)
        }
        val starts = starts(text)
        return starts.mapIndexed { index, start ->
            text.substring(start, starts.getOrElse(index + 1) { text.length })
        }
    }

    /** 返回每个待发送片段在原字符串中的 UTF-16 起点。 */
    fun starts(text: String): List<Int> {
        if (text.isEmpty()) {
            return listOf(0)
        }
        val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(text) }
        val starts = arrayListOf(0)
        var start = 0
        while (start < text.length) {
            val limit = minOf(text.length, start + MAX_TELEGRAM_MESSAGE_TEXT_LENGTH)
            if (limit == text.length) {
                break
            }
            var end = boundaries.preceding(limit + 1)
            if (end <= start) {
                end = codePointEndAtOrBefore(text, start, limit)
            }
            check(end > start) { "Telegram text chunker failed to advance." }
            start = end
            starts += start
        }
        return starts
    }

    /** 返回以 [start] 开始的当前片段；[start] 必须是 [starts] 中的稳定边界。 */
    fun chunkAt(text: String, start: Int): String {
        require(isChunkStart(text, start)) { "nextChunkStart must be a Telegram text chunk boundary." }
        val end = starts(text).firstOrNull { it > start } ?: text.length
        return text.substring(start, end)
    }

    /** 返回当前片段成功后应保存的下一个 UTF-16 起点，末段成功时为 `text.length`。 */
    fun nextStartAfter(text: String, start: Int): Int {
        require(isChunkStart(text, start)) { "nextChunkStart must be a Telegram text chunk boundary." }
        return starts(text).firstOrNull { it > start } ?: text.length
    }

    /** 判断 [start] 是否是待发送片段的稳定 UTF-16 起点。 */
    fun isChunkStart(text: String, start: Int): Boolean = start in starts(text)

    private fun codePointEndAtOrBefore(text: String, start: Int, limit: Int): Int {
        var end = start
        while (end < limit) {
            val width = Character.charCount(text.codePointAt(end))
            if (end + width > limit) {
                break
            }
            end += width
        }
        return end
    }
}
