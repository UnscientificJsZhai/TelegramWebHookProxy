package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.models.ExactHttpToolCidr
import com.unscientificjszhai.tgp.models.HttpCallTarget
import com.unscientificjszhai.tgp.models.HttpToolMethod
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.models.MAX_HTTP_TOOL_CONCURRENCY
import com.unscientificjszhai.tgp.models.MAX_HTTP_TOOL_TARGET_ID_LENGTH
import com.unscientificjszhai.tgp.models.parseExactHttpToolCidr
import com.unscientificjszhai.tgp.models.validateHttpToolSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.CookieJar
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Protocol
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 可注入的 HTTP 工具 DNS 解析器。
 *
 * 解析器必须在每次调用时返回当次解析得到的全部地址，不能依赖调用方提供的 URL 或省略候选地址。
 */
fun interface HttpToolDnsResolver {
    /**
     * 解析固定目标的裸主机名。
     *
     * @param host 要解析的固定裸主机名或字面 IP 地址，不能为空且不含 URL 组成部分。
     * @return 此次查找得到的全部候选 IP 地址；空列表会使请求失败。
     * @throws UnknownHostException 主机无法解析时抛出。
     */
    @Throws(UnknownHostException::class)
    fun lookup(host: String): List<InetAddress>
}

/**
 * 使用 JVM 系统解析器的 HTTP 工具 DNS 解析器。
 */
object SystemHttpToolDnsResolver : HttpToolDnsResolver {
    /**
     * 使用 JVM 系统 DNS 解析器查询固定目标的全部地址。
     *
     * @param host 要查询的固定裸主机名或字面 IP 地址。
     * @return 查询返回的全部候选地址。
     * @throws UnknownHostException 主机无法解析时抛出。
     */
    override fun lookup(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
}

/**
 * 提供由固定目标配置约束的模型 HTTP 函数调用能力。
 *
 * 模型只能选择 [HttpCallTarget.id] 并为 POST 提供小型 JSON 文本。每次执行都重新读取并校验
 * 当前设置快照、使用独立 HTTP/1.1 客户端及经校验的 DNS，且不会把 URL、请求头、异常详情或
 * 非文本响应交给模型。
 *
 */
class HttpCallingFunctionProvider private constructor(
    private val settingsRepository: SettingsRepository,
    private val dnsResolver: HttpToolDnsResolver,
    private val connectionObserver: HttpToolConnectionObserver,
) : LocalFunctionProvider(), AutoCloseable {
    /**
     * 创建受限 HTTP 函数提供者。
     *
     * @param settingsRepository 提供当前 HTTP 工具设置快照；配置变更由其代理生命周期屏障协调。
     * @param dnsResolver 实际 DNS 查询使用的解析器；其全部结果都会在连接前接受地址边界校验。
     */
    constructor(
        settingsRepository: SettingsRepository,
        dnsResolver: HttpToolDnsResolver = SystemHttpToolDnsResolver,
    ) : this(settingsRepository, dnsResolver, HttpToolConnectionObserver {})

    private val closed = AtomicBoolean(false)
    private val activeClients = ConcurrentHashMap.newKeySet<HttpClient>()
    private val hardRequestLimit = Semaphore(MAX_HTTP_TOOL_CONCURRENCY)
    private val semaphores = ConcurrentHashMap<Int, Semaphore>()

    /**
     * 获取 `call_http_api` 的受限函数声明。
     *
     * 未启用、无目标或当前配置不合法时返回空列表，因此 Gemini 和 OpenAI 均不会向模型声明该
     * 函数。声明仅允许 `targetId` 与 POST 的 `body` 字段。
     */
    override val providedFunctions: List<FunctionDeclaration>
        get() {
            val settings = currentSettingsOrNull() ?: return emptyList()
            if (!settings.enabled || settings.targets.isEmpty() || closed.get()) return emptyList()
            return listOf(httpFunctionDeclaration)
        }

    /**
     * 执行受限 HTTP 函数调用。
     *
     * 调用前会重新读取和校验当前配置快照。调用方取消时会停止请求并继续抛出取消异常；其余错误
     * 始终返回稳定、无敏感信息的错误代码。
     *
     * @param functionName 要执行的函数名称；仅 `call_http_api` 有效。
     * @param args 模型函数参数；只能包含非空字符串 `targetId`，以及 POST 的可选、最大 64 KiB 的
     * 合法 JSON 字符串 `body`。
     * @return 成功时包含状态码和可安全返回的文本正文；失败时仅包含稳定的 `error` 代码。
     * @throws CancellationException 调用协程取消时抛出。
     */
    override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
        if (functionName != HTTP_FUNCTION_NAME) return error(ERROR_UNSUPPORTED_FUNCTION)
        if (closed.get()) return error(ERROR_DISABLED)

        val settings = currentSettingsOrNull() ?: return error(ERROR_DISABLED)
        if (!settings.enabled || settings.targets.isEmpty()) return error(ERROR_DISABLED)
        val arguments = parseArguments(args) ?: return error(ERROR_INVALID_ARGUMENTS)
        val target =
            settings.targets.singleOrNull { it.id == arguments.targetId } ?: return error(ERROR_TARGET_NOT_ALLOWED)
        if (target.method == HttpToolMethod.GET && arguments.body != null) return error(ERROR_INVALID_ARGUMENTS)
        if (arguments.body != null && arguments.body.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BODY_BYTES) {
            return error(ERROR_INVALID_ARGUMENTS)
        }
        if (target.method == HttpToolMethod.POST && arguments.body != null &&
            runCatching { Json.parseToJsonElement(arguments.body) }.isFailure
        ) {
            return error(ERROR_INVALID_ARGUMENTS)
        }

        val semaphore = semaphores.computeIfAbsent(settings.maxConcurrentRequests, ::Semaphore)
        return hardRequestLimit.withPermit {
            semaphore.withPermit {
                executeTarget(settings, target, arguments.body)
            }
        }
    }

    private suspend fun executeTarget(
        settings: HttpToolSettings,
        target: HttpCallTarget,
        body: String?,
    ): JsonObject {
        if (closed.get()) return error(ERROR_DISABLED)

        // The lifecycle barrier prevents a persisted settings transition from racing a model turn.
        // Re-read the snapshot nevertheless so direct callers cannot use a stale target.
        val currentSettings = currentSettingsOrNull() ?: return error(ERROR_DISABLED)
        if (!currentSettings.enabled || currentSettings != settings ||
            currentSettings.targets.singleOrNull { it.id == target.id } != target
        ) {
            return error(ERROR_TARGET_NOT_ALLOWED)
        }

        val client = createClient(settings, target)
        if (closed.get()) {
            client.close()
            return error(ERROR_DISABLED)
        }
        activeClients += client
        return try {
            val response = client.request(target.toFixedUrl()) {
                method = when (target.method) {
                    HttpToolMethod.GET -> HttpMethod.Get
                    HttpToolMethod.POST -> HttpMethod.Post
                }
                // Fixed headers only: do not expose credentials or caller-controlled header input.
                header(HttpHeaders.AcceptEncoding, "identity")
                if (target.method == HttpToolMethod.POST) {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    if (body != null) setBody(body)
                }
            }
            response.toSafeResult()
        } catch (e: CancellationException) {
            throw e
        } catch (_: ResponseTooLargeException) {
            error(ERROR_RESPONSE_TOO_LARGE)
        } catch (_: Exception) {
            error(ERROR_REQUEST_FAILED)
        } finally {
            activeClients -= client
            client.close()
        }
    }

    private fun currentSettingsOrNull(): HttpToolSettings? {
        val settings = settingsRepository.settingsFlow.value.ai?.httpToolSettings ?: return null
        return settings.takeIf { runCatching { validateHttpToolSettings(it) }.isSuccess }
    }

    private fun createClient(settings: HttpToolSettings, target: HttpCallTarget): HttpClient =
        HttpClient(OkHttp) {
            expectSuccess = false
            followRedirects = false
            engine {
                clientCacheSize = 0
                config {
                    dns(ValidatingDns(target, dnsResolver))
                    proxy(Proxy.NO_PROXY)
                    followRedirects(false)
                    followSslRedirects(false)
                    retryOnConnectionFailure(false)
                    cookieJar(CookieJar.NO_COOKIES)
                    protocols(listOf(Protocol.HTTP_1_1))
                    eventListenerFactory {
                        object : EventListener() {
                            override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                                connectionObserver.onConnectStart()
                            }
                        }
                    }
                }
            }
            install(HttpTimeout) {
                requestTimeoutMillis = settings.requestTimeoutMillis
                connectTimeoutMillis = settings.requestTimeoutMillis
                socketTimeoutMillis = settings.requestTimeoutMillis
            }
        }

    private suspend fun HttpResponse.toSafeResult(): JsonObject {
        val bytes = readBoundedBody()
        return buildJsonObject {
            put("status", status.value)
            if (isTextualResponse()) put("body", bytes.toString(Charsets.UTF_8))
        }
    }

    private fun HttpResponse.isTextualResponse(): Boolean {
        val value = headers[HttpHeaders.ContentType]?.substringBefore(';')?.lowercase() ?: return false
        return value.startsWith("text/") ||
                value == "application/json" ||
                value.endsWith("+json") ||
                value == "application/xml" ||
                value.endsWith("+xml")
    }

    private suspend fun HttpResponse.readBoundedBody(): ByteArray {
        val declaredLength = headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declaredLength != null && declaredLength > MAX_RESPONSE_BODY_BYTES) throw ResponseTooLargeException()
        val bytes = ByteArray(MAX_RESPONSE_BODY_BYTES + 1)
        val channel = bodyAsChannel()
        var total = 0
        while (total < bytes.size) {
            val count = channel.readAvailable(bytes, total, bytes.size - total)
            if (count < 0) break
            if (count > 0) total += count
        }
        if (total > MAX_RESPONSE_BODY_BYTES) throw ResponseTooLargeException()
        return bytes.copyOf(total)
    }

    /**
     * 关闭正在使用和后续创建的 HTTP 客户端。
     *
     * 此方法可重复调用；关闭后不再声明或执行 HTTP 工具，并会中断仍在进行的请求。
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            activeClients.forEach(HttpClient::close)
            activeClients.clear()
        }
    }

    private data class CallArguments(
        val targetId: String,
        val body: String?,
    )

    private fun parseArguments(args: Map<String, Any?>): CallArguments? {
        if (args.keys.any { it != TARGET_ID_ARGUMENT && it != BODY_ARGUMENT }) return null
        val targetId = args[TARGET_ID_ARGUMENT] as? String
        if (targetId.isNullOrBlank() || targetId.length > MAX_HTTP_TOOL_TARGET_ID_LENGTH) return null
        val body = args[BODY_ARGUMENT]
        if (body != null && body !is String) return null
        return CallArguments(targetId, body)
    }

    private class ResponseTooLargeException : Exception()

    private class ValidatingDns(
        private val target: HttpCallTarget,
        private val resolver: HttpToolDnsResolver,
    ) : Dns {
        private val allowedAddresses = target.allowedCidrs.map(::parseExactHttpToolCidr)

        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != target.host) throw UnknownHostException("HTTP tool target mismatch")
            val addresses = resolver.lookup(hostname)
            if (addresses.isEmpty() || addresses.any { !it.isAllowedForTarget(allowedAddresses) }) {
                throw UnknownHostException("HTTP tool DNS result rejected")
            }
            return addresses
        }
    }

    internal companion object {
        const val HTTP_FUNCTION_NAME = "call_http_api"
        const val TARGET_ID_ARGUMENT = "targetId"
        const val BODY_ARGUMENT = "body"
        const val MAX_REQUEST_BODY_BYTES = 64 * 1024
        const val MAX_RESPONSE_BODY_BYTES = 256 * 1024
        const val ERROR_UNSUPPORTED_FUNCTION = "HTTP_TOOL_UNSUPPORTED_FUNCTION"
        const val ERROR_DISABLED = "HTTP_TOOL_DISABLED"
        const val ERROR_INVALID_ARGUMENTS = "HTTP_TOOL_INVALID_ARGUMENTS"
        const val ERROR_TARGET_NOT_ALLOWED = "HTTP_TOOL_TARGET_NOT_ALLOWED"
        const val ERROR_REQUEST_FAILED = "HTTP_TOOL_REQUEST_FAILED"
        const val ERROR_RESPONSE_TOO_LARGE = "HTTP_TOOL_RESPONSE_TOO_LARGE"

        val httpFunctionDeclaration: FunctionDeclaration by lazy {
            val schema = buildJsonObject {
                put("type", "OBJECT")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            TARGET_ID_ARGUMENT,
                            buildJsonObject {
                                put("type", "STRING")
                                put("description", "Configured fixed HTTP target identifier.")
                            },
                        )
                        put(
                            BODY_ARGUMENT,
                            buildJsonObject {
                                put("type", "STRING")
                                put("description", "Optional JSON body. It is accepted only for POST targets.")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add(JsonPrimitive(TARGET_ID_ARGUMENT)) })
            }.toString()
            FunctionDeclaration.builder()
                .name(HTTP_FUNCTION_NAME)
                .description("Call one configured fixed HTTP target by targetId.")
                .parameters(Schema.fromJson(schema))
                .build()
        }

        internal fun withConnectionObserver(
            settingsRepository: SettingsRepository,
            dnsResolver: HttpToolDnsResolver,
            connectionObserver: HttpToolConnectionObserver,
        ): HttpCallingFunctionProvider =
            HttpCallingFunctionProvider(settingsRepository, dnsResolver, connectionObserver)

        fun error(code: String): JsonObject = buildJsonObject { put("error", code) }
    }
}

internal fun interface HttpToolConnectionObserver {
    fun onConnectStart()
}

private fun HttpCallTarget.toFixedUrl(): String {
    val hostPart = if (host.contains(':')) "[$host]" else host
    return "$scheme://$hostPart:$port$path"
}

private fun InetAddress.isAllowedForTarget(allowedCidrs: List<ExactHttpToolCidr>): Boolean =
    allowedCidrs.any { it.address == this } || isHttpToolPublicInternetAddress(this)

internal fun isHttpToolPublicInternetAddress(address: InetAddress): Boolean = address.isPublicInternetAddress()

private fun InetAddress.isPublicInternetAddress(): Boolean = when (this) {
    is Inet4Address -> isPublicIpv4(address)
    is Inet6Address -> isPublicIpv6(address)
    else -> false
}

private fun isPublicIpv4(bytes: ByteArray): Boolean {
    val first = bytes[0].toUnsignedInt()
    val second = bytes[1].toUnsignedInt()
    val third = bytes[2].toUnsignedInt()
    return when {
        first == 0 || first == 10 || first == 127 || first >= 224 -> false
        first == 100 && second in 64..127 -> false
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 0 && third == 0 -> false
        first == 192 && second == 0 && third == 2 -> false
        first == 192 && second == 31 && third == 196 -> false
        first == 192 && second == 52 && third == 193 -> false
        first == 192 && second == 88 && third == 99 -> false
        first == 192 && second == 168 -> false
        first == 192 && second == 175 && third == 48 -> false
        first == 198 && second in 18..19 -> false
        first == 198 && second == 51 && third == 100 -> false
        first == 203 && second == 0 && third == 113 -> false
        else -> true
    }
}

private fun isPublicIpv6(bytes: ByteArray): Boolean {
    if (bytes.size != IPV6_ADDRESS_BYTE_LENGTH || matchesAny(bytes)) return false
    val first = bytes[0].toUnsignedInt()
    // Only ordinary global-unicast space is eligible after the explicit special-use denylist.
    return first in 0x20..0x3f
}

/**
 * IANA IPv6 special-purpose prefixes that must never be reached by the HTTP tool.
 *
 * The list deliberately includes translation and transition ranges even where an IANA entry has a
 * conditional reachability value: their embedded address semantics make them unsuitable as a
 * generic outbound destination. `2001::/23` is denied as a whole instead of allowing its narrow
 * anycast exceptions, so no IETF protocol-assignment subrange becomes an accidental SSRF route.
 */
private val IPV6_SPECIAL_PURPOSE_DENYLIST = listOf(
    ipv6Prefix(96, 0, 0, 0, 0, 0), // IPv4-compatible and IPv4-mapped address space.
    ipv6Prefix(128, 0, 0, 0, 0, 0, 0, 0, 1), // ::1/128 loopback.
    ipv6Prefix(96, 0, 0, 0, 0, 0, 0xffff), // ::ffff:0:0/96 IPv4-mapped.
    ipv6Prefix(96, 0x64, 0xff9b, 0, 0, 0, 0), // 64:ff9b::/96 IPv4/IPv6 translation.
    ipv6Prefix(48, 0x64, 0xff9b, 1), // 64:ff9b:1::/48 local translation.
    ipv6Prefix(64, 0x100, 0, 0, 0), // 100::/64 discard-only.
    ipv6Prefix(64, 0x100, 0, 0, 1), // 100:0:0:1::/64 dummy prefix.
    ipv6Prefix(23, 0x2001, 0), // 2001::/23 IETF protocol assignments, including Teredo/benchmarking.
    ipv6Prefix(32, 0x2001, 0), // 2001::/32 Teredo transition.
    ipv6Prefix(48, 0x2001, 2, 0), // 2001:2::/48 benchmarking.
    ipv6Prefix(28, 0x2001, 0x10), // 2001:10::/28 deprecated ORCHID.
    ipv6Prefix(32, 0x2001, 0xdb8), // 2001:db8::/32 documentation.
    ipv6Prefix(16, 0x2002), // 2002::/16 6to4 transition.
    ipv6Prefix(16, 0x3ffe), // 3ffe::/16 historical 6bone.
    ipv6Prefix(20, 0x3fff, 0), // 3fff::/20 documentation.
    ipv6Prefix(16, 0x5f00), // 5f00::/16 SRv6 SID special purpose.
    ipv6Prefix(7, 0xfc00), // fc00::/7 unique local.
    ipv6Prefix(10, 0xfe80), // fe80::/10 link local.
    ipv6Prefix(8, 0xff00), // ff00::/8 multicast.
)

private const val IPV6_ADDRESS_BYTE_LENGTH = 16

private class Ipv6Prefix(
    val bitLength: Int,
    val bytes: ByteArray,
)

private fun ipv6Prefix(bitLength: Int, vararg groups: Int): Ipv6Prefix {
    require(bitLength in 0..128 && groups.size <= 8)
    return Ipv6Prefix(
        bitLength = bitLength,
        bytes = ByteArray(IPV6_ADDRESS_BYTE_LENGTH).also { bytes ->
            groups.forEachIndexed { index, group ->
                require(group in 0..0xffff)
                bytes[index * 2] = (group ushr 8).toByte()
                bytes[index * 2 + 1] = group.toByte()
            }
        },
    )
}

private fun matchesAny(bytes: ByteArray): Boolean =
    IPV6_SPECIAL_PURPOSE_DENYLIST.any { prefix -> bytes.matches(prefix) }

private fun ByteArray.matches(prefix: Ipv6Prefix): Boolean {
    val completeBytes = prefix.bitLength / 8
    if ((0 until completeBytes).any { this[it] != prefix.bytes[it] }) return false
    val remainingBits = prefix.bitLength % 8
    if (remainingBits == 0) return true
    val mask = 0xff shl (8 - remainingBits)
    return (this[completeBytes].toUnsignedInt() and mask) == (prefix.bytes[completeBytes].toUnsignedInt() and mask)
}

private fun Byte.toUnsignedInt(): Int = toInt() and 0xff
