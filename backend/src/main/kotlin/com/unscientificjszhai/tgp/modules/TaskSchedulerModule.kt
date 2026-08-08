package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import io.ktor.server.application.*

/**
 * 启动定时任务扫描服务。
 *
 * 应用统一在 [io.ktor.server.application.ApplicationStopPreparing] 中先关闭调度准入并等待其停止；本模块不注册
 * 独立生命周期监听器，以免关闭顺序依赖订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param appComponent 提供定时任务服务的应用级组件。
 */
@Suppress("UnusedReceiverParameter")
fun Application.taskSchedulerModule(appComponent: AppComponent) {
    appComponent.taskSchedulerService.start()
}
