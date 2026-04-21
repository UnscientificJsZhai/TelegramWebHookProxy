package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import io.ktor.server.application.*

fun Application.taskSchedulerModule(appComponent: AppComponent) {
    val taskSchedulerService = appComponent.taskSchedulerService

    monitor.subscribe(ApplicationStopped) {
        taskSchedulerService.close()
    }
}
