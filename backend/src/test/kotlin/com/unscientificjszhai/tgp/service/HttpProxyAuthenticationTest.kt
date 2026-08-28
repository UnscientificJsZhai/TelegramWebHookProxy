package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.*
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** HTTP 代理 Basic 认证器的挑战与重试保护测试。 */
class HttpProxyAuthenticationTest {
    private val proxyServer = MockWebServer()

    init {
        proxyServer.start()
    }

    @AfterTest
    fun cleanUp() {
        proxyServer.close()
    }

    /** 验证真实 HTTP 代理挑战只重试一次，并在第二次请求携带 Basic 认证头。 */
    @Test
    fun `real HTTP proxy adds Basic credentials once after 407`() {
        proxyServer.enqueue(
            MockResponse.Builder()
                .code(407)
                .setHeader("Proxy-Authenticate", "Basic realm=proxy")
                .build(),
        )
        proxyServer.enqueue(MockResponse.Builder().code(200).body("proxied").build())
        val client = OkHttpClient.Builder().apply {
            proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyServer.port)))
            configureHttpProxyBasicAuthentication(
                ProxySettings("127.0.0.1", proxyServer.port, ProxyType.HTTP, "user", "password"),
            )
        }.build()

        client.newCall(Request.Builder().url("http://upstream.example/resource").build()).execute().use { response ->
            assertEquals(200, response.code)
            assertEquals("proxied", response.body.string())
        }
        assertNull(
            assertNotNull(proxyServer.takeRequest(5, TimeUnit.SECONDS)).headers["Proxy-Authorization"],
        )
        assertEquals(
            Credentials.basic("user", "password"),
            assertNotNull(proxyServer.takeRequest(5, TimeUnit.SECONDS)).headers["Proxy-Authorization"],
        )
        assertEquals(2, proxyServer.requestCount)
    }

    /** 验证 HTTP 代理收到一次 407 后携带 Basic 凭据重试，重复挑战时停止重试。 */
    @Test
    fun `HTTP proxy authentication retries once with Basic credentials`() {
        val client = OkHttpClient.Builder().apply {
            configureHttpProxyBasicAuthentication(
                ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, "user", "password"),
            )
        }.build()
        val challenge = proxyAuthenticationChallenge(Request.Builder().url("http://upstream.example/resource").build())

        val retriedRequest = assertNotNull(client.proxyAuthenticator.authenticate(null, challenge))
        assertEquals(Credentials.basic("user", "password"), retriedRequest.header("Proxy-Authorization"))

        assertNull(client.proxyAuthenticator.authenticate(null, proxyAuthenticationChallenge(retriedRequest)))
    }

    /** 验证 SOCKS 代理不会安装 HTTP Basic 认证响应。 */
    @Test
    fun `SOCKS proxy does not authenticate HTTP proxy challenges`() {
        val client = OkHttpClient.Builder().apply {
            configureHttpProxyBasicAuthentication(
                ProxySettings("proxy.example.com", 1080, ProxyType.SOCKS, "user", "password"),
            )
        }.build()

        assertNull(
            client.proxyAuthenticator.authenticate(
                null,
                proxyAuthenticationChallenge(Request.Builder().url("http://upstream.example/resource").build()),
            ),
        )
    }

    private fun proxyAuthenticationChallenge(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(407)
        .message("Proxy Authentication Required")
        .build()
}
