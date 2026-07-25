package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
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
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 管理到 MCP 服务器的连接，并提供已发现工具的查询与调用能力。
 *
 * 服务维护当前连接配置的快照；调用 [connect] 会将连接状态同步为传入配置，调用
 * [disconnectAll] 返回的任务完成后不再保留任何服务器连接。
 */
@Singleton
class MCPClientService internal constructor(
    parentScope: CoroutineScope,
    private val clientFactory: () -> Client,
) {
    /**
     * 使用默认 MCP 客户端创建器创建服务。
     *
     * @param parentScope 服务后台连接与断开任务所属的协程作用域；取消该作用域会取消
     * 服务启动的任务。
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
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private val httpClient =
        HttpClient(OkHttp) {
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
     * 串行化连接与断开操作，保证旧 Agent 的关闭先于新 Agent 的连接。
     */
    private val connectionMutex = Mutex()

    /**
     * 使用不可变快照，使工具发现和调用可以安全读取已提交的连接状态。
     */
    @Volatile
    private var connectionState = ConnectionState()

    /**
     * 将已连接的服务器同步为指定配置，并发现各服务器提供的工具。
     *
     * 此方法会断开未包含在 [configs] 中或配置已变化的服务器。网络连接失败会被记录，
     * 但不会阻止处理其余服务器；取消协程时会关闭正在建立的客户端并重新抛出取消异常。
     *
     * @param configs 目标 MCP 服务器配置列表；列表为空时断开所有当前服务器，服务器名称应在
     * 列表内唯一。
     */
    suspend fun connect(configs: List<MCPServerConfig>) = connectionMutex.withLock {
        val newNames = configs.map { it.name }.toSet()
        val toRemove = connectionState.clients.keys - newNames
        toRemove.forEach { disconnectLocked(it) }

        for (config in configs) {
            val previousConfig = connectionState.configs[config.name]
            if (previousConfig != null && previousConfig != config) {
                disconnectLocked(config.name)
            }
            if (config.name !in connectionState.clients) {
                connectToServerLocked(config)
            }
        }
    }

    private suspend fun connectToServerLocked(config: MCPServerConfig) {
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
            connectionState = connectionState.copy(
                clients = connectionState.clients + (config.name to client),
                serverTools = connectionState.serverTools + (config.name to tools),
                configs = connectionState.configs + (config.name to config),
            )
            logger.info("Connected to MCP server: ${config.name}, discovered ${tools.size} tools.")
        } catch (e: CancellationException) {
            closeClient(config.name, client)
            throw e
        } catch (e: Exception) {
            closeClient(config.name, client)
            logger.error("Failed to connect to MCP server: ${config.name}", e)
        }
    }

    /**
     * 异步断开所有当前 MCP 服务器。
     *
     * @return 执行断开操作的任务；等待该任务完成后，服务不再保留服务器连接或工具快照。
     */
    fun disconnectAll(): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        connectionMutex.withLock {
            connectionState.clients.keys.toList().forEach { disconnectLocked(it) }
        }
    }

    private suspend fun disconnectLocked(name: String) {
        val client = connectionState.clients[name] ?: return
        connectionState = connectionState.copy(
            clients = connectionState.clients - name,
            serverTools = connectionState.serverTools - name,
            configs = connectionState.configs - name,
        )
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
        connectionState.serverTools.flatMap { (serverName, tools) ->
            tools.map { serverName to it }
        }

    /**
     * 调用指定 MCP 服务器上的工具。
     *
     * 调用会与连接变更串行执行，避免在工具调用期间关闭目标客户端。
     *
     * @param serverName 已连接 MCP 服务器的名称。
     * @param toolName 目标服务器已提供的工具名称。
     * @param args 传递给工具的参数映射；值可为 `null`，其键和值应符合工具的输入架构。
     * @return MCP 服务器返回的工具调用结果。
     * @throws IllegalStateException 当 [serverName] 对应的服务器尚未连接时抛出。
     */
    suspend fun callTool(
        serverName: String,
        toolName: String,
        args: Map<String, Any?>,
    ): CallToolResult = connectionMutex.withLock {
        val client = connectionState.clients[serverName]
            ?: throw IllegalStateException("MCP server $serverName not connected")
        client.callTool(toolName, args)
    }
}
