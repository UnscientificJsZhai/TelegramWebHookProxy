package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** 原生 AI HTTP 传输的取消、关闭和响应生命周期测试。 */
class CancellableOkHttpTransportTest {
    private val server = MockWebServer()

    init {
        server.start()
    }

    @AfterTest
    fun cleanUp() {
        server.close()
    }

    /** 验证协程取消会取消已经由服务器接收的实际 OkHttp Call。 */
    @Test
    fun `cancellation aborts the native OkHttp call`() = runBlocking {
        val failedCalls = AtomicInteger()
        val transport = CancellableOkHttpTransport(
            OkHttpClient.Builder()
                .eventListener(object : EventListener() {
                    override fun callFailed(call: okhttp3.Call, ioe: IOException) {
                        failedCalls.incrementAndGet()
                    }
                })
                .build(),
        )
        server.enqueue(MockResponse.Builder().headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build())
        val request = Request.Builder().url(server.url("/slow")).build()

        val requestJob = async(Dispatchers.Default) { transport.execute(request) }
        assertEquals("/slow", server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)?.target)
        requestJob.cancelAndJoin()

        withTimeout(5.seconds) {
            while (failedCalls.get() == 0) {
                kotlinx.coroutines.yield()
            }
        }
        assertTrue(requestJob.isCancelled)
        transport.close()
    }

    /** 验证关闭会拒绝新请求，避免关闭竞争中创建未受管理的 Call。 */
    @Test
    fun `close rejects new calls`() = runBlocking {
        val transport = CancellableOkHttpTransport(OkHttpClient())
        transport.close()

        assertFailsWith<IllegalStateException> {
            transport.execute(Request.Builder().url(server.url("/after-close")).build())
        }
        Unit
    }
}
