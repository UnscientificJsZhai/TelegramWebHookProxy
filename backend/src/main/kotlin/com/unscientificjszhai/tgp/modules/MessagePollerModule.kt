package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import io.ktor.server.application.*

/**
 * 启动消息轮询服务，并在应用停止时关闭该服务。
 *
 * 调用一次即可；重复调用会重复注册停止监听器。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param appComponent 提供消息轮询服务的应用级组件。
 */
fun Application.messagePollerModule(appComponent: AppComponent) {
    val messagePoller = appComponent.messagePoller.apply { start() }

    monitor.subscribe(ApplicationStopped) {
        messagePoller.close()
    }
}
