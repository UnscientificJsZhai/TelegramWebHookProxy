package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.service.MessagePoller
import io.ktor.server.application.*

/**
 * 启动消息轮询服务。
 *
 * 应用统一在 [io.ktor.server.application.ApplicationStopPreparing] 中先关闭轮询准入并等待其停止；本模块不注册
 * 独立生命周期监听器，以免关闭顺序依赖订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param messagePoller 要启动的应用级消息轮询 worker。
 */
@Suppress("UnusedReceiverParameter")
fun Application.messagePollerModule(messagePoller: MessagePoller) {
    messagePoller.start()
}
