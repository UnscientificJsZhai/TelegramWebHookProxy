package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.ai.agent.ModelDiscoveryService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.IOException
import java.net.Authenticator
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ServerSocket
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/** 覆盖 JDK 认证边界、配置发布，以及两种现有客户端的真实 SOCKS5 握手。 */
class SocksProxyAuthenticationTest {
    private var originalAuthenticator: Authenticator? = null

    @BeforeTest
    fun isolateAuthenticator() {
        originalAuthenticator = Authenticator.getDefault()
        Authenticator.setDefault(null)
    }

    @AfterTest
    fun restoreAuthenticator() {
        Authenticator.setDefault(originalAuthenticator)
    }

    @Test
    fun `credentials only answer SOCKS5 at the configured host and port`() {
        var proxy: ProxySettings? = ProxySettings("Proxy.Example", 1080, ProxyType.SOCKS, "user", "päss")
        installSocksProxyAuthentication { proxy }.use {
            val auth = assertNotNull(challenge("proxy.example"))
            assertEquals("user", auth.userName)
            assertEquals("päss", String(auth.password))
            assertNull(challenge("other.example"))
            assertNull(challenge("proxy.example", port = 1081))
            assertNull(challenge("proxy.example", protocol = "http"))
            assertNull(challenge(null))

            proxy = proxy!!.copy(type = ProxyType.HTTP)
            assertNull(challenge("proxy.example"))
            proxy = proxy!!.copy(type = ProxyType.SOCKS, username = null, password = null)
            assertNull(challenge("proxy.example"))
            proxy = null
            assertNull(challenge("proxy.example"))
        }
    }

    @Test
    fun `numeric proxy matching accepts JDK normalized IPv4 and IPv6 without hostname aliases`() {
        var proxy = ProxySettings("127.000.000.001", 1080, ProxyType.SOCKS, "user", "pass")
        installSocksProxyAuthentication { proxy }.use {
            assertNotNull(challenge("127.0.0.1"))
            assertNull(challenge(""))
            assertNull(challenge("127.0.0.2"))
            assertNull(challenge("localhost"))
            proxy = proxy.copy(host = "2001:db8::1")
            assertNotNull(challenge("2001:db8:0:0:0:0:0:1"))
            assertNull(challenge("2001:db8::2"))
        }
    }

    @Test
    fun `unmatched authentication is delegated and closing restores the previous authenticator`() {
        var requestedProtocol: String? = null
        val previous = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                requestedProtocol = requestingProtocol
                return PasswordAuthentication("previous", "old".toCharArray())
            }
        }
        Authenticator.setDefault(previous)
        installSocksProxyAuthentication {
            ProxySettings("proxy.example", 1080, ProxyType.SOCKS, "user", "pass")
        }.use {
            assertEquals("user", challenge("proxy.example")?.userName)
            assertNull(requestedProtocol)
            assertEquals("previous", challenge("other.example", protocol = "https")?.userName)
            assertEquals("https", requestedProtocol)
        }
        assertSame(previous, Authenticator.getDefault())

        val registration = installSocksProxyAuthentication { null }
        val replacement = object : Authenticator() {}
        Authenticator.setDefault(replacement)
        registration.close()
        registration.close()
        assertSame(replacement, Authenticator.getDefault())
    }

    @Test
    fun `only successfully saved settings replace authentication credentials`() {
        val directory = createTempDirectory("socks-settings").toFile()
        try {
            val file = directory.resolve("settings.json")
            val initial = AppSettings(proxy = ProxySettings("proxy.example", 1080, ProxyType.SOCKS, "user", "old"))
            file.writeText(ConfigJson.encodeToString(initial))
            var failWrites = false
            val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
                override fun atomicReplace(source: Path, target: Path) {
                    if (failWrites && target == file.toPath()) throw IOException("injected write failure")
                    DefaultAtomicJsonFileOperations.atomicReplace(source, target)
                }
            }
            val settings = SettingsChangeCoordinator.forTesting(file, ModelSwitchBarrier(), operations)
            installSocksProxyAuthentication { settings.settingsFlow.value.proxy }.use {
                assertEquals("old", String(assertNotNull(challenge("proxy.example")).password))
                val updated = initial.copy(proxy = initial.proxy!!.copy(password = "new"))
                failWrites = true
                assertFailsWith<IOException> { settings.replaceSettingsForTest(updated) }
                assertEquals("old", String(assertNotNull(challenge("proxy.example")).password))
                failWrites = false
                settings.replaceSettingsForTest(updated)
                assertEquals("new", String(assertNotNull(challenge("proxy.example")).password))
                settings.replaceSettingsForTest(updated.copy(proxy = updated.proxy!!.copy(host = "next.example")))
                assertNull(challenge("proxy.example"))
                assertEquals("new", String(assertNotNull(challenge("next.example")).password))
                settings.replaceSettingsForTest(updated.copy(proxy = null))
                assertNull(challenge("next.example"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `OkHttp negotiates Latin1 credentials and sends the target domain through SOCKS`() {
        val credentials = "é".repeat(255) to "ÿ".repeat(255)
        Socks5Server(credentials).use { server ->
            val proxy = server.settings(credentials)
            installSocksProxyAuthentication { proxy }.use {
                val client = okhttpClient(proxy)
                try {
                    client.newCall(Request.Builder().url("http://upstream.invalid/resource").build()).execute().use {
                        assertEquals("proxied", it.body.string())
                    }
                    val exchange = server.awaitExchange()
                    assertEquals(credentials, exchange.credentials)
                    assertEquals("upstream.invalid", exchange.host)
                    assertTrue(exchange.headers.first().startsWith("GET /resource "))
                    assertFalse(exchange.headers.any { it.startsWith("Proxy-Authorization:", ignoreCase = true) })
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test
    fun `authentication rejection fails the request`() {
        Socks5Server("user" to "correct").use { server ->
            val proxy = server.settings("user" to "wrong")
            installSocksProxyAuthentication { proxy }.use {
                val client = okhttpClient(proxy)
                try {
                    assertFailsWith<IOException> {
                        client.newCall(Request.Builder().url("http://upstream.invalid/resource").build()).execute().close()
                    }
                    val exchange = server.awaitExchange()
                    assertEquals("user" to "wrong", exchange.credentials)
                    assertNull(exchange.host)
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                }
            }
        }
    }

    @Test
    fun `Ktor OkHttp supports both authenticated and anonymous SOCKS`() = runBlocking {
        for (credentials in listOf("user" to "päss", null)) {
            Socks5Server(credentials).use { server ->
                val proxySettings = server.settings(credentials)
                installSocksProxyAuthentication { proxySettings }.use {
                    HttpClient(OkHttp) {
                        engine {
                            proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxySettings.host, proxySettings.port))
                            config { configureHttpProxyBasicAuthentication(proxySettings) }
                        }
                    }.use { client ->
                        assertEquals("proxied", client.get("http://upstream.invalid/ktor").bodyAsText())
                    }
                    val exchange = server.awaitExchange()
                    assertEquals(credentials, exchange.credentials)
                    assertEquals("upstream.invalid", exchange.host)
                    assertFalse(exchange.headers.any { it.startsWith("Proxy-Authorization:", ignoreCase = true) })
                }
            }
        }
    }

    @Test
    fun `model discovery uses the saved SOCKS credentials`() = runBlocking {
        val credentials = "user" to "pass"
        val models = """{"data":[{"id":"socks-model","object":"model","created":0,"owned_by":"test"}]}"""
        Socks5Server(credentials, models).use { server ->
            val settings = AppSettings(
                proxy = server.settings(credentials),
                ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "test-key", openAiBaseUrl = "http://provider.invalid/v1"),
            )
            installSocksProxyAuthentication { settings.proxy }.use {
                assertEquals(listOf("socks-model"), ModelDiscoveryService().listModels(settings).availableModels)
                val exchange = server.awaitExchange()
                assertEquals(credentials, exchange.credentials)
                assertEquals("provider.invalid", exchange.host)
                assertTrue(exchange.headers.first().startsWith("GET /v1/models "))
            }
        }
    }

    private fun challenge(host: String?, port: Int = 1080, protocol: String = "SOCKS5"): PasswordAuthentication? =
        Authenticator.requestPasswordAuthentication(host, null, port, protocol, "SOCKS authentication", null)

    private fun okhttpClient(settings: ProxySettings): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(settings.host, settings.port)))
        .apply { configureHttpProxyBasicAuthentication(settings) }
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
}

/** 单次连接的本地 SOCKS5 服务：校验握手后直接返回 HTTP 响应，不访问外网。 */
private class Socks5Server(
    private val requiredCredentials: Pair<String, String>?,
    private val body: String = "proxied",
) : AutoCloseable {
    private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
    private val executor = Executors.newSingleThreadExecutor()
    private val exchange = executor.submit<Exchange> {
        listener.accept().use { socket ->
            socket.soTimeout = 5000
            val input = DataInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            assertEquals(5, input.readUnsignedByte())
            val methods = input.readNBytes(input.readUnsignedByte())
            val method = if (requiredCredentials == null) 0 else 2
            assertTrue(method.toByte() in methods)
            output.write(byteArrayOf(5, method.toByte()))
            output.flush()
            val credentials = if (method == 2) {
                assertEquals(1, input.readUnsignedByte())
                val username = input.readNBytes(input.readUnsignedByte()).toString(Charsets.ISO_8859_1)
                val password = input.readNBytes(input.readUnsignedByte()).toString(Charsets.ISO_8859_1)
                username to password
            } else null
            if (method == 2) {
                output.write(byteArrayOf(1, if (credentials == requiredCredentials) 0 else 1))
                output.flush()
                if (credentials != requiredCredentials) return@submit Exchange(credentials, null, emptyList())
            }
            assertEquals(5, input.readUnsignedByte())
            assertEquals(1, input.readUnsignedByte()) // CONNECT
            assertEquals(0, input.readUnsignedByte())
            assertEquals(3, input.readUnsignedByte()) // DOMAIN：目标域名由代理解析
            val host = input.readNBytes(input.readUnsignedByte()).toString(Charsets.ISO_8859_1)
            assertEquals(80, input.readUnsignedShort())
            output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 80))
            output.flush()
            val reader = input.bufferedReader(Charsets.ISO_8859_1)
            val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
            val bytes = body.toByteArray(Charsets.UTF_8)
            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            output.write(bytes)
            output.flush()
            Exchange(credentials, host, headers)
        }
    }

    fun settings(credentials: Pair<String, String>?): ProxySettings =
        ProxySettings("127.0.0.1", listener.localPort, ProxyType.SOCKS, credentials?.first, credentials?.second)

    fun awaitExchange(): Exchange = exchange.get(5, TimeUnit.SECONDS)

    override fun close() {
        listener.close()
        executor.shutdownNow()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    data class Exchange(val credentials: Pair<String, String>?, val host: String?, val headers: List<String>)
}
