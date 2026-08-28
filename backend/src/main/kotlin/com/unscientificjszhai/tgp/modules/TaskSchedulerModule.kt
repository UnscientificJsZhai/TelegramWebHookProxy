package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.service.ai.ScheduledTaskWorker
import io.ktor.server.application.*

/**
 * 启动定时任务扫描服务。
 *
 * 应用统一在 [io.ktor.server.application.ApplicationStopPreparing] 中先关闭调度准入并等待其停止；本模块不注册
 * 独立生命周期监听器，以免关闭顺序依赖订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param scheduledTaskWorker 要启动的应用级定时任务 worker。
 */
@Suppress("UnusedReceiverParameter")
fun Application.taskSchedulerModule(scheduledTaskWorker: ScheduledTaskWorker) {
    scheduledTaskWorker.start()
}
