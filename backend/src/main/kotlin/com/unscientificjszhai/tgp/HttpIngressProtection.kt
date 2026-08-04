/*
 * Portions of TrackingHttpServerCodec derive from Netty's HttpServerCodec.
 * Copyright 2012 The Netty Project. Licensed under the Apache License, Version 2.0.
 */
package com.unscientificjszhai.tgp

import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.pipeline.PipelinePhase
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOption
import io.netty.channel.ChannelPipeline
import io.netty.channel.CombinedChannelDuplexHandler
import io.netty.handler.codec.http.HttpDecoderConfig
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpRequestDecoder
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseEncoder
import io.netty.handler.codec.http.HttpServerUpgradeHandler
import io.netty.handler.codec.http.HttpStatusClass
import io.netty.handler.codec.http.LastHttpContent
import io.netty.handler.timeout.IdleState
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP/1 入站连接和请求读取的资源上限。
 *
 * 所有时限均为有限正值；每个请求在第一个原始字节到请求体完成之间不得超过
 * [requestTotalTimeout]，以防止持续滴流绕过阶段时限。
 */
internal data class HttpIngressLimits(
    val maxConnections: Int = 128,
    val runningRequestsPerConnection: Int = 8,
    val acceptBacklog: Int = 128,
    val maxInitialLineLength: Int = 4 * 1024,
    val maxHeaderSize: Int = 16 * 1024,
    val maxChunkSize: Int = 64 * 1024,
    val rawReadIdleTimeout: Duration = 15.seconds,
    val headerTimeout: Duration = 10.seconds,
    val bodyTimeout: Duration = 30.seconds,
    val requestTotalTimeout: Duration = 45.seconds,
) {
    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(runningRequestsPerConnection > 0) { "runningRequestsPerConnection must be positive" }
        require(acceptBacklog > 0) { "acceptBacklog must be positive" }
        require(maxInitialLineLength > 0) { "maxInitialLineLength must be positive" }
        require(maxHeaderSize > 0) { "maxHeaderSize must be positive" }
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }
        require(rawReadIdleTimeout.isFinite() && rawReadIdleTimeout.isPositive()) {
            "rawReadIdleTimeout must be finite and positive"
        }
        require(headerTimeout.isFinite() && headerTimeout.isPositive()) { "headerTimeout must be finite and positive" }
        require(bodyTimeout.isFinite() && bodyTimeout.isPositive()) { "bodyTimeout must be finite and positive" }
        require(requestTotalTimeout.isFinite() && requestTotalTimeout.isPositive()) {
            "requestTotalTimeout must be finite and positive"
        }
    }
}

/**
 * 跨 Netty channel 共享的连接许可池。
 *
 * 一个连接只能取得一个许可，并且只会在该连接的 `closeFuture` 完成时归还。
 */
internal class HttpConnectionAdmission(maxConnections: Int) {
    private val permits = Semaphore(maxConnections)

    init {
        require(maxConnections > 0) { "maxConnections must be positive" }
    }

    fun tryAcquire(): Boolean = permits.tryAcquire()

    fun release() {
        permits.release()
    }

    internal fun availablePermits(): Int = permits.availablePermits()
}

/**
 * 将 HTTP/1 入站保护安装到 Netty 引擎配置。
 *
 * HTTP/2 与 h2c 被显式关闭，因为该保护器按 HTTP/1 的请求边界维护截止时间。
 *
 * 该配置替换 Netty 的 HTTP codec，因而不支持 Ktor 的非字节缓冲 [io.ktor.http.content.OutgoingContent.ProtocolUpgrade]
 * 路径；该类响应会在应用发送管线中被替换为 `501 Not Implemented`。
 */
internal fun NettyApplicationEngine.Configuration.configureHttpIngressProtection(
    limits: HttpIngressLimits = HttpIngressLimits(),
    admission: HttpConnectionAdmission = HttpConnectionAdmission(limits.maxConnections),
) {
    enableHttp2 = false
    enableH2c = false
    runningLimit = limits.runningRequestsPerConnection
    maxInitialLineLength = limits.maxInitialLineLength
    maxHeaderSize = limits.maxHeaderSize
    maxChunkSize = limits.maxChunkSize
    configureBootstrap = {
        option(ChannelOption.SO_BACKLOG, limits.acceptBacklog)
    }
    channelPipelineConfig = {
        installHttpIngressProtection(limits, admission)
    }
}

/**
 * 在 Netty 引擎写入响应前拒绝 Ktor 的协议升级响应，避免替换后的 HTTP codec 被当作原生
 * [io.netty.handler.codec.http.HttpServerCodec] 使用。
 */
internal fun Application.installProtocolUpgradeRejection() {
    sendPipeline.insertPhaseBefore(ApplicationSendPipeline.Engine, ProtocolUpgradeRejection)
    sendPipeline.intercept(ProtocolUpgradeRejection) {
        if (subject is OutgoingContent.ProtocolUpgrade) {
            proceedWith(HttpStatusCodeContent(HttpStatusCode.NotImplemented))
        }
    }
}

/**
 * 在 Ktor Netty 引擎处理响应内容前拒绝协议升级的发送管线阶段。
 */
private val ProtocolUpgradeRejection = PipelinePhase("ProtocolUpgradeRejection")

/**
 * 为一个 HTTP/1 channel 安装读取超时和连接准入处理器。
 *
 * 原始读空闲检测必须处于 codec 之前，才能计入尚未构成完整 HTTP 对象的字节；请求阶段截止
 * 处理器必须处于 codec 之后，才能观察 [HttpRequest] 与 [LastHttpContent] 边界。
 */
internal fun ChannelPipeline.installHttpIngressProtection(
    limits: HttpIngressLimits,
    admission: HttpConnectionAdmission,
) {
    val requestDeadline = HttpRequestDeadlineHandler(limits)
    replace("codec", "codec", TrackingHttpServerCodec(limits))
    addBefore(
        "codec",
        "rawReadIdle",
        IdleStateHandler(
            limits.rawReadIdleTimeout.inWholeNanoseconds.coerceAtLeast(1),
            0,
            0,
            TimeUnit.NANOSECONDS,
        ),
    )
    addAfter("codec", "requestDeadline", requestDeadline)
    addAfter("requestDeadline", "connectionAdmission", HttpConnectionAdmissionHandler(admission))
}

/**
 * 由 HTTP codec 在解析到请求首字节时发出的内部标记。
 *
 * 标记只携带首个请求字节的单调时钟，不持有 [ByteBuf]，因此不需要引用计数释放。它会被
 * [HttpRequestDeadlineHandler] 消费，不会传递给 Ktor 的 HTTP 处理器。
 */
internal data class RequestStartMarker(val startedAtNanos: Long)

/**
 * 带请求首字节标记的 Netty HTTP/1 server codec。
 *
 * 此实现保留 Netty [io.netty.handler.codec.http.HttpServerCodec] 的请求方法队列与 HEAD、CONNECT
 * 响应编码语义，并在每个请求首字节处插入 [RequestStartMarker]。
 */
internal class TrackingHttpServerCodec(
    limits: HttpIngressLimits,
) : CombinedChannelDuplexHandler<TrackingHttpRequestDecoder, TrackingHttpServerResponseEncoder>(),
    HttpServerUpgradeHandler.SourceCodec {
    private val requestMethods = ArrayDeque<HttpMethod>()

    init {
        val decoderConfig = HttpDecoderConfig()
            .setMaxInitialLineLength(limits.maxInitialLineLength)
            .setMaxHeaderSize(limits.maxHeaderSize)
            .setMaxChunkSize(limits.maxChunkSize)
        init(
            TrackingHttpRequestDecoder(decoderConfig, requestMethods),
            TrackingHttpServerResponseEncoder(requestMethods),
        )
    }

    override fun upgradeFrom(ctx: ChannelHandlerContext) {
        ctx.pipeline().remove(this)
    }
}

/**
 * 记录本次 raw read 的时钟，并在 HTTP decoder 确认请求边界后产生同分片标记。
 */
internal class TrackingHttpRequestDecoder(
    config: HttpDecoderConfig,
    private val requestMethods: ArrayDeque<HttpMethod>,
) : HttpRequestDecoder(config) {
    private val byteTimeline = HttpByteTimeline()
    private var awaitingRequestStart = true

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is ByteBuf && msg.isReadable) {
            byteTimeline.recordRawBytes(msg.readableBytes(), System.nanoTime())
        }
        super.channelRead(ctx, msg)
    }

    override fun decode(ctx: ChannelHandlerContext, buffer: ByteBuf, out: MutableList<Any>) {
        val outputStart = out.size
        val readerIndexBeforeDecode = buffer.readerIndex()
        val consumedOffsetBeforeDecode = byteTimeline.currentOffset()
        val requestStartAtNanos = if (awaitingRequestStart) {
            findHttpMethodStart(buffer)?.let { methodStartIndex ->
                byteTimeline.timestampAtOffset(consumedOffsetBeforeDecode + methodStartIndex - readerIndexBeforeDecode)
            }
        } else {
            null
        }
        super.decode(ctx, buffer, out)
        val consumedByteCount = buffer.readerIndex() - readerIndexBeforeDecode
        byteTimeline.advance(consumedByteCount)

        var completedRequest = false
        var decodedRequest = false
        for (index in outputStart until out.size) {
            when (val message = out[index]) {
                is HttpRequest -> {
                    requestMethods.add(message.method())
                    decodedRequest = message.decoderResult().isSuccess
                }

                is LastHttpContent -> completedRequest = true
            }
        }
        if (awaitingRequestStart && decodedRequest && consumedByteCount > 0) {
            out.add(outputStart, RequestStartMarker(requireNotNull(requestStartAtNanos)))
            awaitingRequestStart = false
        }
        if (completedRequest && buffer.isReadable) {
            if (hasHttpMethodTokenPrefix(buffer)) {
                out.add(RequestStartMarker(byteTimeline.timestampAtCurrentOffset()))
                awaitingRequestStart = false
            } else {
                awaitingRequestStart = true
            }
        } else if (completedRequest) {
            awaitingRequestStart = true
        } else if (awaitingRequestStart) {
            val partialRequestStartAtNanos = when {
                requestStartAtNanos != null && (consumedByteCount > 0 || buffer.isReadable) -> requestStartAtNanos
                buffer.isReadable && hasHttpMethodTokenPrefix(buffer) -> byteTimeline.timestampAtCurrentOffset()
                else -> null
            }
            if (partialRequestStartAtNanos != null) {
                ctx.fireUserEventTriggered(RequestStartMarker(partialRequestStartAtNanos))
                awaitingRequestStart = false
            }
        }
    }

    private fun hasHttpMethodTokenPrefix(buffer: ByteBuf): Boolean {
        return findHttpMethodStart(buffer) != null
    }

    private fun findHttpMethodStart(buffer: ByteBuf): Int? {
        for (index in buffer.readerIndex() until buffer.writerIndex()) {
            val asciiByte = buffer.getUnsignedByte(index).toInt()
            if (asciiByte.isNettyControlOrWhitespace()) continue
            return index.takeIf { asciiByte.isHttpTokenStart() }
        }
        return null
    }

    private fun Int.isNettyControlOrWhitespace(): Boolean = this in 0x00..0x20 || this == 0x7f

    private fun Int.isHttpTokenStart(): Boolean =
        this in '0'.code..'9'.code ||
                this in 'A'.code..'Z'.code ||
                this in 'a'.code..'z'.code ||
                toChar() in "!#$%&'*+-.^_`|~"
}

/**
 * 连接私有的原始字节时间线，将 decoder 的消费偏移量映射回对应 raw read 的单调时钟。
 */
internal class HttpByteTimeline {
    private data class Segment(val endOffsetExclusive: Long, val receivedAtNanos: Long)

    private val segments = ArrayDeque<Segment>()
    private var receivedOffset = 0L
    private var consumedOffset = 0L

    fun recordRawBytes(byteCount: Int, receivedAtNanos: Long) {
        require(byteCount > 0) { "byteCount must be positive" }
        receivedOffset += byteCount
        segments.add(Segment(receivedOffset, receivedAtNanos))
    }

    fun timestampAtCurrentOffset(): Long {
        return timestampAtOffset(consumedOffset)
    }

    fun currentOffset(): Long = consumedOffset

    fun timestampAtOffset(offset: Long): Long {
        require(offset in consumedOffset until receivedOffset) { "offset $offset has no received raw byte" }
        return requireNotNull(segments.firstOrNull()) {
            "offset $offset has no raw byte timestamp"
        }.let { first ->
            if (offset < first.endOffsetExclusive) first.receivedAtNanos else {
                segments.first { offset < it.endOffsetExclusive }.receivedAtNanos
            }
        }
    }

    fun advance(consumedByteCount: Int) {
        require(consumedByteCount >= 0) { "consumedByteCount must not be negative" }
        consumedOffset += consumedByteCount
        check(consumedOffset <= receivedOffset) { "decoder consumed bytes that were not received" }
        discardConsumedSegments()
    }

    internal fun pendingSegmentCount(): Int = segments.size

    private fun discardConsumedSegments() {
        while (segments.isNotEmpty() && segments.first.endOffsetExclusive <= consumedOffset) {
            segments.removeFirst()
        }
    }
}

/**
 * 复制 Netty server response encoder 的请求方法关联逻辑，确保 HEAD 与 CONNECT 响应保持原语义。
 */
internal class TrackingHttpServerResponseEncoder(
    private val requestMethods: ArrayDeque<HttpMethod>,
) : HttpResponseEncoder() {
    private var requestMethod: HttpMethod? = null

    override fun sanitizeHeadersBeforeEncode(msg: HttpResponse, isAlwaysEmpty: Boolean) {
        if (
            !isAlwaysEmpty &&
            HttpMethod.CONNECT == requestMethod &&
            msg.status().codeClass() == HttpStatusClass.SUCCESS
        ) {
            msg.headers().remove(HttpHeaderNames.TRANSFER_ENCODING)
            return
        }
        super.sanitizeHeadersBeforeEncode(msg, isAlwaysEmpty)
    }

    override fun isContentAlwaysEmpty(msg: HttpResponse): Boolean {
        requestMethod = requestMethods.poll()
        return HttpMethod.HEAD == requestMethod || super.isContentAlwaysEmpty(msg)
    }
}

/**
 * 为每个连接持有共享许可，拒绝超过并发上限的连接。
 */
internal class HttpConnectionAdmissionHandler(
    private val admission: HttpConnectionAdmission,
) : ChannelInboundHandlerAdapter() {
    private val released = AtomicBoolean(false)
    private var acquired = false

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        acquired = admission.tryAcquire()
        if (!acquired) {
            ctx.close()
            return
        }
        ctx.channel().closeFuture().addListener {
            if (released.compareAndSet(false, true)) {
                admission.release()
            }
        }
    }

    override fun channelActive(ctx: ChannelHandlerContext) {
        if (acquired) {
            ctx.fireChannelActive()
        } else {
            ctx.close()
        }
    }
}

/**
 * 按 HTTP/1 请求边界维护绝对读取截止时间。
 *
 * [RequestStartMarker] 会同时启动请求头与请求总时限；收到 [HttpRequest] 后转为等待请求体；
 * 收到 [LastHttpContent] 后取消该请求的截止时间。原始读空闲始终是连接级上限，完整请求后的
 * keep-alive 连接也必须继续发送入站字节。
 */
internal class HttpRequestDeadlineHandler(
    private val limits: HttpIngressLimits,
) : ChannelInboundHandlerAdapter() {
    private var deadline: ScheduledFuture<*>? = null
    private var requestStartedAtNanos: Long? = null
    private var awaitingRequestStart = true
    private var generation = 0L

    override fun channelActive(ctx: ChannelHandlerContext) {
        ctx.fireChannelActive()
    }

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is RequestStartMarker) {
            check(awaitingRequestStart) { "request start marker arrived while another request is active" }
            startRequestHeaderDeadline(ctx, msg.startedAtNanos)
            return
        }
        if (msg is HttpRequest) {
            check(!awaitingRequestStart) { "HTTP request arrived without a request start marker" }
            startBodyDeadline(ctx)
        }
        if (msg is LastHttpContent) {
            completeRequest()
        }
        ctx.fireChannelRead(msg)
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        cancelDeadline()
        ctx.fireChannelInactive()
    }

    override fun handlerRemoved(ctx: ChannelHandlerContext) {
        cancelDeadline()
    }

    override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
        if (evt is RequestStartMarker) {
            check(awaitingRequestStart) { "request start marker arrived while another request is active" }
            startRequestHeaderDeadline(ctx, evt.startedAtNanos)
            return
        }
        if (evt is IdleStateEvent && evt.state() == IdleState.READER_IDLE) {
            ctx.close()
            return
        }
        ctx.fireUserEventTriggered(evt)
    }

    private fun startRequestHeaderDeadline(ctx: ChannelHandlerContext, startedAtNanos: Long) {
        requestStartedAtNanos = startedAtNanos
        awaitingRequestStart = false
        scheduleHeaderDeadline(ctx)
    }

    private fun scheduleHeaderDeadline(ctx: ChannelHandlerContext) {
        val requestStartedAt = requestStartedAtNanos ?: error("request start must be recorded before header deadline")
        val elapsedNanos = (System.nanoTime() - requestStartedAt).coerceAtLeast(0)
        val remainingHeaderNanos = (limits.headerTimeout.inWholeNanoseconds - elapsedNanos).coerceAtLeast(1)
        val remainingTotalNanos = (limits.requestTotalTimeout.inWholeNanoseconds - elapsedNanos).coerceAtLeast(1)
        schedule(ctx, minOf(remainingHeaderNanos, remainingTotalNanos).nanoseconds)
    }

    private fun completeRequest() {
        requestStartedAtNanos = null
        awaitingRequestStart = true
        cancelDeadline()
    }

    private fun startBodyDeadline(ctx: ChannelHandlerContext) {
        val requestStartedAt = requestStartedAtNanos ?: error("request start must be recorded before body deadline")
        val elapsedNanos = (System.nanoTime() - requestStartedAt).coerceAtLeast(0)
        val remainingTotalNanos = (limits.requestTotalTimeout.inWholeNanoseconds - elapsedNanos).coerceAtLeast(1)
        val phaseNanos = minOf(limits.bodyTimeout.inWholeNanoseconds, remainingTotalNanos)
        schedule(ctx, phaseNanos.nanoseconds)
    }

    private fun schedule(ctx: ChannelHandlerContext, timeout: Duration) {
        cancelDeadline()
        val scheduledGeneration = ++generation
        deadline = ctx.executor().schedule(
            {
                if (generation == scheduledGeneration && ctx.channel().isOpen) {
                    ctx.close()
                }
            },
            timeout.inWholeNanoseconds.coerceAtLeast(1),
            TimeUnit.NANOSECONDS,
        )
    }

    private fun cancelDeadline() {
        generation++
        deadline?.cancel(false)
        deadline = null
    }
}
