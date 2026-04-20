package com.unscientificjszhai.tgp.di

import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import dagger.Component
import javax.inject.Singleton

@Singleton
@Component(modules = [AppModule::class])
interface AppComponent {
    val settingsRepository: SettingsRepository

    val telegramService: TelegramService

    val messagePoller: MessagePoller

    @Component.Factory
    interface Factory {
        fun create(appModule: AppModule): AppComponent
    }
}
