package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 设置存储结构。
 *
 * @property telegramToken Telegram机器人的Token。
 * @property chatId 当前选择的聊天ID。
 * @property proxy 代理服务器设置。
 */
@Serializable
data class AppSettings(
    val telegramToken: String = "",
    val chatId: String = "",
    val proxy: ProxySettings? = null
)

/**
 * 代理服务器设置。
 */
@Serializable
data class ProxySettings(
    val host: String,
    val port: Int,
    val type: ProxyType,
    val username: String? = null,
    val password: String? = null
)

@Serializable
enum class ProxyType {
    HTTP, SOCKS
}