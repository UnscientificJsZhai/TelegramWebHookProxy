package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.coroutines.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

/**
 * MCP 客户端连接更新与终态关闭行为的测试设计。
 */
class MCPClientServiceTest {

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
        val result = mockk<CallToolResult>()
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
