package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.models.validateMcpServerConfigs
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.nio.charset.StandardCharsets
import javax.inject.Inject

/**
 * 管理一个 Agent 作用域到 MCP 服务器的连接，并提供已发现工具的查询与调用能力。
 *
 * 同一 [com.unscientificjszhai.tgp.di.AgentComponent] 会复用同一实例及其 HTTP 客户端；不同组件的
 * 连接、工具快照和 HTTP 客户端彼此隔离。服务维护当前连接配置的快照；调用 [connect] 会将连接状态
 * 同步为传入配置。调用 [close] 后服务进入终态，不再接受新的连接或工具调用。
 */
@AgentScope
class MCPClientService internal constructor(
    @Suppress("UNUSED_PARAMETER") parentScope: CoroutineScope,
    private val clientFactory: () -> Client,
) {
    /**
     * 使用默认 MCP 客户端创建器创建服务。
     *
     * @param parentScope 创建此服务的父协程作用域。终态关闭不依赖该作用域，即使其已取消也会完成资源
     * 清理。
     */
    @Inject
    constructor(parentScope: CoroutineScope) : this(
        parentScope,
        {
            Client(
                Implementation(name = "telegram-webhook-proxy", version = "1.1.3"),
            )
        },
    )

    private val logger = LoggerFactory.getLogger(MCPClientService::class.java)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient =
        HttpClient(OkHttp) {
            engine {
                config {
                    addInterceptor(Interceptor { chain ->
                        val response = chain.proceed(chain.request())
                        if (response.body.contentLength() > MAX_MCP_RESPONSE_BYTES) {
                            response.close()
                            throw McpResponseTooLargeException()
                        }
                        response.newBuilder()
                            .body(BoundedMcpResponseBody(response.body, MAX_MCP_RESPONSE_BYTES))
                            .build()
                    })
                }
            }
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = 300000
            }
        }

    private data class ConnectionState(
        val clients: Map<String, Client> = emptyMap(),
        val serverTools: Map<String, List<Tool>> = emptyMap(),
        val configs: Map<String, MCPServerConfig> = emptyMap(),
    )

    /**
     * 串行化连接、断开与工具调用，使客户端不会在在途工具调用期间关闭。
     */
    private val connectionMutex = Mutex()

    /**
     * 保护关闭标记和快照发布，避免关闭与正在建立的连接相互覆盖。
     */
    private val stateLock = Any()

    /**
     * 串行化终态关闭的创建，使重复关闭共用同一清理任务。
     */
    private val lifecycleLock = Any()

    @Volatile
    private var closed = false

    private var closeJob: Job? = null

    /**
     * 使用不可变快照，使工具发现和调用可以安全读取已提交的连接状态。
     */
    @Volatile
    private var connectionState = ConnectionState()

    /**
     * 将已连接的服务器同步为指定配置，并发现各服务器提供的工具。
     *
     * 此方法先构造并验证完整候选连接快照；任何连接或发现失败都会关闭候选并保留当前快照。仅当所有
     * 服务器成功就绪后才一次发布新快照，随后关闭旧客户端，避免工具声明与调用绑定跨快照混用。
     *
     * @param configs 目标 MCP 服务器配置列表；列表为空时断开所有当前服务器。方法会在连接互斥锁内先复制并
     * 校验列表及请求头，服务器名称应在复制后的列表内唯一且符合 [validateMcpServerConfigs] 的连接边界。
     * @throws IllegalStateException 当服务已经 [close] 时抛出。
     * @throws IllegalArgumentException [configs] 包含不合法 MCP 配置时抛出；不会断开已有连接或发布部分状态。
     */
    suspend fun connect(configs: List<MCPServerConfig>) {
        ensureOpen()
        connectionMutex.withLock {
            ensureOpen()
            val requestedConfigs = configs.map { config -> config.copy(headers = config.headers.toMap()) }
            validateMcpServerConfigs(requestedConfigs)
            val candidates = linkedMapOf<String, Pair<Client, List<Tool>>>()
            try {
                for (config in requestedConfigs) {
                    val candidate = connectCandidate(config)
                    try {
                        validateDiscoveredTools(
                            candidate.second,
                            candidates.values.sumOf { it.second.size },
                        )
                        candidates[config.name] = candidate
                    } catch (e: Exception) {
                        closeClient(config.name, candidate.first)
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                candidates.forEach { (name, candidate) -> closeClient(name, candidate.first) }
                throw e
            } catch (e: Exception) {
                candidates.forEach { (name, candidate) -> closeClient(name, candidate.first) }
                logger.warn("Failed to prepare MCP connection candidate; keeping previous snapshot.")
                return
            }
            val candidateState = ConnectionState(
                clients = candidates.mapValues { it.value.first },
                serverTools = candidates.mapValues { it.value.second },
                configs = requestedConfigs.associateBy { it.name },
            )
            val previous = synchronized(stateLock) {
                if (closed) null else connectionState.also { connectionState = candidateState }
            }
            if (previous == null) {
                candidates.forEach { (name, candidate) -> closeClient(name, candidate.first) }
                throw IllegalStateException("MCP client service is closed.")
            }
            previous.clients.forEach { (name, client) -> closeClient(name, client) }
        }
    }

    private suspend fun connectCandidate(config: MCPServerConfig): Pair<Client, List<Tool>> {
        val client = clientFactory()

        val url = config.url
        val transport =
            StreamableHttpClientTransport(
                httpClient,
                url,
            ) {
                config.headers.forEach { (key, value) ->
                    header(key, value)
                }
            }

        try {
            client.connect(transport)
            val tools = client.listTools().tools.toList()
            currentCoroutineContext().ensureActive()
            logger.info("Connected to MCP server: ${config.name}, discovered ${tools.size} tools.")
            return client to tools
        } catch (e: CancellationException) {
            closeClient(config.name, client)
            throw e
        } catch (e: Exception) {
            closeClient(config.name, client)
            throw e
        }
    }

    /**
     * 终态关闭 MCP 服务。
     *
     * 首次调用会同步拒绝后续 [connect] 和 [callTool]，并立即清空可见工具快照；已开始的工具调用会先
     * 完成，随后恰好一次地关闭当时持有的 MCP 客户端与 HTTP 客户端。清理任务使用独立作用域，因此
     * 不依赖创建服务的父作用域仍处于活动状态。重复调用返回同一个任务。
     *
     * @return 幂等的异步清理任务；等待其完成后所有此实例拥有的网络资源均已释放。
     */
    fun close(): Job = synchronized(lifecycleLock) {
        closeJob ?: run {
            val clientsToClose = synchronized(stateLock) {
                closed = true
                connectionState.also { connectionState = ConnectionState() }.clients
            }
            closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    connectionMutex.withLock {
                        clientsToClose.forEach { (name, client) -> closeClient(name, client) }
                    }
                    httpClient.close()
                }
            }.also { closeJob = it }
        }
    }

    @Suppress("unused")
    private suspend fun disconnectLocked(name: String) {
        val client = synchronized(stateLock) {
            val state = connectionState
            val currentClient = state.clients[name] ?: return
            connectionState = state.copy(
                clients = state.clients - name,
                serverTools = state.serverTools - name,
                configs = state.configs - name,
            )
            currentClient
        }
        closeClient(name, client)
    }

    private suspend fun closeClient(name: String, client: Client) {
        withContext(NonCancellable) {
            try {
                client.close()
            } catch (e: Exception) {
                logger.error("Error closing MCP client: $name", e)
            }
        }
    }

    /**
     * 获取当前所有已连接服务器发现到的工具。
     *
     * @return 服务器名称与工具组成的列表；未连接任何服务器或未发现工具时返回空列表。列表
     * 顺序来自当前连接快照，不应视为稳定排序。
     */
    fun getAllTools(): List<Pair<String, Tool>> =
        currentConnectionState().serverTools.flatMap { (serverName, tools) ->
            tools.map { serverName to it }
        }

    /**
     * 调用指定 MCP 服务器上的工具。
     *
     * 调用会与连接变更串行执行，避免在工具调用期间关闭目标客户端。服务终态关闭后，即使此前
     * 调用已清空工具快照也不会再启动新的调用。
     *
     * @param serverName 已连接 MCP 服务器的名称。
     * @param toolName 目标服务器已提供的工具名称。
     * @param args 传递给工具的参数映射；值可为 `null`，其键和值应符合工具的输入架构。
     * @return MCP 服务器返回的工具调用结果。
     * @throws IllegalStateException 当服务已 [close]，或 [serverName] 对应的服务器尚未连接时抛出。
     */
    suspend fun callTool(
        serverName: String,
        toolName: String,
        args: Map<String, Any?>,
    ): CallToolResult {
        ensureOpen()
        validateMcpArguments(args)
        return connectionMutex.withLock {
            val client = synchronized(stateLock) {
                check(!closed) { "MCP client service is closed." }
                connectionState.clients[serverName]
                    ?: throw IllegalStateException("MCP server $serverName not connected")
            }
            client.callTool(toolName, args).also(::validateMcpToolResult)
        }
    }

    private fun ensureOpen() {
        check(!closed) { "MCP client service is closed." }
    }

    private fun currentConnectionState(): ConnectionState = synchronized(stateLock) { connectionState }
}

internal const val MAX_MCP_RESPONSE_BYTES = 1024L * 1024L
internal const val MAX_MCP_TOOLS_PER_SERVER = 32
internal const val MAX_MCP_TOOLS_TOTAL = 128
internal const val MAX_MCP_TOOL_NAME_BYTES = 128
internal const val MAX_MCP_TOOL_DESCRIPTION_BYTES = 4 * 1024
internal const val MAX_MCP_TOOL_SCHEMA_BYTES = 64 * 1024
internal const val MAX_MCP_TOOL_ARGUMENT_BYTES = 64 * 1024
internal const val MAX_MCP_TOOL_RESULT_BYTES = 256 * 1024

/** MCP 上游响应在解压后的实际读取字节超过上限。 */
internal class McpResponseTooLargeException : IOException("MCP 响应超过 1 MiB 限制。")

/** MCP 工具调用参数超过可安全传递给服务器的上限。 */
internal class McpToolArgumentsTooLargeException : IllegalArgumentException("MCP 工具调用参数超过限制。")

/** MCP 工具结果超过可安全放入模型上下文的上限。 */
internal class McpToolResultTooLargeException : IllegalStateException("MCP 工具结果超过限制。")

private class BoundedMcpResponseBody(
    private val delegate: ResponseBody,
    private val limit: Long,
) : ResponseBody() {
    private val boundedSource = object : ForwardingSource(delegate.source()) {
        private var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val read = super.read(sink, byteCount)
            if (read > 0) {
                total += read
                if (total > limit) {
                    close()
                    throw McpResponseTooLargeException()
                }
            }
            return read
        }
    }.buffer()

    override fun contentType() = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source() = boundedSource

    override fun close() = delegate.close()
}

private fun validateDiscoveredTools(tools: List<Tool>, existingCount: Int) {
    require(tools.size <= MAX_MCP_TOOLS_PER_SERVER) {
        "单个 MCP 服务器工具不能超过 $MAX_MCP_TOOLS_PER_SERVER 个。"
    }
    require(existingCount + tools.size <= MAX_MCP_TOOLS_TOTAL) {
        "MCP 工具总数不能超过 $MAX_MCP_TOOLS_TOTAL 个。"
    }
    tools.forEach { tool ->
        require(tool.name.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_MCP_TOOL_NAME_BYTES) {
            "MCP 工具名称长度不合法。"
        }
        require((tool.description ?: "").toByteArray(StandardCharsets.UTF_8).size <= MAX_MCP_TOOL_DESCRIPTION_BYTES) {
            "MCP 工具描述超过限制。"
        }
        require(tool.inputSchema.toString().toByteArray(StandardCharsets.UTF_8).size <= MAX_MCP_TOOL_SCHEMA_BYTES) {
            "MCP 工具输入架构超过限制。"
        }
    }
}

private fun validateMcpArguments(args: Map<String, Any?>) {
    val encoded = runCatching {
        Json.encodeToString(JsonObject.serializer(), buildJsonObject {
            args.forEach { (name, value) -> put(name, value.toMcpJsonElement()) }
        })
    }
        .getOrElse { throw McpToolArgumentsTooLargeException() }
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_MCP_TOOL_ARGUMENT_BYTES) {
        throw McpToolArgumentsTooLargeException()
    }
}

private fun Any?.toMcpJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Byte, is Short, is Int, is Long, is Float, is Double -> JsonPrimitive(this as Number)
    is Map<*, *> -> buildJsonObject {
        this@toMcpJsonElement.forEach { (key, value) ->
            val name = key as? String ?: throw IllegalArgumentException("MCP 参数对象键必须是字符串。")
            put(name, value.toMcpJsonElement())
        }
    }

    is Iterable<*> -> JsonArray(map { it.toMcpJsonElement() })
    is Array<*> -> JsonArray(map { it.toMcpJsonElement() })
    else -> throw IllegalArgumentException("MCP 参数包含不支持的值类型。")
}

private fun validateMcpToolResult(result: CallToolResult) {
    val encoded = Json.encodeToString(CallToolResult.serializer(), result)
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_MCP_TOOL_RESULT_BYTES) {
        throw McpToolResultTooLargeException()
    }
}
