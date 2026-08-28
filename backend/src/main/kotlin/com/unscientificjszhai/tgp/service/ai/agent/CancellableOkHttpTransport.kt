package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okio.Buffer
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
     * @return 响应状态、响应头和成功响应的受限正文；非成功响应正文为空且永不读取。调用方无需也不能再关闭响应体。
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
                            // Error bodies are never needed for recovery decisions and may be attacker-controlled or
                            // arbitrarily large. Preserve the already-known status/headers without reading or storing
                            // the body so providers can still classify 401/429/5xx and Retry-After exactly.
                            body = if (it.isSuccessful) {
                                it.body.readUtf8AtMost(MAX_RAW_RESPONSE_BYTES)
                            } else {
                                ""
                            },
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

internal const val MAX_RAW_RESPONSE_BYTES = 1024 * 1024

/** 上游原生 AI 响应在解压后的实际读取字节超过上限。 */
internal class UpstreamResponseTooLargeException : IOException("上游响应超过 1 MiB 限制。")

private fun ResponseBody.readUtf8AtMost(limit: Int): String {
    if (contentLength() > limit) throw UpstreamResponseTooLargeException()
    val source = source()
    val buffer = Buffer()
    var total = 0L
    while (true) {
        val read = source.read(buffer, 8192)
        if (read == -1L) break
        total += read
        if (total > limit) throw UpstreamResponseTooLargeException()
    }
    return buffer.readUtf8()
}

/** 已由 [CancellableOkHttpTransport] 读取并关闭响应体的 HTTP 响应快照。 */
internal data class HttpResult(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
)
