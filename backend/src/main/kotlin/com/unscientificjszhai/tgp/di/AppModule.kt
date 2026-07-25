package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.DelegatingAgentService
import dagger.Module
import dagger.Provides
import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import javax.inject.Singleton

/**
 * 提供应用级依赖的 Dagger 模块。
 *
 * 该模块与创建它的 Ktor [Application] 具有相同生命周期。
 *
 * @param application 已初始化且尚未停止的 Ktor 应用实例。
 */
@Module
class AppModule(
    private val application: Application,
) {
    /**
     * 提供与应用生命周期绑定的协程作用域。
     *
     * @return 可用于启动应用级协程的作用域。
     */
    @Provides
    @Singleton
    fun provideCoroutineScope(): CoroutineScope = application

    /**
     * 提供统一调度不同 AI 服务实现的代理服务。
     *
     * @param delegatingAgentService 已注入的代理服务委托实现。
     * @return 以 [AgentService] 接口形式暴露的代理服务。
     */
    @Provides
    @Singleton
    fun provideAgentService(delegatingAgentService: DelegatingAgentService): AgentService = delegatingAgentService
}
