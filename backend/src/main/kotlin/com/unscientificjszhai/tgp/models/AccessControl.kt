package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.net.InetAddress

/** 管理入口的来源限制；字段缺失时由 AppSettings 使用关闭限制的默认值。 */
@Serializable
data class AccessControlSettings(val enabled: Boolean, val rules: List<String>) {
    @Transient
    private val compiledRules = lazy { rules.map(IpNetwork::parse) }

    internal fun allowsAddress(address: InetAddress): Boolean = compiledRules.value.any { it.contains(address) }
}

/** 有界、无 DNS 查询的 IP/CIDR 规则。IPv4 映射 IPv6 统一为 IPv4。 */
class IpNetwork private constructor(private val bytes: ByteArray, private val prefix: Int) {
    val cidr: String get() = "${InetAddress.getByAddress(bytes).hostAddress}/$prefix"

    fun contains(address: InetAddress): Boolean {
        val candidate = address.address
        return candidate.size == bytes.size && candidate.indices.all { index ->
            val bits = (prefix - index * 8).coerceIn(0, 8)
            val mask = (0xff shl (8 - bits)) and 0xff
            (candidate[index].toInt() and mask) == (bytes[index].toInt() and mask)
        }
    }

    companion object {
        fun parse(value: String): IpNetwork {
            require(value.length in 1..64 && value.count { it == '/' } <= 1) { "IP/CIDR 格式不合法。" }
            val parts = value.split('/')
            val literal = parts[0]
            val address = parseIpLiteral(literal) ?: throw IllegalArgumentException("必须使用字面 IP 地址。")
            val mapped = literal.contains(':') && address.address.size == 4
            val fullBits = if (mapped) 128 else address.address.size * 8
            var prefix = if (parts.size == 1) fullBits else {
                require(parts[1].isNotEmpty() && parts[1].all { it in '0'..'9' }) { "CIDR 前缀不合法。" }
                parts[1].toIntOrNull() ?: throw IllegalArgumentException("CIDR 前缀不合法。")
            }
            require(prefix in 0..fullBits) { "CIDR 前缀不合法。" }
            if (mapped) {
                require(prefix >= 96) { "映射 IPv6 的 CIDR 前缀必须在 96..128 范围内。" }
                prefix -= 96
            }
            val network = address.address.mapIndexed { index, byte ->
                val bits = (prefix - index * 8).coerceIn(0, 8)
                (byte.toInt() and ((0xff shl (8 - bits)) and 0xff)).toByte()
            }.toByteArray()
            return IpNetwork(network, prefix)
        }
    }
}

/** 只接受标准 IPv4/IPv6 字面地址；连接的 IPv6 scope 不参与网络匹配。 */
fun parseIpLiteral(value: String): InetAddress? {
    if (value.contains(':')) {
        if (!value.all { it in "0123456789abcdefABCDEF:." }) return null
        val tail = value.substringAfterLast(':')
        if (tail.contains('.') && parseIpLiteral(tail) == null) return null
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }
    val parts = value.split('.')
    if (parts.size != 4 || parts.any { part ->
            part.isEmpty() || part.any { it !in '0'..'9' } ||
                    (part.length > 1 && part.startsWith('0')) || part.toIntOrNull() !in 0..255
        }) return null
    return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray())
}

fun validateAccessControl(settings: AccessControlSettings) {
    require(settings.rules.size <= 128) { "访问规则不能超过 128 项。" }
    settings.rules.forEach(IpNetwork::parse)
}

fun AccessControlSettings.allows(peerIp: String): Boolean {
    if (!enabled) return true
    val address = parseIpLiteral(peerIp.substringBefore('%')) ?: return false
    return allowsAddress(address)
}
