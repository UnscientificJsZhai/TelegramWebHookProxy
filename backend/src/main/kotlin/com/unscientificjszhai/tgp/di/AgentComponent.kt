package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import dagger.Subcomponent

/**
 * 管理单个代理作用域内 AI 服务实例的 Dagger 子组件。
 *
 * 通过父级 [AppComponent] 的工厂创建，并在代理作用域结束后废弃。
 */
@AgentScope
@Subcomponent
interface AgentComponent {
    /** Gemini 协议对应的代理服务。 */
    val geminiAgentService: GeminiAgentService

    /** OpenAI 兼容协议对应的代理服务。 */
    val openAIAgentService: OpenAIAgentService

    /** 创建 [AgentComponent] 的 Dagger 子组件工厂。 */
    @Subcomponent.Factory
    interface Factory {
        /**
         * 创建一个新的代理作用域组件。
         *
         * @return 具有独立 [AgentScope] 生命周期的子组件。
         */
        fun create(): AgentComponent
    }
}
