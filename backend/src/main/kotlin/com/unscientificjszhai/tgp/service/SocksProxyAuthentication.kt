package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import java.net.Authenticator
import java.net.InetAddress
import java.net.PasswordAuthentication

/**
 * 为当前应用的 SOCKS5 代理安装 JDK 认证器；凭据始终取自已发布的设置快照。
 *
 * JDK 的 SOCKS 握手使用进程级认证器，因此仅匹配当前代理的协议、主机和端口，其余请求委托给原认证器。
 * 返回的句柄应在启动失败或应用完全停止时关闭；若其他代码已替换认证器，则不会覆盖它。
 */
internal fun installSocksProxyAuthentication(currentProxy: () -> ProxySettings?): AutoCloseable =
    synchronized(Authenticator::class.java) {
        val previous = Authenticator.getDefault()
        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                val proxy = currentProxy()
                // JDK 的 SOCKS5 回调使用默认的 SERVER 类型，不能要求 RequestorType.PROXY。
                if (
                    requestingProtocol.equals("SOCKS5", ignoreCase = true) &&
                    proxy?.type == ProxyType.SOCKS &&
                    requestingPort == proxy.port &&
                    matchesProxyHost(proxy.host, requestingHost) &&
                    proxy.username != null && proxy.password != null
                ) {
                    return PasswordAuthentication(proxy.username, proxy.password.toCharArray())
                }
                return previous?.requestPasswordAuthenticationInstance(
                    requestingHost, requestingSite, requestingPort, requestingProtocol,
                    requestingPrompt, requestingScheme, requestingURL, requestorType,
                )
            }
        }
        Authenticator.setDefault(authenticator)
        AutoCloseable {
            synchronized(Authenticator::class.java) {
                if (Authenticator.getDefault() === authenticator) {
                    Authenticator.setDefault(previous)
                }
            }
        }
    }

/** 主机名直接比较；只解析数字地址，兼容 JDK 的 IPv4/IPv6 规范化且不触发 DNS 查询。 */
private fun matchesProxyHost(configured: String, requested: String?): Boolean {
    if (requested.isNullOrEmpty()) return false
    if (configured.equals(requested, ignoreCase = true)) return true
    fun String.isNumericAddress(): Boolean = contains(':') || all { it in '0'..'9' || it == '.' }
    return !(!configured.isNumericAddress() || !requested.isNumericAddress()) && runCatching { InetAddress.getByName(configured) == InetAddress.getByName(requested) }.getOrDefault(false)
}
