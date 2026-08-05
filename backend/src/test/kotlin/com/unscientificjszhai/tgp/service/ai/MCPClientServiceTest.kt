package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.JsonStructureLimitExceededException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okhttp3.ResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer

/**
 * MCP 客户端连接更新与终态关闭行为的测试设计。
 */
class MCPClientServiceTest {

    /** 验证深层 Kotlin 参数在序列化到 MCP JSON 前被显式栈校验，底层客户端不会收到调用。 */
    @Test
    fun `deep tool arguments are rejected before client serialization`() = runBlocking {
        val client = mockk<Client>()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { client }
        coEvery { client.connect(any()) } returns Unit
        coEvery { client.listTools() } returns ListToolsResult(listOf(Tool("tool", ToolSchema())))
        coEvery { client.close() } returns Unit
        service.connect(listOf(MCPServerConfig(name = "server", url = "https://example.com/mcp")))
        var nested: Any? = "leaf"
        repeat(JsonStructureLimits.MAX_DEPTH + 1) {
            nested = mapOf("next" to nested)
        }

        val failure = try {
            service.callTool("server", "tool", mapOf("root" to nested))
            null
        } catch (error: McpToolArgumentsTooLargeException) {
            error
        }

        assertNotNull(failure)
        coVerify(exactly = 0) { client.callTool(any<String>(), any<Map<String, Any?>>()) }
        service.close().join()
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

    /** application/json 的深层 JSON-RPC wire 响应会在 SDK tools decoder 前被中止。 */
    @Test
    fun `deep application json MCP wire response is rejected before SDK decode`() = runBlocking {
        runWireMcpConnectionTest(
            contentType = "application/json",
            toolsResult = deeplyNestedJson(JsonStructureLimits.MAX_DEPTH + 1),
            expectPublishedTool = null,
        )
    }

    /** SSE 的深层多 data 行 CRLF wire 响应跨单字节 chunk 时也必须在 SDK decode 前被中止。 */
    @Test
    fun `deep SSE MCP wire response is rejected across CRLF multi data chunks`() = runBlocking {
        runWireMcpConnectionTest(
            contentType = "text/event-stream",
            toolsResult = deeplyNestedJson(JsonStructureLimits.MAX_DEPTH + 1),
            expectPublishedTool = null,
        )
    }

    /** 多 data 行、CRLF 与单字节分块的合法 SSE 响应仍可由 SDK 正常解码。 */
    @Test
    fun `SSE multi data CRLF chunks safely reach SDK after wire scan`() = runBlocking {
        runWireMcpConnectionTest(
            contentType = "text/event-stream",
            toolsResult = """{"tools":[{"name":"sse-safe-tool","inputSchema":{"type":"object"}}]}""",
            expectPublishedTool = "sse-safe-tool",
        )
    }

    /**
     * 验证超出工具数量上限的候选连接会关闭旧连接和候选连接，并清空工具快照。
     */
    @Test
    fun `over limit discovery closes old and candidate connections then clears the snapshot`() = runBlocking {
        val stableClient = mockk<Client>()
        val rejectedClient = mockk<Client>()
        val clients = ArrayDeque(listOf(stableClient, rejectedClient))
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
        val stableConfig = MCPServerConfig(name = "stable", url = "https://stable.example.com/mcp")
        val rejectedConfig = MCPServerConfig(name = "rejected", url = "https://rejected.example.com/mcp")

        coEvery { stableClient.connect(any()) } returns Unit
        coEvery { stableClient.listTools() } returns ListToolsResult(emptyList())
        coEvery { stableClient.callTool("tool", emptyMap()) } returns CallToolResult(emptyList())
        coEvery { stableClient.close() } returns Unit
        coEvery { rejectedClient.connect(any()) } returns Unit
        coEvery { rejectedClient.listTools() } returns ListToolsResult(
            List(MAX_MCP_TOOLS_PER_SERVER + 1) { Tool("tool-$it", ToolSchema()) },
        )
        coEvery { rejectedClient.close() } returns Unit

        service.connect(listOf(stableConfig))
        service.connect(listOf(rejectedConfig))

        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("stable", "tool", emptyMap())
        }
        coVerify(exactly = 1) { rejectedClient.close() }
        coVerify(exactly = 1) { stableClient.close() }
        coVerify(exactly = 0) { stableClient.callTool("tool", emptyMap()) }

        service.close().join()
        coVerify(exactly = 1) { stableClient.close() }
    }

    /**
     * 验证直接调用 connect 会在断开已有连接前校验完整列表，非法配置不会扰动当前状态。
     */
    @Test
    fun `invalid direct connect leaves existing MCP connections untouched`() = runBlocking {
        val client = mockk<Client>()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { client }
        val validConfig = MCPServerConfig(name = "server", url = "https://example.com/mcp")
        val invalidConfig = MCPServerConfig(name = "invalid", url = "ftp://example.com/mcp")
        coEvery { client.connect(any()) } returns Unit
        coEvery { client.listTools() } returns ListToolsResult(emptyList())
        coEvery { client.close() } returns Unit

        service.connect(listOf(validConfig))
        assertFailsWith<IllegalArgumentException> {
            service.connect(listOf(invalidConfig))
        }

        coVerify(exactly = 1) { client.connect(any()) }
        coVerify(exactly = 0) { client.close() }
        service.close().join()
    }

    /**
     * 验证同名 MCP 配置更新时的重连设计。
     *
     * 验证旧连接会关闭，且服务会使用新配置建立连接。
     */
    @Test
    fun test同名MCP配置变更会关闭旧连接后重新连接() = runBlocking {
        val firstClient = mockk<Client>()
        val secondClient = mockk<Client>()
        val clients = ArrayDeque(listOf(firstClient, secondClient))
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
        val firstConfig = MCPServerConfig(name = "server", url = "https://first.example.com/mcp")
        val updatedConfig = MCPServerConfig(name = "server", url = "https://second.example.com/mcp")

        coEvery { firstClient.connect(any()) } returns Unit
        coEvery { firstClient.listTools() } returns ListToolsResult(emptyList())
        coEvery { firstClient.close() } returns Unit
        coEvery { secondClient.connect(any()) } returns Unit
        coEvery { secondClient.listTools() } returns ListToolsResult(emptyList())
        coEvery { secondClient.close() } returns Unit

        withTimeout(5.seconds) {
            service.connect(listOf(firstConfig))
            service.connect(listOf(updatedConfig))
        }

        coVerify(exactly = 1) { firstClient.connect(any()) }
        coVerify(exactly = 1) { firstClient.close() }
        coVerify(exactly = 1) { secondClient.connect(any()) }
        assertEquals(emptyList(), service.getAllTools())

        val closeJob: Job = service.close()
        withTimeout(5.seconds) { closeJob.join() }
        coVerify(exactly = 1) { secondClient.close() }
    }

    /**
     * 验证 URL 变更后的候选连接失败会撤销旧凭据关联的连接和工具快照。
     */
    @Test
    fun `URL rotation failure closes old and candidate clients without retaining old tools`() = runBlocking {
        val oldClient = mockk<Client>()
        val rejectedClient = mockk<Client>()
        val clients = ArrayDeque(listOf(oldClient, rejectedClient))
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
        val oldConfig = MCPServerConfig(name = "server", url = "https://old.example.com/mcp")
        val newConfig = MCPServerConfig(name = "server", url = "https://new.example.com/mcp")

        coEvery { oldClient.connect(any()) } returns Unit
        coEvery { oldClient.listTools() } returns ListToolsResult(emptyList())
        coEvery { oldClient.close() } returns Unit
        coEvery { rejectedClient.connect(any()) } throws IOException("candidate rejected")
        coEvery { rejectedClient.close() } returns Unit

        service.connect(listOf(oldConfig))
        service.connect(listOf(newConfig))

        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("server", "tool", emptyMap())
        }
        coVerify(exactly = 1) { oldClient.close() }
        coVerify(exactly = 1) { rejectedClient.close() }
        coVerify(exactly = 0) { oldClient.callTool("tool", emptyMap()) }

        service.close().join()
    }

    /**
     * 验证请求头变更后的候选连接失败不会保留使用旧请求头的客户端。
     */
    @Test
    fun `header rotation failure closes old and candidate clients without retaining old tools`() = runBlocking {
        val oldClient = mockk<Client>()
        val rejectedClient = mockk<Client>()
        val clients = ArrayDeque(listOf(oldClient, rejectedClient))
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
        val oldConfig = MCPServerConfig(
            name = "server",
            url = "https://example.com/mcp",
            headers = mapOf("Authorization" to "Bearer old"),
        )
        val newConfig = oldConfig.copy(headers = mapOf("Authorization" to "Bearer new"))

        coEvery { oldClient.connect(any()) } returns Unit
        coEvery { oldClient.listTools() } returns ListToolsResult(emptyList())
        coEvery { oldClient.close() } returns Unit
        coEvery { rejectedClient.connect(any()) } throws IOException("candidate rejected")
        coEvery { rejectedClient.close() } returns Unit

        service.connect(listOf(oldConfig))
        service.connect(listOf(newConfig))

        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("server", "tool", emptyMap())
        }
        coVerify(exactly = 1) { oldClient.close() }
        coVerify(exactly = 1) { rejectedClient.close() }
        coVerify(exactly = 0) { oldClient.callTool("tool", emptyMap()) }

        service.close().join()
    }

    /**
     * 验证相同的完整配置快照会复用当前连接，且请求头映射的插入顺序不影响比较结果。
     */
    @Test
    fun `equivalent distinct configurations reuse the current complete connection`() = runBlocking {
        val client = mockk<Client>()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { client }
        val firstConfig = MCPServerConfig(
            name = "server",
            url = "https://example.com/mcp",
            headers = linkedMapOf("X-First" to "one", "X-Second" to "two"),
        )
        val equivalentConfig = MCPServerConfig(
            name = "server",
            url = "https://example.com/mcp",
            headers = linkedMapOf("X-Second" to "two", "X-First" to "one"),
        )

        coEvery { client.connect(any()) } returns Unit
        coEvery { client.listTools() } returns ListToolsResult(emptyList())
        coEvery { client.close() } returns Unit

        service.connect(listOf(firstConfig))
        service.connect(listOf(equivalentConfig))

        coVerify(exactly = 1) { client.connect(any()) }
        coVerify(exactly = 1) { client.listTools() }
        coVerify(exactly = 0) { client.close() }

        service.close().join()
        coVerify(exactly = 1) { client.close() }
    }

    /**
     * 验证服务器顺序属于完整配置身份，列表调换后不会复用旧连接。
     */
    @Test
    fun `server list reordering closes the previous snapshot instead of reusing it`() = runBlocking {
        val firstClient = mockk<Client>()
        val secondClient = mockk<Client>()
        val rejectedClient = mockk<Client>()
        val clients = ArrayDeque(listOf(firstClient, secondClient, rejectedClient))
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
        val firstConfig = MCPServerConfig(name = "first", url = "https://first.example.com/mcp")
        val secondConfig = MCPServerConfig(name = "second", url = "https://second.example.com/mcp")

        listOf(firstClient, secondClient).forEach { client ->
            coEvery { client.connect(any()) } returns Unit
            coEvery { client.listTools() } returns ListToolsResult(emptyList())
            coEvery { client.close() } returns Unit
        }
        coEvery { rejectedClient.connect(any()) } throws IOException("candidate rejected")
        coEvery { rejectedClient.close() } returns Unit

        service.connect(listOf(firstConfig, secondConfig))
        service.connect(listOf(secondConfig, firstConfig))

        assertEquals(emptyList(), service.getAllTools())
        coVerify(exactly = 1) { firstClient.close() }
        coVerify(exactly = 1) { secondClient.close() }
        coVerify(exactly = 1) { rejectedClient.connect(any()) }
        coVerify(exactly = 1) { rejectedClient.close() }

        service.close().join()
    }

    /**
     * 验证候选连接准备期间取消时会关闭旧客户端和已创建候选，并保持空快照。
     */
    @Test
    fun `cancelling replacement while preparing a candidate closes every client and clears the snapshot`() =
        runBlocking {
            val oldClient = mockk<Client>()
            val cancelledClient = mockk<Client>()
            val clients = ArrayDeque(listOf(oldClient, cancelledClient))
            val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
            val oldConfig = MCPServerConfig(name = "server", url = "https://old.example.com/mcp")
            val newConfig = MCPServerConfig(name = "server", url = "https://new.example.com/mcp")
            val candidateStarted = CompletableDeferred<Unit>()

            coEvery { oldClient.connect(any()) } returns Unit
            coEvery { oldClient.listTools() } returns ListToolsResult(emptyList())
            coEvery { oldClient.close() } returns Unit
            coEvery { cancelledClient.connect(any()) } coAnswers {
                candidateStarted.complete(Unit)
                awaitCancellation()
            }
            coEvery { cancelledClient.close() } returns Unit

            service.connect(listOf(oldConfig))
            val replacement = launch(Dispatchers.Default) { service.connect(listOf(newConfig)) }
            candidateStarted.await()
            replacement.cancelAndJoin()

            assertEquals(emptyList(), service.getAllTools())
            assertFailsWith<IllegalStateException> {
                service.callTool("server", "tool", emptyMap())
            }
            coVerify(exactly = 1) { oldClient.close() }
            coVerify(exactly = 1) { cancelledClient.close() }

            service.close().join()
        }

    /**
     * 验证后续候选失败时，会关闭此前已准备成功的候选并保持空快照。
     */
    @Test
    fun `partial candidate preparation failure closes all candidates and the old connection`() = runBlocking {
        val oldClient = mockk<Client>()
        val firstCandidate = mockk<Client>()
        val rejectedCandidate = mockk<Client>()
        val clients = ArrayDeque(listOf(oldClient, firstCandidate, rejectedCandidate))
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { clients.removeFirst() }
        val oldConfig = MCPServerConfig(name = "old", url = "https://old.example.com/mcp")
        val firstNewConfig = MCPServerConfig(name = "first", url = "https://first.example.com/mcp")
        val rejectedNewConfig = MCPServerConfig(name = "second", url = "https://second.example.com/mcp")

        coEvery { oldClient.connect(any()) } returns Unit
        coEvery { oldClient.listTools() } returns ListToolsResult(emptyList())
        coEvery { oldClient.close() } returns Unit
        coEvery { firstCandidate.connect(any()) } returns Unit
        coEvery { firstCandidate.listTools() } returns ListToolsResult(emptyList())
        coEvery { firstCandidate.close() } returns Unit
        coEvery { rejectedCandidate.connect(any()) } throws IOException("candidate rejected")
        coEvery { rejectedCandidate.close() } returns Unit

        service.connect(listOf(oldConfig))
        service.connect(listOf(firstNewConfig, rejectedNewConfig))

        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("old", "tool", emptyMap())
        }
        coVerify(exactly = 1) { oldClient.close() }
        coVerify(exactly = 1) { firstCandidate.close() }
        coVerify(exactly = 1) { rejectedCandidate.close() }

        service.close().join()
    }

    /**
     * 验证候选批次超时不会在连接锁中等待挂起的客户端关闭，但终态关闭会严格等待这些已登记清理结束。
     *
     * 可见快照保持为空，后续连接仍能取得连接锁；而不合作客户端未离开 `close` 前，终态任务不得虚称完成。
     */
    @Test
    fun `batch timeout releases connection lock while terminal close waits for deferred cleanup`() = runBlocking {
        val oldClient = mockk<Client>()
        val stalledCandidate = mockk<Client>()
        val clients = ArrayDeque(listOf(oldClient, stalledCandidate))
        val oldCloseStarted = CompletableDeferred<Unit>()
        val candidateCloseStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val candidateStarted = CompletableDeferred<Unit>()
        val service = MCPClientService(
            CoroutineScope(EmptyCoroutineContext),
            AgentExecutionDeadlines(
                mcpBatch = 100.milliseconds,
                candidateInitialization = 1.seconds,
                scheduledTurn = 1.seconds,
            ),
        ) { clients.removeFirst() }
        val oldConfig = MCPServerConfig(name = "old", url = "https://old.example.com/mcp")
        val newConfig = MCPServerConfig(name = "new", url = "https://new.example.com/mcp")

        coEvery { oldClient.connect(any()) } returns Unit
        coEvery { oldClient.listTools() } returns ListToolsResult(listOf(Tool("old_tool", ToolSchema())))
        coEvery { oldClient.close() } coAnswers {
            oldCloseStarted.complete(Unit)
            releaseCleanup.await()
        }
        coEvery { stalledCandidate.connect(any()) } coAnswers {
            candidateStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { stalledCandidate.close() } coAnswers {
            candidateCloseStarted.complete(Unit)
            releaseCleanup.await()
        }

        service.connect(listOf(oldConfig))
        service.connect(listOf(newConfig))

        withTimeout(1.seconds) { oldCloseStarted.await() }
        withTimeout(1.seconds) { candidateStarted.await() }
        withTimeout(1.seconds) { service.connect(emptyList()) }
        withTimeout(1.seconds) { candidateCloseStarted.await() }

        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("old", "old_tool", emptyMap())
        }
        coVerify(exactly = 0) { stalledCandidate.listTools() }

        val terminalClose = service.close()
        assertFalse(terminalClose.isCompleted, "terminal close must wait for both deferred client closes")
        releaseCleanup.complete(Unit)
        withTimeout(1.seconds) { terminalClose.join() }
    }

    /**
     * 验证等待连接锁的批次超时只摘除快照；终态关闭会跟踪该交接，直到在途工具退出并完成客户端关闭。
     */
    @Test
    fun `batch timeout waits for an in flight tool before closing detached published client`() = runBlocking {
        val client = mockk<Client>()
        val toolStarted = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        val closeStarted = CompletableDeferred<Unit>()
        val service = MCPClientService(
            CoroutineScope(EmptyCoroutineContext),
            AgentExecutionDeadlines(
                mcpBatch = 100.milliseconds,
                candidateInitialization = 1.seconds,
                scheduledTurn = 1.seconds,
            ),
        ) { client }
        val config = MCPServerConfig(name = "server", url = "https://example.com/mcp")

        coEvery { client.connect(any()) } returns Unit
        coEvery { client.listTools() } returns ListToolsResult(listOf(Tool("tool", ToolSchema())))
        coEvery { client.callTool("tool", emptyMap()) } coAnswers {
            toolStarted.complete(Unit)
            releaseTool.await()
            CallToolResult(emptyList())
        }
        coEvery { client.close() } coAnswers {
            closeStarted.complete(Unit)
        }

        service.connect(listOf(config))
        val inFlightTool = async(Dispatchers.Default) { service.callTool("server", "tool", emptyMap()) }
        withTimeout(1.seconds) { toolStarted.await() }

        service.connect(emptyList())

        assertEquals(emptyList(), service.getAllTools())
        assertFalse(closeStarted.isCompleted, "detached client must stay open while the tool owns the connection lock")
        val closeJob = service.close()
        assertFalse(closeJob.isCompleted, "terminal close must track the deferred published-client cleanup")

        releaseTool.complete(Unit)
        withTimeout(1.seconds) { inFlightTool.await() }
        withTimeout(1.seconds) { closeStarted.await() }
        withTimeout(1.seconds) { closeJob.join() }
    }

    /**
     * 验证 MCP 实例的连接资源不会跨实例关闭。
     *
     * 关闭旧实例后，另一个实例的客户端和工具快照仍保持可用。
     */
    @Test
    fun `关闭旧 MCP 实例不会影响新实例`() = runBlocking {
        val oldClient = mockk<Client>()
        val newClient = mockk<Client>()
        val oldService = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { oldClient }
        val newService = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { newClient }
        val config = MCPServerConfig(name = "server", url = "https://example.com/mcp")

        listOf(oldClient, newClient).forEach { client ->
            coEvery { client.connect(any()) } returns Unit
            coEvery { client.listTools() } returns ListToolsResult(emptyList())
            coEvery { client.close() } returns Unit
        }

        oldService.connect(listOf(config))
        newService.connect(listOf(config))

        oldService.close().join()

        coVerify(exactly = 1) { oldClient.close() }
        coVerify(exactly = 0) { newClient.close() }
        assertEquals(emptyList(), newService.getAllTools())

        newService.close().join()
        coVerify(exactly = 1) { newClient.close() }
    }

    /**
     * 验证终态关闭的并发与幂等设计。
     *
     * 已开始的工具调用会先完成；关闭后新调用和连接立即失败，快照被清空，并且客户端只关闭一次。
     */
    @Test
    fun `关闭等待在途工具调用并拒绝后续操作且可重复调用`() = runBlocking {
        val client = mockk<Client>()
        val result = CallToolResult(emptyList())
        val toolCallStarted = CompletableDeferred<Unit>()
        val releaseToolCall = CompletableDeferred<Unit>()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext)) { client }
        val config = MCPServerConfig(name = "server", url = "https://example.com/mcp")

        coEvery { client.connect(any()) } returns Unit
        coEvery { client.listTools() } returns ListToolsResult(emptyList())
        coEvery { client.callTool("tool", emptyMap()) } coAnswers {
            toolCallStarted.complete(Unit)
            releaseToolCall.await()
            result
        }
        coEvery { client.close() } returns Unit
        service.connect(listOf(config))

        val inFlightCall = async(Dispatchers.Default) {
            service.callTool("server", "tool", emptyMap())
        }
        toolCallStarted.await()

        val firstClose = service.close()
        val repeatedClose = service.close()

        assertSame(firstClose, repeatedClose)
        assertFalse(firstClose.isCompleted)
        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("server", "tool", emptyMap())
        }
        assertFailsWith<IllegalStateException> {
            service.connect(listOf(config))
        }

        releaseToolCall.complete(Unit)
        assertSame(result, inFlightCall.await())
        withTimeout(5.seconds) { firstClose.join() }

        coVerify(exactly = 1) { client.close() }
    }

    /**
     * 验证 Streamable HTTP 的 SSE GET 重定向不会向另一主机转发 MCP 配置中的敏感请求头。
     *
     * 使用服务的默认客户端和实际 MCP transport 完成 initialize、initialized 与 tools/list，以覆盖 SDK
     * 在 initialized 通知之后启动的后台 SSE GET 路径。
     */
    @Test
    fun `cross host SSE redirect does not replay MCP headers to the redirect target`() = runBlocking {
        val source = MockWebServer()
        val redirectTarget = MockWebServer()
        val sourceSseRequest = CompletableDeferred<RecordedRequest>()
        val sourcePostRequests = ConcurrentHashMap<String, RecordedRequest>()
        val toolsListRequest = CompletableDeferred<RecordedRequest>()
        val releaseToolsListResponse = CompletableDeferred<Unit>()
        source.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "POST" -> {
                    val method = jsonRpcMethod(request)
                    sourcePostRequests[method] = request
                    when (method) {
                        "initialize" -> jsonRpcResponse(
                            request,
                            """{
                                "protocolVersion":${jsonRpcProtocolVersion(request)},
                                "capabilities":{"tools":{}},
                                "serverInfo":{"name":"test","version":"1.0"}
                            }""".trimIndent(),
                        )

                        "notifications/initialized" -> MockResponse.Builder().code(202).build()
                        "tools/list" -> runBlocking {
                            toolsListRequest.complete(request)
                            // 保持 client 存活，直到测试在 302 已实际写出后确认攻击站未收到重定向请求。
                            // 否则未修复客户端可能在候选清理前尚未来得及跟随重定向，形成假阳性。
                            releaseToolsListResponse.await()
                            jsonRpcResponse(request, mcpToolsResult(MAX_MCP_TOOLS_PER_SERVER + 1))
                        }

                        else -> MockResponse.Builder().code(400).body("unexpected MCP method: $method").build()
                    }
                }

                "GET" -> {
                    sourceSseRequest.complete(request)
                    MockResponse.Builder()
                        .code(302)
                        .addHeader("Location", redirectTarget.url("/stolen"))
                        .build()
                }

                else -> MockResponse.Builder().code(405).build()
            }
        }
        source.start()
        redirectTarget.start()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext))
        val connection = async(Dispatchers.Default) {
            service.connect(
                listOf(
                    MCPServerConfig(
                        name = "server",
                        url = source.url("/mcp").toString(),
                        headers = mapOf(
                            "X-Mcp-Test-Secret" to "header-canary",
                            "Cookie" to "session=cookie-canary",
                        ),
                    ),
                ),
            )
        }

        try {
            val sseRequest = withTimeout(5.seconds) { sourceSseRequest.await() }
            withTimeout(5.seconds) { toolsListRequest.await() }
            assertEquals("GET", sseRequest.method)
            assertEquals("header-canary", sseRequest.headers["X-Mcp-Test-Secret"])
            assertEquals("session=cookie-canary", sseRequest.headers["Cookie"])
            setOf("initialize", "notifications/initialized", "tools/list").forEach { method ->
                val request = assertNotNull(sourcePostRequests[method], "$method must reach the MCP source")
                assertEquals(
                    "header-canary",
                    request.headers["X-Mcp-Test-Secret"],
                    "$method must retain the secret header"
                )
                assertEquals("session=cookie-canary", request.headers["Cookie"], "$method must retain the cookie")
            }
            assertNull(
                redirectTarget.takeRequest(500, TimeUnit.MILLISECONDS),
                "redirect target must not receive a replayed SSE request",
            )
            releaseToolsListResponse.complete(Unit)
            withTimeout(5.seconds) { connection.await() }
            // tools/list 返回了可解析的 canary 工具；超过服务端接受数量时，候选必须失败并绝不发布它。
            assertEquals(emptyList(), service.getAllTools())
        } finally {
            releaseToolsListResponse.complete(Unit)
            connection.cancelAndJoin()
            service.close().join()
            source.close()
            redirectTarget.close()
        }
    }

    /** 验证即使重定向仍指向同一主机，SSE GET 也不会自动转发到目标路径。 */
    @Test
    fun `same host SSE redirect does not request its redirect path`() = runBlocking {
        val source = MockWebServer()
        val sourceSseRequest = CompletableDeferred<RecordedRequest>()
        val redirectPathRequested = CompletableDeferred<RecordedRequest>()
        val toolsListRequest = CompletableDeferred<RecordedRequest>()
        val releaseToolsListResponse = CompletableDeferred<Unit>()
        source.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "POST" -> when (jsonRpcMethod(request)) {
                    "initialize" -> jsonRpcResponse(
                        request,
                        """{
                            "protocolVersion":${jsonRpcProtocolVersion(request)},
                            "capabilities":{"tools":{}},
                            "serverInfo":{"name":"test","version":"1.0"}
                        }""".trimIndent(),
                    )

                    "notifications/initialized" -> MockResponse.Builder().code(202).build()
                    "tools/list" -> runBlocking {
                        toolsListRequest.complete(request)
                        releaseToolsListResponse.await()
                        jsonRpcResponse(request, mcpToolsResult(MAX_MCP_TOOLS_PER_SERVER + 1))
                    }

                    else -> MockResponse.Builder().code(400).build()
                }

                "GET" -> when (request.target) {
                    "/mcp" -> {
                        sourceSseRequest.complete(request)
                        MockResponse.Builder()
                            .code(302)
                            .addHeader("Location", source.url("/redirect-target"))
                            .build()
                    }

                    "/redirect-target" -> {
                        redirectPathRequested.complete(request)
                        MockResponse.Builder().code(500).build()
                    }

                    else -> MockResponse.Builder().code(404).build()
                }

                else -> MockResponse.Builder().code(405).build()
            }
        }
        source.start()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext))
        val connection = async(Dispatchers.Default) {
            service.connect(
                listOf(
                    MCPServerConfig(
                        name = "server",
                        url = source.url("/mcp").toString(),
                        headers = mapOf("X-Mcp-Test-Secret" to "header-canary"),
                    ),
                ),
            )
        }

        try {
            withTimeout(5.seconds) { sourceSseRequest.await() }
            withTimeout(5.seconds) { toolsListRequest.await() }
            assertFalse(
                withTimeoutOrNull(500.milliseconds) { redirectPathRequested.await() } != null,
                "same-host redirect target must not receive an SSE request",
            )
            releaseToolsListResponse.complete(Unit)
            withTimeout(5.seconds) { connection.await() }
            assertEquals(emptyList(), service.getAllTools())
        } finally {
            releaseToolsListResponse.complete(Unit)
            connection.cancelAndJoin()
            service.close().join()
            source.close()
        }
    }

    private fun jsonRpcMethod(request: RecordedRequest): String =
        jsonRpcPayload(request).getValue("method").toString().trim('"')

    private fun jsonRpcProtocolVersion(request: RecordedRequest): String =
        jsonRpcPayload(request)
            .getValue("params")
            .jsonObject
            .getValue("protocolVersion")
            .toString()

    private fun jsonRpcResponse(request: RecordedRequest, result: String): MockResponse {
        val id = jsonRpcPayload(request).getValue("id")
        return MockResponse.Builder()
            .addHeader("Content-Type", "application/json")
            .body("""{"jsonrpc":"2.0","id":$id,"result":$result}""")
            .build()
    }

    private fun jsonRpcPayload(request: RecordedRequest) =
        Json.parseToJsonElement(requireNotNull(request.body) { "MCP request must have a JSON-RPC body" }.utf8())
            .jsonObject

    /** 以真实 Streamable HTTP transport 执行 tools/list，使 wire scanner 覆盖 SDK 解码之前的路径。 */
    private suspend fun runWireMcpConnectionTest(
        contentType: String,
        toolsResult: String,
        expectPublishedTool: String?,
    ) {
        val server = MockWebServer()
        val toolsListRequest = CompletableDeferred<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.method) {
                "POST" -> when (jsonRpcMethod(request)) {
                    "initialize" -> jsonRpcResponse(
                        request,
                        """{
                            "protocolVersion":${jsonRpcProtocolVersion(request)},
                            "capabilities":{"tools":{}},
                            "serverInfo":{"name":"wire-test","version":"1.0"}
                        }""".trimIndent(),
                    )

                    "notifications/initialized" -> MockResponse.Builder().code(202).build()
                    "tools/list" -> {
                        toolsListRequest.complete(request)
                        jsonRpcWireResponse(request, toolsResult, contentType)
                    }

                    else -> MockResponse.Builder().code(400).build()
                }

                // initialized 后 SDK 会尝试建立后台 SSE；本测试仅覆盖 POST inline response，405 会安全关停它。
                "GET", "DELETE" -> MockResponse.Builder().code(405).build()
                else -> MockResponse.Builder().code(405).build()
            }
        }
        server.start()
        val service = MCPClientService(CoroutineScope(EmptyCoroutineContext))
        try {
            withTimeout(5.seconds) {
                service.connect(listOf(MCPServerConfig(name = "wire", url = server.url("/mcp").toString())))
            }
            withTimeout(5.seconds) { toolsListRequest.await() }
            val names = service.getAllTools().map { (_, tool) -> tool.name }
            assertEquals(expectPublishedTool?.let(::listOf).orEmpty(), names)
        } finally {
            service.close().join()
            server.close()
        }
    }

    /** 使用单字节 HTTP chunk 制造 scanner 必须跨边界处理的真实 wire 响应。 */
    private fun jsonRpcWireResponse(request: RecordedRequest, result: String, contentType: String): MockResponse {
        val id = jsonRpcPayload(request).getValue("id")
        val json = """{"jsonrpc":"2.0","id":$id,"result":$result}"""
        val body = if (contentType == "text/event-stream") {
            val splitAt = json.indexOf("\"result\":") + "\"result\":".length
            "event: message\r\ndata: ${json.take(splitAt)}\r\ndata: ${json.drop(splitAt)}\r\n\r\n"
        } else {
            json
        }
        return MockResponse.Builder()
            .addHeader("Content-Type", contentType)
            .chunkedBody(body, 1)
            .build()
    }

    /** 构造超过统一嵌套上限的对象，供 wire 与 close 回归共用。 */
    private fun deeplyNestedJson(depth: Int): String = buildString {
        repeat(depth) { append("{\"next\":") }
        append("\"leaf\"")
        repeat(depth) { append('}') }
    }

    /** 可观察 close 的最小 ResponseBody，用于验证 scanner 错误会释放底层连接。 */
    private class CloseTrackingResponseBody(
        private val contentType: okhttp3.MediaType,
        body: String,
    ) : ResponseBody() {
        private val source = Buffer().writeUtf8(body)
        var closed = false
            private set

        override fun contentType(): okhttp3.MediaType = contentType

        override fun contentLength(): Long = source.size

        override fun source() = source

        override fun close() {
            closed = true
            source.close()
        }
    }

    private fun mcpToolsResult(toolCount: Int): String =
        (0 until toolCount).joinToString(prefix = """{"tools":[""", postfix = "]}") { index ->
            val name = if (index == 0) "redirect-canary-tool" else "tool-$index"
            """{"name":"$name","inputSchema":{"type":"object"}}"""
        }
}
