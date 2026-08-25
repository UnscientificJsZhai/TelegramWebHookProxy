package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.HttpCallTarget
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.service.SettingsUpdate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Agent 工具执行上下文的不可变快照测试。
 */
class AgentToolExecutionContextTest {
    /**
     * 验证上下文会逐层复制 HTTP 目标和 CIDR 列表，不受原始可变列表随后变更影响。
     */
    @Test
    fun `context deep copies HTTP targets and allowed CIDRs`() {
        val allowedCidrs = mutableListOf("127.0.0.1/32")
        val targets = mutableListOf(
            HttpCallTarget(
                id = "fixed",
                scheme = "http",
                host = "127.0.0.1",
                port = 8080,
                path = "/fixed",
                allowedCidrs = allowedCidrs,
            ),
        )
        val context = AgentToolExecutionContext.from(
            SettingsUpdate(
                settings = AppSettings(
                    telegramToken = "100:token-a",
                    ai = AISettings(httpToolSettings = HttpToolSettings(enabled = true, targets = targets)),
                ),
                version = 7,
                switchGeneration = null,
            ),
        )

        targets.clear()
        allowedCidrs.clear()

        assertEquals(7, context.settingsVersion)
        assertEquals(listOf("127.0.0.1/32"), context.httpToolSettings?.targets?.single()?.allowedCidrs)
    }
}
