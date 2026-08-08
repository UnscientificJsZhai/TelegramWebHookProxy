package com.unscientificjszhai.tgp.utils

import kotlinx.serialization.json.*
import java.io.IOException
import java.util.*

/**
 * JSON 结构超出安全边界时抛出的异常。
 *
 * 该异常继承自 [IOException]，以便在流式 HTTP 响应读取期间直接中止上游连接；在本地解析路径中同样
 * 可作为普通输入校验失败处理。
 */
internal class JsonStructureLimitExceededException(message: String) : IOException(message)

/**
 * 对不可信 JSON 的深度和节点数实行统一限制的工具。
 *
 * Kotlin serialization 的 JSON 树解析和编码会随嵌套层数递归。所有不可信原始 JSON 必须先经过
 * [validateUtf8]，已构造的 JSON 树在序列化或递归转换前必须经过 [validateElement]。这些校验均使用
 * 显式栈，不会因攻击者提供的深层结构消耗调用栈。调用方可为特定存储传入 [Budget]，未指定时
 * 使用 [DEFAULT_BUDGET]。
 */
internal object JsonStructureLimits {
    /** 允许的最大 JSON 容器嵌套层数。 */
    const val MAX_DEPTH = 64

    /** 允许的最大 JSON 令牌节点数，包含对象键，因而比纯值节点计数更保守。 */
    const val MAX_NODES = 4_096

    /**
     * 一次 JSON 结构校验的资源预算。
     *
     * @property maxDepth 允许的最大容器嵌套层数，必须为正数。
     * @property maxNodes 允许的最大令牌节点数，包含对象键，必须为正数。
     */
    data class Budget(
        val maxDepth: Int = MAX_DEPTH,
        val maxNodes: Int = MAX_NODES,
    ) {
        init {
            require(maxDepth > 0) { "JSON maximum depth must be positive." }
            require(maxNodes > 0) { "JSON maximum node count must be positive." }
        }
    }

    /** 通用不可信 JSON 输入使用的默认结构预算。 */
    val DEFAULT_BUDGET = Budget()

    /** JSON 树到 Kotlin 容器的显式后序转换工作项。 */
    private sealed interface KotlinConversionWork {
        data class Visit(val value: JsonElement) : KotlinConversionWork
        data class BuildArray(val value: JsonArray) : KotlinConversionWork
        data class BuildObject(val value: JsonObject) : KotlinConversionWork
    }

    /**
     * 流式校验一段 UTF-8 JSON 字节。
     *
     * 返回的扫描器可继续接收后续 chunk；调用方在一份完整 JSON 文本结束时必须调用
     * [Utf8Scanner.finish]。扫描器只负责结构边界，完整语法和 UTF-8 合法性仍由随后的 JSON 解码器
     * 负责。
     */
    fun newUtf8Scanner(budget: Budget = DEFAULT_BUDGET): Utf8Scanner =
        Utf8Scanner(budget.maxDepth, budget.maxNodes)

    /** 在解析前校验一份完整的 UTF-8 JSON 文本的结构边界。 */
    fun validateUtf8(bytes: ByteArray, budget: Budget = DEFAULT_BUDGET) {
        newUtf8Scanner(budget).apply {
            consume(bytes)
            finish()
        }
    }

    /** 在解析前校验一份完整字符串形式 JSON 的结构边界。 */
    fun validateJsonString(source: String, budget: Budget = DEFAULT_BUDGET) =
        validateUtf8(source.toByteArray(Charsets.UTF_8), budget)

    /**
     * 先完成非递归边界校验，再交给 Kotlin serialization 解析。
     *
     * 解析成功后再次校验树，以覆盖解码器的边缘语义，并确保后续编码或转换的输入已受限。
     */
    fun parseToJsonElement(
        json: Json,
        source: String,
        budget: Budget = DEFAULT_BUDGET,
    ): JsonElement {
        validateJsonString(source, budget)
        return json.parseToJsonElement(source).also { validateElement(it, budget) }
    }

    /**
     * 使用显式栈校验既有 JSON 树。
     *
     * 对象键也纳入节点预算，防止少量深层以外的大型对象绕过工作量限制。
     */
    fun validateElement(root: JsonElement, budget: Budget = DEFAULT_BUDGET) {
        data class Pending(val value: JsonElement, val depth: Int)

        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending(root, 0))
        var nodes = 0
        while (pending.isNotEmpty()) {
            val (value, depth) = pending.removeLast()
            nodes = checkedNodeCount(nodes, budget.maxNodes)
            when (value) {
                is JsonObject -> {
                    checkDepth(depth + 1, budget.maxDepth)
                    // 先计入键的节点预算，再逐项压栈；不得以 `entries.toList()` 复制不可信的大对象。
                    nodes = checkedNodeCount(nodes, budget.maxNodes, value.size)
                    for ((_, child) in value) {
                        pending.addLast(Pending(child, depth + 1))
                    }
                }

                is JsonArray -> {
                    checkDepth(depth + 1, budget.maxDepth)
                    // 子值随后才实际计数，但必须在将它们全部压入工作栈前确认预算。
                    ensureNodeCapacity(nodes, value.size, budget.maxNodes)
                    for (child in value) {
                        pending.addLast(Pending(child, depth + 1))
                    }
                }

                is JsonPrimitive,
                JsonNull,
                    -> Unit
            }
        }
    }

    /**
     * 将 JSON 对象转换为 Kotlin `Map`，不使用递归。
     *
     * 数字转换规则与原有实现一致：依次尝试布尔、Int、Long、Double，最后保留原始文本。
     */
    @Suppress("UNCHECKED_CAST")
    fun toKotlinMap(
        root: JsonObject,
        budget: Budget = DEFAULT_BUDGET,
    ): Map<String, Any?> =
        toKotlinValue(root, budget) as? Map<String, Any?> ?: error("JSON object conversion did not produce a map.")

    /** 将任意 JSON 值转换为 Kotlin 容器值，先校验结构并采用显式后序栈。 */
    fun toKotlinValue(root: JsonElement, budget: Budget = DEFAULT_BUDGET): Any? {
        validateElement(root, budget)

        val values = IdentityHashMap<JsonElement, Any?>()
        val work = ArrayDeque<KotlinConversionWork>()
        work.addLast(KotlinConversionWork.Visit(root))
        while (work.isNotEmpty()) {
            when (val item = work.removeLast()) {
                is KotlinConversionWork.Visit -> when (val value = item.value) {
                    JsonNull -> values[value] = null
                    is JsonPrimitive -> values[value] = primitiveToKotlin(value)
                    is JsonArray -> {
                        work.addLast(KotlinConversionWork.BuildArray(value))
                        for (index in value.indices.reversed()) work.addLast(KotlinConversionWork.Visit(value[index]))
                    }

                    is JsonObject -> {
                        work.addLast(KotlinConversionWork.BuildObject(value))
                        value.forEach { (_, child) -> work.addLast(KotlinConversionWork.Visit(child)) }
                    }
                }

                is KotlinConversionWork.BuildArray -> values[item.value] = item.value.map { values[it] }
                is KotlinConversionWork.BuildObject -> values[item.value] = LinkedHashMap<String, Any?>().apply {
                    item.value.forEach { (name, child) -> put(name, values[child]) }
                }
            }
        }
        return values[root]
    }

    private fun primitiveToKotlin(value: JsonPrimitive): Any =
        if (value.isString) {
            value.content
        } else if (value.booleanOrNull != null) {
            value.boolean
        } else if (value.intOrNull != null) {
            value.int
        } else if (value.longOrNull != null) {
            value.long
        } else if (value.doubleOrNull != null) {
            value.double
        } else {
            value.content
        }

    private fun checkedNodeCount(current: Int, maxNodes: Int, increment: Int = 1): Int {
        ensureNodeCapacity(current, increment, maxNodes)
        return current + increment
    }

    /** 在批量压入显式工作栈前验证节点预算，防止超大浅层容器先占用内存。 */
    private fun ensureNodeCapacity(current: Int, increment: Int, maxNodes: Int) {
        if (increment < 0 || current > maxNodes - increment) {
            throw JsonStructureLimitExceededException("JSON 节点数超过 $maxNodes 限制。")
        }
    }

    private fun checkDepth(depth: Int, maxDepth: Int) {
        if (depth > maxDepth) {
            throw JsonStructureLimitExceededException("JSON 嵌套深度超过 $maxDepth 限制。")
        }
    }

    /**
     * 可跨网络读取 chunk 的原始 JSON 字节扫描器。
     *
     * 它不分配 JSON 树，只识别字符串、容器与裸值的边界；因此括号或引号跨 chunk 时仍会保留正确状态。
     */
    class Utf8Scanner internal constructor(
        private val maxDepth: Int,
        private val maxNodes: Int,
    ) {
        private var inString = false
        private var escaped = false
        private var inBareValue = false
        private var depth = 0
        private var nodes = 0
        private var finished = false

        /** 消费一个网络读取 chunk；同一实例可被重复调用直到 [finish]。 */
        fun consume(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length) { "Invalid JSON byte range." }
            check(!finished) { "JSON scanner is already finished." }
            var index = offset
            val end = offset + length
            while (index < end) {
                val byte = bytes[index].toInt() and 0xff
                if (inString) {
                    when {
                        escaped -> escaped = false
                        byte == BACKSLASH -> escaped = true
                        byte == QUOTE -> inString = false
                    }
                    index++
                    continue
                }
                if (inBareValue && !isBareValueByte(byte)) {
                    inBareValue = false
                }
                if (!inBareValue) {
                    when (byte) {
                        QUOTE -> {
                            incrementNodes()
                            inString = true
                        }

                        OPEN_OBJECT,
                        OPEN_ARRAY,
                            -> {
                            incrementNodes()
                            depth++
                            if (depth > maxDepth) {
                                throw JsonStructureLimitExceededException("JSON 嵌套深度超过 $maxDepth 限制。")
                            }
                        }

                        CLOSE_OBJECT,
                        CLOSE_ARRAY,
                            -> {
                            depth--
                            if (depth < 0) {
                                throw JsonStructureLimitExceededException("JSON 容器闭合顺序无效。")
                            }
                        }

                        else -> if (isBareValueStart(byte)) {
                            incrementNodes()
                            inBareValue = true
                        }
                    }
                }
                index++
            }
        }

        /** 结束当前 JSON 文本并拒绝未闭合的字符串或容器。 */
        fun finish() {
            check(!finished) { "JSON scanner is already finished." }
            finished = true
            if (inString || escaped || depth != 0) {
                throw JsonStructureLimitExceededException("JSON 结构未完整闭合。")
            }
        }

        private fun incrementNodes() {
            if (nodes >= maxNodes) {
                throw JsonStructureLimitExceededException("JSON 节点数超过 $maxNodes 限制。")
            }
            nodes++
        }

        private companion object {
            const val QUOTE = 0x22
            const val BACKSLASH = 0x5c
            const val OPEN_OBJECT = 0x7b
            const val CLOSE_OBJECT = 0x7d
            const val OPEN_ARRAY = 0x5b
            const val CLOSE_ARRAY = 0x5d

            fun isBareValueStart(byte: Int): Boolean =
                byte == '-'.code || byte in '0'.code..'9'.code || byte == 't'.code || byte == 'f'.code || byte == 'n'.code

            fun isBareValueByte(byte: Int): Boolean =
                byte == '-'.code || byte == '+'.code || byte == '.'.code ||
                        byte in '0'.code..'9'.code || byte in 'a'.code..'z'.code || byte in 'A'.code..'Z'.code
        }
    }
}
