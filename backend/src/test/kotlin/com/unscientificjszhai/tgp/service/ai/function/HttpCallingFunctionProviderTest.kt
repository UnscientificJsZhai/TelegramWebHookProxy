package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.HttpCallTarget
import com.unscientificjszhai.tgp.models.HttpToolMethod
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.models.HttpToolSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentToolExecutionContext
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 模型 HTTP 工具的出站边界测试设计。
 */
class HttpCallingFunctionProviderTest {
    private val temporaryDirectory = createTempDirectory("http-tool-test").toFile()
    private val server = MockWebServer()

    init {
        server.start()
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        temporaryDirectory.deleteRecursively()
    }

    /**
     * 验证默认配置不会向模型声明 HTTP 工具，且执行会被稳定地拒绝。
     */
    @Test
    fun `default disabled configuration declares no HTTP function`() = runBlocking {
        val provider = providerWith(HttpToolSettings())

        assertTrue(provider.providedFunctions.isEmpty())
        assertEquals(
            "HTTP_TOOL_DISABLED",
            provider.execute("call_http_api", mapOf("targetId" to "fixed"))["error"]?.toString()?.trim('"')
        )
        provider.close()
    }

    /**
     * 验证关闭不会遗漏已通过二次状态检查但尚未登记的客户端，且关闭返回后该调用无法发起连接。
     */
    @Test
    fun `close cannot miss a client paused before registration`() = runBlocking {
        server.enqueue(response("must-not-be-requested", "text/plain"))
        val passedSecondCloseCheck = CountDownLatch(1)
        val releaseRegistration = CountDownLatch(1)
        val registeredBeforeRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val firstCloseAttempted = CountDownLatch(1)
        val secondCloseAttempted = CountDownLatch(1)
        val secondCloseJoined = CountDownLatch(1)
        val closeSnapshotTaken = CountDownLatch(1)
        val releaseClientClose = CountDownLatch(1)
        val closeAttempts = AtomicInteger()
        val connectionAttempts = AtomicInteger()
        val provider = providerWith(
            enabledSettings(HttpToolMethod.GET),
            HttpToolDnsResolver { listOf(InetAddress.getByName("127.0.0.1")) },
            HttpToolConnectionObserver { connectionAttempts.incrementAndGet() },
            object : HttpToolLifecycleObserver {
                override fun afterSecondCloseCheckBeforeRegistration() {
                    passedSecondCloseCheck.countDown()
                    assertTrue(
                        releaseRegistration.await(5, TimeUnit.SECONDS),
                        "request was not released to register its client",
                    )
                }

                override fun afterRegistrationBeforeRequest() {
                    registeredBeforeRequest.countDown()
                    assertTrue(
                        releaseRequest.await(5, TimeUnit.SECONDS),
                        "request was not released after close returned",
                    )
                }

                override fun onCloseAttempt() {
                    when (closeAttempts.incrementAndGet()) {
                        1 -> firstCloseAttempted.countDown()
                        2 -> secondCloseAttempted.countDown()
                    }
                }

                override fun afterJoiningInitialCloseBeforeWaiting() {
                    secondCloseJoined.countDown()
                }

                override fun afterCloseSnapshotBeforeClosingClients() {
                    closeSnapshotTaken.countDown()
                    assertTrue(
                        releaseClientClose.await(5, TimeUnit.SECONDS),
                        "client close was not released after the concurrent close joined",
                    )
                }
            },
        )

        try {
            val request = async(Dispatchers.Default) {
                provider.execute("call_http_api", mapOf("targetId" to "fixed"))
            }
            var firstClosing: Job? = null
            var secondClosing: Job? = null
            try {
                assertTrue(
                    passedSecondCloseCheck.await(5, TimeUnit.SECONDS),
                    "request did not reach the registration barrier",
                )
                val firstCloseJob = async(Dispatchers.Default) { provider.close() }
                firstClosing = firstCloseJob
                assertTrue(firstCloseAttempted.await(5, TimeUnit.SECONDS), "first close did not begin")
                assertFalse(firstCloseJob.isCompleted, "close returned while registration held the lifecycle lock")

                releaseRegistration.countDown()
                assertTrue(
                    registeredBeforeRequest.await(5, TimeUnit.SECONDS),
                    "request did not stop after registering its client",
                )
                assertTrue(closeSnapshotTaken.await(5, TimeUnit.SECONDS), "first close did not snapshot the client")

                val secondCloseJob = async(Dispatchers.Default) { provider.close() }
                secondClosing = secondCloseJob
                assertTrue(secondCloseAttempted.await(5, TimeUnit.SECONDS), "second close did not begin")
                assertTrue(secondCloseJoined.await(5, TimeUnit.SECONDS), "second close did not join the first close")
                assertFalse(secondCloseJob.isCompleted, "concurrent close returned before the client was closed")

                releaseClientClose.countDown()
                withTimeout(5.seconds) { firstCloseJob.await() }
                withTimeout(5.seconds) { secondCloseJob.await() }

                releaseRequest.countDown()
                val result = withTimeout(5.seconds) { request.await() }
                assertEquals("HTTP_TOOL_REQUEST_FAILED", result["error"]?.toString()?.trim('"'))
                assertEquals(0, connectionAttempts.get())
                assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
            } finally {
                releaseRegistration.countDown()
                releaseClientClose.countDown()
                provider.close()
                releaseRequest.countDown()
                firstClosing?.cancel()
                secondClosing?.cancel()
                request.cancel()
            }
        } finally {
            provider.close()
        }
    }

    /**
     * 验证函数架构和执行均只接受固定目标标识及受限 POST 正文。
     */
    @Test
    fun `model cannot override URL method headers or GET body`() = runBlocking {
        val provider = providerWith(enabledSettings(HttpToolMethod.GET))
        val schema = provider.providedOpenAIFunctions.single().parameters().toString()

        assertTrue(schema.contains("targetId"))
        assertFalse(schema.contains("url"))
        listOf(
            mapOf("targetId" to "fixed", "url" to "http://169.254.169.254/latest"),
            mapOf("targetId" to "fixed", "method" to "POST"),
            mapOf("targetId" to "fixed", "headers" to mapOf("Authorization" to "secret")),
            mapOf("targetId" to "fixed", "body" to "{}"),
        ).forEach { arguments ->
            val result = provider.execute("call_http_api", arguments)
            assertEquals("HTTP_TOOL_INVALID_ARGUMENTS", result["error"]?.toString()?.trim('"'))
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        provider.close()
    }

    /**
     * 验证调用只能抵达设置中的精确主机、端口、路径和方法。
     */
    @Test
    fun `only the configured fixed target is requested`() = runBlocking {
        server.enqueue(response("{\"ok\":true}", "application/json"))
        val provider = providerWith(enabledSettings(HttpToolMethod.GET))

        val denied = provider.execute("call_http_api", mapOf("targetId" to "other"))
        assertEquals("HTTP_TOOL_TARGET_NOT_ALLOWED", denied["error"]?.toString()?.trim('"'))
        val result = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

        assertEquals("200", result["status"]?.toString())
        assertEquals("{\"ok\":true}", result["body"]?.toString()?.trim('"')?.replace("\\\"", "\""))
        server.takeRequest().also { request ->
            assertEquals("GET", request.method)
            assertEquals("/fixed", request.target)
            assertEquals("identity", request.headers["Accept-Encoding"])
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        provider.close()
    }

    /**
     * 验证已有 Agent 回合在设置切换后只使用准入时固定的 HTTP 目标，且上下文离开后不会影响直接调用。
     */
    @Test
    fun `Agent tool context keeps HTTP target fixed across settings changes`() = runBlocking {
        val repository = SettingsRepository.forTesting(
            File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier(),
        )
        val settingsA = enabledSettings(HttpToolMethod.GET, path = "/a")
        val settingsB = enabledSettings(HttpToolMethod.GET, path = "/b")
        repository.saveSettings(
            AppSettings(
                telegramToken = "100:token-a",
                ai = AISettings(httpToolSettings = settingsA)
            )
        )
        val executionContext = AgentToolExecutionContext.from(repository.settingsUpdateFlow.value)
        val provider = HttpCallingFunctionProvider(repository)
        repository.saveSettings(
            AppSettings(
                telegramToken = "200:token-b",
                ai = AISettings(httpToolSettings = settingsB)
            )
        )
        server.enqueue(response("from-a", "text/plain"))
        server.enqueue(response("from-b", "text/plain"))
        server.enqueue(response("from-b-again", "text/plain"))

        try {
            val fixedResult = withContext(executionContext) {
                provider.execute("call_http_api", mapOf("targetId" to "fixed"))
            }
            val directResult = provider.execute("call_http_api", mapOf("targetId" to "fixed"))
            val afterContextResult = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

            assertEquals("200", fixedResult["status"]?.toString())
            assertEquals("200", directResult["status"]?.toString())
            assertEquals("200", afterContextResult["status"]?.toString())
            assertEquals("/a", server.takeRequest().target)
            assertEquals("/b", server.takeRequest().target)
            assertEquals("/b", server.takeRequest().target)
        } finally {
            provider.close()
        }
    }

    /**
     * 验证每个工具客户端只使用 HTTP/1.1，且不会保留服务端设置的 Cookie。
     */
    @Test
    fun `HTTP tool uses HTTP1 and does not retain cookies`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/plain")
                .setHeader("Set-Cookie", "session=secret")
                .body("first")
                .build(),
        )
        server.enqueue(response("second", "text/plain"))
        val provider = providerWith(enabledSettings(HttpToolMethod.GET))

        assertEquals("200", provider.execute("call_http_api", mapOf("targetId" to "fixed"))["status"]?.toString())
        assertEquals("200", provider.execute("call_http_api", mapOf("targetId" to "fixed"))["status"]?.toString())

        server.takeRequest().also { request ->
            assertEquals("HTTP/1.1", request.version)
            assertNull(request.headers["Cookie"])
        }
        server.takeRequest().also { request ->
            assertEquals("HTTP/1.1", request.version)
            assertNull(request.headers["Cookie"])
        }
        provider.close()
    }

    /**
     * 验证 POST 正文受大小限制，并以固定 JSON Content-Type 发送。
     */
    @Test
    fun `POST body uses fixed content type and enforces size limit`() = runBlocking {
        server.enqueue(response("done", "text/plain"))
        val provider = providerWith(enabledSettings(HttpToolMethod.POST))

        val success = provider.execute("call_http_api", mapOf("targetId" to "fixed", "body" to "{\"task\":1}"))
        assertEquals("200", success["status"]?.toString())
        server.takeRequest().also { request ->
            assertEquals("POST", request.method)
            assertEquals("application/json", request.headers["Content-Type"])
            assertEquals("{\"task\":1}", request.body?.utf8())
        }

        val malformed = provider.execute("call_http_api", mapOf("targetId" to "fixed", "body" to "{\"task\":"))
        assertEquals("HTTP_TOOL_INVALID_ARGUMENTS", malformed["error"]?.toString()?.trim('"'))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))

        val oversized = provider.execute(
            "call_http_api",
            mapOf("targetId" to "fixed", "body" to "x".repeat(64 * 1024 + 1)),
        )
        assertEquals("HTTP_TOOL_INVALID_ARGUMENTS", oversized["error"]?.toString()?.trim('"'))
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        provider.close()
    }

    /** 验证小于字节上限但过深的 JSON 正文不会进入 HTTP 客户端或递归 JSON 解析器。 */
    @Test
    fun `deep POST JSON body is rejected before parsing`() = runBlocking {
        val provider = providerWith(enabledSettings(HttpToolMethod.POST))
        val deepBody = "[".repeat(JsonStructureLimits.MAX_DEPTH + 1) +
                "0" +
                "]".repeat(JsonStructureLimits.MAX_DEPTH + 1)

        try {
            val result = provider.execute("call_http_api", mapOf("targetId" to "fixed", "body" to deepBody))

            assertEquals("HTTP_TOOL_INVALID_ARGUMENTS", result["error"]?.toString()?.trim('"'))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        } finally {
            provider.close()
        }
    }

    /**
     * 验证私网或混合 DNS 结果会在连接前拒绝，且每次调用都会重新检查解析结果。
     */
    @Test
    fun `private mixed and rebinding DNS results are blocked`() = runBlocking {
        val calls = AtomicInteger()
        val rebindResolver = HttpToolDnsResolver {
            when (calls.incrementAndGet()) {
                1 -> listOf(InetAddress.getByName("127.0.0.1"))
                else -> listOf(InetAddress.getByName("127.0.0.1"), InetAddress.getByName("10.0.0.8"))
            }
        }
        val provider = providerWith(httpsHostnameSettings(), rebindResolver)

        assertEquals(
            "HTTP_TOOL_REQUEST_FAILED",
            provider.execute("call_http_api", mapOf("targetId" to "fixed"))["error"]?.toString()?.trim('"'),
        )
        server.takeRequest(1, TimeUnit.SECONDS)
        val blocked = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

        assertEquals("HTTP_TOOL_REQUEST_FAILED", blocked["error"]?.toString()?.trim('"'))
        assertEquals(2, calls.get())
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        provider.close()
    }

    /**
     * 验证 IANA IPv6 特殊用途、转换、过渡和文档前缀均会在建立连接前被 DNS 校验拒绝。
     */
    @Test
    fun `IPv6 special purpose DNS results are blocked`() = runBlocking {
        val specialPurposeAddresses = listOf(
            "::",
            "::1",
            "64:ff9b::c000:201",
            "64:ff9b:1::1",
            "100::1",
            "100:0:0:1::1",
            "2001::1",
            "2001:2::1",
            "2001:1ff:ffff::1",
            "2001:db8::1",
            "2002:c000:201::1",
            "3fff::1",
            "5f00::1",
            "fc00::1",
            "fe80::1",
            "ff02::1",
        )

        specialPurposeAddresses.forEach { address ->
            val connectionAttempts = AtomicInteger()
            val repository = SettingsRepository.forTesting(
                File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
                ModelSwitchBarrier(),
            )
            repository.saveSettings(AppSettings(ai = AISettings(httpToolSettings = httpsHostnameSettings())))
            val provider = providerWith(
                repository,
                HttpToolDnsResolver { listOf(InetAddress.getByName(address)) },
                HttpToolConnectionObserver { connectionAttempts.incrementAndGet() },
            )
            try {
                val result = provider.execute("call_http_api", mapOf("targetId" to "fixed"))
                assertEquals("HTTP_TOOL_REQUEST_FAILED", result["error"]?.toString()?.trim('"'), address)
                assertEquals(0, connectionAttempts.get(), address)
            } finally {
                provider.close()
            }
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }

    /**
     * 验证 `/23` 前缀匹配恰好覆盖 `2001::/23`，不会把紧邻的正常全球单播地址纳入拒绝范围。
     */
    @Test
    fun `IETF IPv6 special prefix applies exact 2001 slash 23 boundary`() {
        assertFalse(isHttpToolPublicInternetAddress(InetAddress.getByName("2001:1ff:ffff::1")))
        assertTrue(isHttpToolPublicInternetAddress(InetAddress.getByName("2001:200::1")))
    }

    /**
     * 验证工具客户端会忽略 JVM 默认代理选择器，直接连接配置的固定目标。
     */
    @Test
    fun `HTTP tool ignores system proxy selector and connects directly`() = runBlocking {
        proxySelectorLock.lock()
        try {
            val proxyServer = MockWebServer()
            proxyServer.start()
            val previousSelector = ProxySelector.getDefault()
            val forcedProxySelections = AtomicInteger()
            try {
                ProxySelector.setDefault(
                    object : ProxySelector() {
                        override fun select(uri: URI): List<Proxy> {
                            if (uri.host == "127.0.0.1" && uri.port == server.port) {
                                forcedProxySelections.incrementAndGet()
                                return listOf(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyServer.port)))
                            }
                            return listOf(Proxy.NO_PROXY)
                        }

                        override fun connectFailed(uri: URI, socketAddress: SocketAddress, exception: IOException) =
                            Unit
                    },
                )
                server.enqueue(response("direct", "text/plain"))
                val provider = providerWith(enabledSettings(HttpToolMethod.GET))
                try {
                    val result = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

                    assertEquals("200", result["status"]?.toString())
                    assertEquals("/fixed", server.takeRequest().target)
                    assertTrue(forcedProxySelections.get() > 0)
                    assertNull(proxyServer.takeRequest(100, TimeUnit.MILLISECONDS))
                } finally {
                    provider.close()
                }
            } finally {
                ProxySelector.setDefault(previousSelector)
                proxyServer.close()
            }
        } finally {
            proxySelectorLock.unlock()
        }
    }

    /**
     * 验证响应读取有硬上限。
     */
    @Test
    fun `response size is bounded`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "text/plain")
                .chunkedBody("x".repeat(256 * 1024 + 1), 4096)
                .build(),
        )
        val provider = providerWith(enabledSettings(HttpToolMethod.GET))

        val oversized = provider.execute("call_http_api", mapOf("targetId" to "fixed"))
        assertEquals("HTTP_TOOL_RESPONSE_TOO_LARGE", oversized["error"]?.toString()?.trim('"'))
        assertEquals("/fixed", server.takeRequest().target)
        provider.close()
    }

    /**
     * 验证二进制响应不会作为模型上下文返回。
     */
    @Test
    fun `binary response body is omitted`() = runBlocking {
        server.enqueue(response("binary-data", "application/octet-stream"))
        val provider = providerWith(enabledSettings(HttpToolMethod.GET))

        val result = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

        assertEquals("200", result["status"]?.toString())
        assertNull(result["body"])
        provider.close()
    }

    /**
     * 验证二进制分块响应同样受 256 KiB 硬上限约束。
     */
    @Test
    fun `oversized binary response is rejected`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .setHeader("Content-Type", "application/octet-stream")
                .chunkedBody("x".repeat(256 * 1024 + 1), 4096)
                .build(),
        )
        val provider = providerWith(enabledSettings(HttpToolMethod.GET))

        val result = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

        assertEquals("HTTP_TOOL_RESPONSE_TOO_LARGE", result["error"]?.toString()?.trim('"'))
        provider.close()
    }

    /**
     * 验证 3xx 响应作为固定目标的结果返回，而不会跟随至新的 URL。
     */
    @Test
    fun `redirects are not followed`() = runBlocking {
        val redirectServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        redirectServer.createContext("/fixed") { exchange ->
            exchange.responseHeaders.add("Location", "/elsewhere")
            exchange.sendResponseHeaders(302, 0)
            exchange.responseBody.close()
        }
        redirectServer.createContext("/elsewhere") { exchange ->
            exchange.sendResponseHeaders(500, 0)
            exchange.responseBody.close()
        }
        redirectServer.start()
        val provider = providerWith(enabledSettings(HttpToolMethod.GET, port = redirectServer.address.port))
        try {
            val redirect = provider.execute("call_http_api", mapOf("targetId" to "fixed"))

            assertEquals("302", redirect["status"]?.toString())
        } finally {
            provider.close()
            redirectServer.stop(0)
        }
    }

    /**
     * 验证请求超时与 DNS 拒绝仅返回稳定错误代码，不泄露主机或异常详情。
     */
    @Test
    fun `timeouts and DNS failures expose no sensitive details`() = runBlocking {
        server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).build())
        val provider = providerWith(enabledSettings(HttpToolMethod.GET, requestTimeoutMillis = 100))

        val timeout = provider.execute("call_http_api", mapOf("targetId" to "fixed"))
        assertEquals("HTTP_TOOL_REQUEST_FAILED", timeout["error"]?.toString()?.trim('"'))

        val privateResolver = HttpToolDnsResolver { listOf(InetAddress.getByName("10.0.0.8")) }
        val dnsProvider = providerWith(httpsHostnameSettings(), privateResolver)
        val dnsFailure = dnsProvider.execute("call_http_api", mapOf("targetId" to "fixed"))
        assertEquals("HTTP_TOOL_REQUEST_FAILED", dnsFailure["error"]?.toString()?.trim('"'))
        assertFalse(dnsFailure.toString().contains("10.0.0.8"))
        assertFalse(dnsFailure.toString().contains("fixed"))
        val metadataResolver = HttpToolDnsResolver { listOf(InetAddress.getByName("100.100.100.200")) }
        val metadataProvider = providerWith(httpsHostnameSettings(), metadataResolver)
        val metadataFailure = metadataProvider.execute("call_http_api", mapOf("targetId" to "fixed"))
        assertEquals("HTTP_TOOL_REQUEST_FAILED", metadataFailure["error"]?.toString()?.trim('"'))
        assertFalse(metadataFailure.toString().contains("100.100.100.200"))
        provider.close()
        dnsProvider.close()
        metadataProvider.close()
    }

    private fun providerWith(
        httpToolSettings: HttpToolSettings,
        resolver: HttpToolDnsResolver = HttpToolDnsResolver { listOf(InetAddress.getByName("127.0.0.1")) },
    ): HttpCallingFunctionProvider {
        val repository = SettingsRepository.forTesting(
            File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier(),
        )
        repository.saveSettings(AppSettings(ai = AISettings(httpToolSettings = httpToolSettings)))
        return HttpCallingFunctionProvider(repository, resolver)
    }

    private fun providerWith(
        repository: SettingsRepository,
        resolver: HttpToolDnsResolver,
        connectionObserver: HttpToolConnectionObserver,
    ): HttpCallingFunctionProvider =
        HttpCallingFunctionProvider.withConnectionObserver(repository, resolver, connectionObserver)

    private fun providerWith(
        repository: SettingsRepository,
        resolver: HttpToolDnsResolver,
        connectionObserver: HttpToolConnectionObserver,
        lifecycleObserver: HttpToolLifecycleObserver,
    ): HttpCallingFunctionProvider =
        HttpCallingFunctionProvider.withConnectionObserver(repository, resolver, connectionObserver, lifecycleObserver)

    private fun providerWith(
        httpToolSettings: HttpToolSettings,
        resolver: HttpToolDnsResolver,
        connectionObserver: HttpToolConnectionObserver,
        lifecycleObserver: HttpToolLifecycleObserver,
    ): HttpCallingFunctionProvider {
        val repository = SettingsRepository.forTesting(
            File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier(),
        )
        repository.saveSettings(AppSettings(ai = AISettings(httpToolSettings = httpToolSettings)))
        return providerWith(repository, resolver, connectionObserver, lifecycleObserver)
    }

    private fun enabledSettings(
        method: HttpToolMethod,
        requestTimeoutMillis: Long = 10_000,
        port: Int = server.port,
        path: String = "/fixed",
    ): HttpToolSettings = HttpToolSettings(
        enabled = true,
        requestTimeoutMillis = requestTimeoutMillis,
        targets = listOf(
            HttpCallTarget(
                id = "fixed",
                scheme = "http",
                host = "127.0.0.1",
                port = port,
                path = path,
                method = method,
                allowedCidrs = listOf("127.0.0.1/32"),
            ),
        ),
    )

    private fun httpsHostnameSettings(): HttpToolSettings = HttpToolSettings(
        enabled = true,
        requestTimeoutMillis = 1_000,
        targets = listOf(
            HttpCallTarget(
                id = "fixed",
                scheme = "https",
                host = "fixed.example.test",
                port = server.port,
                path = "/fixed",
                method = HttpToolMethod.GET,
                allowedCidrs = listOf("127.0.0.1/32"),
            ),
        ),
    )

    private fun response(body: String, contentType: String): MockResponse =
        MockResponse.Builder().setHeader("Content-Type", contentType).body(body).build()

    private companion object {
        val proxySelectorLock = ReentrantLock()
    }
}
