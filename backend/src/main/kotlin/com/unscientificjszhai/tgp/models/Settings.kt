package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale

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
 * @property httpToolSettings 模型 HTTP 工具的受限出站设置；默认禁用且不声明工具。
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
    val httpToolSettings: HttpToolSettings = HttpToolSettings(),
)

/**
 * 模型 HTTP 工具的持久化边界设置。
 *
 * 工具仅在 [enabled] 为 `true` 且至少存在一个合法目标时声明。请求超时和并发数仅可在
 * 本类型规定的硬上限内配置，目标以完整固定的 URL 组成部分表示，模型不能覆盖它们。
 *
 * @property enabled 是否向模型声明 HTTP 工具；`false` 时任何调用都会被拒绝。
 * @property targets 允许调用的固定 HTTP 目标；为空时不声明工具。
 * @property requestTimeoutMillis 单次 HTTP 请求超时，单位为毫秒，必须在 `1..30000` 范围内。
 * @property maxConcurrentRequests 此提供者允许的并发 HTTP 请求数，必须在 `1..4` 范围内。
 */
@Serializable
data class HttpToolSettings(
    val enabled: Boolean = false,
    val targets: List<HttpCallTarget> = emptyList(),
    val requestTimeoutMillis: Long = 10_000,
    val maxConcurrentRequests: Int = 2,
)

/**
 * 由模型 HTTP 工具调用的精确固定目标。
 *
 * [scheme]、[host]、[port]、[path] 和 [method] 共同组成唯一请求地址。模型只能传递
 * [id] 和 POST 请求的 JSON 文本，不能添加查询参数、请求头或覆盖任何目标字段。
 *
 * @property id 目标的稳定标识，长度为 `1..64`，只能包含 ASCII 字母、数字、`-` 和 `_`。
 * @property scheme 固定协议，只能为小写 `https` 或受限的 `http`。
 * @property host 固定的裸主机名或 IP 地址，不含 URL、端口、通配符或用户信息。
 * @property port 固定端口，必须在 `1..65535` 范围内。
 * @property path 固定的绝对路径，必须以 `/` 开始且不含查询、片段、百分号编码或路径遍历段。
 * @property method 固定请求方法；v1 仅支持 GET 和 POST。
 * @property allowedCidrs 允许 DNS 结果使用的精确单 IP CIDR 例外；每项只能是 `/32` 或 `/128`。
 */
@Serializable
data class HttpCallTarget(
    val id: String,
    val scheme: String = "https",
    val host: String,
    val port: Int = 443,
    val path: String,
    val method: HttpToolMethod = HttpToolMethod.GET,
    val allowedCidrs: List<String> = emptyList(),
)

/**
 * HTTP 工具目标允许的固定请求方法。
 */
@Serializable
enum class HttpToolMethod {
    /** 不带请求体的读取请求。 */
    GET,

    /** 仅允许携带受限 JSON 请求体的写入请求。 */
    POST,
}

/**
 * 校验模型 HTTP 工具配置是否满足出站边界。
 *
 * 校验不进行 DNS 查询或网络 I/O。调用方必须在接收配置和持久化配置前各调用一次；运行时
 * 仍会在实际 DNS 查询与执行前重新校验快照。
 *
 * @param settings 要校验的 HTTP 工具设置；即使 [HttpToolSettings.enabled] 为 `false`，其中已
 * 配置的目标也必须满足相同的语法和边界约束。
 * @throws IllegalArgumentException 目标、CIDR、超时或并发数不符合固定边界时抛出。
 */
fun validateHttpToolSettings(settings: HttpToolSettings) {
    require(settings.targets.size <= MAX_HTTP_TOOL_TARGETS) { "HTTP 工具目标不能超过 $MAX_HTTP_TOOL_TARGETS 个。" }
    require(settings.requestTimeoutMillis in 1..MAX_HTTP_TOOL_TIMEOUT_MILLIS) {
        "HTTP 工具超时必须在 1..$MAX_HTTP_TOOL_TIMEOUT_MILLIS 毫秒范围内。"
    }
    require(settings.maxConcurrentRequests in 1..MAX_HTTP_TOOL_CONCURRENCY) {
        "HTTP 工具并发数必须在 1..$MAX_HTTP_TOOL_CONCURRENCY 范围内。"
    }

    val ids = HashSet<String>()
    settings.targets.forEach { target ->
        validateHttpCallTarget(target)
        require(ids.add(target.id)) { "HTTP 工具目标标识不能重复。" }
    }
}

/**
 * 校验单个模型 HTTP 工具目标是否为精确、可隔离的固定目标。
 *
 * @param target 要校验的固定目标。
 * @throws IllegalArgumentException 目标包含 URL 可变部分、不受支持的方法或不安全的 HTTP 例外时抛出。
 */
fun validateHttpCallTarget(target: HttpCallTarget) {
    require(target.id.length in 1..MAX_HTTP_TOOL_TARGET_ID_LENGTH && target.id.all(::isHttpToolIdCharacter)) {
        "HTTP 工具目标标识格式不合法。"
    }
    require(target.scheme == HTTPS_SCHEME || target.scheme == HTTP_SCHEME) { "HTTP 工具仅支持 https 或受限 http。" }
    require(target.host.length in 1..MAX_HTTP_TOOL_HOST_LENGTH && isValidHttpToolHost(target.host)) {
        "HTTP 工具主机必须是固定的裸主机名或 IP 地址。"
    }
    require(target.port in 1..65535) { "HTTP 工具端口必须在 1..65535 范围内。" }
    require(target.path.length in 1..MAX_HTTP_TOOL_PATH_LENGTH && isValidHttpToolPath(target.path)) {
        "HTTP 工具路径必须是固定的绝对路径，且不能包含查询、片段、百分号编码或路径遍历。"
    }
    val bracketLength = if (target.host.contains(':')) 2 else 0
    val urlLength = target.scheme.length + 3 + target.host.length + bracketLength +
            1 + target.port.toString().length + target.path.length
    require(urlLength <= MAX_HTTP_TOOL_URL_LENGTH) { "HTTP 工具固定 URL 不能超过 $MAX_HTTP_TOOL_URL_LENGTH 个字符。" }
    require(target.allowedCidrs.size <= MAX_HTTP_TOOL_CIDR_EXCEPTIONS) {
        "HTTP 工具 CIDR 例外不能超过 $MAX_HTTP_TOOL_CIDR_EXCEPTIONS 个。"
    }
    val cidrs = target.allowedCidrs.map(::parseExactHttpToolCidr)
    require(cidrs.distinct().size == cidrs.size) { "HTTP 工具 CIDR 例外不能重复。" }

    if (target.scheme == HTTP_SCHEME) {
        val hostAddress = parseHttpToolLiteralAddress(target.host)
        require(hostAddress != null && (target.host == LOOPBACK_IPV4 || target.host == LOOPBACK_IPV6)) {
            "HTTP 工具仅允许精确的 127.0.0.1 或 ::1 目标。"
        }
        require(cidrs.size == 1 && cidrs.single().address == hostAddress) {
            "HTTP loopback 目标必须配置与主机完全一致的单 IP CIDR 例外。"
        }
    }
}

internal const val MAX_HTTP_TOOL_TARGETS = 32
internal const val MAX_HTTP_TOOL_TIMEOUT_MILLIS = 30_000L
internal const val MAX_HTTP_TOOL_CONCURRENCY = 4
internal const val MAX_HTTP_TOOL_TARGET_ID_LENGTH = 64
internal const val MAX_HTTP_TOOL_HOST_LENGTH = 253
internal const val MAX_HTTP_TOOL_PATH_LENGTH = 2_048
internal const val MAX_HTTP_TOOL_URL_LENGTH = 4_096
internal const val MAX_HTTP_TOOL_CIDR_EXCEPTIONS = 8
internal const val HTTPS_SCHEME = "https"
internal const val HTTP_SCHEME = "http"
internal const val LOOPBACK_IPV4 = "127.0.0.1"
internal const val LOOPBACK_IPV6 = "::1"

internal data class ExactHttpToolCidr(val address: InetAddress)

internal fun parseExactHttpToolCidr(value: String): ExactHttpToolCidr {
    require(value.length in 4..MAX_HTTP_TOOL_HOST_LENGTH && value.count { it == '/' } == 1) {
        "HTTP 工具 CIDR 必须是精确的单 IP CIDR。"
    }
    val separator = value.indexOf('/')
    val addressText = value.substring(0, separator)
    val prefixText = value.substring(separator + 1)
    val address = parseHttpToolLiteralAddress(addressText)
        ?: throw IllegalArgumentException("HTTP 工具 CIDR 必须使用字面 IP 地址。")
    val expectedPrefix = address.address.size * 8
    require(prefixText.toIntOrNull() == expectedPrefix && prefixText.all(Char::isDigit)) {
        "HTTP 工具 CIDR 例外必须是精确的 /$expectedPrefix 单 IP 地址。"
    }
    return ExactHttpToolCidr(address)
}

internal fun parseHttpToolLiteralAddress(value: String): InetAddress? = when {
    isValidHttpToolIpv4Address(value) ->
        InetAddress.getByAddress(value.split('.').map(String::toInt).map(Int::toByte).toByteArray())

    isValidIpv6Address(value) -> runCatching { InetAddress.getByName(value) }
        .getOrNull()
        ?.takeIf { it is Inet6Address && !it.isIpv4MappedAddress() }

    else -> null
}

private fun isHttpToolIdCharacter(character: Char): Boolean =
    character.isAsciiLetterOrDigit() || character == '-' || character == '_'

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private fun isValidHttpToolHost(host: String): Boolean =
    !host.contains('*') &&
            !host.any { it.isWhitespace() || it.isISOControl() } &&
            !host.contains("://") &&
            !host.any { it == '/' || it == '?' || it == '#' || it == '@' || it == '[' || it == ']' } &&
            when {
                host.contains(':') -> parseHttpToolLiteralAddress(host) is Inet6Address
                host.all { it.isDigit() || it == '.' } -> isValidHttpToolIpv4Address(host) || isValidHostname(host)
                else -> isValidHostname(host)
            }

private fun isValidHttpToolPath(path: String): Boolean {
    if (!path.startsWith('/') || path.contains('?') || path.contains('#') || path.contains('*') || path.contains('%')) return false
    if (path.any { it.isWhitespace() || it.isISOControl() || it == '\\' }) return false
    return path.split('/').none { it == "." || it == ".." }
}

private fun isValidHttpToolIpv4Address(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
                octet.all(Char::isDigit) &&
                (octet == "0" || !octet.startsWith('0')) &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun Inet6Address.isIpv4MappedAddress(): Boolean {
    val bytes = address
    return bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
}

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
 * @property name 服务器显示名称；列表内精确唯一，长度为 `1..64`，且仅可使用 ASCII 字母、数字、`_` 与 `-`。
 * @property url MCP 服务器的连接地址；必须为无用户信息、无片段且包含主机名的绝对 `http` 或 `https` URL。
 * @property headers 连接请求附加的 HTTP 请求头；名称必须是 HTTP token，值必须为可见 ASCII 字符，且不能覆盖
 * 路由控制请求头；空映射表示不附加请求头。
 */
@Serializable
data class MCPServerConfig(
    val name: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
)

/** 校验设置中直接接收的文本字段不会超过入口与持久化资源边界。 */
internal fun validateAppSettingsResourceLimits(settings: AppSettings) {
    require(settings.telegramToken.utf8ByteSize() <= 256) { "Telegram 令牌不能超过 256 字节。" }
    require(settings.chatId.utf8ByteSize() <= 64) { "聊天标识不能超过 64 字节。" }
    settings.proxy?.let { proxy ->
        require((proxy.username?.utf8ByteSize() ?: 0) <= 512) { "代理用户名不能超过 512 字节。" }
        require((proxy.password?.utf8ByteSize() ?: 0) <= 512) { "代理密码不能超过 512 字节。" }
    }
    settings.ai?.let { ai ->
        listOf(ai.geminiApiKey, ai.openAiApiKey).forEach { key ->
            require(key.utf8ByteSize() <= 512) { "API 密钥不能超过 512 字节。" }
        }
        require(ai.openAiBaseUrl.utf8ByteSize() <= 2 * 1024) { "OpenAI 基础地址不能超过 2 KiB。" }
        require(ai.selectedModel.utf8ByteSize() <= 256) { "模型名称不能超过 256 字节。" }
        require(ai.agentChatId.utf8ByteSize() <= 64) { "代理聊天标识不能超过 64 字节。" }
        require(ai.globalContext.utf8ByteSize() <= 64 * 1024) { "全局上下文不能超过 64 KiB。" }
    }
}

private fun String.utf8ByteSize(): Int = toByteArray(StandardCharsets.UTF_8).size

/**
 * 校验 MCP 服务器配置是否满足持久化、连接和工具发现共用的安全边界。
 *
 * 配置名称在列表内精确唯一且只能使用安全 ASCII 字符；每个 URL 都必须是没有用户信息或片段的绝对、小写
 * `http` 或 `https` 地址。请求头会作为固定的 MCP 连接头，名称必须是 HTTP token，值只能使用可见 ASCII
 * 字符，并且不得覆盖路由控制请求头。
 *
 * @param configs 要校验的 MCP 服务器配置列表；最多包含 [MAX_MCP_SERVER_CONFIGS] 项。
 * @throws IllegalArgumentException 服务器数量、名称、URL 或请求头不符合 MCP 连接边界时抛出。
 */
fun validateMcpServerConfigs(configs: List<MCPServerConfig>) {
    require(configs.size <= MAX_MCP_SERVER_CONFIGS) { "MCP 服务器不能超过 $MAX_MCP_SERVER_CONFIGS 个。" }
    val names = HashSet<String>()
    configs.forEach { config ->
        require(config.name.length in 1..MAX_MCP_SERVER_NAME_LENGTH && config.name.all(::isMcpServerNameCharacter)) {
            "MCP 服务器名称必须是长度为 1..$MAX_MCP_SERVER_NAME_LENGTH 的 ASCII 字母、数字、下划线或连字符。"
        }
        require(names.add(config.name)) { "MCP 服务器名称不能重复。" }
        validateMcpServerUrl(config.url)
        validateMcpHeaders(config.headers)
    }
}

/** MCP 服务器数量上限。 */
const val MAX_MCP_SERVER_CONFIGS = 16

/** MCP 服务器名称最大 ASCII 字符数。 */
const val MAX_MCP_SERVER_NAME_LENGTH = 64

/** MCP 服务器 URL 最大 UTF-16 字符数。 */
const val MAX_MCP_SERVER_URL_LENGTH = 2_048

/** 单个 MCP 服务器允许的请求头数量上限。 */
const val MAX_MCP_SERVER_HEADERS = 32

/** 单个 MCP 请求头名称最大 UTF-8 字节数。 */
const val MAX_MCP_HEADER_NAME_LENGTH = 128

/** 单个 MCP 请求头值最大 UTF-8 字节数。 */
const val MAX_MCP_HEADER_VALUE_LENGTH = 4_096

/** 单个 MCP 服务器全部请求头 UTF-8 字节数上限。 */
const val MAX_MCP_HEADERS_TOTAL_BYTES = 16_384

private fun validateMcpServerUrl(value: String) {
    require(value.length in 1..MAX_MCP_SERVER_URL_LENGTH) {
        "MCP 服务器 URL 长度必须在 1..$MAX_MCP_SERVER_URL_LENGTH 个字符范围内。"
    }
    require(value.none { it.isWhitespace() || it.isISOControl() }) { "MCP 服务器 URL 不能包含空白或控制字符。" }
    val url = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("MCP 服务器 URL 格式不合法。") }
    require(url.isAbsolute && url.scheme in setOf("http", "https")) {
        "MCP 服务器 URL 必须是绝对 http 或 https 地址。"
    }
    require(!url.host.isNullOrBlank()) { "MCP 服务器 URL 必须包含主机名。" }
    require(url.userInfo == null) { "MCP 服务器 URL 不能包含用户信息。" }
    require(url.fragment == null) { "MCP 服务器 URL 不能包含片段。" }
    require(url.port == -1 || url.port in 1..65535) { "MCP 服务器 URL 端口必须在 1..65535 范围内。" }
}

private fun validateMcpHeaders(headers: Map<String, String>) {
    require(headers.size <= MAX_MCP_SERVER_HEADERS) { "MCP 请求头不能超过 $MAX_MCP_SERVER_HEADERS 个。" }
    val normalizedNames = HashSet<String>()
    var totalBytes = 0
    headers.forEach { (name, value) ->
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        require(nameBytes.size in 1..MAX_MCP_HEADER_NAME_LENGTH && name.all(::isMcpHeaderTokenCharacter)) {
            "MCP 请求头名称必须是长度不超过 $MAX_MCP_HEADER_NAME_LENGTH 的 HTTP token。"
        }
        require(normalizedNames.add(name.lowercase(Locale.ROOT))) { "MCP 请求头名称不能按大小写重复。" }
        require(name.lowercase(Locale.ROOT) !in MCP_FORBIDDEN_HEADER_NAMES) { "MCP 请求头不能覆盖路由控制头。" }
        require(valueBytes.size <= MAX_MCP_HEADER_VALUE_LENGTH) {
            "MCP 请求头值不能超过 $MAX_MCP_HEADER_VALUE_LENGTH 个字节。"
        }
        require(value.all(::isVisibleAsciiCharacter)) { "MCP 请求头值只能包含可见 ASCII 字符。" }
        totalBytes += nameBytes.size + MCP_HEADER_LINE_DELIMITER_BYTES + valueBytes.size + MCP_HEADER_LINE_END_BYTES
        require(totalBytes <= MAX_MCP_HEADERS_TOTAL_BYTES) {
            "MCP 请求头总大小不能超过 $MAX_MCP_HEADERS_TOTAL_BYTES 字节。"
        }
    }
}

private fun isMcpHeaderTokenCharacter(character: Char): Boolean =
    character.isAsciiLetterOrDigit() || character == '$' || character in "!#%&'*+-.^_`|~"

private fun isMcpServerNameCharacter(character: Char): Boolean =
    character.isAsciiLetterOrDigit() || character == '_' || character == '-'

private fun isVisibleAsciiCharacter(character: Char): Boolean = character.code in 32..126

private const val MCP_HEADER_LINE_DELIMITER_BYTES = 2
private const val MCP_HEADER_LINE_END_BYTES = 2
private val MCP_FORBIDDEN_HEADER_NAMES = setOf(
    "host",
    "content-length",
    "transfer-encoding",
    "connection",
    "upgrade",
    "te",
    "trailer",
)

/**
 * 代理服务器的连接设置。
 *
 * @property host 代理服务器主机名或 IP 地址；不得为空。
 * @property port 代理服务器端口；必须在 `1..65535` 范围内。
 * @property type 代理协议类型。
 * @property username HTTP 代理认证用户名；与 [password] 必须同时提供或同时为 `null`。
 * SOCKS 代理必须为 `null`。
 * @property password HTTP 代理认证密码；与 [username] 必须同时提供或同时为 `null`。
 * SOCKS 代理必须为 `null`。
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
 * 校验代理设置能否作为本应用的连接目标使用。
 *
 * 此校验只检查输入的语法，不会执行 DNS 查询或任何网络请求。主机必须是裸主机名、IPv4
 * 地址，或未加方括号的标准 IPv6 地址；不接受 URL、用户信息或附带端口的主机字符串。
 *
 * @param proxy 要校验的代理设置；`null` 表示不使用代理，视为合法。
 * @throws IllegalArgumentException 主机、端口、协议类型或认证凭据组合不符合约束时抛出。
 */
fun validateProxySettings(proxy: ProxySettings?) {
    if (proxy == null) {
        return
    }

    val host = proxy.host
    if (host.isBlank()) {
        throw IllegalArgumentException("代理主机不能为空。")
    }
    if (host.any { it.isWhitespace() || it.isISOControl() }) {
        throw IllegalArgumentException("代理主机不能包含空白或控制字符。")
    }
    if (host.contains("://") || host.any { it == '/' || it == '?' || it == '#' || it == '@' }) {
        throw IllegalArgumentException("代理主机必须是裸主机名或 IP 地址，不能包含 URL 组成部分。")
    }
    if (!isValidProxyHost(host)) {
        throw IllegalArgumentException("代理主机必须是裸主机名、IPv4 地址或未加方括号的 IPv6 地址。")
    }
    if (proxy.port !in 1..65535) {
        throw IllegalArgumentException("代理端口必须在 1..65535 范围内。")
    }
    when (proxy.type) {
        ProxyType.HTTP,
        ProxyType.SOCKS,
            -> Unit
    }
    require((proxy.username == null) == (proxy.password == null)) { "代理认证用户名和密码必须同时提供。" }
    if (proxy.username != null) {
        require(proxy.username.isNotBlank() && proxy.password!!.isNotBlank()) { "代理认证用户名和密码不能为空白。" }
    }
    if (proxy.type == ProxyType.SOCKS) {
        require(proxy.username == null && proxy.password == null) { "SOCKS 代理不支持用户名和密码认证。" }
    }
}

private fun isValidProxyHost(host: String): Boolean =
    when {
        host.contains(':') -> isValidIpv6Address(host)
        host.all { it.isDigit() || it == '.' } -> isValidIpv4Address(host) || isValidHostname(host)
        else -> isValidHostname(host)
    }

private fun isValidHostname(host: String): Boolean =
    host.length <= 253 && !host.startsWith('.') && !host.endsWith('.') && host.split('.').all { label ->
        label.length in 1..63 &&
                label.first().isLetterOrDigit() &&
                label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
    }

private fun isValidIpv4Address(host: String): Boolean {
    val octets = host.split('.')
    return octets.size == 4 && octets.all { octet ->
        octet.isNotEmpty() &&
                octet.all(Char::isDigit) &&
                octet.toIntOrNull()?.let { it in 0..255 } == true
    }
}

private fun isValidIpv6Address(host: String): Boolean {
    if (
        host.contains(":::") ||
        host.indexOf("::") != host.lastIndexOf("::")
    ) {
        return false
    }

    val hasCompression = host.contains("::")
    if (
        (!hasCompression && (host.startsWith(':') || host.endsWith(':'))) ||
        (host.startsWith(':') && !host.startsWith("::")) ||
        (host.endsWith(':') && !host.endsWith("::"))
    ) {
        return false
    }

    val parts = host.split(':').filter(String::isNotEmpty)
    if (parts.isEmpty()) {
        return host == "::"
    }

    val ipv4Part = parts.last().takeIf { it.contains('.') }
    if (parts.dropLast(1).any { it.contains('.') } || (ipv4Part != null && !isValidIpv4Address(ipv4Part))) {
        return false
    }

    val groupCount = parts.sumOf { part -> if (part == ipv4Part) 2 else 1 }
    return parts.all { part -> part == ipv4Part || part.matches(Regex("[0-9A-Fa-f]{1,4}")) } &&
            if (hasCompression) groupCount < 8 else groupCount == 8
}

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
