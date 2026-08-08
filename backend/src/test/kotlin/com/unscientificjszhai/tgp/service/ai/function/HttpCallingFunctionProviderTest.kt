package com.unscientificjszhai.tgp.service.ai.function

import com.sun.net.httpserver.HttpServer
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.agent.AgentToolExecutionContext
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
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
        val repository = SettingsRepository.forTesting(
            File(temporaryDirectory, "settings-${System.nanoTime()}.json"),
            ModelSwitchBarrier(),
        )
        repository.replaceSettingsForTest(AppSettings(ai = AISettings(httpToolSettings = httpToolSettings)))
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
