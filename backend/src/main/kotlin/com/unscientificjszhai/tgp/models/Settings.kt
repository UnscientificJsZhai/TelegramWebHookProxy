package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val telegramToken: String = "",
    val chatId: String = "",
    val proxy: ProxySettings? = null,
    val ai: AISettings? = null,
)

@Serializable
data class AISettings(
    val geminiApiKey: String = "",
    val agentEnabled: Boolean = false,
    val agentChatId: String = "",
    val globalContext: String = "",
    val mcpServers: List<MCPServerConfig> = emptyList(),
)

@Serializable
data class MCPServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ProxySettings(
    val host: String,
    val port: Int,
    val type: ProxyType,
    val username: String? = null,
    val password: String? = null,
)

@Serializable
enum class ProxyType {
    HTTP,
    SOCKS,
}
