@file:Suppress(
    "LoggingSimilarMessage",
    "RedundantIf",
    "RemoveExplicitTypeArguments",
)

package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.di.AgentScope
import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.models.validateMcpServerConfigs
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.EmbeddedResource
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
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
import org.slf4j.LoggerFactory
import okhttp3.Interceptor
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.IdentityHashMap
import javax.inject.Inject

/**
 * 管理一个 Agent 作用域到 MCP 服务器的连接，并提供已发现工具的查询与调用能力。
 *
 * 同一 [com.unscientificjszhai.tgp.di.AgentComponent] 会复用同一实例及其 HTTP 客户端；不同组件的
 * 连接、工具快照和 HTTP 客户端彼此隔离。服务维护当前连接配置的快照；调用 [connect] 会将连接状态
 * 同步为传入配置。调用 [close] 后服务进入终态，不再接受新的连接或工具调用；返回的任务只有在关闭栅栏前
 * 已登记的客户端清理与 HTTP 客户端都实际结束后才完成。
 */
@AgentScope
class MCPClientService internal constructor(
    @Suppress("UNUSED_PARAMETER") parentScope: CoroutineScope,
    private val deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    private val clientFactory: () -> Client,
) {
    /**
     * 使用默认 MCP 客户端创建器创建服务。
     *
     * @param parentScope 创建此服务的父协程作用域。终态关闭不依赖该作用域，即使其已取消也会完成资源
     * 清理。
     */
    @Inject
    internal constructor(parentScope: CoroutineScope, deadlines: AgentExecutionDeadlines) : this(
        parentScope,
        deadlines,
        {
            Client(
                Implementation(name = "telegram-webhook-proxy", version = "1.1.3"),
            )
        },
    )

    /**
     * 使用默认时限和默认 MCP 客户端创建器创建服务。
     *
     * 此构造器保留给未通过依赖注入创建服务的既有调用方；生产代码应由依赖注入提供统一的
     * [AgentExecutionDeadlines]。
     *
     * @param parentScope 创建此服务的父协程作用域。
     */
    constructor(parentScope: CoroutineScope) : this(parentScope, AgentExecutionDeadlines())

    private val logger = LoggerFactory.getLogger(MCPClientService::class.java)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient =
        HttpClient(OkHttp) {
            // MCP 配置可包含凭据，且 Streamable HTTP 会将其请求头用于 POST、SSE GET 和 DELETE；共享客户端
            // 的所有请求均不得自动跟随重定向，以免向重定向目标重放这些请求头。
            followRedirects = false
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
        val configSnapshot: ConnectionConfigSnapshot = ConnectionConfigSnapshot(),
    )

    /**
     * 用于比较连接配置的私有规范化快照。
     *
     * 服务器顺序以及每个请求头的原始名称和值均属于连接身份；请求头已按名称和值排序，因而输入映射的
     * 迭代顺序不会导致不必要的重连。
     */
    private data class ConnectionConfigSnapshot(
        val servers: List<ServerConfigSnapshot> = emptyList(),
    ) {
        fun withoutServer(name: String): ConnectionConfigSnapshot =
            copy(servers = servers.filterNot { it.name == name })
    }

    /** 单个服务器的私有规范化连接身份。 */
    private data class ServerConfigSnapshot(
        val name: String,
        val url: String,
        val headers: List<HeaderSnapshot>,
    )

    /** 单个请求头的私有规范化连接身份。 */
    private data class HeaderSnapshot(
        val name: String,
        val value: String,
    )

    /** 已防御复制、校验并规范化的连接请求。 */
    private data class PreparedConnection(
        val configs: List<MCPServerConfig>,
        val snapshot: ConnectionConfigSnapshot,
    )

    /**
     * 串行化连接、断开与工具调用，使客户端不会在在途工具调用期间关闭。
     */
    private val connectionMutex = Mutex()

    /**
     * 保护关闭标记和快照发布，避免关闭与正在建立的连接相互覆盖。
     */
    private val stateLock = Any()

    /** 最新连接请求的代次；超时、关闭或更晚请求会使旧候选永久失去发布资格。 */
    private var connectionRequestGeneration = 0L

    /**
     * 串行化终态关闭的创建，使重复关闭共用同一清理任务。
     */
    private val lifecycleLock = Any()

    /** 跟踪已从可见状态摘除、但仍在独立作用域中关闭的客户端，避免慢速关闭占用连接锁。 */
    private val cleanupLock = Any()
    private val clientCleanupJobs = mutableMapOf<Client, Job>()

    /** 终态关闭在连接栅栏后封闭登记；封闭前已登记的全部清理都必须完成才可报告关闭完成。 */
    private var cleanupRegistrationFenced = false

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
     * 此方法先防御复制并验证完整配置。完整批次受 [AgentExecutionDeadlines.mcpBatch] 限制，时限覆盖取得
     * 连接锁、全部服务器连接和工具发现；超时会立即摘除可见状态并在独立作用域清理客户端，因而不会让
     * 后续连接等待缓慢或不响应取消的 `close`。若当前完整连接的规范化快照与请求相同，会直接复用，既不发起
     * 网络 I/O 也不关闭客户端。其他配置变更会先原子清空工具快照并关闭旧客户端，再建立完整候选；
     * 候选连接或发现失败时会关闭已创建的候选并保持空快照，确保旧凭据和旧地址绝不会继续被调用。
     *
     * @param configs 目标 MCP 服务器配置列表；列表为空时断开所有当前服务器。方法会先复制并校验列表及
     * 请求头，服务器名称应在复制后的列表内唯一且符合 [validateMcpServerConfigs] 的连接边界。
     * @throws IllegalStateException 当服务已经 [close] 时抛出。
     * @throws IllegalArgumentException [configs] 包含不合法 MCP 配置时抛出；不会断开已有连接或发布部分状态。
     */
    suspend fun connect(configs: List<MCPServerConfig>) {
        ensureOpen()
        val requested = prepareConnection(configs)
        val generation = synchronized(stateLock) {
            check(!closed) { "MCP client service is closed." }
            ++connectionRequestGeneration
        }
        try {
            withTimeout(deadlines.mcpBatch) {
                connectWithinDeadline(requested, generation)
            }
        } catch (_: TimeoutCancellationException) {
            // 不能等待 candidate/old client 的 close：某些实现会忽略取消，若在锁内 NonCancellable 等待会
            // 使所有未来配置永久饥饿。先摘除状态；若摘除的是已发布客户端，后台清理会先取得连接锁，确保
            // 已经开始的 tool call 结束后才关闭它。
            synchronized(lifecycleLock) {
                // 与终态关闭共用此短临界区：要么超时先摘除并登记完整清理交接，要么关闭先推进代次使本次
                // 摘除为空。这样终态的 cleanup fence 不会漏掉刚超时的已发布客户端。
                schedulePublishedClientsForCleanupAfterInFlightCalls(detachTimedOutConnectionState(generation).clients)
            }
            logger.warn("MCP connection batch timed out; leaving the connection snapshot empty.")
        }
    }

    /** 在总体 deadline 内完成一次连接状态替换；调用方负责将 timeout 转为安全的空快照。 */
    private suspend fun connectWithinDeadline(requested: PreparedConnection, generation: Long) {
        connectionMutex.withLock {
            if (!isCurrentConnectionRequest(generation)) {
                return
            }
            ensureOpen()
            if (isCompleteReuse(requested.snapshot)) {
                return
            }

            val previousClients = synchronized(stateLock) {
                if (closed) {
                    null
                } else if (connectionRequestGeneration != generation) {
                    emptyMap()
                } else {
                    connectionState.also { connectionState = ConnectionState() }.clients
                }
            }
            if (previousClients == null) {
                throw IllegalStateException("MCP client service is closed.")
            }
            if (!isCurrentConnectionRequest(generation)) {
                return
            }
            scheduleClientsForCleanup(previousClients)

            val candidates = linkedMapOf<String, Pair<Client, List<Tool>>>()
            try {
                for (config in requested.configs) {
                    val candidate = connectCandidate(config, candidates.values.sumOf { it.second.size })
                    candidates[config.name] = candidate
                }
                currentCoroutineContext().ensureActive()
            } catch (e: CancellationException) {
                scheduleCandidatesForCleanup(candidates)
                throw e
            } catch (_: Exception) {
                scheduleCandidatesForCleanup(candidates)
                logger.warn("Failed to prepare MCP connection candidate; leaving the connection snapshot empty.")
                return
            }
            val candidateState = ConnectionState(
                clients = candidates.mapValues { it.value.first },
                serverTools = candidates.mapValues { it.value.second },
                configSnapshot = requested.snapshot,
            )
            val published = synchronized(stateLock) {
                if (closed || connectionRequestGeneration != generation) {
                    false
                } else {
                    connectionState = candidateState
                    true
                }
            }
            if (!published) {
                scheduleCandidatesForCleanup(candidates)
                return
            }
        }
    }

    private fun prepareConnection(configs: List<MCPServerConfig>): PreparedConnection {
        val copiedConfigs = configs.map { config -> config.copy(headers = config.headers.toMap()) }
        validateMcpServerConfigs(copiedConfigs)
        val serverSnapshots = copiedConfigs.map { config ->
            ServerConfigSnapshot(
                name = config.name,
                url = config.url,
                headers = config.headers.entries
                    .sortedWith(compareBy<Map.Entry<String, String>>({ it.key }, { it.value }))
                    .map { HeaderSnapshot(it.key, it.value) },
            )
        }
        val normalizedConfigs = serverSnapshots.map { server ->
            MCPServerConfig(
                name = server.name,
                url = server.url,
                headers = server.headers.associate { it.name to it.value },
            )
        }
        return PreparedConnection(
            configs = normalizedConfigs,
            snapshot = ConnectionConfigSnapshot(serverSnapshots),
        )
    }

    private fun isCompleteReuse(requestedSnapshot: ConnectionConfigSnapshot): Boolean = synchronized(stateLock) {
        if (closed) {
            false
        } else {
            val state = connectionState
            state.configSnapshot == requestedSnapshot &&
                    state.clients.size == requestedSnapshot.servers.size &&
                    state.serverTools.size == requestedSnapshot.servers.size &&
                    requestedSnapshot.servers.all { server ->
                        server.name in state.clients && server.name in state.serverTools
                    }
        }
    }

    /**
     * 在当前批次超时时原子摘除快照，并推进代次以阻止其候选或更早候选随后发布。
     *
     * 已有更新请求时不干扰它：只有仍为最新的超时批次可以清空快照。
     */
    private fun detachTimedOutConnectionState(generation: Long): ConnectionState = synchronized(stateLock) {
        if (connectionRequestGeneration != generation) {
            ConnectionState()
        } else {
            connectionRequestGeneration++
            connectionState.also { connectionState = ConnectionState() }
        }
    }

    /** 判断代次是否仍可修改或发布连接状态。 */
    private fun isCurrentConnectionRequest(generation: Long): Boolean = synchronized(stateLock) {
        !closed && connectionRequestGeneration == generation
    }

    /** 将候选客户端的关闭放到独立作用域，绝不在连接锁或不可取消区中等待。 */
    private fun scheduleCandidatesForCleanup(candidates: Map<String, Pair<Client, List<Tool>>>) {
        candidates.forEach { (name, candidate) -> scheduleClientForCleanup(name, candidate.first) }
    }

    /** 将多个已摘除客户端的关闭放到独立作用域，绝不在连接锁或不可取消区中等待。 */
    private fun scheduleClientsForCleanup(clients: Map<String, Client>) {
        clients.forEach { (name, client) -> scheduleClientForCleanup(name, client) }
    }

    /**
     * 在超时摘除已发布快照后，等待已经开始的工具调用退出，再在锁外关闭客户端。
     *
     * 此方法只用于从 [connectionState] 摘除的客户端。候选客户端从未发布，不可能被 [callTool] 持有，
     * 可直接走 [scheduleClientsForCleanup]。每个客户端从创建时起即登记为 tracked cleanup，登记任务先
     * 等待 [connectionMutex] 再在锁外关闭，因而终态 [close] 不会遗漏这段交接期。
     */
    private fun schedulePublishedClientsForCleanupAfterInFlightCalls(clients: Map<String, Client>) {
        clients.forEach { (name, client) -> schedulePublishedClientForCleanupAfterInFlightCalls(name, client) }
    }

    /**
     * 登记已发布客户端的完整关闭交接：先等待 in-flight tool 退出，再在连接锁外关闭。
     *
     * 此任务和普通客户端关闭共用 [clientCleanupJobs]；终态 [close] 会在连接栅栏后封闭登记，并等待其实际
     * 完成，绝不把仍在后台运行的清理误报为已关闭。
     */
    private fun schedulePublishedClientForCleanupAfterInFlightCalls(name: String, client: Client) {
        lateinit var cleanupJob: Job
        synchronized(cleanupLock) {
            check(!cleanupRegistrationFenced) {
                "MCP client cleanup registration happened after the terminal shutdown fence."
            }
            if (client in clientCleanupJobs) {
                return
            }
            cleanupJob = closingScope.launch(start = CoroutineStart.LAZY) {
                try {
                    // 即使当前调用者恰好运行在 IO dispatcher，也先让出其连接临界区；client.close 绝不能以内联
                    // 方式在 connectionMutex 或其他状态锁内开始。
                    yield()
                    connectionMutex.withLock {
                        // 仅作为 in-flight tool 调用的栅栏；真正的 client.close 必须在锁外执行。
                    }
                    client.close()
                } catch (e: Exception) {
                    logger.error(
                        "Error closing MCP client {}; category={}",
                        name,
                        SafeLogging.failureCategory(e).wireName,
                    )
                } finally {
                    synchronized(cleanupLock) { clientCleanupJobs.remove(client) }
                }
            }
            clientCleanupJobs[client] = cleanupJob
        }
        cleanupJob.start()
    }

    /**
     * 跟踪并启动单个客户端的异步关闭。
     *
     * 同一客户端只会进入一次清理；调用者已从快照中摘除它，故慢速关闭不会阻塞新的连接或发布。终态
     * 关闭会等待在 cleanup fence 前登记的清理实际完成；不合作的 `client.close` 可以令终态等待持续，不能
     * 被误报为已关闭。
     */
    private fun scheduleClientForCleanup(name: String, client: Client) {
        lateinit var cleanupJob: Job
        synchronized(cleanupLock) {
            check(!cleanupRegistrationFenced) {
                "MCP client cleanup registration happened after the terminal shutdown fence."
            }
            if (client in clientCleanupJobs) {
                return
            }
            cleanupJob = closingScope.launch(start = CoroutineStart.LAZY) {
                try {
                    // 与已发布客户端的交接保持同一边界：登记可在锁内完成，但 client.close 必须异步离开调用方锁。
                    yield()
                    client.close()
                } catch (e: Exception) {
                    logger.error(
                        "Error closing MCP client {}; category={}",
                        name,
                        SafeLogging.failureCategory(e).wireName,
                    )
                } finally {
                    synchronized(cleanupLock) { clientCleanupJobs.remove(client) }
                }
            }
            clientCleanupJobs[client] = cleanupJob
        }
        cleanupJob.start()
    }

    /**
     * 封闭客户端清理登记并取得必须完成的任务快照。
     *
     * 调用方必须已通过 [connectionMutex] 栅栏：该栅栏使所有在关闭前已取得连接的调用完成其摘除和 cleanup
     * 登记。随后此锁内封闭快照，消除“先观察为空、后登记清理任务”的竞态；不会在任何锁内等待任务。
     */
    private fun fenceTrackedClientCleanup(): List<Job> = synchronized(cleanupLock) {
        cleanupRegistrationFenced = true
        clientCleanupJobs.values.toList()
    }

    /** 等待 cleanup fence 前登记的客户端清理实际结束；调用时不持有连接锁或清理锁。 */
    private suspend fun awaitTrackedClientCleanup(): Unit = fenceTrackedClientCleanup().joinAll()

    private suspend fun connectCandidate(config: MCPServerConfig, existingToolCount: Int): Pair<Client, List<Tool>> {
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
            val tools = listAllTools(client, existingToolCount)
            currentCoroutineContext().ensureActive()
            logger.info("Connected to MCP server: ${config.name}, discovered ${tools.size} tools.")
            return client to tools
        } catch (e: CancellationException) {
            scheduleClientForCleanup(config.name, client)
            throw e
        } catch (e: Exception) {
            scheduleClientForCleanup(config.name, client)
            throw e
        }
    }

    /**
     * 在当前 MCP 批次与连接锁内读取一个服务器的全部工具页。
     *
     * 每页加入候选前即校验工具并累计单服务器和跨服务器预算；发现失败会由调用方清理该候选，绝不发布部分
     * 工具快照。
     */
    private suspend fun listAllTools(client: Client, existingToolCount: Int): List<Tool> {
        val discovered = mutableListOf<Tool>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            check(++pages <= MAX_MCP_TOOL_DISCOVERY_PAGES) { "MCP 工具发现页数超过限制。" }
            val result = if (cursor == null) {
                client.listTools(ListToolsRequest())
            } else {
                client.listTools(ListToolsRequest(PaginatedRequestParams(cursor)))
            }
            result.tools.forEach { tool ->
                check(discovered.size < MAX_MCP_TOOLS_PER_SERVER) {
                    "单个 MCP 服务器工具不能超过 $MAX_MCP_TOOLS_PER_SERVER 个。"
                }
                check(existingToolCount + discovered.size < MAX_MCP_TOOLS_TOTAL) {
                    "MCP 工具总数不能超过 $MAX_MCP_TOOLS_TOTAL 个。"
                }
                validateDiscoveredTool(tool)
                discovered += tool
            }
            val nextCursor = result.nextCursor ?: return discovered
            check(seenCursors.add(nextCursor)) { "MCP 工具分页游标重复。" }
            cursor = nextCursor
        }
    }

    /**
     * 异步断开所有当前 MCP 服务器。
     *
     * 等待返回任务完成后不再保留服务器连接或工具快照，但服务仍可再次 [connect]。关闭已开始后该方法
     * 不再启动额外断开操作，并返回已关闭资源的完成任务。
     *
     * @return 执行断开操作的任务；终态关闭已开始时返回其清理任务。
     */
    @Suppress("unused")
    fun disconnectAll(): Job = synchronized(lifecycleLock) {
        closeJob ?: closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
            connectionMutex.withLock {
                if (!closed) {
                    synchronized(stateLock) { connectionRequestGeneration++ }
                    currentConnectionState().clients.keys.toList().forEach { disconnectLocked(it) }
                }
            }
        }
    }

    /**
     * 终态关闭 MCP 服务。
     *
     * 首次调用会同步拒绝后续 [connect] 和 [callTool]，并立即清空可见工具快照；随后在不持有
     * [connectionMutex] 的情况下等待已开始的工具调用离开，再在锁外关闭当时持有的 MCP 客户端。清理登记
     * 在连接栅栏后封闭，返回任务只会在封闭前全部客户端清理及 HTTP 客户端实际结束后完成。清理任务使用
     * 独立作用域，因此不依赖创建服务的父作用域仍处于活动状态；不合作的客户端可以无限阻塞其关闭，终态
     * 不会虚称完成。重复调用返回同一个任务。
     *
     * @return 幂等的异步清理任务；等待其完成后本实例拒绝新操作、清空快照并已完成所有在关闭栅栏前登记的
     * MCP 客户端清理及 HTTP 客户端关闭。
     */
    fun close(): Job {
        val jobToStart = synchronized(lifecycleLock) {
            closeJob ?: run {
                val clientsToClose = synchronized(stateLock) {
                    closed = true
                    connectionRequestGeneration++
                    connectionState.also { connectionState = ConnectionState() }.clients
                }
                closingScope.launch(start = CoroutineStart.LAZY) {
                    connectionMutex.withLock {
                        // 仅等待关闭前已开始的 tool call 或连接切换离开；真正 client.close 必须在锁外执行。
                    }
                    scheduleClientsForCleanup(clientsToClose)
                    awaitTrackedClientCleanup()
                    httpClient.close()
                }.also { closeJob = it }
            }
        }
        jobToStart.start()
        return jobToStart
    }

    private fun disconnectLocked(name: String) {
        val client = synchronized(stateLock) {
            val state = connectionState
            val currentClient = state.clients[name] ?: return
            connectionState = state.copy(
                clients = state.clients - name,
                serverTools = state.serverTools - name,
                configSnapshot = state.configSnapshot.withoutServer(name),
            )
            currentClient
        }
        scheduleClientForCleanup(name, client)
    }

    /**
     * 获取当前所有已连接服务器发现到的工具。
     *
     * @return 服务器名称与工具组成的列表；未连接任何服务器、候选连接失败或未发现工具时返回空列表。列表
     * 顺序来自当前连接快照，不应视为稳定排序。
     */
    fun getAllTools(): List<Pair<String, Tool>> =
        currentConnectionState().serverTools.flatMap { (serverName, tools) ->
            tools.map { serverName to it }
        }

    /**
     * 调用指定 MCP 服务器上的工具。
     *
     * 调用会与连接变更串行执行，避免在工具调用期间关闭目标客户端。配置变更会先清空快照，因此候选
     * 失败、取消或服务终态关闭后，旧服务器均不会再启动新的调用。
     *
     * @param serverName 已连接 MCP 服务器的名称。
     * @param toolName 目标服务器已提供的工具名称。
     * @param args 传递给工具的参数映射；值可为 `null`，其键和值应符合工具的输入架构。
     * @return MCP 服务器返回的工具调用结果。
     * @throws IllegalStateException 当服务已 [close]、候选连接未完成或失败，或 [serverName] 对应的服务器
     * 尚未连接时抛出。
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
internal const val MAX_MCP_TOOL_DISCOVERY_PAGES = 16
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

/** 将不可信函数参数转换为 JSON 时使用的显式后序工作项。 */
private sealed interface McpArgumentWork {
    data class Visit(val value: Any?, val depth: Int) : McpArgumentWork
    data class BuildMap(val source: Any, val entries: List<Pair<String, Any?>>) : McpArgumentWork
    data class BuildArray(val source: Any, val values: List<Any?>) : McpArgumentWork
}

internal class BoundedMcpResponseBody(
    private val delegate: ResponseBody,
    private val limit: Long,
) : ResponseBody() {
    private val structureScanner: McpWireStructureScanner? = when {
        delegate.contentType().isMcpJson() -> JsonWireStructureScanner()
        delegate.contentType().isMcpSse() -> SseWireStructureScanner()
        else -> null
    }
    private val boundedSource = object : ForwardingSource(delegate.source()) {
        private var total = 0L
        private var reachedEnd = false

        override fun read(sink: Buffer, byteCount: Long): Long {
            val previousSize = sink.size
            val read = super.read(sink, byteCount)
            if (read > 0) {
                total += read
                if (total > limit) {
                    close()
                    throw McpResponseTooLargeException()
                }
                val copied = sink.clone().apply { skip(previousSize) }.readByteArray(read)
                try {
                    structureScanner?.consume(copied)
                } catch (error: Throwable) {
                    // SSE 可无限期保持连接；结构限制命中时不能等待 SDK 或 GC 关闭底层 socket。
                    this@BoundedMcpResponseBody.close()
                    throw error
                }
            } else if (read < 0 && !reachedEnd) {
                reachedEnd = true
                try {
                    structureScanner?.finish()
                } catch (error: Throwable) {
                    this@BoundedMcpResponseBody.close()
                    throw error
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

/** 流式 MCP 响应在交给 SDK 解码前进行的结构校验。 */
private interface McpWireStructureScanner {
    fun consume(bytes: ByteArray)

    fun finish()
}

/** 对 application/json 响应全量但分 chunk 进行 JSON 结构预检查。 */
private class JsonWireStructureScanner : McpWireStructureScanner {
    private val delegate = JsonStructureLimits.newUtf8Scanner()

    override fun consume(bytes: ByteArray) = delegate.consume(bytes)

    override fun finish() = delegate.finish()
}

/**
 * 对 text/event-stream 的 data 字段逐事件进行 JSON 结构预检查。
 *
 * SSE 行和 CRLF 均可横跨网络 chunk；同一事件的多个 data 行按 SSE 规则以换行拼接后再继续扫描。
 */
private class SseWireStructureScanner : McpWireStructureScanner {
    private val currentLine = ByteArrayOutputStream()
    private var jsonScanner: JsonStructureLimits.Utf8Scanner? = null
    private var eventHasData = false

    override fun consume(bytes: ByteArray) {
        bytes.forEach { byte ->
            if (byte.toInt() == '\n'.code) {
                consumeLine(currentLine.toByteArray().stripTrailingCarriageReturn())
                currentLine.reset()
            } else {
                currentLine.write(byte.toInt())
            }
        }
    }

    override fun finish() {
        if (currentLine.size() > 0) {
            consumeLine(currentLine.toByteArray().stripTrailingCarriageReturn())
            currentLine.reset()
        }
        finishEvent()
    }

    private fun consumeLine(line: ByteArray) {
        if (line.isEmpty()) {
            finishEvent()
            return
        }
        if (!line.startsWithAscii("data:")) return
        val payloadStart = if (line.size > 5 && line[5].toInt() == ' '.code) 6 else 5
        val scanner = jsonScanner ?: JsonStructureLimits.newUtf8Scanner().also { jsonScanner = it }
        if (eventHasData) scanner.consume(byteArrayOf('\n'.code.toByte()))
        scanner.consume(line, payloadStart, line.size - payloadStart)
        eventHasData = true
    }

    private fun finishEvent() {
        if (eventHasData) {
            jsonScanner?.finish()
        }
        jsonScanner = null
        eventHasData = false
    }
}

private fun ByteArray.stripTrailingCarriageReturn(): ByteArray =
    if (isNotEmpty() && last().toInt() == '\r'.code) copyOf(size - 1) else this

private fun ByteArray.startsWithAscii(prefix: String): Boolean =
    size >= prefix.length && prefix.indices.all { index -> this[index].toInt() == prefix[index].code }

private fun okhttp3.MediaType?.isMcpJson(): Boolean =
    this?.type == "application" && (subtype == "json" || subtype.endsWith("+json"))

private fun okhttp3.MediaType?.isMcpSse(): Boolean = this?.type == "text" && subtype == "event-stream"

private fun validateDiscoveredTool(tool: Tool) {
    tool.inputSchema.properties?.let(JsonStructureLimits::validateElement)
    tool.inputSchema.defs?.let(JsonStructureLimits::validateElement)
    tool.outputSchema?.properties?.let(JsonStructureLimits::validateElement)
    tool.outputSchema?.defs?.let(JsonStructureLimits::validateElement)
    tool.meta?.let(JsonStructureLimits::validateElement)
    require(tool.name.toByteArray(StandardCharsets.UTF_8).size in 1..MAX_MCP_TOOL_NAME_BYTES) {
        "MCP 工具名称长度不合法。"
    }
    require((tool.description ?: "").toByteArray(StandardCharsets.UTF_8).size <= MAX_MCP_TOOL_DESCRIPTION_BYTES) {
        "MCP 工具描述超过限制。"
    }
    // 结构已经先受显式栈校验，之后才允许 SDK 进行可能递归的 schema 序列化。
    require(tool.inputSchema.toString().toByteArray(StandardCharsets.UTF_8).size <= MAX_MCP_TOOL_SCHEMA_BYTES) {
        "MCP 工具输入架构超过限制。"
    }
}

private fun validateMcpArguments(args: Map<String, Any?>) {
    val objectValue = try {
        args.toMcpJsonObject()
    } catch (_: Exception) {
        throw McpToolArgumentsTooLargeException()
    }
    val encoded = try {
        JsonStructureLimits.validateElement(objectValue)
        Json.encodeToString(JsonObject.serializer(), objectValue).also(JsonStructureLimits::validateJsonString)
    } catch (_: Exception) {
        throw McpToolArgumentsTooLargeException()
    }
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_MCP_TOOL_ARGUMENT_BYTES) {
        throw McpToolArgumentsTooLargeException()
    }
}

private fun Map<String, Any?>.toMcpJsonObject(): JsonObject =
    toMcpJsonElementIteratively(this) as JsonObject

/** 在构造参数 JSON 中间列表前以剩余节点预算有界复制一个不可信 Iterable。 */
private fun Iterable<*>.copyWithinRemainingMcpNodeBudget(nodesUsed: Int): List<Any?> {
    val remaining = JsonStructureLimits.MAX_NODES - nodesUsed
    if (remaining <= 0) throw McpToolArgumentsTooLargeException()
    val values = ArrayList<Any?>()
    for (value in this) {
        if (values.size >= remaining) throw McpToolArgumentsTooLargeException()
        values += value
    }
    return values
}

/**
 * 将参数值转换为 JSON，不依赖 Kotlin 容器的递归遍历。
 *
 * `Map`、数组与 Iterable 都会受统一深度/节点预算和路径循环检查，避免模型或调用方构造的嵌套参数在
 * 编码前触发 StackOverflowError。
 */
private fun toMcpJsonElementIteratively(root: Any?): JsonElement {
    val converted = IdentityHashMap<Any, JsonElement>()
    val ancestors = IdentityHashMap<Any, Boolean>()
    val work = ArrayDeque<McpArgumentWork>()
    work.addLast(McpArgumentWork.Visit(root, 0))
    var nodes = 0
    while (work.isNotEmpty()) {
        when (val item = work.removeLast()) {
            is McpArgumentWork.Visit -> {
                if (++nodes > JsonStructureLimits.MAX_NODES || item.depth > JsonStructureLimits.MAX_DEPTH) {
                    throw McpToolArgumentsTooLargeException()
                }
                when (val value = item.value) {
                    null -> Unit
                    is JsonElement -> {
                        JsonStructureLimits.validateElement(value)
                        converted[value] = value
                    }

                    is String -> converted[value] = JsonPrimitive(value)
                    is Boolean -> converted[value] = JsonPrimitive(value)
                    is Byte, is Short, is Int, is Long, is Float, is Double ->
                        converted[value] = JsonPrimitive(value as Number)

                    is Map<*, *> -> {
                        if (ancestors.put(value, true) != null) throw McpToolArgumentsTooLargeException()
                        val entries = ArrayList<Pair<String, Any?>>()
                        for ((key, child) in value) {
                            if (entries.size >= JsonStructureLimits.MAX_NODES - nodes) {
                                throw McpToolArgumentsTooLargeException()
                            }
                            entries += (key as? String
                                ?: throw IllegalArgumentException("MCP 参数对象键必须是字符串。")) to child
                        }
                        work.addLast(McpArgumentWork.BuildMap(value, entries))
                        entries.forEach { (_, child) ->
                            work.addLast(McpArgumentWork.Visit(child, item.depth + 1))
                        }
                    }

                    is Iterable<*> -> {
                        if (ancestors.put(value, true) != null) throw McpToolArgumentsTooLargeException()
                        val values = value.copyWithinRemainingMcpNodeBudget(nodes)
                        work.addLast(McpArgumentWork.BuildArray(value, values))
                        values.forEach { child -> work.addLast(McpArgumentWork.Visit(child, item.depth + 1)) }
                    }

                    is Array<*> -> {
                        if (ancestors.put(value, true) != null) throw McpToolArgumentsTooLargeException()
                        val values = value.asIterable().copyWithinRemainingMcpNodeBudget(nodes)
                        work.addLast(McpArgumentWork.BuildArray(value, values))
                        values.forEach { child -> work.addLast(McpArgumentWork.Visit(child, item.depth + 1)) }
                    }

                    else -> throw IllegalArgumentException("MCP 参数包含不支持的值类型。")
                }
            }

            is McpArgumentWork.BuildMap -> {
                converted[item.source] = JsonObject(LinkedHashMap<String, JsonElement>().apply {
                    item.entries.forEach { (name, child) -> put(name, converted[child] ?: JsonNull) }
                })
                ancestors.remove(item.source)
            }

            is McpArgumentWork.BuildArray -> {
                converted[item.source] = JsonArray(item.values.map { child -> converted[child] ?: JsonNull })
                ancestors.remove(item.source)
            }
        }
    }
    return when (root) {
        null -> JsonNull
        is JsonElement -> converted[root] ?: root
        else -> converted[root] ?: throw McpToolArgumentsTooLargeException()
    }
}

internal fun validateMcpToolResult(result: CallToolResult) {
    result.structuredContent?.let(JsonStructureLimits::validateElement)
    result.meta?.let(JsonStructureLimits::validateElement)
    result.content.forEach { content ->
        content.meta?.let(JsonStructureLimits::validateElement)
        if (content is EmbeddedResource) content.resource.meta?.let(JsonStructureLimits::validateElement)
    }
    val encoded = Json.encodeToString(CallToolResult.serializer(), result)
    JsonStructureLimits.validateJsonString(encoded)
    if (encoded.toByteArray(StandardCharsets.UTF_8).size > MAX_MCP_TOOL_RESULT_BYTES) {
        throw McpToolResultTooLargeException()
    }
}
