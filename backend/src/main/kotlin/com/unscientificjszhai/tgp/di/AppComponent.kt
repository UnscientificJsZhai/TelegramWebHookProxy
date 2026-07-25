package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
    val settingsRepository: SettingsRepository

    val skillRepository: SkillRepository

    val telegramService: TelegramService

    val messagePoller: MessagePoller

    val taskSchedulerService: TaskSchedulerService

    fun agentComponentFactory(): AgentComponent.Factory

    @Component.Factory
    interface Factory {
        fun create(appModule: AppModule): AppComponent
    }
}
