package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.ModelDiscoveryService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
            proxy = proxy.copy(type = ProxyType.SOCKS, username = null, password = null)
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
    fun `retired proxy credentials remain until the final client releases them`() {
        val old = ProxySettings("old.example", 1080, ProxyType.SOCKS, "old-user", "old-pass")
        val newer = ProxySettings("new.example", 1081, ProxyType.SOCKS, "new-user", "new-pass")
        var current: ProxySettings? = old
        installSocksProxyAuthentication { current }.use { registration ->
            val firstClient = registration.retain(old)
            val secondClient = registration.retain(old)
            current = newer
            assertEquals("old-user", challengeWithClientSnapshot(registration, old, firstClient)?.userName)
            assertNull(challenge("old.example"))
            assertEquals("new-user", challenge("new.example", port = 1081)?.userName)
            firstClient.close()
            assertEquals("old-user", challengeWithClientSnapshot(registration, old, secondClient)?.userName)
            secondClient.close()
            secondClient.close()
            assertNull(challenge("old.example"))
        }
    }

    @Test
    fun `Telegram keeps the old proxy credentials through an in-flight client lease`() = runBlocking {
        val directory = createTempDirectory("telegram-socks-lease").toFile()
        val scopeJob = SupervisorJob()
        val old = ProxySettings("old.example", 1080, ProxyType.SOCKS, "old-user", "old-pass")
        val newer = ProxySettings("new.example", 1081, ProxyType.SOCKS, "new-user", "new-pass")
        val settings = SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
        settings.replaceSettingsForTest(AppSettings(proxy = old))
        val previousAuthenticator = Authenticator.getDefault()
        val oldRequestStarted = CompletableDeferred<Unit>()
        val finishOldRequest = CompletableDeferred<Unit>()
        val newClientInstalled = CompletableDeferred<Unit>()
        var oldClientLease: SocksProxyAuthentication.Lease? = null
        installSocksProxyAuthentication { settings.settingsFlow.value.proxy }.use { authentication ->
            val service = TelegramService(
                CoroutineScope(scopeJob), settings, UpdatesRepository(directory.resolve("updates.json")),
                { proxy, lease ->
                    if (proxy == old) oldClientLease = lease
                    HttpClient(MockEngine {
                        if (proxy == old) {
                            oldRequestStarted.complete(Unit)
                            finishOldRequest.await()
                        }
                        respond("{}", HttpStatusCode.OK)
                    }) { install(ContentNegotiation) { json() } }
                },
                authentication,
                { proxy -> if (proxy == newer) newClientInstalled.complete(Unit) },
            )
            try {
                val request = async { service.sendMessageForToken("100:test", "123", "hello") }
                withTimeout(5_000) { oldRequestStarted.await() }
                settings.replaceSettingsForTest(AppSettings(proxy = newer))
                withTimeout(5_000) { newClientInstalled.await() }
                assertEquals("old-user", challengeWithClientSnapshot(authentication, old, checkNotNull(oldClientLease))?.userName)
                assertNull(challenge("old.example"))
                assertEquals("new-user", challenge("new.example", port = 1081)?.userName)
                service.close()
                authentication.close()
                assertEquals("old-user", challengeWithClientSnapshot(authentication, old, checkNotNull(oldClientLease))?.userName)
                finishOldRequest.complete(Unit)
                withTimeout(5_000) { request.await() }
                assertSame(previousAuthenticator, Authenticator.getDefault())
            } finally {
                finishOldRequest.complete(Unit)
                service.close()
                scopeJob.cancelAndJoin()
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `same endpoint rotation and anonymous clients use their own credential snapshots`() {
        val old = ProxySettings("proxy.example", 1080, ProxyType.SOCKS, "user", "old")
        var current = old
        installSocksProxyAuthentication { current }.use { registration ->
            val oldLease = registration.retain(old)
            val rotated = old.copy(password = "new")
            current = rotated
            registration.retain(rotated).use { rotatedLease ->
                assertEquals("old", String(assertNotNull(challengeWithClientSnapshot(registration, old, oldLease)).password))
                assertEquals("new", String(assertNotNull(challengeWithClientSnapshot(registration, rotated, rotatedLease)).password))
                val anonymous = old.copy(username = null, password = null)
                current = anonymous
                registration.retain(anonymous).use { anonymousLease ->
                    assertNull(challengeWithClientSnapshot(registration, anonymous, anonymousLease))
                }
                assertEquals("old", String(assertNotNull(challengeWithClientSnapshot(registration, old, oldLease)).password))
            }
            oldLease.close()
        }
    }

    @Test
    fun `application stop waits for an in-flight SOCKS lease before uninstalling authentication`() {
        val previous = Authenticator.getDefault()
        val proxy = ProxySettings("proxy.example", 1080, ProxyType.SOCKS, "user", "pass")
        val registration = installSocksProxyAuthentication { proxy }
        val lease = registration.retain(proxy)
        try {
            registration.close()
            assertNotSame(previous, Authenticator.getDefault())
            assertEquals("user", challengeWithClientSnapshot(registration, proxy, lease)?.userName)
            registration.retain(proxy).use { lateLease ->
                assertNull(challengeWithClientSnapshot(registration, proxy, lateLease))
            }
        } finally {
            lease.close()
            registration.close()
        }
        assertSame(previous, Authenticator.getDefault())
    }

    @Test
    fun `anonymous SOCKS client retains its authentication boundary through application stop`() {
        var previousLookups = 0
        val previous = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                previousLookups++
                return PasswordAuthentication("previous", "secret".toCharArray())
            }
        }
        Authenticator.setDefault(previous)
        val anonymous = ProxySettings("proxy.example", 1080, ProxyType.SOCKS, null, null)
        val registration = installSocksProxyAuthentication { anonymous }
        val lease = registration.retain(anonymous)
        try {
            registration.close()
            assertNotSame(previous, Authenticator.getDefault())
            assertNull(challengeWithClientSnapshot(registration, anonymous, lease))
            assertEquals(0, previousLookups)
        } finally {
            lease.close()
        }
        assertSame(previous, Authenticator.getDefault())
    }

    @Test
    fun `a real delayed SOCKS handshake keeps the old password after same endpoint rotation`() = runBlocking {
        val greeting = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        Socks5Server("user" to "old", beforeMethodSelection = {
            greeting.countDown()
            assertTrue(proceed.await(5, TimeUnit.SECONDS))
        }).use { server ->
            val old = server.settings("user" to "old")
            val current = AtomicReference(old)
            installSocksProxyAuthentication { current.get() }.use { registration ->
                val lease = registration.retain(old)
                val client = okhttpClient(old, registration, lease)
                try {
                    val response = async(Dispatchers.IO) {
                        client.newCall(Request.Builder().url("http://upstream.invalid/resource").build()).execute().use {
                            it.body.string()
                        }
                    }
                    assertTrue(greeting.await(5, TimeUnit.SECONDS))
                    current.set(old.copy(password = "new"))
                    proceed.countDown()
                    assertEquals("proxied", withTimeout(5_000) { response.await() })
                    assertEquals("user" to "old", server.awaitExchange().credentials)
                } finally {
                    proceed.countDown()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdown()
                    lease.close()
                }
            }
        }
    }

    @Test
    fun `connection completion and failure clear unused SOCKS handshake context`() {
        val old = ProxySettings("proxy.example", 1080, ProxyType.SOCKS, "user", "old")
        var current = old
        installSocksProxyAuthentication { current }.use { registration ->
            registration.retain(old).use { lease ->
                val client = okhttpClient(old, registration, lease)
                val call = client.newCall(Request.Builder().url("http://upstream.invalid/resource").build())
                val listener = client.eventListenerFactory.create(call)
                val route = Proxy(Proxy.Type.SOCKS, InetSocketAddress(old.host, old.port))
                val address = InetSocketAddress(old.host, old.port)
                try {
                    listener.connectStart(call, address, route)
                    current = old.copy(password = "new")
                    listener.connectEnd(call, address, route, Protocol.HTTP_1_1)
                    assertEquals("new", String(assertNotNull(challenge(old.host)).password))

                    listener.connectStart(call, address, route)
                    listener.connectFailed(call, address, route, null, IOException("injected failure"))
                    assertEquals("new", String(assertNotNull(challenge(old.host)).password))
                } finally {
                    listener.callEnd(call)
                }
            }
        }
    }

    @Test
    fun `closing an older registration unlinks it before the newer registration closes`() {
        val previous = object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication("original", "pass".toCharArray())
        }
        Authenticator.setDefault(previous)
        var oldLookups = 0
        val older = installSocksProxyAuthentication {
            oldLookups++
            ProxySettings("old.example", 1080, ProxyType.SOCKS, "old-user", "old-pass")
        }
        val newer = installSocksProxyAuthentication {
            ProxySettings("new.example", 1081, ProxyType.SOCKS, "new-user", "new-pass")
        }
        try {
            older.close()
            assertEquals("new-user", challenge("new.example", port = 1081)?.userName)
            assertEquals("original", challenge("old.example")?.userName)
            assertEquals(0, oldLookups)
            newer.close()
            assertSame(previous, Authenticator.getDefault())
            assertEquals("original", challenge("old.example")?.userName)
            assertEquals(0, oldLookups)
        } finally {
            newer.close()
            older.close()
        }
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

    private fun challengeWithClientSnapshot(
        registration: SocksProxyAuthentication,
        proxySettings: ProxySettings,
        lease: SocksProxyAuthentication.Lease,
    ): PasswordAuthentication? {
        val client = okhttpClient(proxySettings, registration, lease)
        val call = client.newCall(Request.Builder().url("http://upstream.invalid/resource").build())
        val listener = client.eventListenerFactory.create(call)
        val route = Proxy(Proxy.Type.SOCKS, InetSocketAddress(proxySettings.host, proxySettings.port))
        listener.connectStart(call, InetSocketAddress(proxySettings.host, proxySettings.port), route)
        return try {
            challenge(proxySettings.host, proxySettings.port)
        } finally {
            listener.callEnd(call)
        }
    }

    private fun okhttpClient(
        settings: ProxySettings,
        registration: SocksProxyAuthentication? = null,
        lease: SocksProxyAuthentication.Lease? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(settings.host, settings.port)))
        .apply {
            configureHttpProxyBasicAuthentication(settings)
            if (registration != null) registration.configureClient(this, checkNotNull(lease))
        }
        .retryOnConnectionFailure(false)
        .callTimeout(5, TimeUnit.SECONDS)
        .build()
}

/** 单次连接的本地 SOCKS5 服务：校验握手后直接返回 HTTP 响应，不访问外网。 */
private class Socks5Server(
    private val requiredCredentials: Pair<String, String>?,
    private val body: String = "proxied",
    private val beforeMethodSelection: (() -> Unit)? = null,
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
            beforeMethodSelection?.invoke()
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
