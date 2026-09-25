package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.di.DaggerAppComponent
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.installSocksProxyAuthentication
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.net.Authenticator
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 使用正式应用模块与真实 Netty 生命周期，替换业务组件以避免启动外部请求。 */
class ApplicationProxyAuthenticationTest {
    @Test
    fun `application installs SOCKS authentication before services and restores it after stopping`() = withComponent { component ->
        val previous = Authenticator.getDefault()
        val poller = component.messagePoller
        every { component.messagePoller } answers {
            assertNotSame(previous, Authenticator.getDefault())
            poller
        }
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module() }
        try {
            server.start(wait = false)
            val credentials = Authenticator.requestPasswordAuthentication(
                "proxy.example", null, 1080, "SOCKS5", "SOCKS authentication", null,
            )
            assertEquals("user", assertNotNull(credentials).userName)
        } finally {
            server.stop(0, 1_000)
        }
        assertSame(previous, Authenticator.getDefault())
    }

    @Test
    fun `module initialization failure immediately restores the authenticator`() = withComponent { component ->
        val previous = Authenticator.getDefault()
        every { component.messagePoller } throws IllegalStateException("injected startup failure")
        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module() }
        try {
            assertFails { server.start(wait = false) }
            assertSame(previous, Authenticator.getDefault())
        } finally {
            server.stop(0, 1_000)
        }
    }

    @Test
    fun `stopping after a port bind failure restores the authenticator`() = withComponent {
        val previous = Authenticator.getDefault()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val server = embeddedServer(Netty, host = "127.0.0.1", port = occupied.localPort) { module() }
            try {
                assertFails { server.start(wait = true) }
            } finally {
                // 与 main 相同，模块返回后的引擎启动失败也必须执行 stop。
                server.stop(0, 1_000)
            }
            assertSame(previous, Authenticator.getDefault())
        }
    }

    private fun withComponent(block: (AppComponent) -> Unit) {
        val directory = createTempDirectory("application-socks").toFile()
        val previous = Authenticator.getDefault()
        var authentication: Lazy<com.unscientificjszhai.tgp.service.SocksProxyAuthentication>? = null
        mockkStatic(DaggerAppComponent::class)
        try {
            val settings = SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            settings.replaceSettingsForTest(
                AppSettings(proxy = ProxySettings("proxy.example", 1080, ProxyType.SOCKS, "user", "pass")),
            )
            val component = mockk<AppComponent>(relaxed = true)
            every { component.settingsChangeCoordinator } returns settings
            authentication = lazy { installSocksProxyAuthentication { settings.settingsFlow.value.proxy } }
            every { component.socksProxyAuthentication } answers { requireNotNull(authentication).value }
            val factory = mockk<AppComponent.Factory>()
            every { factory.create(any()) } returns component
            every { DaggerAppComponent.factory() } returns factory
            block(component)
        } finally {
            authentication?.takeIf { it.isInitialized() }?.value?.close()
            unmockkStatic(DaggerAppComponent::class)
            Authenticator.setDefault(previous)
            directory.deleteRecursively()
        }
    }
}
