package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.*
import java.io.IOException
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * MCP 客户端连接更新与终态关闭行为的测试设计。
 */
class MCPClientServiceTest {

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
     * 验证候选批次超时不会在连接锁中等待挂起的旧客户端关闭。
     *
     * 旧连接和超时候选的 `close` 都不返回时，调用方仍必须在批次时限后取得连接锁；可见快照保持为空，
     * 因而超时候选绝不能在稍后重新发布工具。
     */
    @Test
    fun `batch timeout releases connection lock despite hanging client cleanup`() = runBlocking {
        val oldClient = mockk<Client>()
        val stalledCandidate = mockk<Client>()
        val clients = ArrayDeque(listOf(oldClient, stalledCandidate))
        val oldCloseStarted = CompletableDeferred<Unit>()
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
            awaitCancellation()
        }
        coEvery { stalledCandidate.connect(any()) } coAnswers {
            candidateStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { stalledCandidate.close() } coAnswers { awaitCancellation() }

        service.connect(listOf(oldConfig))
        service.connect(listOf(newConfig))

        withTimeout(1.seconds) { oldCloseStarted.await() }
        withTimeout(1.seconds) { candidateStarted.await() }
        withTimeout(1.seconds) { service.connect(emptyList()) }

        assertEquals(emptyList(), service.getAllTools())
        assertFailsWith<IllegalStateException> {
            service.callTool("old", "old_tool", emptyMap())
        }
        coVerify(exactly = 0) { stalledCandidate.listTools() }

        withTimeout(1.seconds) { service.close().join() }
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
}
