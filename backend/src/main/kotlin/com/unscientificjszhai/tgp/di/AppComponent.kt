package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.BotCommandReconciler
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.ScheduledTaskWorker
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import dagger.Component
import javax.inject.Singleton

/**
 * 管理应用单例依赖并创建代理服务子组件的 Dagger 组件。
 */
@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
    /** 协调应用设置持久化、条件写入与生命周期事件。 */
    val settingsChangeCoordinator: SettingsChangeCoordinator

    /** 技能数据的持久化仓库。 */
    val skillRepository: SkillRepository

    /** 与 Telegram Bot API 通信的服务。 */
    val telegramService: TelegramService

    /** 串行收敛当前设置对应 Telegram Bot 命令的应用级协调器。 */
    val botCommandReconciler: BotCommandReconciler

    /** 轮询和处理 Telegram 消息的服务。 */
    val messagePoller: MessagePoller

    /** 扫描并执行已到期定时任务的应用级 worker。 */
    val scheduledTaskWorker: ScheduledTaskWorker

    /**
     * 管理当前 AI 提供商代理的应用级委派服务。
     *
     * 应用停止时必须调用其 [AgentService.close] 并等待返回的任务，以释放当前 Agent 组件持有的资源。
     */
    val agentService: AgentService

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
