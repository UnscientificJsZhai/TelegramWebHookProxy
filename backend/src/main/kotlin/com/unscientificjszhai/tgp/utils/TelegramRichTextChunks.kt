package com.unscientificjszhai.tgp.utils

import com.unscientificjszhai.tgp.models.TelegramReplyPart
import com.unscientificjszhai.tgp.models.TelegramRichFormat
import org.commonmark.ext.footnotes.FootnoteDefinition
import org.commonmark.ext.footnotes.FootnotesExtension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.*
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import java.util.ArrayDeque
import java.util.Locale

/** Telegram 富消息的内容预算；HTTP 编码字节数与这些限制不同。 */
internal object TelegramRichLimits {
    const val TEXT_CHARACTERS = 32768
    const val BLOCKS = 500
    const val NESTING = 16
    const val MEDIA = 50
    const val TABLE_COLUMNS = 20
}

/**
 * 按结构规划 AI 回复，保留原文范围供持久化及普通消息降级使用。
 * 仅使用 AST 判断边界，发送内容取自原文；源码字符数作为解析后文字长度的保守上界。
 */
internal object TelegramRichTextChunks {
    private val parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), FootnotesExtension.create(), TaskListItemsExtension.create(), StrikethroughExtension.create()))
        .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
        .build()

    fun plan(source: String): List<TelegramReplyPart> {
        if (source.isBlank()) return emptyList()
        return try {
            Planner(source).build()
        } catch (_: Exception) {
            plainParts(source, 0, source.length)
        } catch (_: StackOverflowError) {
            // 解析器遇到异常深层结构时仍保留回复，不能让格式处理导致 AI 结果丢失。
            plainParts(source, 0, source.length)
        }
    }

    private fun plainParts(source: String, start: Int, end: Int): List<TelegramReplyPart> {
        var cursor = start
        return TelegramTextChunks.split(source.substring(start, end)).map { text ->
            TelegramReplyPart(text, cursor, cursor + text.length).also { cursor += text.length }
        }
    }

    private class Planner(private val source: String) {
        private val document = parser.parse(source)
        private val definitions = linkedMapOf<String, String>()
        private val result = mutableListOf<TelegramReplyPart>()
        private val protectedRanges = protectedRanges(source, document)

        fun build(): List<TelegramReplyPart> {
            if (fits(source)) return listOf(TelegramReplyPart(source, 0, source.length, TelegramRichFormat.MARKDOWN))
            walk(document) { node, _ ->
                val label = when (node) {
                    is LinkReferenceDefinition -> node.label
                    is FootnoteDefinition -> "^${node.label}"
                    else -> null
                }
                if (label != null && node.sourceSpans.isNotEmpty()) {
                    definitions.putIfAbsent(normalizeLabel(label), source.substring(startOf(node), endOf(node)).trimEnd())
                }
            }
            val children = children(document)
            if (children.isEmpty()) return plainParts(source, 0, source.length)
            val boundaries = sortedSetOf(0, source.length)
            children.drop(1).forEach { node ->
                val start = startOf(node)
                if (protectedRanges.none { start > it.first && start < it.second }) boundaries += start
            }
            val positions = boundaries.toList()
            for (index in 0 until positions.lastIndex) {
                val start = positions[index]
                val end = positions[index + 1]
                val node = children.firstOrNull { startOf(it) in start until end }
                split(node, start, end)
            }
            check(result.first().sourceStart == 0 && result.last().sourceEnd == source.length)
            check(result.zipWithNext().all { (left, right) -> left.sourceEnd == right.sourceStart })
            return result
        }

        private fun split(node: Node?, start: Int, end: Int) {
            val raw = source.substring(start, end)
            if (emitRich(raw, start, end)) return
            if (node !is ListBlock && node !is BlockQuote && protectedRanges.any { start < it.second && end > it.first }) {
                emitPlain(start, end)
                return
            }
            when (node) {
                is FencedCodeBlock -> if (node.info?.trim() == "math") emitPlain(start, end) else splitCode(node, start, end)
                is TableBlock -> splitTable(node, start, end)
                is ListBlock, is BlockQuote -> splitContainer(node, start, end)
                is Paragraph -> splitParagraph(node, start, end)
                else -> emitPlain(start, end)
            }
        }

        private fun splitCode(node: FencedCodeBlock, start: Int, end: Int) {
            val opening = startOf(node)
            val bodyStart = lineEnd(opening)
            val bodyEnd = if (node.closingFenceLength != null) lineStart(node.sourceSpans.last().inputIndex) else end
            if (bodyStart >= bodyEnd) {
                emitPlain(start, end)
                return
            }
            val fence = node.fenceCharacter.repeat(node.openingFenceLength ?: 3)
            // 保留原始 info 与缩进；重新编码 node.info 或去掉缩进会改变代码的含义。
            val prefix = source.substring(opening, bodyStart)
            val indent = prefix.takeWhile { it == ' ' || it == '\t' }
            val suffix = "$indent$fence\n"
            val allowance = TelegramRichLimits.TEXT_CHARACTERS - characterCount(prefix + "\n" + suffix)
            if (allowance <= 0) {
                emitPlain(start, end)
                return
            }
            var cursor = bodyStart
            while (cursor < bodyEnd) {
                val limit = codePointEnd(source, cursor, bodyEnd, allowance)
                val newline = source.lastIndexOf('\n', limit - 1).takeIf { it >= cursor }
                val next = if (limit < bodyEnd && newline != null) newline + 1 else limit
                val rangeStart = if (cursor == bodyStart) start else cursor
                val rangeEnd = if (next == bodyEnd) end else next
                val body = source.substring(cursor, next)
                val payload = prefix + body + (if (body.endsWith('\n')) "" else "\n") + suffix
                if (!emitRich(payload, rangeStart, rangeEnd, merge = false)) emitPlain(rangeStart, rangeEnd)
                cursor = next
            }
        }

        private fun splitTable(node: TableBlock, start: Int, end: Int) {
            val rows = mutableListOf<TableRow>()
            walk(node) { child, _ -> if (child is TableRow) rows += child }
            if (rows.size < 2 || rows.any { children(it).count { cell -> cell is TableCell } > TelegramRichLimits.TABLE_COLUMNS }) {
                emitPlain(start, end)
                return
            }
            val firstBodyStart = startOf(rows[1])
            val header = source.substring(start, firstBodyStart)
            var groupStart = start
            var body = ""
            for (index in 1 until rows.size) {
                val rowStart = startOf(rows[index])
                val rowEnd = if (index == rows.lastIndex) end else startOf(rows[index + 1])
                val row = source.substring(rowStart, rowEnd)
                if (fits(withReferences(header + body + row))) {
                    body += row
                    continue
                }
                if (body.isNotEmpty()) {
                    check(emitRich(header + body, groupStart, rowStart, merge = false))
                    groupStart = rowStart
                    body = ""
                }
                if (fits(withReferences(header + row))) {
                    body = row
                } else {
                    emitPlain(groupStart, rowEnd)
                    groupStart = rowEnd
                }
            }
            if (body.isNotEmpty()) check(emitRich(header + body, groupStart, end, merge = false))
        }

        private fun splitContainer(node: Node, start: Int, end: Int) {
            val items = children(node)
            if (items.size < 2) {
                emitPlain(start, end)
                return
            }
            val boundaries = sortedSetOf(start, end)
            items.drop(1).forEach { item ->
                val boundary = startOf(item)
                if (protectedRanges.none { boundary > it.first && boundary < it.second }) boundaries += boundary
            }
            boundaries.toList().zipWithNext().forEach { (itemStart, itemEnd) ->
                val raw = source.substring(itemStart, itemEnd)
                // 引用的惰性续行在新消息中也必须保留引用前缀。
                val payload = if (node is BlockQuote && !raw.trimStart().startsWith(">")) "> $raw" else raw
                if (!emitRich(payload, itemStart, itemEnd)) emitPlain(itemStart, itemEnd)
            }
        }

        private fun splitParagraph(node: Paragraph, start: Int, end: Int) {
            val raw = source.substring(start, end)
            // 普通文字可安全按 Unicode 边界续接；跨段内联格式和 Telegram 扩展不做猜测性改写。
            if (children(node).any { it !is Text && it !is SoftLineBreak && it !is HardLineBreak } ||
                raw.any { it == '$' || it == '<' || it == '\\' || it == '&' } || raw.contains("||") || raw.contains("==")
            ) {
                emitPlain(start, end)
                return
            }
            var cursor = start
            while (cursor < end) {
                val next = codePointEnd(source, cursor, end, TelegramRichLimits.TEXT_CHARACTERS)
                val fragment = source.substring(cursor, next)
                // 新片段的开头可能把原来的普通文字变成标题、列表、引用或分隔线。
                val fragmentNode = parser.parse(fragment).firstChild
                val retainsParagraph = fragmentNode is Paragraph && fragmentNode.next == null &&
                        children(fragmentNode).all { it is Text || it is SoftLineBreak || it is HardLineBreak }
                if (!retainsParagraph || !emitRich(fragment, cursor, next)) emitPlain(cursor, next)
                cursor = next
            }
        }

        private fun emitRich(raw: String, start: Int, end: Int, merge: Boolean = true): Boolean {
            val text = withReferences(raw)
            if (!fits(text)) return false
            val previous = result.lastOrNull()
            if (merge && previous?.format == TelegramRichFormat.MARKDOWN && previous.sourceEnd == start) {
                val combined = previous.text + "\n\n" + text
                if (fits(combined)) {
                    result[result.lastIndex] = previous.copy(text = combined, sourceEnd = end)
                    return true
                }
            }
            result += TelegramReplyPart(text, start, end, TelegramRichFormat.MARKDOWN)
            return true
        }

        private fun emitPlain(start: Int, end: Int) {
            result += plainParts(source, start, end)
        }

        private fun withReferences(text: String): String {
            if (definitions.isEmpty()) return text
            val needed = linkedSetOf<String>()
            val queue = ArrayDeque<String>()
            queue.add(text)
            while (queue.isNotEmpty()) {
                val fragment = queue.removeFirst()
                referenceLabels(fragment).forEach { label ->
                    val definition = definitions[label]
                    if (definition != null && needed.add(label)) queue.add(definition)
                }
            }
            val additions = needed.mapNotNull { definitions[it] }.filterNot { text.contains(it) }
            return if (additions.isEmpty()) text else text + "\n\n" + additions.joinToString("\n\n")
        }

        /** 保留 label 中的原始标记及跨行文字，避免 Text 节点反转义或去掉强调/代码标记。 */
        private fun referenceLabels(fragment: String): Set<String> {
            val document = parser.parse(fragment)
            val codeRanges = mutableListOf<IntRange>()
            val containers = mutableListOf<Node>()
            walk(document) { node, _ ->
                if ((node is Code || node is FencedCodeBlock || node is IndentedCodeBlock) && node.sourceSpans.isNotEmpty()) {
                    codeRanges += node.sourceSpans.first().inputIndex until node.sourceSpans.last().let { it.inputIndex + it.length }
                }
                if (node is Paragraph || node is Heading || node is TableCell || node is HtmlBlock) containers += node
            }
            return buildSet {
                for (node in containers) {
                    val spans = node.sourceSpans
                    val literal = spans.joinToString("\n") { fragment.substring(it.inputIndex, it.inputIndex + it.length) }
                    fun originalOffset(offset: Int): Int {
                        var remaining = offset
                        for (span in spans) {
                            if (remaining < span.length) return span.inputIndex + remaining
                            remaining -= span.length + 1
                        }
                        error("引用位置必须落在原文跨度中。")
                    }
                    LABEL.findAll(literal).forEach { match ->
                        val start = originalOffset(match.range.first)
                        val end = originalOffset(match.range.last)
                        // 整个引用处在代码中时忽略；label 内的行内代码仍是 label 的一部分。
                        if (codeRanges.none { start in it && end in it }) add(normalizeLabel(match.groupValues[1]))
                    }
                }
            }
        }

        private fun startOf(node: Node): Int = lineStart(node.sourceSpans.minOfOrNull { it.inputIndex } ?: 0)
        private fun endOf(node: Node): Int = lineEnd(node.sourceSpans.maxOfOrNull { it.inputIndex + it.length } ?: 0)
        private fun lineStart(offset: Int): Int = if (offset <= 0) 0 else source.lastIndexOf('\n', offset - 1) + 1
        private fun lineEnd(offset: Int): Int = source.indexOf('\n', offset).let { if (it < 0) source.length else it + 1 }
    }

    /** 以源码长度和 AST 计数保守规划，Telegram 对扩展语法保留最终判定权。 */
    private fun fits(text: String): Boolean {
        if (text.isEmpty() || characterCount(text) > TelegramRichLimits.TEXT_CHARACTERS) return false
        var blocks = 0
        var media = 0
        var valid = true
        val html = StringBuilder()
        walk(parser.parse(text)) { node, depth ->
            if (node is Block && node !is Document) blocks++
            if (node is TableRow) blocks++
            if (node is Image) media++
            if (depth > TelegramRichLimits.NESTING + 1) valid = false
            if (node is TableRow && children(node).count { it is TableCell } > TelegramRichLimits.TABLE_COLUMNS) valid = false
            // 围栏及行内代码里的标签只是文字，不能计为富消息容器或媒体。
            when (node) {
                is HtmlBlock -> html.append(node.literal).append('\n')
                is HtmlInline -> html.append(node.literal).append('\n')
            }
        }
        var htmlDepth = 0
        HTML_TAG.findAll(html).forEach { match ->
            val name = match.groupValues[2].lowercase(Locale.ROOT)
            if (name !in VOID_TAGS) {
                if (match.groupValues[1] == "/") htmlDepth = (htmlDepth - 1).coerceAtLeast(0)
                else if (!match.value.endsWith("/>")) htmlDepth++
                if (htmlDepth > TelegramRichLimits.NESTING) valid = false
            }
            if (match.groupValues[1].isEmpty()) {
                if (name in HTML_BLOCK_TAGS) blocks++
                if (name in setOf("img", "video", "audio")) media++
            }
        }
        return valid && blocks <= TelegramRichLimits.BLOCKS && media <= TelegramRichLimits.MEDIA
    }

    private fun children(node: Node): List<Node> = buildList {
        var child = node.firstChild
        while (child != null) {
            add(child)
            child = child.next
        }
    }

    private fun walk(root: Node, visit: (Node, Int) -> Unit) {
        val queue = ArrayDeque<Pair<Node, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeLast()
            visit(node, depth)
            var child = node.lastChild
            while (child != null) {
                queue.add(child to depth + 1)
                child = child.previous
            }
        }
    }

    /** Telegram 容器及块公式即使含空行，也不能被 CommonMark 的普通段落边界切开。 */
    private fun protectedRanges(source: String, document: Node): List<Pair<Int, Int>> {
        val ranges = mutableListOf<Pair<Int, Int>>()
        val inlineCodeRanges = mutableListOf<IntRange>()
        walk(document) { node, _ ->
            if (node is Code && node.sourceSpans.isNotEmpty()) {
                inlineCodeRanges += node.sourceSpans.first().inputIndex until node.sourceSpans.last().let { it.inputIndex + it.length }
            }
        }
        var offset = 0
        var fenced: String? = null
        var protectedStart: Int? = null
        var tag: String? = null
        var depth = 0
        for (line in source.splitToSequence('\n')) {
            val trimmed = line.trim().replace(CONTAINER_PREFIX, "")
            val end = minOf(source.length, offset + line.length + 1)
            val fence = Regex("^(`{3,}|~{3,})").find(trimmed)?.value
            val markdownBody = tag == null || tag in setOf("details", "tg-collage", "tg-slideshow")
            if (fenced != null) {
                if (fence != null && fence.first() == fenced.first() && fence.length >= fenced.length && trimmed.drop(fence.length).isBlank()) fenced = null
            } else if (fence != null && (protectedStart == null || markdownBody)) {
                fenced = fence
            } else {
                if (protectedStart == null) {
                    val open = CONTAINER_OPEN.find(trimmed)
                    if (open != null) {
                        protectedStart = offset
                        tag = open.groupValues[1].lowercase(Locale.ROOT)
                        depth = 0
                    } else if (trimmed.startsWith("$$")) {
                        protectedStart = offset
                        tag = null
                    }
                }
                if (protectedStart != null) {
                    val currentTag = tag
                    val closed = if (currentTag != null) {
                        HTML_TAG.findAll(line).filter { match ->
                            match.groupValues[2].equals(currentTag, ignoreCase = true) &&
                                    inlineCodeRanges.none { offset + match.range.first in it }
                        }.forEach {
                            depth += if (it.groupValues[1] == "/") -1 else if (it.value.endsWith("/>")) 0 else 1
                        }
                        depth <= 0
                    } else {
                        trimmed.endsWith("$$") && (offset != protectedStart || trimmed.length > 2)
                    }
                    if (closed) {
                        ranges += protectedStart to end
                        protectedStart = null
                    }
                }
            }
            offset = end
        }
        protectedStart?.let { ranges += it to source.length }
        return ranges
    }

    private fun normalizeLabel(label: String): String = label.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
    private fun characterCount(text: String): Int = text.codePointCount(0, text.length)
    private fun codePointEnd(text: String, start: Int, end: Int, count: Int): Int =
        text.offsetByCodePoints(start, minOf(count, text.codePointCount(start, end)))

    private val LABEL = Regex("\\[([^]\\u0000]+)]")
    private val HTML_TAG = Regex("<(/?)([a-zA-Z][\\w-]*)\\b[^>]*>")
    private val CONTAINER_OPEN = Regex("^<(details|tg-collage|tg-slideshow|table|tg-math-block|tg-math|figure|blockquote|aside|pre)(?=[\\s/>])", RegexOption.IGNORE_CASE)
    private val CONTAINER_PREFIX = Regex("^(?:>[ \\t]*|[-+*][ \\t]+|[0-9]+[.)][ \\t]+)*")
    private val VOID_TAGS = setOf("img", "br", "hr", "source", "input")
    private val HTML_BLOCK_TAGS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "blockquote", "details", "pre", "hr", "img", "video", "audio")
}
