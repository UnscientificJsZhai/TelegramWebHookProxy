package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * MCP 函数提供者的声明快照、路由及失败关闭测试设计。
 */
class McpFunctionProviderTest {
    /**
     * 验证名称中的下划线通过已发布绑定保留，不会被执行路径重新拆分。
     */
    @Test
    fun `underscored server and tool names use the published binding`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server_name" to tool("tool_name"))
        coEvery {
            mcpClientService.callTool(
                "server_name",
                "tool_name",
                emptyMap()
            )
        } returns CallToolResult(emptyList())
        val provider = McpFunctionProvider(mcpClientService)

        assertEquals(listOf("server_name_tool_name"), provider.providedFunctions.map { it.name().get() })
        provider.execute("server_name_tool_name", emptyMap())

        coVerify(exactly = 1) { mcpClientService.callTool("server_name", "tool_name", emptyMap()) }
    }

    /**
     * 验证只读取最近一次声明刷新发布的 MCP 快照，检查名称不会隐式刷新。
     */
    @Test
    fun `canHandle does not refresh and a refresh retires stale bindings`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        var tools = listOf("server" to tool("old_tool"))
        every { mcpClientService.getAllTools() } answers { tools }
        val provider = McpFunctionProvider(mcpClientService)

        provider.providedFunctions
        tools = listOf("server" to tool("new_tool"))

        assertTrue(provider.canHandle("server_old_tool"))
        assertFalse(provider.canHandle("server_new_tool"))
        verify(exactly = 1) { mcpClientService.getAllTools() }

        assertEquals(listOf("server_new_tool"), provider.providedFunctions.map { it.name().get() })
        assertFalse(provider.canHandle("server_old_tool"))
        assertTrue(provider.canHandle("server_new_tool"))
        assertEquals(
            "mcp_tool_unavailable",
            provider.execute("server_old_tool", emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
    }

    /**
     * 验证内部名称碰撞会从声明和执行路由中整体移除。
     */
    @Test
    fun `internal MCP name collisions fail closed`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server_part" to tool("tool"),
            "server" to tool("part_tool"),
        )
        val provider = McpFunctionProvider(mcpClientService)

        assertTrue(provider.providedFunctions.isEmpty())
        assertFalse(provider.canHandle("server_part_tool"))
        assertEquals(
            "mcp_tool_unavailable",
            provider.execute("server_part_tool", emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
        coVerify(exactly = 0) { mcpClientService.callTool(any(), any(), any()) }
    }

    /**
     * 验证关闭或底层异常不会向模型泄露连接细节。
     */
    @Test
    fun `missing and failed MCP calls return the same stable error`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        coEvery { mcpClientService.callTool("server", "tool", any()) } throws IllegalStateException("secret endpoint")
        val provider = McpFunctionProvider(mcpClientService)

        assertEquals(
            "mcp_tool_unavailable",
            provider.execute("missing", emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
        provider.providedFunctions
        val failed = provider.execute("server_tool", emptyMap())

        assertEquals("mcp_tool_unavailable", failed["error"]?.toString()?.removeSurrounding("\""))
        assertFalse(failed.toString().contains("secret endpoint"))
    }

    /**
     * 验证 MCP 工具调用取消会原样传递给函数调用方。
     */
    @Test
    fun `MCP tool cancellation propagates to the caller`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        val provider = McpFunctionProvider(mcpClientService)
        provider.providedFunctions
        coEvery { mcpClientService.callTool("server", "tool", any()) } throws CancellationException("调用已取消")

        assertFailsWith<CancellationException> {
            provider.execute("server_tool", emptyMap())
        }
    }

    /**
     * 验证 MCP 声明不会按列表顺序覆盖其他本地提供者的同名声明。
     */
    @Test
    fun `MCP and local provider collisions are not declared or executed`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        val mcpProvider = McpFunctionProvider(mcpClientService)
        val localProvider = FixedFunctionProvider("server_tool")
        val router = LocalFunctionRouter(listOf(mcpProvider, localProvider))
        val routeSnapshot = router.refresh()

        assertTrue(routeSnapshot.providedFunctions().isEmpty())
        assertFalse(routeSnapshot.canHandle("server_tool"))
        assertFailsWith<IllegalArgumentException> {
            routeSnapshot.execute("server_tool", emptyMap())
        }
        coVerify(exactly = 0) { mcpClientService.callTool(any(), any(), any()) }
        assertFalse(localProvider.executed)
    }

    /**
     * 验证已向模型声明的函数在后续刷新后仍调用原始 MCP 服务器和工具名称。
     */
    @Test
    fun `a route snapshot keeps the MCP binding declared for its model turn`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        var tools = listOf("server_old" to tool("tool"))
        every { mcpClientService.getAllTools() } answers { tools }
        coEvery { mcpClientService.callTool("server_old", "tool", emptyMap()) } returns CallToolResult(emptyList())
        coEvery { mcpClientService.callTool("server", "old_tool", emptyMap()) } returns CallToolResult(emptyList())
        val router = LocalFunctionRouter(listOf(McpFunctionProvider(mcpClientService)))
        val declarationPublished = CompletableDeferred<LocalFunctionRouteSnapshot>()
        val allowToolInvocation = CompletableDeferred<Unit>()
        val modelTurn = async {
            val declaredTurn = router.refresh()
            declarationPublished.complete(declaredTurn)
            allowToolInvocation.await()
            declaredTurn.execute("server_old_tool", emptyMap())
        }

        declarationPublished.await()
        tools = listOf("server" to tool("old_tool"))
        router.refresh()
        allowToolInvocation.complete(Unit)

        modelTurn.await()

        coVerify(exactly = 1) { mcpClientService.callTool("server_old", "tool", emptyMap()) }
        coVerify(exactly = 0) { mcpClientService.callTool("server", "old_tool", any()) }
    }

    private class FixedFunctionProvider(
        name: String,
    ) : LocalFunctionProvider() {
        override val providedFunctions: List<FunctionDeclaration> = listOf(
            FunctionDeclaration.builder()
                .name(name)
                .parameters(Schema.builder().build())
                .build(),
        )
        var executed = false

        override suspend fun execute(functionName: String, args: Map<String, Any?>) = buildJsonObject {
            executed = true
        }
    }

    private companion object {
        fun tool(name: String): Tool = Tool(name, ToolSchema())
    }
}
