package com.unscientificjszhai.tgp.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Telegram 纯文本分块的 UTF-16 与 Unicode 边界测试。 */
class TelegramTextChunksTest {
    /** 短文本与恰好 4096 个 UTF-16 code unit 的文本都保持单段。 */
    @Test
    fun `short and limit length text stay in one chunk`() {
        assertEquals(listOf(""), TelegramTextChunks.split(""))
        assertEquals(listOf("x".repeat(4096)), TelegramTextChunks.split("x".repeat(4096)))
    }

    /** 4097 个 BMP 字符会被稳定拆成 4096 与 1，且拼接严格还原输入。 */
    @Test
    fun `text above limit splits and round trips exactly`() {
        val text = "x".repeat(4097)
        val chunks = TelegramTextChunks.split(text)

        assertEquals(listOf(4096, 1), chunks.map(String::length))
        assertEquals(text, chunks.joinToString(separator = ""))
        assertEquals(listOf(0, 4096), TelegramTextChunks.starts(text))
    }

    /** 代理对和组合字符不被拆开；每段均满足 Telegram 的 UTF-16 上限。 */
    @Test
    fun `astral and combining text keeps Unicode boundaries`() {
        val text = "a".repeat(4095) + "😀" + "e\u0301" + "z".repeat(4095)
        val chunks = TelegramTextChunks.split(text)

        assertEquals(text, chunks.joinToString(separator = ""))
        assertTrue(chunks.all { it.length <= MAX_TELEGRAM_MESSAGE_TEXT_LENGTH })
        assertTrue(chunks.none { it.isNotEmpty() && Character.isHighSurrogate(it.last()) })
        assertTrue(chunks.none { it.isNotEmpty() && Character.isLowSurrogate(it.first()) })
        assertTrue(chunks.none { it.isNotEmpty() && Character.getType(it.first()) == Character.NON_SPACING_MARK.toInt() })
    }
}
