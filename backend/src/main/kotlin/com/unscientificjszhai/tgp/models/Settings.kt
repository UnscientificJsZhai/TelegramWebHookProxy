package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val telegramToken: String = "",
    val chatId: String = "",
    val proxy: ProxySettings = ProxySettings()
)

@Serializable
data class ProxySettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8080,
    val type: ProxyType = ProxyType.HTTP,
    val username: String? = null,
    val password: String? = null
)

@Serializable
enum class ProxyType {
    HTTP, SOCKS
}
