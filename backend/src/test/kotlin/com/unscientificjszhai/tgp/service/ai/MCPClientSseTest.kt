package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class MCPClientSseTest {
    @Test
    fun `POST tool call receives final result after progress stream exceeds one MiB`() = runBlocking {
        val progress = "data: " + buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
            put("params", buildJsonObject {
                put("progressToken", "test")
                put("progress", 1)
                put("message", "x".repeat(1024))
            })
        } + "\n\n"
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val service = MCPClientService(scope)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.method == "GET") return MockResponse.Builder().code(405).build()
                if (request.method == "DELETE") return MockResponse.Builder().code(200).build()
                val message = Json.parseToJsonElement(checkNotNull(request.body).utf8()).jsonObject
                val id = message["id"]
                val result = when (message["method"]?.jsonPrimitive?.content) {
                    "initialize" ->
                        """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"test","version":"1"}}"""

                    "tools/list" -> """{"tools":[{"name":"test_tool","inputSchema":{"type":"object"}}]}"""
                    "tools/call" -> {
                        val body = progress.repeat(1000) +
                                "data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}}\n\n"
                        assertTrue(body.toByteArray().size > MAX_MCP_RESPONSE_BYTES)
                        // 有限 SSE 的 Content-Length 同样不能被误当作单个事件的大小。
                        return MockResponse.Builder().setHeader("Content-Type", "text/event-stream")
                            .body(body).build()
                    }

                    else -> return MockResponse.Builder().code(202).build()
                }
                return MockResponse.Builder().setHeader("Content-Type", "application/json")
                    .body("{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":$result}").build()
            }
        }
        try {
            server.start()
            withTimeout(30.seconds) {
                service.connect(listOf(MCPServerConfig("test", server.url("/mcp").toString())))
                assertEquals("test_tool", service.getAllTools().single().second.name)

                val result = service.callTool("test", "test_tool", emptyMap())

                assertEquals("done", assertIs<TextContent>(result.content.single()).text)
            }
        } finally {
            withTimeout(5.seconds) { service.close().join() }
            scope.coroutineContext[Job]?.cancelAndJoin()
            server.close()
        }
    }
}
