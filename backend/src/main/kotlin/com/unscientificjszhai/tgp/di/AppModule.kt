package com.unscientificjszhai.tgp.di

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
}
