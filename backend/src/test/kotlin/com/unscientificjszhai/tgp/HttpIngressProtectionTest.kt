package com.unscientificjszhai.tgp

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.measureTime
import kotlin.coroutines.CoroutineContext

/**
 * Netty HTTP/1 入站读取保护的套接字级测试设计。
 */
class HttpIngressProtectionTest {

    /**
     * 验证完全静默的 TCP 连接会被 codec 前的原始读空闲处理器主动关闭。
     */
    @Test
    fun `silent connection is closed by raw read idle timeout`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 120.milliseconds,
            headerTimeout = 2.seconds,
            requestTotalTimeout = 3.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            val elapsed = measureTime {
                assertTrue(socket.awaitClosed())
            }
            assertTrue(
                elapsed < 500.milliseconds,
                "raw idle connection closed after $elapsed instead of before the header deadline",
            )
        }
    }

    /**
     * 验证持续滴流但始终无法形成完整请求头的连接会超过请求头绝对时限。
     */
    @Test
    fun `slow header drip is closed by header deadline`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 150.milliseconds,
            headerTimeout = 180.milliseconds,
            requestTotalTimeout = 2.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            val elapsed = measureTime {
                val closedWhileWriting = socket.writeSlowly("GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\n", 45)
                assertTrue(closedWhileWriting || socket.awaitClosed())
            }
            assertTrue(elapsed < 500.milliseconds, "header drip closed after $elapsed instead of its deadline")
        }
    }

    /**
     * 验证请求读取完成后，Ktor 业务处理超过 raw idle 期限仍能写出完整的最终响应。
     */
    @Test
    fun `slow Ktor handler is not closed by raw idle before its response`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 100.milliseconds,
            headerTimeout = 1.seconds,
            bodyTimeout = 1.seconds,
            requestTotalTimeout = 2.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest("GET /slow HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
        }
    }

    /**
     * 验证 CR、LF、空格和 DEL 的滴流都会从首个原始字节开始计算 header deadline。
     */
    @Test
    fun `control character drip is closed by header deadline`() {
        val controls = listOf(
            "CR" to "\r",
            "LF" to "\n",
            "space" to " ",
            "DEL" to "\u007f",
        )
        for ((description, control) in controls) {
            withIngressServer(
                limits = testLimits(
                    rawReadIdleTimeout = 150.milliseconds,
                    headerTimeout = 180.milliseconds,
                    requestTotalTimeout = 2.seconds,
                ),
            ) { port, _ ->
                Socket("127.0.0.1", port).use { socket ->
                    val elapsed = measureTime {
                        val closedWhileWriting = socket.writeSlowly(control.repeat(10), 45)
                        assertTrue(closedWhileWriting || socket.awaitClosed(), "$description drip did not close")
                    }
                    assertTrue(
                        elapsed < 500.milliseconds,
                        "$description drip closed after $elapsed instead of the header deadline",
                    )
                }
            }
        }
    }

    /**
     * 验证请求体滴流不会通过持续发送字节重置请求体绝对时限。
     */
    @Test
    fun `slow body drip is closed by body deadline`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 150.milliseconds,
            bodyTimeout = 180.milliseconds,
            requestTotalTimeout = 2.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.write(
                "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 16\r\n\r\n".toByteArray(),
            )
            socket.outputStream.flush()

            val elapsed = measureTime {
                val closedWhileWriting = socket.writeSlowly("0123456789abcdef", 45)
                assertTrue(closedWhileWriting || socket.awaitClosed())
            }
            assertTrue(elapsed < 500.milliseconds, "body drip closed after $elapsed instead of its deadline")
        }
    }

    /**
     * 验证总时限包含滴流请求头已经消耗的时间，且不会被转入请求体阶段而重置。
     */
    @Test
    fun `request total deadline includes slow header and body drip`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 150.milliseconds,
            headerTimeout = 2.seconds,
            bodyTimeout = 2.seconds,
            requestTotalTimeout = 250.milliseconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.write('P'.code)
            socket.outputStream.flush()

            val elapsedFromFirstHeaderByte = measureTime {
                assertFalse(socket.writeSlowly("OST /echo ", 20))
                socket.outputStream.write(
                    "HTTP/1.1\r\nHost: localhost\r\nContent-Length: 32\r\n\r\n".toByteArray(),
                )
                socket.outputStream.flush()

                val closedWhileWriting = socket.writeSlowly("0123456789abcdefghijklmnopqrstuv", 30)
                assertTrue(closedWhileWriting || socket.awaitClosed())
            }
            assertTrue(
                elapsedFromFirstHeaderByte < 350.milliseconds,
                "request closed after $elapsedFromFirstHeaderByte instead of the total deadline from its first byte",
            )
        }
    }

    /**
     * 验证到达连接上限的 channel 不会获得许可，关闭已获许可的 channel 后许可会仅归还一次。
     */
    @Test
    fun `connection permit is rejected and released on close`() = withIngressServer(
        limits = testLimits(maxConnections = 1, rawReadIdleTimeout = 500.milliseconds),
    ) { port, admission ->
        Socket("127.0.0.1", port).use { first ->
            assertEventually { admission.availablePermits() == 0 }

            Socket("127.0.0.1", port).use { rejected ->
                assertTrue(rejected.awaitClosed())
            }
            assertEquals(0, admission.availablePermits())

            first.close()
            assertEventually { admission.availablePermits() == 1 }

            Socket("127.0.0.1", port).use {
                assertEventually { admission.availablePermits() == 0 }
            }
            assertEventually { admission.availablePermits() == 1 }
        }
    }

    /**
     * 验证 keep-alive 上的下一请求会取消前一请求留下的截止任务。
     */
    @Test
    fun `keep alive request restarts deadline without closing connection`() = withIngressServer(
        limits = testLimits(headerTimeout = 500.milliseconds, rawReadIdleTimeout = 400.milliseconds),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)

            Thread.sleep(300)
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)

            // 此时已经超过第一请求的 500 ms 截止点，但尚未超过第二请求重启后的截止点。
            Thread.sleep(250)
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
        }
    }

    /**
     * 验证 keep-alive 请求在原始读空闲期限将至时仍可正常开始，不会继承前一请求的时间预算。
     */
    @Test
    fun `keep alive request shortly before raw idle remains accepted`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 400.milliseconds,
            headerTimeout = 500.milliseconds,
            bodyTimeout = 1.seconds,
            requestTotalTimeout = 1.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)

            Thread.sleep(300)
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
        }
    }

    /**
     * 验证上一请求完成 9.9 秒后的首个下一请求字节会获得自己的 header 期限。
     */
    @Test
    fun `keep alive header after nine point nine seconds does not inherit prior request time`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 11.seconds,
            headerTimeout = 11.seconds,
            bodyTimeout = 12.seconds,
            requestTotalTimeout = 12.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)

            Thread.sleep(9_900)
            socket.outputStream.writeRequest("GET /ok ")
            Thread.sleep(1_500)
            socket.outputStream.writeRequest("HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
        }
    }

    /**
     * 验证完成请求后的静默 keep-alive 连接会被 raw idle 关闭并归还连接许可。
     */
    @Test
    fun `completed keep alive connection is closed and releases permit on raw idle`() = withIngressServer(
        limits = testLimits(
            maxConnections = 1,
            rawReadIdleTimeout = 120.milliseconds,
            headerTimeout = 2.seconds,
            bodyTimeout = 2.seconds,
            requestTotalTimeout = 3.seconds,
        ),
    ) { port, admission ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
            val elapsed = measureTime {
                assertTrue(socket.awaitClosed())
            }
            assertTrue(elapsed < 500.milliseconds)
            assertEventually { admission.availablePermits() == 1 }
        }
    }

    /**
     * 验证一个 TCP 写入内的 HTTP/1 流水线请求会分别完成，不会被已取消的截止任务误杀。
     */
    @Test
    fun `pipelined requests do not inherit a stale deadline`() = withIngressServer(
        limits = testLimits(
            headerTimeout = 250.milliseconds,
            bodyTimeout = 2.seconds,
            rawReadIdleTimeout = 200.milliseconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest(
                "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n" +
                        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\nConnection: close\r\n\r\nP",
            )
            Thread.sleep(100)
            socket.outputStream.writeRequest("O")
            Thread.sleep(100)
            socket.outputStream.writeRequest("S")
            Thread.sleep(100)
            socket.outputStream.writeRequest("T")
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
            assertEquals(HttpStatusCode.OK.value, socket.readHttpResponse().status)
        }
    }

    /**
     * 验证同一 TCP 分片中的前一请求结尾与下一请求开头不会使下一请求遗漏首字节总时限。
     */
    @Test
    fun `same packet pipelined request keeps its own total deadline`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 120.milliseconds,
            headerTimeout = 2.seconds,
            bodyTimeout = 2.seconds,
            requestTotalTimeout = 250.milliseconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            val secondRequestFirstByteAtNanos = System.nanoTime()
            socket.outputStream.writeRequest(
                "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n" +
                        "POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 1024\r\nConnection: close\r\n\r\nP",
            )

            // 继续滴流以避免 raw idle 抢先关闭；错误实现会直到下一次 raw read 才启动第二个
            // 请求的总时限，而正确实现仍会从该同分片首字节起关闭连接。
            val keepDripping = AtomicBoolean(true)
            val dripper = thread(isDaemon = true) {
                while (keepDripping.get()) {
                    try {
                        socket.outputStream.write('x'.code)
                        socket.outputStream.flush()
                        Thread.sleep(40)
                    } catch (_: SocketException) {
                        break
                    }
                }
            }
            assertTrue(socket.awaitEof())
            keepDripping.set(false)
            dripper.join(1_000)
            val elapsedFromSecondRequestFirstByte =
                (System.nanoTime() - secondRequestFirstByteAtNanos).nanoseconds
            assertTrue(
                elapsedFromSecondRequestFirstByte < 500.milliseconds,
                "same-packet pipelined request closed after $elapsedFromSecondRequestFirstByte instead of its total deadline",
            )
        }
    }

    /**
     * 验证同分片中仅含下一请求部分 header 时，首字节 header deadline 仍从该分片的正确字节偏移开始。
     */
    @Test
    fun `same packet partial next header is closed by header deadline`() = withIngressServer(
        limits = testLimits(
            rawReadIdleTimeout = 2.seconds,
            headerTimeout = 120.milliseconds,
            bodyTimeout = 2.seconds,
            requestTotalTimeout = 3.seconds,
        ),
    ) { port, _ ->
        Socket("127.0.0.1", port).use { socket ->
            val secondRequestFirstByteAtNanos = System.nanoTime()
            socket.outputStream.writeRequest(
                "GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\nP",
            )

            Thread.sleep(300)
            assertTrue(socket.awaitEof())
            val elapsedFromSecondRequestFirstByte =
                (System.nanoTime() - secondRequestFirstByteAtNanos).nanoseconds
            assertTrue(
                elapsedFromSecondRequestFirstByte < 500.milliseconds,
                "partial same-packet request closed after $elapsedFromSecondRequestFirstByte instead of its header deadline",
            )
        }
    }

    /**
     * 验证原始读空闲期限必须是有限正值。
     */
    @Test
    fun `limits reject non positive or infinite raw idle`() {
        assertFailsWith<IllegalArgumentException> {
            testLimits(rawReadIdleTimeout = 0.seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            testLimits(rawReadIdleTimeout = (-1).seconds)
        }
        assertFailsWith<IllegalArgumentException> {
            testLimits(rawReadIdleTimeout = INFINITE)
        }
    }

    /**
     * 验证连接私有字节时间线会在 decoder 消费跨越 raw read 边界时切换到相应时间戳。
     */
    @Test
    fun `byte timeline maps decoder offset to matching raw read timestamp`() {
        val timeline = HttpByteTimeline()
        timeline.recordRawBytes(3, 100)
        timeline.recordRawBytes(5, 200)
        assertEquals(100, timeline.timestampAtCurrentOffset())
        timeline.advance(3)
        assertEquals(200, timeline.timestampAtCurrentOffset())
    }

    /**
     * 验证持续分片请求体在每次 decoder 消费后都会释放已消费的 raw-read 时间线段。
     */
    @Test
    fun `byte timeline discards consumed segments during multi read body`() {
        val timeline = HttpByteTimeline()
        repeat(1_000) { index ->
            timeline.recordRawBytes(1, index.toLong())
            timeline.advance(1)
        }
        assertEquals(0, timeline.pendingSegmentCount())
    }

    /**
     * 验证 codec 先发出首字节标记、再发 HTTP 对象；同分片下一请求的标记位于前一请求结尾之后。
     */
    @Test
    fun `tracking codec orders request start markers around same packet requests`() {
        val observed = mutableListOf<String>()
        val channel = EmbeddedChannel(
            TrackingHttpServerCodec(testLimits()),
            markerObservingHandler(observed),
        )
        try {
            assertTrue(
                channel.writeInbound(
                    Unpooled.copiedBuffer(
                        "GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\nP",
                        Charsets.US_ASCII,
                    ),
                ),
            )
            assertEquals(listOf("marker", "request", "last", "marker"), observed)
        } finally {
            channel.drainInboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证 deadline handler 消费内部标记，不把它泄漏给 Ktor 下游处理器。
     */
    @Test
    fun `deadline handler consumes request start markers`() {
        val observed = mutableListOf<String>()
        val channel = EmbeddedChannel(
            TrackingHttpServerCodec(testLimits()),
            HttpRequestDeadlineHandler(testLimits()),
            markerObservingHandler(observed),
        )
        try {
            channel.writeInbound(
                Unpooled.copiedBuffer(
                    "GET /ok HTTP/1.1\r\nHost: localhost\r\n\r\nP",
                    Charsets.US_ASCII,
                ),
            )
            assertEquals(listOf("request", "last"), observed)
        } finally {
            channel.drainInboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证等待请求状态的首个原始字节（包括 Netty 忽略的前导控制字符）会启动 marker，且后续控制字节不会重复启动。
     */
    @Test
    fun `tracking codec starts marker from raw first byte only once`() {
        assertMarkerForPartialRequest("MKCOL /", "MKCOL")
        assertMarkerForPartialRequest("BREW /", "BREW")

        for ((description, control) in listOf("CR" to "\r", "LF" to "\n", "space" to " ", "DEL" to "\u007f")) {
            val observed = mutableListOf<String>()
            val channel = EmbeddedChannel(TrackingHttpServerCodec(testLimits()), markerObservingHandler(observed))
            try {
                channel.writeInbound(Unpooled.copiedBuffer(control, Charsets.US_ASCII))
                assertEquals(listOf("marker"), observed, "$description first byte must start a request deadline")
            } finally {
                channel.drainInboundMessages()
                channel.finishAndReleaseAll()
            }
        }

        val repeatedControlEvents = mutableListOf<String>()
        val repeatedControlChannel = EmbeddedChannel(
            TrackingHttpServerCodec(testLimits()),
            markerObservingHandler(repeatedControlEvents),
        )
        try {
            repeatedControlChannel.writeInbound(Unpooled.copiedBuffer("\r", Charsets.US_ASCII))
            repeatedControlChannel.writeInbound(Unpooled.copiedBuffer("\n", Charsets.US_ASCII))
            repeatedControlChannel.writeInbound(Unpooled.copiedBuffer(" \u007f", Charsets.US_ASCII))
            assertEquals(listOf("marker"), repeatedControlEvents)
        } finally {
            repeatedControlChannel.drainInboundMessages()
            repeatedControlChannel.finishAndReleaseAll()
        }
    }

    /**
     * 验证流水线中仍有另一个最终响应未完成时，已完成响应不会使 raw idle 关闭连接。
     */
    @Test
    fun `raw idle waits for every pipelined final response`() {
        val channel = EmbeddedChannel(HttpRequestDeadlineHandler(testLimits()))
        try {
            channel.writeCompletedRequest("/one")
            channel.writeCompletedRequest("/two")

            assertTrue(channel.writeOutbound(okResponse()))
            channel.drainOutboundMessages()
            channel.runPendingTasks()

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT)
            assertTrue(channel.isOpen)

            assertTrue(channel.writeOutbound(okResponse()))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT)
            assertFalse(channel.isOpen)
        } finally {
            channel.drainInboundMessages()
            channel.drainOutboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证 `100 Continue` 不会完成请求，而 [DefaultFullHttpResponse] 只会完成一次并重置
     * keep-alive 的 raw idle 计时。
     */
    @Test
    fun `continue then full final response completes once and resets raw idle`() {
        val rawReadIdle = ResetTrackingIdleStateHandler()
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("rawReadIdle", rawReadIdle)
        channel.pipeline().addLast("requestDeadline", HttpRequestDeadlineHandler(testLimits()))
        try {
            channel.writeCompletedRequest()

            assertTrue(
                channel.writeOutbound(
                    DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE),
                ),
            )
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(0, rawReadIdle.resetCalls)

            assertTrue(channel.writeOutbound(okResponse()))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(1, rawReadIdle.resetCalls)
        } finally {
            channel.drainInboundMessages()
            channel.drainOutboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证普通流式最终响应会等待成功写出的终止块；响应头和中间内容都不完成请求。
     */
    @Test
    fun `streaming final response completes after its successful last content`() {
        val rawReadIdle = ResetTrackingIdleStateHandler()
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("rawReadIdle", rawReadIdle)
        channel.pipeline().addLast("requestDeadline", HttpRequestDeadlineHandler(testLimits()))
        try {
            channel.writeCompletedRequest()

            assertTrue(channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(0, rawReadIdle.resetCalls)

            assertTrue(channel.writeOutbound(DefaultHttpContent(Unpooled.copiedBuffer("part", Charsets.US_ASCII))))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(0, rawReadIdle.resetCalls)

            assertTrue(channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(1, rawReadIdle.resetCalls)
        } finally {
            channel.drainInboundMessages()
            channel.drainOutboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证 Netty 的非完整无正文响应仍以空 [LastHttpContent] 结束，且该结束不会遗留响应状态。
     */
    @Test
    fun `no-body final response completes after empty last content`() {
        val rawReadIdle = ResetTrackingIdleStateHandler()
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("rawReadIdle", rawReadIdle)
        channel.pipeline().addLast("requestDeadline", HttpRequestDeadlineHandler(testLimits()))
        try {
            channel.writeCompletedRequest()

            assertTrue(channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(0, rawReadIdle.resetCalls)

            assertTrue(channel.writeOutbound(LastHttpContent.EMPTY_LAST_CONTENT))
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(1, rawReadIdle.resetCalls)

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT)
            assertFalse(channel.isOpen)
        } finally {
            channel.drainInboundMessages()
            channel.drainOutboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证 `101 Switching Protocols` 不需要 [LastHttpContent] 也会结束对应请求。
     */
    @Test
    fun `switching protocols response completes without last content`() {
        val rawReadIdle = ResetTrackingIdleStateHandler()
        val channel = EmbeddedChannel()
        channel.pipeline().addLast("rawReadIdle", rawReadIdle)
        channel.pipeline().addLast("requestDeadline", HttpRequestDeadlineHandler(testLimits()))
        try {
            channel.writeCompletedRequest()

            assertTrue(
                channel.writeOutbound(
                    DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS),
                ),
            )
            channel.drainOutboundMessages()
            channel.runPendingTasks()
            assertEquals(1, rawReadIdle.resetCalls)

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT)
            assertFalse(channel.isOpen)
        } finally {
            channel.drainInboundMessages()
            channel.drainOutboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证最终响应的响应头或中间内容写入失败都会立即关闭连接，防止遗留未完成的响应计数。
     */
    @Test
    fun `failed final response head or content write closes the channel`() {
        for ((description, failingWriteNumber) in listOf("head" to 1, "content" to 2)) {
            val channel = EmbeddedChannel(
                FailingWriteHandler(failingWriteNumber),
                HttpRequestDeadlineHandler(testLimits()),
            )
            try {
                channel.writeCompletedRequest()

                if (failingWriteNumber == 2) {
                    assertTrue(channel.writeOutbound(DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)))
                    channel.drainOutboundMessages()
                }
                assertFailsWith<IllegalStateException>("$description write must fail") {
                    val message = if (failingWriteNumber == 1) {
                        DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
                    } else {
                        DefaultHttpContent(Unpooled.copiedBuffer("part", Charsets.US_ASCII))
                    }
                    channel.writeOutbound(message)
                }
                channel.runPendingTasks()
                assertFalse(channel.isOpen, "$description write failure must close the channel")
            } finally {
                channel.drainInboundMessages()
                channel.drainOutboundMessages()
                channel.finishAndReleaseAll()
            }
        }
    }

    /**
     * 验证前一个请求仍等待业务响应时，第二个正在读取的请求仍受 raw idle 保护。
     */
    @Test
    fun `raw idle still closes a slow second request while prior response is pending`() {
        val channel = EmbeddedChannel(HttpRequestDeadlineHandler(testLimits()))
        try {
            channel.writeCompletedRequest("/first")
            channel.pipeline().fireUserEventTriggered(RequestStartMarker(System.nanoTime()))
            assertTrue(
                channel.writeInbound(
                    DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/second"),
                ),
            )
            channel.drainInboundMessages()

            channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT)
            assertFalse(channel.isOpen)
        } finally {
            channel.drainInboundMessages()
            channel.drainOutboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    /**
     * 验证复制的 server encoder 保留 Netty 对 GET、HEAD 与 CONNECT 的响应语义。
     */
    @Test
    fun `tracking codec preserves get head and connect response encoding`() {
        assertTrue(codecResponse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n").contains("body"))
        assertFalse(codecResponse("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n").contains("body"))
        assertFalse(
            codecResponse(
                "CONNECT localhost:443 HTTP/1.1\r\nHost: localhost\r\n\r\n",
                chunked = true,
            ).lowercase().contains("transfer-encoding"),
        )
    }

    /**
     * 验证应用响应管线会在 Netty upgrade 路径前以 501 拒绝不受支持的 ProtocolUpgrade。
     */
    @Test
    fun `protocol upgrade response is rejected before netty codec replacement`() = withIngressServer(
        limits = testLimits(),
    ) { port, _ ->
        unsupportedProtocolUpgradeWasInvoked.set(false)
        Socket("127.0.0.1", port).use { socket ->
            socket.outputStream.writeRequest("GET /upgrade HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
            val response = runCatching { socket.readHttpResponse() }.getOrNull()
            assertEquals(HttpStatusCode.NotImplemented.value, response?.status)
        }
        assertFalse(unsupportedProtocolUpgradeWasInvoked.get())
    }

    private fun withIngressServer(
        limits: HttpIngressLimits,
        block: (port: Int, admission: HttpConnectionAdmission) -> Unit,
    ) {
        val admission = HttpConnectionAdmission(limits.maxConnections)
        val server = embeddedServer(
            factory = Netty,
            rootConfig = serverConfig {
                module {
                    installProtocolUpgradeRejection()
                    routing {
                        get("/ok") {
                            call.respondText("ok")
                        }
                        get("/slow") {
                            delay(250.milliseconds)
                            call.respondText("slow")
                        }
                        post("/echo") {
                            call.receiveText()
                            call.respondText("ok")
                        }
                        get("/upgrade") {
                            call.respond(UnsupportedProtocolUpgrade)
                        }
                    }
                }
            },
            configure = {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                configureHttpIngressProtection(limits, admission)
            },
        ).start(wait = false)

        try {
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            block(port, admission)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 0)
        }
    }

    private fun testLimits(
        maxConnections: Int = 8,
        rawReadIdleTimeout: kotlin.time.Duration = 1.seconds,
        headerTimeout: kotlin.time.Duration = 1.seconds,
        bodyTimeout: kotlin.time.Duration = 1.seconds,
        requestTotalTimeout: kotlin.time.Duration = 3.seconds,
    ) = HttpIngressLimits(
        maxConnections = maxConnections,
        runningRequestsPerConnection = 4,
        acceptBacklog = 8,
        rawReadIdleTimeout = rawReadIdleTimeout,
        headerTimeout = headerTimeout,
        bodyTimeout = bodyTimeout,
        requestTotalTimeout = requestTotalTimeout,
    )

    private fun markerObservingHandler(observed: MutableList<String>) = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            observed += when (msg) {
                is RequestStartMarker -> "marker"
                is HttpRequest -> "request"
                is LastHttpContent -> "last"
                else -> "other"
            }
            ctx.fireChannelRead(msg)
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            observed += if (evt is RequestStartMarker) "marker" else "event"
            ctx.fireUserEventTriggered(evt)
        }
    }

    private fun assertMarkerForPartialRequest(requestPrefix: String, expectedMethod: String) {
        val observed = mutableListOf<String>()
        val channel = EmbeddedChannel(TrackingHttpServerCodec(testLimits()), markerObservingHandler(observed))
        try {
            channel.writeInbound(Unpooled.copiedBuffer(requestPrefix, Charsets.US_ASCII))
            assertEquals(listOf("marker"), observed, "$expectedMethod partial request must start a deadline")
        } finally {
            channel.drainInboundMessages()
            channel.finishAndReleaseAll()
        }
    }

    private fun codecResponse(request: String, chunked: Boolean = false): String {
        val channel = EmbeddedChannel(TrackingHttpServerCodec(testLimits()))
        try {
            channel.writeInbound(Unpooled.copiedBuffer(request, Charsets.US_ASCII))
            channel.drainInboundMessages()

            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                Unpooled.copiedBuffer("body", Charsets.US_ASCII),
            )
            if (chunked) {
                response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked")
            }
            assertTrue(channel.writeOutbound(response))
            return channel.drainOutboundText()
        } finally {
            channel.finishAndReleaseAll()
        }
    }

    private fun EmbeddedChannel.drainOutboundText(): String {
        val result = StringBuilder()
        while (true) {
            val message = readOutbound<Any>() ?: break
            try {
                if (message is ByteBuf) {
                    result.append(message.toString(Charsets.US_ASCII))
                }
            } finally {
                ReferenceCountUtil.release(message)
            }
        }
        return result.toString()
    }

    private fun EmbeddedChannel.drainInboundMessages() {
        while (true) {
            val message = readInbound<Any>() ?: return
            ReferenceCountUtil.release(message)
        }
    }

    private fun EmbeddedChannel.drainOutboundMessages() {
        while (true) {
            val message = readOutbound<Any>() ?: return
            ReferenceCountUtil.release(message)
        }
    }

    private fun EmbeddedChannel.writeCompletedRequest(uri: String = "/") {
        pipeline().fireUserEventTriggered(RequestStartMarker(System.nanoTime()))
        assertTrue(
            writeInbound(
                DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri),
            ),
        )
        drainInboundMessages()
    }

    private fun okResponse() = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)

    private class ResetTrackingIdleStateHandler : IdleStateHandler(1, 0, 0) {
        var resetCalls = 0

        override fun resetReadTimeout() {
            resetCalls++
            super.resetReadTimeout()
        }
    }

    private class FailingWriteHandler(
        private val failingWriteNumber: Int = 1,
    ) : ChannelOutboundHandlerAdapter() {
        private var writeCount = 0

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            writeCount++
            if (writeCount == failingWriteNumber) {
                ReferenceCountUtil.release(msg)
                promise.setFailure(IllegalStateException("test final write failure"))
            } else {
                ctx.write(msg, promise)
            }
        }
    }

    private fun Socket.awaitClosed(): Boolean {
        soTimeout = 3_000
        return inputStream.read() == -1
    }

    private fun Socket.awaitEof(): Boolean {
        soTimeout = 3_000
        while (inputStream.read() >= 0) {
            // 丢弃前一流水线请求可能已写出的响应，直到服务端关闭连接。
        }
        return true
    }

    private fun Socket.writeSlowly(value: String, delayMillis: Long): Boolean {
        return try {
            value.toByteArray().forEach { byte ->
                outputStream.write(byte.toInt())
                outputStream.flush()
                Thread.sleep(delayMillis)
            }
            false
        } catch (_: SocketException) {
            true
        }
    }

    private fun OutputStream.writeRequest(value: String) {
        write(value.toByteArray())
        flush()
    }

    private fun Socket.readHttpResponse(): ParsedHttpResponse {
        soTimeout = 3_000
        val headerBytes = ByteArrayOutputStream()
        var matches = 0
        while (matches < 4) {
            val next = inputStream.read()
            check(next >= 0) { "connection closed before HTTP response headers" }
            headerBytes.write(next)
            matches = when {
                matches == 0 && next == '\r'.code -> 1
                matches == 1 && next == '\n'.code -> 2
                matches == 2 && next == '\r'.code -> 3
                matches == 3 && next == '\n'.code -> 4
                next == '\r'.code -> 1
                else -> 0
            }
        }

        val headerText = headerBytes.toString(Charsets.ISO_8859_1)
        val status = headerText.substringBefore("\r\n").split(' ')[1].toInt()
        val contentLength = Regex("(?im)^content-length: (\\d+)\\s*$")
            .find(headerText)
            ?.groupValues
            ?.get(1)
            ?.toInt()
            ?: 0
        repeat(contentLength) {
            check(inputStream.read() >= 0) { "connection closed before HTTP response body" }
        }
        return ParsedHttpResponse(status)
    }

    private fun assertEventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 2.seconds.inWholeNanoseconds
        while (!condition()) {
            if (System.nanoTime() >= deadline) {
                assertTrue(condition(), "condition did not become true before timeout")
            }
            Thread.sleep(10)
        }
    }

    private data class ParsedHttpResponse(val status: Int)

    private object UnsupportedProtocolUpgrade : OutgoingContent.ProtocolUpgrade() {
        override suspend fun upgrade(
            input: ByteReadChannel,
            output: ByteWriteChannel,
            engineContext: CoroutineContext,
            userContext: CoroutineContext,
        ): Job {
            unsupportedProtocolUpgradeWasInvoked.set(true)
            return Job()
        }
    }

    private companion object {
        val unsupportedProtocolUpgradeWasInvoked = AtomicBoolean(false)
    }
}
