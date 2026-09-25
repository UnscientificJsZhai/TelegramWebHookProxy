package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 为应用安装 JDK 的进程级 SOCKS5 认证器，并保存仍被客户端使用的代理设置快照。
 *
 * 每个 OkHttp 客户端在 SOCKS 握手时使用其创建时的设置快照；快照保留到客户端关闭。
 * 每个注册仅回答自己的代理请求，其余请求委托给安装前的认证器。
 */
@Singleton
class SocksProxyAuthentication internal constructor(
    private var currentProxy: (() -> ProxySettings?)?,
    install: Boolean,
) : AutoCloseable {
    @Inject
    constructor(settings: SettingsChangeCoordinator) : this({ settings.settingsFlow.value.proxy }, true)

    private val authenticator = ManagedSocksAuthenticator(this)
    private val retained = linkedMapOf<ProxySettings, Int>()
    private var stopping = !install
    private var uninstalled = !install

    init {
        if (install) {
            synchronized(Authenticator::class.java) {
                authenticator.previous = Authenticator.getDefault()
                Authenticator.setDefault(authenticator)
            }
        }
    }

    /** 客户端创建前取得句柄，待客户端及其所有在途请求结束后释放。 */
    fun retain(proxy: ProxySettings?): Lease = synchronized(Authenticator::class.java) {
        if (proxy?.type != ProxyType.SOCKS || stopping) {
            Lease(this, proxy, false)
        } else {
            retained[proxy] = (retained[proxy] ?: 0) + 1
            Lease(this, proxy, true)
        }
    }

    class Lease internal constructor(
        internal val owner: SocksProxyAuthentication,
        internal val proxySettings: ProxySettings?,
        accepted: Boolean,
    ) : AutoCloseable {
        internal val active = AtomicBoolean(accepted)

        override fun close() {
            if (active.compareAndSet(true, false)) owner.release(checkNotNull(proxySettings))
        }
    }

    private fun release(proxy: ProxySettings) {
        synchronized(Authenticator::class.java) {
            val remaining = retained[proxy] ?: return
            if (remaining == 1) retained.remove(proxy) else retained[proxy] = remaining - 1
            if (stopping && retained.isEmpty()) uninstallLocked()
        }
    }

    /** 在 OkHttp 的同步 SOCKS socket.connect 回调线程上绑定该客户端的认证快照。 */
    fun configureClient(builder: OkHttpClient.Builder, lease: Lease) {
        if (lease.owner !== this || lease.proxySettings?.type != ProxyType.SOCKS || !lease.active.get()) return
        builder.eventListenerFactory {
            object : EventListener() {
                private val contexts = ConcurrentLinkedQueue<ConnectionContext>()

                override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
                    if (proxy.type() == Proxy.Type.SOCKS) {
                        val context = ConnectionContext(lease)
                        contexts.add(context)
                        connectionContext.set(context)
                    }
                }

                override fun connectEnd(
                    call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?,
                ) = clearContexts()

                override fun connectFailed(
                    call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException,
                ) = clearContexts()

                override fun callEnd(call: Call) = clearContexts()

                override fun callFailed(call: Call, ioe: IOException) = clearContexts()

                private fun clearContexts() {
                    val current = connectionContext.get()
                    if (current != null && current in contexts) connectionContext.remove()
                    contexts.forEach { it.lease = null }
                    contexts.clear()
                }
            }
        }
    }

    private fun credentialsFor(protocol: String?, host: String?, port: Int): PasswordAuthentication? =
        synchronized(Authenticator::class.java) {
            if (uninstalled || !protocol.equals("SOCKS5", ignoreCase = true)) return@synchronized null
            val proxy = if (stopping) null else currentProxy?.invoke()
            if (proxy?.matches(protocol, host, port) != true) return@synchronized null
            proxy.authentication()
        }

    private fun credentialsForSnapshot(lease: Lease, protocol: String?, host: String?, port: Int): PasswordAuthentication? =
        synchronized(Authenticator::class.java) {
            val proxy = lease.proxySettings
            if (uninstalled || !lease.active.get() || proxy == null || !proxy.matches(protocol, host, port)) return@synchronized null
            if (retained[proxy] == null) return@synchronized null
            proxy.authentication()
        }

    override fun close() {
        synchronized(Authenticator::class.java) {
            if (stopping) return
            stopping = true
            currentProxy = null
            if (retained.isEmpty()) uninstallLocked()
        }
    }

    /** 必须持有 Authenticator 类锁；所有租约释放后才移除本应用的认证器。 */
    private fun uninstallLocked() {
        if (uninstalled) return
        uninstalled = true
        val installed = Authenticator.getDefault()
        if (installed === authenticator) {
            Authenticator.setDefault(authenticator.previous)
        } else {
            // 多个应用可交错停止；从仍安装的自有认证器链中摘除本节点。
            var node = installed
            while (node is ManagedSocksAuthenticator) {
                if (node.previous === authenticator) {
                    node.previous = authenticator.previous
                    break
                }
                node = node.previous
            }
        }
        // 外部代码若持有本节点，卸载后的节点也只会委托，不再引用设置或凭据。
    }

    private class ManagedSocksAuthenticator(private val owner: SocksProxyAuthentication) : Authenticator() {
        @Volatile
        var previous: Authenticator? = null

        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val previousContext = connectionContext.get()
            val context = previousContext?.takeIf { it.lease != null }
            if (previousContext != null && context == null) connectionContext.remove()
            if (context?.lease?.owner === owner) {
                connectionContext.remove()
                val lease = checkNotNull(context.lease)
                context.lease = null
                // 已识别的客户端只使用自己的快照；匿名 SOCKS 客户端也不能回退到旧凭据。
                return owner.credentialsForSnapshot(lease, requestingProtocol, requestingHost, requestingPort)
            }
            if (context == null) {
                owner.credentialsFor(requestingProtocol, requestingHost, requestingPort)?.let { return it }
            }
            return previous?.requestPasswordAuthenticationInstance(
                    requestingHost, requestingSite, requestingPort, requestingProtocol,
                    requestingPrompt, requestingScheme, requestingURL, requestorType,
                )
        }
    }

    companion object {
        private val connectionContext = ThreadLocal<ConnectionContext>()

        /** 供不启动完整应用组件的现有单元测试使用。 */
        val noOp: SocksProxyAuthentication = SocksProxyAuthentication(null, false)
    }

    private class ConnectionContext(@Volatile var lease: Lease?)
}

internal fun installSocksProxyAuthentication(currentProxy: () -> ProxySettings?): SocksProxyAuthentication =
    SocksProxyAuthentication(currentProxy, true)

private fun ProxySettings.matches(protocol: String?, host: String?, port: Int): Boolean =
    type == ProxyType.SOCKS && protocol.equals("SOCKS5", ignoreCase = true) &&
        this.port == port && matchesProxyHost(this.host, host) && username != null && password != null

private fun ProxySettings.authentication(): PasswordAuthentication =
    PasswordAuthentication(username!!, password!!.toCharArray())

/** 主机名直接比较；只解析数字地址，兼容 JDK 的 IPv4/IPv6 规范化且不触发 DNS 查询。 */
private fun matchesProxyHost(configured: String, requested: String?): Boolean {
    if (requested.isNullOrEmpty()) return false
    if (configured.equals(requested, ignoreCase = true)) return true
    fun String.isNumericAddress(): Boolean = contains(':') || all { it in '0'..'9' || it == '.' }
    return !(!configured.isNumericAddress() || !requested.isNumericAddress()) && runCatching { InetAddress.getByName(configured) == InetAddress.getByName(requested) }.getOrDefault(false)
}
