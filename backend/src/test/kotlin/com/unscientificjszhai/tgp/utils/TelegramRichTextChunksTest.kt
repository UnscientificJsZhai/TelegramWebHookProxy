package com.unscientificjszhai.tgp.utils

import com.unscientificjszhai.tgp.models.TelegramReplyPart
import com.unscientificjszhai.tgp.models.TelegramRichFormat
import com.unscientificjszhai.tgp.repository.validateTelegramDeliveryPlan
import kotlin.test.*

class TelegramRichTextChunksTest {
    private fun plan(source: String): List<TelegramReplyPart> = TelegramRichTextChunks.plan(source).also { parts ->
        validateTelegramDeliveryPlan(source, parts)
        assertEquals(source, parts.joinToString("") { source.substring(it.sourceStart, it.sourceEnd) })
        parts.filter { it.format != null }.forEach { assertTrue(it.text.codePointCount(0, it.text.length) <= 32768) }
    }

    @Test
    fun `short markdown and supplementary unicode retain rich formatting`() {
        val source = "# 标题\n\n**结论**\n\n- 项目\n  - 子项目\n\n> 引用\n"
        assertTrue(plan(source).all { it.format == TelegramRichFormat.MARKDOWN })
        val emoji = "😀".repeat(20000)
        assertEquals(emoji, plan(emoji).single().text)
        assertEquals(2, plan("😀".repeat(40000)).size)
    }

    @Test
    fun `long code repeats language and balanced fences without losing body`() {
        val body = "println(\"你好😀\")\n".repeat(3000)
        val source = "```kotlin\n$body```\n"
        val parts = plan(source)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.format != null && it.text.startsWith("```kotlin\n") && it.text.trimEnd().endsWith("```") })
        val restored = parts.joinToString("") { it.text.removePrefix("```kotlin\n").removeSuffix("```\n") }
        assertEquals(body, restored)
    }

    @Test
    fun `long table repeats its header and keeps rows ordered`() {
        val header = "|编号|结果|\n|---|---|\n"
        val rows = (1..650).map { "|$it|正常|\n" }
        val parts = plan(header + rows.joinToString(""))
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.format != null && it.text.startsWith(header) })
        assertEquals(rows.joinToString(""), parts.joinToString("") { it.text.removePrefix(header) })
    }

    @Test
    fun `unsplittable table cell degrades locally and later prose stays rich`() {
        val source = "开头\n\n|列|\n|---|\n|${"x".repeat(40000)}|\n\n结尾"
        val parts = plan(source)
        assertNotNull(parts.first().format)
        assertTrue(parts.any { it.format == null })
        assertNotNull(parts.last().format)
        assertTrue(parts.last().text.contains("结尾"))
    }

    @Test
    fun `footnotes and reference links are available in each referring fragment`() {
        val source = "第一段[^note] [文档][doc]\n\n" + "x".repeat(32700) + "\n\n第二段[^note] [文档][doc]\n\n[^note]: 注释内容\n\n[doc]: https://example.test\n"
        val parts = plan(source)
        assertTrue(parts.size > 1)
        for (part in parts.filter { it.format != null && (it.text.contains("第一段") || it.text.contains("第二段")) }) {
            assertTrue(part.text.contains("[^note]: 注释内容"), part.text.take(100))
            assertTrue(part.text.contains("[doc]: https://example.test"), part.text.take(100))
        }
    }

    @Test
    fun `oversized details and formula remain atomic and only their source becomes plain`() {
        for (block in listOf("<details><summary>展开</summary>\n\n${"x".repeat(40000)}\n\n</details>", "$$\n\n${"x".repeat(40000)}\n\n$$")) {
            val parts = plan("前言\n\n$block\n\n后记")
            assertNotNull(parts.first().format)
            assertNotNull(parts.last().format)
            assertTrue(parts.any { it.format == null })
            assertTrue(parts.filter { it.format != null }.none { it.text.contains("<details>") || it.text.contains("$$") })
        }
    }

    @Test
    fun `many list items and media split on complete structures`() {
        for (source in listOf((1..300).joinToString("\n") { "- 第 $it 项\n  - 子项目" }, (1..51).joinToString("\n\n") { "![](https://example.test/$it.jpg)" })) {
            val parts = plan(source)
            assertTrue(parts.size > 1)
            assertTrue(parts.all { it.format != null })
        }
    }

    @Test
    fun `code tags are literal and duplicate reference definitions keep first priority`() {
        val code = "```html\n" + "<div>\n".repeat(1000) + "```"
        assertEquals(code, plan(code).single().text)
        assertNotNull(plan(code).single().format)
        val references = "[foo]\n\n[foo]: https://first.example\n\n[foo]: https://last.example\n"
        val payload = plan(references).single().text
        assertTrue(payload.indexOf("https://first.example") < payload.indexOf("https://last.example"))
    }

    @Test
    fun `quote fragments retain quote prefix and excessive columns degrade only table`() {
        val quote = (1..300).joinToString("\n>\n") { "> 第 $it 段" }
        val quoteParts = plan(quote)
        assertTrue(quoteParts.all { it.format != null && it.text.startsWith(">") })
        val header = "|" + (1..21).joinToString("|") { "列$it" } + "|\n"
        val divider = "|" + (1..21).joinToString("|") { "---" } + "|\n"
        val row = "|" + (1..21).joinToString("|") { "值" } + "|\n"
        val parts = plan("前言\n\n$header$divider$row\n后记")
        assertNotNull(parts.first().format)
        assertNull(parts[1].format)
        assertNotNull(parts.last().format)
    }

    @Test
    fun `long escaped text and entities never split into different markdown semantics`() {
        for (source in listOf("a".repeat(32767) + "\\*literal*", "a".repeat(32766) + "&amp;")) {
            val parts = plan(source)
            assertTrue(parts.all { it.format == null })
            assertEquals(source, parts.joinToString("") { it.text })
        }
        for (suffix in listOf("---", "# heading", "- item", "    code")) {
            val parts = plan("a".repeat(32768) + suffix)
            assertNotNull(parts.first().format)
            assertNull(parts.last().format)
            assertEquals(suffix, parts.last().text)
        }
    }

    @Test
    fun `indented fences retain exact code literal and raw language info`() {
        val body = "  print('first')\n" + "  print('next')\n".repeat(3000)
        val source = "  ```python\n$body  ```\n"
        val parts = plan(source)
        assertTrue(parts.size > 1 && parts.all { it.format != null && it.text.startsWith("  ```python\n") })
        val parser = org.commonmark.parser.Parser.builder().build()
        val originalCode = (parser.parse(source).firstChild as org.commonmark.node.FencedCodeBlock).literal
        val sentCode = parts.joinToString("") { (parser.parse(it.text).firstChild as org.commonmark.node.FencedCodeBlock).literal }
        assertEquals(originalCode, sentCode)
    }

    @Test
    fun `Telegram inline extensions and all formula forms stay atomic`() {
        val body = "x".repeat(40000)
        for (block in listOf("||$body||", "==$body==", "<tg-math-block>\n\n$body\n\n</tg-math-block>", "```math\n$body\n```")) {
            val parts = plan("前言\n\n$block\n\n后记")
            assertNotNull(parts.first().format)
            assertNotNull(parts.last().format)
            assertEquals(block + "\n\n", parts.filter { it.format == null }.joinToString("") { it.text })
        }
        val nested = "> <details>\n>\n> $body\n>\n> </details>"
        val parts = plan("前言\n\n$nested\n\n后记")
        assertNotNull(parts.first().format)
        assertNotNull(parts.last().format)
        assertTrue(parts.filter { it.format != null }.none { it.text.contains("<details>") || it.text.contains("</details>") })
    }

    @Test
    fun `multiline reference labels get their definitions before long code`() {
        for ((reference, label) in listOf("[a\nb]" to "a b", "[**API**]" to "**API**", "[`API`]" to "`API`")) {
            val definition = "[$label]: https://target.example"
            val source = "$reference\n\n```text\n${"x".repeat(33000)}\n```\n\n$definition\n"
            val parts = plan(source)
            assertTrue(parts.first().text.contains(definition))
            assertNotNull(parts.first().format)
        }
    }

    @Test
    fun `container closing tag inside fenced sample cannot end the container`() {
        for (example in listOf("```html\n</details>\n```", "使用 `</details>` 闭合容器")) {
            val block = "<details><summary>HTML 示例</summary>\n\n$example\n\n${"x".repeat(40000)}\n\n</details>"
            val parts = plan("前言\n\n$block\n\n后记")
            assertNotNull(parts.first().format)
            assertNotNull(parts.last().format)
            assertEquals(block + "\n\n", parts.filter { it.format == null }.joinToString("") { it.text })
        }
    }

    @Test
    fun `references written only as code do not append definitions to that fragment`() {
        val source = "`[foo]`\n\n```text\n${"x".repeat(33000)}\n```\n\n[foo]: https://target.example\n"
        assertFalse(plan(source).first().text.contains("https://target.example"))
    }
}
