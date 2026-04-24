package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import dagger.Module
import dagger.Provides
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

@Module
class AppModule(
    private val application: Application,
) {
    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope = application

    @Provides
    @Singleton
    fun provideAgentService(geminiAgentService: GeminiAgentService): AgentService = geminiAgentService
}
