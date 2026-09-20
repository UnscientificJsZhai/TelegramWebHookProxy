package com.unscientificjszhai.tgp.service.ai.function

import com.sun.net.httpserver.HttpServer
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.agent.AgentToolExecutionContext
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.io.IOException
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.io.path.createTempDirectory
import kotlin.test.*

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

    /** 字面地址不会触发 OkHttp DNS 回调，拒绝必须发生在任何连接或解析之前。 */
    @Test
    fun `private and ambiguous literals cannot bypass the address policy`() = runBlocking {
        val cases = listOf(
            "127.0.0.1" to emptyList(),
            "10.0.0.1" to emptyList(),
            "169.254.169.254" to emptyList(),
            "::1" to emptyList(),
            "fd00::1" to emptyList(),
            "127.0.0.1" to listOf("127.0.0.2/32"),
            "::1" to listOf("::2/128"),
            "127.1" to emptyList(),
            "127.000.000.001" to emptyList(),
        )
        cases.forEach { (host, exceptions) ->
            val repository = SettingsChangeCoordinator.forTesting(
                File(temporaryDirectory, "blocked-${System.nanoTime()}.json"), ModelSwitchBarrier(),
            )
            val settings = httpsHostnameSettings().let {
                it.copy(targets = listOf(it.targets.single().copy(host = host, allowedCidrs = exceptions)))
            }
            repository.replaceSettingsForTest(AppSettings(ai = AISettings(httpToolSettings = settings)))
            val resolutions = AtomicInteger()
            val connections = AtomicInteger()
            providerWith(repository, HttpToolDnsResolver {
                resolutions.incrementAndGet()
                throw UnknownHostException("unexpected DNS")
            }, HttpToolConnectionObserver {
                connections.incrementAndGet()
                throw IOException("unexpected connection")
            }).use { provider ->
                assertEquals(
                    HttpCallingFunctionProvider.error(HttpCallingFunctionProvider.ERROR_TARGET_NOT_ALLOWED),
                    provider.execute("call_http_api", mapOf("targetId" to "fixed")), host,
                )
            }
            assertEquals(0, resolutions.get(), host)
            assertEquals(0, connections.get(), host)
        }
    }

    @Test
    fun `exact loopback exception permits a real local request`() = runBlocking {
        server.enqueue(response("allowed", "text/plain"))
        providerWith(enabledSettings(HttpToolMethod.GET), HttpToolDnsResolver {
            throw UnknownHostException("literal must not need DNS")
        }).use { provider ->
            val result = provider.execute("call_http_api", mapOf("targetId" to "fixed"))
            assertEquals("200", result["status"]?.jsonPrimitive?.content)
            assertEquals("allowed", result["body"]?.jsonPrimitive?.content)
        }
        assertEquals("/fixed", server.takeRequest(1, TimeUnit.SECONDS)?.target)
    }

    /** 在真实连接起点主动终止，验证公开地址与 IPv6 精确例外的准入而不访问外部网络。 */
    @Test
    fun `public literals and exact IPv6 exception reach the connection boundary`() = runBlocking {
        listOf(
            "8.8.8.8" to emptyList(),
            "2606:4700:4700::1111" to emptyList(),
            "0:0:0:0:0:0:0:1" to listOf("::1/128"),
        ).forEach { (host, exceptions) ->
            val repository = SettingsChangeCoordinator.forTesting(
                File(temporaryDirectory, "allowed-${System.nanoTime()}.json"), ModelSwitchBarrier(),
            )
            val settings = httpsHostnameSettings().let {
                it.copy(targets = listOf(it.targets.single().copy(host = host, allowedCidrs = exceptions)))
            }
            repository.replaceSettingsForTest(AppSettings(ai = AISettings(httpToolSettings = settings)))
            val connections = AtomicInteger()
            providerWith(
                repository, HttpToolDnsResolver { throw UnknownHostException("unexpected DNS") },
                HttpToolConnectionObserver {
                    connections.incrementAndGet()
                    throw IOException("stop before socket connect")
                }).use { provider ->
                assertEquals(
                    HttpCallingFunctionProvider.error(HttpCallingFunctionProvider.ERROR_REQUEST_FAILED),
                    provider.execute("call_http_api", mapOf("targetId" to "fixed")), host,
                )
            }
            assertEquals(1, connections.get(), host)
        }
    }


    /**
     * 验证 `/23` 前缀匹配恰好覆盖 `2001::/23`，不会把紧邻的正常全球单播地址纳入拒绝范围。
     */
    @Test
    fun `IETF IPv6 special prefix applies exact 2001 slash 23 boundary`() {
        assertFalse(isHttpToolPublicInternetAddress(InetAddress.getByName("2001:1ff:ffff::1")))
        assertTrue(isHttpToolPublicInternetAddress(InetAddress.getByName("2001:200::1")))
    }


    private fun providerWith(
        httpToolSettings: HttpToolSettings,
        resolver: HttpToolDnsResolver = HttpToolDnsResolver { listOf(InetAddress.getByName("127.0.0.1")) },
    ): HttpCallingFunctionProvider {
        val repository = SettingsChangeCoordinator.forTesting(
            File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier(),
        )
        repository.replaceSettingsForTest(AppSettings(ai = AISettings(httpToolSettings = httpToolSettings)))
        return HttpCallingFunctionProvider(repository, resolver)
    }

    private fun providerWith(
        repository: SettingsChangeCoordinator,
        resolver: HttpToolDnsResolver,
        connectionObserver: HttpToolConnectionObserver,
    ): HttpCallingFunctionProvider =
        HttpCallingFunctionProvider.withConnectionObserver(repository, resolver, connectionObserver)

    private fun providerWith(
        repository: SettingsChangeCoordinator,
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
        val repository = SettingsChangeCoordinator.forTesting(
            File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier(),
        )
        repository.replaceSettingsForTest(AppSettings(ai = AISettings(httpToolSettings = httpToolSettings)))
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
