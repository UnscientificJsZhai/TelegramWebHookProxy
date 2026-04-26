package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import dagger.Subcomponent

@AgentScope
@Subcomponent
interface AgentComponent {
    val geminiAgentService: GeminiAgentService
    val openAIAgentService: OpenAIAgentService

    @Subcomponent.Factory
    interface Factory {
        fun create(): AgentComponent
    }
}
