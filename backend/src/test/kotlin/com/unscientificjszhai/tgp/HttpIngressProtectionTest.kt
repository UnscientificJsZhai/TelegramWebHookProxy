package com.unscientificjszhai.tgp

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.*
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import kotlin.test.*
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.seconds

/**
 * Netty HTTP/1 入站读取保护的管线级测试。
 */
class HttpIngressProtectionTest {

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

}
