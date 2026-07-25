package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * MCP 客户端连接更新行为的测试设计。
 */
class MCPClientServiceTest {

    /**
     * 验证同名 MCP 配置更新时的重连设计。
     *
     * 验证旧连接会关闭，且服务会使用新配置建立连接。
     */
    @Test
    fun test同名MCP配置变更会关闭旧连接后重新连接() = kotlinx.coroutines.runBlocking {
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

        val closeJob: Job = service.disconnectAll()
        withTimeout(5.seconds) { closeJob.join() }
        coVerify(exactly = 1) { secondClient.close() }
    }
}
