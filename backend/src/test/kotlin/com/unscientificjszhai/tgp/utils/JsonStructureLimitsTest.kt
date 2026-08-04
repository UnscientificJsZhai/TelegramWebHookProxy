package com.unscientificjszhai.tgp.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** JSON 深度和节点边界的非递归校验测试。 */
class JsonStructureLimitsTest {
    /** 验证原始扫描器会保留跨 chunk 的字符串和容器状态。 */
    @Test
    fun `scanner accepts a chunked JSON document`() {
        val scanner = JsonStructureLimits.newUtf8Scanner()

        scanner.consume("{\"message\":\"braces ".toByteArray())
        scanner.consume("[ are text ]\",\"items\":[1,".toByteArray())
        scanner.consume("2,3]}".toByteArray())
        scanner.finish()
    }

    /** 验证过深原始 JSON 会在交给递归解析器前被拒绝。 */
    @Test
    fun `raw JSON above the nesting boundary is rejected before parsing`() {
        val deepJson = "[".repeat(JsonStructureLimits.MAX_DEPTH + 1) +
                "0" +
                "]".repeat(JsonStructureLimits.MAX_DEPTH + 1)

        assertFailsWith<JsonStructureLimitExceededException> {
            JsonStructureLimits.parseToJsonElement(Json, deepJson)
        }
    }

    /** 验证既有深层树通过显式栈校验时不会递归并会得到同一限制。 */
    @Test
    fun `deep JSON element is rejected by the explicit stack validator`() {
        var value: JsonElement = JsonPrimitive("leaf")
        repeat(JsonStructureLimits.MAX_DEPTH + 1) { index ->
            value = JsonObject(linkedMapOf("level-$index" to value))
        }

        assertFailsWith<JsonStructureLimitExceededException> {
            JsonStructureLimits.validateElement(value)
        }
    }

    /** 浅层但节点数超限的对象也会在把所有子项压入工作栈前被拒绝。 */
    @Test
    fun `wide JSON object above node budget is rejected`() {
        val entries = (0..JsonStructureLimits.MAX_NODES).associate { index ->
            "field-$index" to JsonPrimitive(index)
        }

        assertFailsWith<JsonStructureLimitExceededException> {
            JsonStructureLimits.validateElement(JsonObject(entries))
        }
    }

    /** 验证浅层对象仍按原参数转换规则转换数字、布尔和 null。 */
    @Test
    fun `iterative object conversion preserves primitive coercions`() {
        val objectValue = Json.parseToJsonElement("""{"count":2,"enabled":true,"nested":{"name":"ok"}}""")
                as JsonObject

        assertEquals(
            mapOf(
                "count" to 2,
                "enabled" to true,
                "nested" to mapOf("name" to "ok"),
            ),
            JsonStructureLimits.toKotlinMap(objectValue),
        )
    }
}
