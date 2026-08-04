package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
     * 验证名称中的下划线通过安全别名绑定保留，不会被执行路径重新拆分。
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

        val alias = provider.providedFunctions.single().name().get()
        assertTrue(alias.matches(Regex("mcp_[A-Za-z0-9_-]{43}")))
        provider.execute(alias, emptyMap())

        coVerify(exactly = 1) { mcpClientService.callTool("server_name", "tool_name", emptyMap()) }
    }

    /**
     * 验证 Unicode、空格和超长原始工具名仅以固定长度别名暴露，并仍绑定到原始工具调用。
     */
    @Test
    fun `unsafe raw MCP tool names use fixed safe aliases and retain their binding`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        val rawToolName = "工具 名称 " + "x".repeat(256)
        every { mcpClientService.getAllTools() } returns listOf("服务 名称" to tool(rawToolName))
        coEvery { mcpClientService.callTool("服务 名称", rawToolName, emptyMap()) } returns CallToolResult(emptyList())
        val provider = McpFunctionProvider(mcpClientService)

        val alias = provider.providedFunctions.single().name().get()
        assertEquals(47, alias.length)
        assertTrue(alias.matches(Regex("mcp_[A-Za-z0-9_-]{43}")))
        assertFalse(alias.contains("服务"))
        provider.execute(alias, emptyMap())

        coVerify(exactly = 1) { mcpClientService.callTool("服务 名称", rawToolName, emptyMap()) }
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

        val oldAlias = provider.providedFunctions.single().name().get()
        tools = listOf("server" to tool("new_tool"))

        assertTrue(provider.canHandle(oldAlias))
        verify(exactly = 1) { mcpClientService.getAllTools() }

        val newAlias = provider.providedFunctions.single().name().get()
        assertFalse(provider.canHandle(oldAlias))
        assertTrue(provider.canHandle(newAlias))
        assertEquals(
            "mcp_tool_unavailable",
            provider.execute(oldAlias, emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
    }

    /**
     * 验证别名声明按别名稳定排序，与 MCP 工具发现顺序无关。
     */
    @Test
    fun `MCP aliases are declared in stable alias order`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        var tools = listOf("server" to tool("second"), "server" to tool("first"))
        every { mcpClientService.getAllTools() } answers { tools }
        val provider = McpFunctionProvider(
            mcpClientService,
            McpToolAliasGenerator { _, rawToolName -> if (rawToolName == "first") "alias_a" else "alias_z" },
        )

        assertEquals(listOf("alias_a", "alias_z"), provider.providedFunctions.map { it.name().get() })
        tools = tools.reversed()
        assertEquals(listOf("alias_a", "alias_z"), provider.providedFunctions.map { it.name().get() })
    }

    /**
     * 验证异常或不符合模型函数名规则的别名只移除对应候选，不影响其他 MCP 工具。
     */
    @Test
    fun `invalid or failed MCP alias generation skips only its candidate`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server" to tool("invalid"),
            "server" to tool("failed"),
            "server" to tool("safe"),
        )
        val provider = McpFunctionProvider(
            mcpClientService,
            McpToolAliasGenerator { _, rawToolName ->
                when (rawToolName) {
                    "invalid" -> "invalid alias"
                    "failed" -> throw IllegalStateException("alias generator failure")
                    else -> "safe_alias"
                }
            },
        )

        assertEquals(listOf("safe_alias"), provider.providedFunctions.map { it.name().get() })
    }

    /** 验证恶意深层 MCP schema 在递归 SDK 转换前被拒绝，且不会阻断其他刷新路径。 */
    @Test
    fun `deep MCP schema is rejected without stack overflow`() = runTest {
        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(JsonStructureLimits.MAX_DEPTH + 1) { index ->
            nested = JsonObject(linkedMapOf("level-$index" to nested))
        }
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server" to Tool("deep", ToolSchema(properties = JsonObject(linkedMapOf("nested" to nested)))),
        )
        val provider = McpFunctionProvider(mcpClientService)

        assertTrue(provider.providedFunctions.isEmpty())
        coVerify(exactly = 0) { mcpClientService.callTool(any(), any(), any()) }
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
        val provider = McpFunctionProvider(mcpClientService, McpToolAliasGenerator { _, _ -> collidingAlias })

        assertTrue(provider.providedFunctions.isEmpty())
        assertFalse(provider.canHandle(collidingAlias))
        assertEquals(
            "mcp_tool_unavailable",
            provider.execute(collidingAlias, emptyMap())["error"]?.toString()?.removeSurrounding("\"")
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
        val alias = provider.providedFunctions.single().name().get()
        val failed = provider.execute(alias, emptyMap())

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
        val alias = provider.providedFunctions.single().name().get()
        coEvery { mcpClientService.callTool("server", "tool", any()) } throws CancellationException("调用已取消")

        assertFailsWith<CancellationException> {
            provider.execute(alias, emptyMap())
        }
    }

    /**
     * 验证 MCP 声明不会按列表顺序覆盖其他本地提供者的同名声明。
     */
    @Test
    fun `MCP and local provider collisions are not declared or executed`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        val mcpProvider = McpFunctionProvider(mcpClientService, McpToolAliasGenerator { _, _ -> collidingAlias })
        val localProvider = FixedFunctionProvider(collidingAlias)
        val router = LocalFunctionRouter(listOf(mcpProvider, localProvider))
        val routeSnapshot = router.refresh()

        assertTrue(routeSnapshot.providedFunctions().isEmpty())
        assertFalse(routeSnapshot.canHandle(collidingAlias))
        assertFailsWith<IllegalArgumentException> {
            routeSnapshot.execute(collidingAlias, emptyMap())
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
            declaredTurn.execute(declaredTurn.providedFunctions().single().name().get(), emptyMap())
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
        val collidingAlias = "mcp_" + "a".repeat(43)

        fun tool(name: String): Tool = Tool(name, ToolSchema())
    }
}
