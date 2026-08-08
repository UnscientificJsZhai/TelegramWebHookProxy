package com.unscientificjszhai.tgp.di

import io.ktor.server.testing.*
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Agent Dagger 作用域资源隔离的测试设计。
 */
class AgentComponentTest {

    /**
     * 验证 MCP 服务在同一 Agent 组件内复用、在不同组件间隔离。
     *
     * 每个组件拥有独立的 MCP 服务，从而也拥有独立的 HTTP 客户端生命周期。
     */
    @Test
    fun `MCP 服务在同一 Agent 组件复用且不同组件隔离`() = testApplication {
        val appComponent = DaggerAppComponent.factory().create(AppModule(application))
        val firstComponent = appComponent.agentComponentFactory().create()
        val secondComponent = appComponent.agentComponentFactory().create()

        assertSame(firstComponent.mcpClientService, firstComponent.mcpClientService)
        assertNotSame(firstComponent.mcpClientService, secondComponent.mcpClientService)

        runBlocking {
            firstComponent.mcpClientService.close().join()
            secondComponent.mcpClientService.close().join()
        }
    }
}
