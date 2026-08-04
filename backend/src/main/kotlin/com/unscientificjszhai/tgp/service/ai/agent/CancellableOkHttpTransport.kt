package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 将 OkHttp 请求与协程取消精确关联的私有传输层。
 *
 * 每次执行都会登记唯一的 [Call]。调用协程取消、服务关闭和客户端回调之间通过 [activeCalls] 的
 * 原子移除协调：任一路径都可以安全地调用 [Call.cancel]，但响应体只会由回调路径读取并关闭。
 */
internal class CancellableOkHttpTransport(
    private val client: OkHttpClient,
) {
    private val lifecycleLock = Any()
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    @Volatile
    private var closed = false

    /**
     * 执行一个原生可取消的 HTTP 请求。
     *
     * @param request 要执行的完整 OkHttp 请求。
     * @return 响应状态、响应头和已读取的响应体；调用方无需也不能再关闭响应体。
     * @throws IllegalStateException 当传输层已经关闭时抛出。
     * @throws IOException 当请求被取消、连接失败或读取响应失败时抛出。
     */
    suspend fun execute(request: Request): HttpResult = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        val accepted = synchronized(lifecycleLock) { !closed && activeCalls.add(call) }
        if (!accepted) {
            call.cancel()
            continuation.resumeWithException(IllegalStateException("AI HTTP transport is closed."))
            return@suspendCancellableCoroutine
        }

        continuation.invokeOnCancellation {
            if (activeCalls.remove(call)) {
                call.cancel()
            }
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                activeCalls.remove(call)
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val result = HttpResult(
                            statusCode = it.code,
                            headers = it.headers.toMultimap(),
                            body = it.body.string(),
                        )
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                } finally {
                    activeCalls.remove(call)
                }
            }
        })
    }

    /**
     * 拒绝后续请求并取消所有已经登记的原生调用。
     *
     * 该方法不会等待任一调用的回调或会话锁，从而保证服务关闭能够先中断网络 I/O。
     */
    fun close() {
        val calls = synchronized(lifecycleLock) {
            if (closed) {
                return
            }
            closed = true
            activeCalls.toList().also { activeCalls.clear() }
        }
        calls.forEach(Call::cancel)
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}

/** 已由 [CancellableOkHttpTransport] 读取并关闭响应体的 HTTP 响应快照。 */
internal data class HttpResult(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)
