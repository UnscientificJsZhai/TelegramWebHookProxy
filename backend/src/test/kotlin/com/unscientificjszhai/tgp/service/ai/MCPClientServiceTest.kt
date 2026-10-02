package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.utils.JsonStructureLimitExceededException
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.json.*
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import kotlin.test.*

/**
 * MCP 客户端连接更新与终态关闭行为的测试设计。
 */
class MCPClientServiceTest {

    @Test
    fun `wire root constraints are rejected before SDK decoding in JSON and SSE`() {
        val constraints = listOf(
            "\"oneOf\":[{\"required\":[\"a\"]},{\"required\":[\"b\"]}]",
            "\"additionalProperties\":false",
            "\"type\":\"array\"",
            "\"\$schema\":\"https://json-schema.org/draft-07/schema\"",
        )
        for (constraint in constraints) {
            val payload =
                """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"choice","inputSchema":{"type":"object","properties":{"a":{"type":"string"},"b":{"type":"string"}},$constraint}}]}}"""
            for (sse in listOf(false, true)) {
                val delegate = CloseTrackingResponseBody(
                    (if (sse) "text/event-stream" else "application/json").toMediaType(),
                    if (sse) "event: message\r\ndata: $payload\r\n\r\n" else payload,
                )
                val responseBody = BoundedMcpResponseBody(delegate, MAX_MCP_RESPONSE_BYTES)
                assertFailsWith<McpToolSchemaNotRepresentableException>(constraint) {
                    responseBody.source().readByteArray()
                }
                assertTrue(delegate.closed)
            }
        }
    }

    @Test
    fun `lossless root schema remains available on JSON and consecutive SSE events`() {
        val payload =
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"simple","inputSchema":{"type":"object","properties":{"value":{"type":"string"}},"required":["value"],"${'$'}defs":{}}}]}}"""
        for (sse in listOf(false, true)) {
            val body =
                if (sse) "id: cursor\ndata:\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"ping\"}\n\ndata: $payload\n\n" else payload
            val delegate = CloseTrackingResponseBody(
                (if (sse) "text/event-stream" else "application/json").toMediaType(), body,
            )
            assertEquals(body, BoundedMcpResponseBody(delegate, MAX_MCP_RESPONSE_BYTES).source().readUtf8())
        }
    }

    @Test
    fun `empty JSON notification response is allowed`() {
        val delegate = CloseTrackingResponseBody("application/json".toMediaType(), "")
        assertEquals("", BoundedMcpResponseBody(delegate, MAX_MCP_RESPONSE_BYTES).source().readUtf8())
    }

    @Test
    fun `SSE progress stream larger than response limit delivers final result`() {
        val progress = "data: " + buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
            put("params", buildJsonObject {
                put("progressToken", "test")
                put("progress", 1)
                put("message", "x".repeat(1024))
            })
        } + "\r\n\r\n"
        val final =
            "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}\r\n\r\n"
        val body = progress.repeat(1000) + final
        assertTrue(body.toByteArray().size > MAX_MCP_RESPONSE_BYTES)
        val delegate = CloseTrackingResponseBody("text/event-stream".toMediaType(), body)

        assertEquals(body, BoundedMcpResponseBody(delegate, MAX_MCP_RESPONSE_BYTES).source().readUtf8())
    }

    @Test
    fun `SSE event budget resets across fragmented CR LF and CRLF separators`() {
        for (ending in listOf("\r", "\n", "\r\n")) {
            val event = "id: cursor${ending}data: {\"ok\":true}${ending}data: ${ending}comment: ignored${ending}"
            val body = (event + ending).repeat(10)
            val delegate = CloseTrackingResponseBody("text/event-stream".toMediaType(), body, chunkSize = 1)
            val responseBody = BoundedMcpResponseBody(delegate, event.toByteArray().size.toLong())

            assertEquals(body, responseBody.source().readUtf8())

            val oversized = CloseTrackingResponseBody("text/event-stream".toMediaType(), body, chunkSize = 1)
            assertFailsWith<McpResponseTooLargeException> {
                BoundedMcpResponseBody(oversized, event.toByteArray().size.toLong() - 1).source().readUtf8()
            }
            assertTrue(oversized.closed)
        }
    }

    @Test
    fun `oversized SSE data comments and multiline events close before EOF`() {
        val events = listOf(
            "data: {\"text\":\"${"x".repeat(200)}\"}",
            ":${"x".repeat(200)}",
            "data: {\"text\":\"${"x".repeat(50)}\"}\ndata: ${" ".repeat(50)}\n",
        )
        for (event in events) {
            val delegate = CloseTrackingResponseBody("text/event-stream".toMediaType(), event, chunkSize = 3)

            assertFailsWith<McpResponseTooLargeException> {
                BoundedMcpResponseBody(delegate, 100).source().readByteArray()
            }
            assertTrue(delegate.closed)
        }
    }

    @Test
    fun `JSON responses keep the total byte limit`() {
        val delegate = CloseTrackingResponseBody(
            "application/json".toMediaType(), "{\"text\":\"${"x".repeat(200)}\"}", chunkSize = 3,
        )

        assertFailsWith<McpResponseTooLargeException> {
            BoundedMcpResponseBody(delegate, 100).source().readByteArray()
        }
        assertTrue(delegate.closed)
    }

    @Test
    fun `SSE root constraints are rejected at CR LF and CRLF event boundaries without EOF`() {
        val payload =
            """{"jsonrpc":"2.0","id":1,"result":{"tools":[{"name":"unsafe","inputSchema":{"type":"object","additionalProperties":false}}]}}"""
        for (ending in listOf("\r", "\n", "\r\n")) {
            val delegate = CloseTrackingResponseBody("text/event-stream".toMediaType(), "data: $payload$ending$ending")
            val source = BoundedMcpResponseBody(delegate, MAX_MCP_RESPONSE_BYTES).source()
            // 首次读取必须在返回给 SDK 之前失败，不能依赖第二次读取才发现 EOF。
            assertFailsWith<McpToolSchemaNotRepresentableException> { source.read(Buffer(), 8192) }
            assertTrue(delegate.closed)
        }
    }


    /** 验证深层 structuredContent 在 MCP result serializer 之前被拒绝。 */
    @Test
    fun `deep tool result is rejected before serialization`() {
        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(JsonStructureLimits.MAX_DEPTH + 1) { index ->
            nested = JsonObject(linkedMapOf("level-$index" to nested))
        }

        val failure = try {
            validateMcpToolResult(
                CallToolResult(
                    emptyList(),
                    structuredContent = JsonObject(linkedMapOf("root" to nested))
                )
            )
            null
        } catch (error: JsonStructureLimitExceededException) {
            error
        }

        assertNotNull(failure)
    }

    /** 验证嵌入 resource 的 `_meta` 也会在 SDK serializer 之前受显式栈校验。 */
    @Test
    fun `deep embedded resource metadata is rejected before result serialization`() {
        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(JsonStructureLimits.MAX_DEPTH + 1) { index ->
            nested = JsonObject(linkedMapOf("level-$index" to nested))
        }
        val result = CallToolResult(
            content = listOf(
                EmbeddedResource(
                    TextResourceContents(
                        text = "resource",
                        uri = "test://resource",
                        meta = JsonObject(mapOf("deep" to nested))
                    ),
                ),
            ),
        )

        assertFailsWith<JsonStructureLimitExceededException> { validateMcpToolResult(result) }
    }

    /** 结构 scanner 拒绝响应时立即关闭其委托响应体，不能泄漏无限 SSE 连接。 */
    @Test
    fun `bounded response body closes delegate when structure scanner rejects`() {
        val delegate = CloseTrackingResponseBody(
            contentType = "application/json".toMediaType(),
            body = deeplyNestedJson(JsonStructureLimits.MAX_DEPTH + 1),
        )
        val responseBody = BoundedMcpResponseBody(delegate, MAX_MCP_RESPONSE_BYTES)

        assertFailsWith<JsonStructureLimitExceededException> { responseBody.source().readByteArray() }
        assertTrue(delegate.closed)
    }

    /** 构造超过统一嵌套上限的对象。 */
    private fun deeplyNestedJson(depth: Int): String = buildString {
        repeat(depth) { append("{\"next\":") }
        append("\"leaf\"")
        repeat(depth) { append('}') }
    }

    /** 可观察 close 的最小 ResponseBody，用于验证 scanner 错误会释放底层连接。 */
    private class CloseTrackingResponseBody(
        private val contentType: MediaType,
        body: String,
        chunkSize: Long = 8192,
    ) : ResponseBody() {
        private val length = body.toByteArray().size.toLong()
        private val source = object : ForwardingSource(Buffer().writeUtf8(body)) {
            override fun read(sink: Buffer, byteCount: Long): Long = super.read(sink, minOf(byteCount, chunkSize))
        }.buffer()
        var closed = false
            private set

        override fun contentType(): MediaType = contentType

        override fun contentLength(): Long = length

        override fun source() = source

        override fun close() {
            closed = true
            source.close()
        }
    }
}
