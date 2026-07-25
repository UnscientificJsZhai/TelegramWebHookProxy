package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import dagger.Component
import javax.inject.Singleton

/**
 * 管理应用单例依赖并创建代理服务子组件的 Dagger 组件。
 */
@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
    /** 应用设置的持久化仓库。 */
    val settingsRepository: SettingsRepository

    /** 技能数据的持久化仓库。 */
    val skillRepository: SkillRepository

    /** 与 Telegram Bot API 通信的服务。 */
    val telegramService: TelegramService

    /** 轮询和处理 Telegram 消息的服务。 */
    val messagePoller: MessagePoller

    /** 创建和执行定时任务的服务。 */
    val taskSchedulerService: TaskSchedulerService

    /**
     * 返回用于创建代理服务子组件的工厂。
     *
     * @return 每次调用均可创建独立代理作用域的工厂。
     */
    fun agentComponentFactory(): AgentComponent.Factory

    /** 创建 [AppComponent] 的 Dagger 工厂。 */
    @Component.Factory
    interface Factory {
        /**
         * 使用应用模块创建应用级组件。
         *
         * @param appModule 为当前 Ktor 应用提供依赖的模块。
         * @return 管理应用单例依赖的组件。
         */
        fun create(appModule: AppModule): AppComponent
    }
}
