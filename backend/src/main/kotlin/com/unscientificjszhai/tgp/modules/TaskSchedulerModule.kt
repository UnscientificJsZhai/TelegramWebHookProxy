package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import io.ktor.server.application.*

/**
 * 注册应用停止监听器，以关闭定时任务服务。
 *
 * 调用一次即可；重复调用会重复注册停止监听器。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param appComponent 提供定时任务服务的应用级组件。
 */
fun Application.taskSchedulerModule(appComponent: AppComponent) {
    val taskSchedulerService = appComponent.taskSchedulerService

    monitor.subscribe(ApplicationStopped) {
        taskSchedulerService.close()
    }
}
