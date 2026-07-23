package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.service.ai.MCPClientService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class McpFunctionProviderTest {

    @Test
    fun testMCP工具取消会向调用方透传() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        val provider = McpFunctionProvider(mcpClientService)
        coEvery { mcpClientService.callTool("server", "tool", any()) } throws CancellationException("调用已取消")

        assertFailsWith<CancellationException> {
            provider.execute("server_tool", emptyMap())
        }
    }
}
