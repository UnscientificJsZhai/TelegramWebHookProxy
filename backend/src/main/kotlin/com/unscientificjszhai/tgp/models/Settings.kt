package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 应用运行所需的持久化设置。
 *
 * 未配置的可选功能使用空字符串或 `null` 表示；修改设置后由设置仓库负责持久化。
 *
 * @property telegramToken Telegram Bot 令牌；空字符串表示未配置机器人。
 * @property chatId 默认接收消息的 Telegram 聊天标识；空字符串表示未设置默认聊天。
 * @property proxy AI 服务连接使用的代理设置；`null` 表示不使用代理。
 * @property ai AI 功能设置；`null` 表示未配置 AI 功能。
 */
@Serializable
data class AppSettings(
    val telegramToken: String = "",
    val chatId: String = "",
    val proxy: ProxySettings? = null,
    val ai: AISettings? = null,
)

/**
 * AI 服务及代理功能的持久化设置。
 *
 * @property provider 要使用的 AI 服务提供方。
 * @property geminiApiKey Gemini 服务的 API 密钥；空字符串表示未配置。
 * @property openAiApiKey OpenAI 兼容服务的 API 密钥；空字符串表示未配置。
 * @property openAiBaseUrl OpenAI 兼容服务的基础地址；空字符串表示未配置自定义地址。
 * @property selectedModel 已选择的模型名称；空字符串表示尚未选择模型。
 * @property agentEnabled 是否启用代理功能。
 * @property agentChatId 允许使用代理功能的 Telegram 聊天标识；空字符串表示未指定。
 * @property globalContext 注入每个 AI 会话的全局上下文；允许为空字符串。
 * @property autoCleanContextIntervalMinutes 自动清理会话上下文的间隔，单位为分钟；`0` 表示不自动清理。
 * @property silentContextCleanup 是否在自动清理上下文时保持静默。
 * @property mcpServers 要连接的 MCP 服务器配置；空列表表示不连接 MCP 服务器。
 */
@Serializable
data class AISettings(
    val provider: AIProvider = AIProvider.GEMINI,
    val geminiApiKey: String = "",
    val openAiApiKey: String = "",
    val openAiBaseUrl: String = "",
    val selectedModel: String = "",
    val agentEnabled: Boolean = false,
    val agentChatId: String = "",
    val globalContext: String = "",
    val autoCleanContextIntervalMinutes: Int = 0,
    val silentContextCleanup: Boolean = false,
    val mcpServers: List<MCPServerConfig> = emptyList(),
)

/**
 * 可选的 AI 服务提供方。
 */
@Serializable
enum class AIProvider {
    /** Google Gemini 服务。 */
    GEMINI,

    /** OpenAI 及其兼容服务。 */
    OPENAI
}

/**
 * MCP 服务器的连接配置。
 *
 * @property name 服务器显示名称；用于在连接集合中标识该服务器。
 * @property url MCP 服务器的连接地址；必须是客户端支持的 URL。
 * @property headers 连接请求附加的 HTTP 请求头；空映射表示不附加请求头。
 */
@Serializable
data class MCPServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/**
 * 代理服务器的连接设置。
 *
 * @property host 代理服务器主机名或 IP 地址；不得为空。
 * @property port 代理服务器端口；必须在 `1..65535` 范围内。
 * @property type 代理协议类型。
 * @property username 代理认证用户名；`null` 表示不提供用户名。
 * @property password 代理认证密码；`null` 表示不提供密码。
 */
@Serializable
data class ProxySettings(
    val host: String,
    val port: Int,
    val type: ProxyType,
    val username: String? = null,
    val password: String? = null,
)

/**
 * 支持的代理协议类型。
 */
@Serializable
enum class ProxyType {
    /** HTTP 代理协议。 */
    HTTP,

    /** SOCKS 代理协议。 */
    SOCKS,
}
