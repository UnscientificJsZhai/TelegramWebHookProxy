package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.ConfigJson
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Telegram HTTP 客户端代理切换与关闭生命周期的测试设计。
 */
class TelegramServiceClientLifecycleTest {
    private val temporaryDirectory = createTempDirectory("telegram-client-lifecycle-test").toFile()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        temporaryDirectory.deleteRecursively()
    }

    /**
     * 验证历史非法代理只为 Telegram 禁用代理，且后续合法代理仍能恢复。
     */
    @Test
    fun `historical invalid proxy is disabled only for Telegram and a later valid proxy recovers`() = runBlocking {
        val configFile = temporaryDirectory.resolve("historical-invalid.json")
        val historicalSettings = AppSettings(proxy = ProxySettings("proxy.example.com", 70000, ProxyType.HTTP))
        configFile.writeText(ConfigJson.encodeToString(historicalSettings))
        val settings = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())
        val proxies = mutableListOf<ProxySettings?>()
        val service =
            TelegramService(scope, settings, UpdatesRepository(temporaryDirectory.resolve("updates.json"))) { proxy ->
                synchronized(proxies) { proxies += proxy }
                newClient()
            }

        try {
            assertEquals(listOf(null), synchronized(proxies) { proxies.toList() })
            assertEquals(ConfigJson.encodeToString(historicalSettings), configFile.readText())

            val validProxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS)
            settings.saveSettings(historicalSettings.copy(proxy = validProxy))

            eventually {
                assertEquals(validProxy, synchronized(proxies) { proxies.last() })
            }
        } finally {
            service.close()
        }
    }

    /**
     * 验证候选客户端构造失败不会关闭已安装客户端，后续无关设置会重试当前代理。
     */
    @Test
    fun `candidate construction failure preserves active client and later valid setting succeeds`() = runBlocking {
        val settings =
            SettingsRepository.forTesting(temporaryDirectory.resolve("candidate-failure.json"), ModelSwitchBarrier())
        val clients = mutableListOf<HttpClient>()
        val attemptedHosts = mutableListOf<String?>()
        val service = TelegramService(
            scope,
            settings,
            UpdatesRepository(temporaryDirectory.resolve("candidate-updates.json"))
        ) { proxy ->
            synchronized(attemptedHosts) { attemptedHosts += proxy?.host }
            if (proxy?.host == "broken.example.com") {
                throw IllegalStateException("candidate failure")
            }
            newClient().also { client -> synchronized(clients) { clients += client } }
        }

        try {
            val firstProxy = ProxySettings("127.0.0.1", 1080, ProxyType.HTTP)
            settings.saveSettings(AppSettings(proxy = firstProxy))
            eventually { assertEquals(2, synchronized(clients) { clients.size }) }
            val installedClient = synchronized(clients) { clients.last() }

            settings.saveSettings(AppSettings(proxy = ProxySettings("broken.example.com", 1081, ProxyType.HTTP)))
            eventually {
                assertTrue("broken.example.com" in synchronized(attemptedHosts) { attemptedHosts.toList() })
            }
            assertTrue(installedClient.coroutineContext[Job]!!.isActive)

            settings.saveSettings(
                AppSettings(
                    telegramToken = "100:changed-token",
                    proxy = ProxySettings("broken.example.com", 1081, ProxyType.HTTP),
                ),
            )
            eventually {
                assertEquals(
                    2,
                    synchronized(attemptedHosts) { attemptedHosts.count { it == "broken.example.com" } },
                )
            }
            assertTrue(installedClient.coroutineContext[Job]!!.isActive)

            val secondProxy = ProxySettings("127.0.0.1", 1082, ProxyType.SOCKS)
            settings.saveSettings(AppSettings(proxy = secondProxy))
            eventually {
                assertEquals(3, synchronized(clients) { clients.size })
                assertEquals(secondProxy, settings.settingsFlow.value.proxy)
            }
            eventually { assertFalse(installedClient.coroutineContext[Job]!!.isActive) }
        } finally {
            service.close()
        }
    }

    /**
     * 验证默认入口在代理 collector 被候选客户端构造阻塞时，仍同步使用保存后的 token，且 token
     * 单独变更不会在候选客户端成功安装后重复创建客户端。
     */
    @Test
    fun `default requests use saved token while proxy collector is blocked`() = runBlocking {
        val firstToken = "100:first"
        val secondToken = "200:second"
        val pendingProxy = ProxySettings("pending.example.com", 1080, ProxyType.HTTP)
        val candidateStarted = CompletableDeferred<Unit>()
        val candidateInstalled = CompletableDeferred<ProxySettings?>()
        val allowCandidate = CountDownLatch(1)
        val attemptedProxies = mutableListOf<ProxySettings?>()
        val requestUrls = mutableListOf<String>()
        val settings = SettingsRepository.forTesting(
            temporaryDirectory.resolve("default-token-linearization.json"),
            ModelSwitchBarrier(),
        )
        settings.saveSettings(AppSettings(telegramToken = firstToken))
        val service = TelegramService(
            scope,
            settings,
            UpdatesRepository(temporaryDirectory.resolve("default-token-linearization-updates.json")),
            clientFactory = { proxy ->
                synchronized(attemptedProxies) { attemptedProxies += proxy }
                if (proxy == pendingProxy) {
                    candidateStarted.complete(Unit)
                    allowCandidate.await()
                }
                newClient { request ->
                    synchronized(requestUrls) { requestUrls += request.url.toString() }
                    when {
                        request.url.encodedPath.endsWith("/getUpdates") -> emptyUpdatesResponse()
                        request.url.encodedPath.endsWith("/getFile") -> fileResponse()
                        request.url.encodedPath.contains("/file/") -> respond(
                            content = "downloaded-file",
                            status = HttpStatusCode.OK,
                        )

                        else -> textResponse("ok")
                    }
                }
            },
            clientInstalledObserver = { proxy -> candidateInstalled.complete(proxy) },
        )

        try {
            settings.saveSettings(AppSettings(telegramToken = firstToken, proxy = pendingProxy))
            withTimeout(2.seconds) { candidateStarted.await() }
            settings.saveSettings(AppSettings(telegramToken = secondToken, proxy = pendingProxy))

            service.sendMessage("chat", "message")
            service.sendChatAction("chat", "typing")
            service.getUpdates()
            service.getFile("file-id")
            assertEquals("downloaded-file", service.downloadFile("documents/file.txt").decodeToString())

            val urls = synchronized(requestUrls) { requestUrls.toList() }
            assertEquals(5, urls.size)
            assertTrue(urls.all { "/bot$secondToken/" in it })
            assertTrue(urls.any { it.contains("/sendMessage") })
            assertTrue(urls.any { it.contains("/sendChatAction") })
            assertTrue(urls.any { it.contains("/getUpdates") })
            assertTrue(urls.any { it.contains("/getFile") })
            assertTrue(urls.any { it.contains("/file/bot$secondToken/") })

            allowCandidate.countDown()
            assertEquals(pendingProxy, withTimeout(2.seconds) { candidateInstalled.await() })
            assertEquals(2, synchronized(attemptedProxies) { attemptedProxies.size })
        } finally {
            allowCandidate.countDown()
            service.close()
        }
    }

    /**
     * 验证 token 与代理同时切换时，已获租约的默认请求继续使用旧 token 和客户端，而切换完成后的
     * 默认请求使用新 token 和新客户端；旧客户端在旧租约释放前不会关闭。
     */
    @Test
    fun `token and proxy switch preserves old default lease and routes later request through new client`() =
        runBlocking {
            val firstToken = "100:first"
            val secondToken = "200:second"
            val firstProxy = ProxySettings("first.example.com", 1080, ProxyType.HTTP)
            val secondProxy = ProxySettings("second.example.com", 1081, ProxyType.SOCKS)
            val firstRequestStarted = CompletableDeferred<Unit>()
            val releaseFirstRequest = CompletableDeferred<Unit>()
            val candidateStarted = CompletableDeferred<Unit>()
            val candidateInstalled = CompletableDeferred<ProxySettings?>()
            val allowCandidate = CountDownLatch(1)
            val requestUrls = mutableListOf<String>()
            val clients = mutableListOf<HttpClient>()
            val settings = SettingsRepository.forTesting(
                temporaryDirectory.resolve("token-proxy-lease.json"),
                ModelSwitchBarrier(),
            )
            settings.saveSettings(AppSettings(telegramToken = firstToken, proxy = firstProxy))
            val service = TelegramService(
                scope,
                settings,
                UpdatesRepository(temporaryDirectory.resolve("token-proxy-lease-updates.json")),
                clientFactory = { proxy ->
                    val client = when (proxy) {
                        firstProxy -> newClient { request ->
                            synchronized(requestUrls) { requestUrls += request.url.toString() }
                            firstRequestStarted.complete(Unit)
                            releaseFirstRequest.await()
                            textResponse("first-client")
                        }

                        secondProxy -> {
                            candidateStarted.complete(Unit)
                            allowCandidate.await()
                            newClient { request ->
                                synchronized(requestUrls) { requestUrls += request.url.toString() }
                                textResponse("second-client")
                            }
                        }

                        else -> error("Unexpected proxy: $proxy")
                    }
                    synchronized(clients) { clients += client }
                    client
                },
                clientInstalledObserver = { proxy -> candidateInstalled.complete(proxy) },
            )

            try {
                val firstRequest = async { service.sendMessage("chat", "first") }
                withTimeout(2.seconds) { firstRequestStarted.await() }
                val firstClient = synchronized(clients) { clients.single() }

                settings.saveSettings(AppSettings(telegramToken = secondToken, proxy = secondProxy))
                withTimeout(2.seconds) { candidateStarted.await() }
                allowCandidate.countDown()
                assertEquals(secondProxy, withTimeout(2.seconds) { candidateInstalled.await() })
                assertTrue(firstClient.coroutineContext[Job]!!.isActive)

                assertEquals("second-client", service.sendMessage("chat", "second").body)
                assertTrue(synchronized(requestUrls) { requestUrls.any { "/bot$secondToken/sendMessage" in it } })

                releaseFirstRequest.complete(Unit)
                assertEquals("first-client", withTimeout(2.seconds) { firstRequest.await() }.body)
                assertTrue(synchronized(requestUrls) { requestUrls.any { "/bot$firstToken/sendMessage" in it } })
                withTimeout(2.seconds) {
                    while (firstClient.coroutineContext[Job]!!.isActive) {
                        yield()
                    }
                }
            } finally {
                allowCandidate.countDown()
                releaseFirstRequest.complete(Unit)
                service.close()
            }
        }

    /**
     * 验证已取得租约的长轮询可在客户端切换后完成，旧客户端随后才关闭。
     */
    @Test
    fun `old client remains open until an in flight long poll releases its lease`() = runBlocking {
        val settings =
            SettingsRepository.forTesting(temporaryDirectory.resolve("lease-delay.json"), ModelSwitchBarrier())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val clients = mutableListOf<HttpClient>()
        val service =
            TelegramService(scope, settings, UpdatesRepository(temporaryDirectory.resolve("lease-updates.json"))) { _ ->
                val client = if (synchronized(clients) { clients.isEmpty() }) {
                    newClient {
                        started.complete(Unit)
                        release.await()
                        emptyUpdatesResponse()
                    }
                } else {
                    newClient { emptyUpdatesResponse() }
                }
                synchronized(clients) { clients += client }
                client
            }

        try {
            val longPoll = async { service.getUpdatesForToken("100:token", timeout = 30) }
            withTimeout(2.seconds) { started.await() }
            val oldClient = synchronized(clients) { clients.first() }

            settings.saveSettings(AppSettings(proxy = ProxySettings("127.0.0.1", 1080, ProxyType.HTTP)))
            eventually { assertEquals(2, synchronized(clients) { clients.size }) }
            assertTrue(oldClient.coroutineContext[Job]!!.isActive)

            release.complete(Unit)
            withTimeout(2.seconds) { longPoll.await() }
            eventually { assertFalse(oldClient.coroutineContext[Job]!!.isActive) }
        } finally {
            release.complete(Unit)
            service.close()
        }
    }

    /**
     * 验证发送消息在租约内读取响应正文，代理切换不会截断调用方收到的响应快照。
     */
    @Test
    fun `message response body is read before releasing the client lease`() = runBlocking {
        val settings =
            SettingsRepository.forTesting(temporaryDirectory.resolve("response-body.json"), ModelSwitchBarrier())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val clients = mutableListOf<HttpClient>()
        val service = TelegramService(
            scope,
            settings,
            UpdatesRepository(temporaryDirectory.resolve("response-body-updates.json"))
        ) { _ ->
            val client = if (synchronized(clients) { clients.isEmpty() }) {
                newClient {
                    started.complete(Unit)
                    release.await()
                    textResponse("telegram-body")
                }
            } else {
                newClient { textResponse("next-client") }
            }
            synchronized(clients) { clients += client }
            client
        }

        try {
            val send = async { service.sendMessageForToken("100:token", "chat", "message") }
            withTimeout(2.seconds) { started.await() }
            val oldClient = synchronized(clients) { clients.first() }

            settings.saveSettings(AppSettings(proxy = ProxySettings("127.0.0.1", 1080, ProxyType.HTTP)))
            eventually { assertEquals(2, synchronized(clients) { clients.size }) }
            assertTrue(oldClient.coroutineContext[Job]!!.isActive)

            release.complete(Unit)
            assertEquals("telegram-body", withTimeout(2.seconds) { send.await() }.body)
            eventually { assertFalse(oldClient.coroutineContext[Job]!!.isActive) }
        } finally {
            release.complete(Unit)
            service.close()
        }
    }

    /**
     * 验证关闭与候选创建、流更新竞争时，未安装候选会关闭且服务不再接受新请求。
     */
    @Test
    fun `close wins a race with candidate creation and flow updates`() = runBlocking {
        val settings =
            SettingsRepository.forTesting(temporaryDirectory.resolve("close-race.json"), ModelSwitchBarrier())
        val candidateStarted = CompletableDeferred<Unit>()
        val allowCandidate = CountDownLatch(1)
        val clients = mutableListOf<HttpClient>()
        val service = TelegramService(
            scope,
            settings,
            UpdatesRepository(temporaryDirectory.resolve("close-race-updates.json"))
        ) { proxy ->
            val client = newClient()
            synchronized(clients) { clients += client }
            if (proxy?.host == "candidate.example.com") {
                candidateStarted.complete(Unit)
                allowCandidate.await()
            }
            client
        }

        try {
            settings.saveSettings(AppSettings(proxy = ProxySettings("candidate.example.com", 1080, ProxyType.HTTP)))
            withTimeout(2.seconds) { candidateStarted.await() }
            settings.saveSettings(AppSettings(proxy = ProxySettings("next.example.com", 1081, ProxyType.SOCKS)))
            service.close()
            allowCandidate.countDown()

            eventually {
                val candidate = synchronized(clients) { clients[1] }
                assertFalse(candidate.coroutineContext[Job]!!.isActive)
            }
        } finally {
            allowCandidate.countDown()
            service.close()
        }
    }

    private fun newClient(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { emptyUpdatesResponse() },
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) }) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private fun MockRequestHandleScope.emptyUpdatesResponse(): HttpResponseData = respond(
        content = """{"ok":true,"result":[]}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.fileResponse(): HttpResponseData = respond(
        content = """{"ok":true,"result":{"file_id":"file-id","file_unique_id":"unique-id","file_path":"documents/file.txt"}}""",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

    private fun MockRequestHandleScope.textResponse(content: String): HttpResponseData = respond(
        content = content,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
    )

    private suspend fun eventually(assertion: () -> Unit) {
        withTimeout(3.seconds) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    delay(20)
                }
            }
        }
    }
}
