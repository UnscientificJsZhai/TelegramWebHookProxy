package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import io.ktor.server.application.*

fun Application.messagePollerModule(appComponent: AppComponent) {
    val messagePoller = appComponent.messagePoller.apply { start() }

    monitor.subscribe(ApplicationStopped) {
        messagePoller.close()
    }
}
