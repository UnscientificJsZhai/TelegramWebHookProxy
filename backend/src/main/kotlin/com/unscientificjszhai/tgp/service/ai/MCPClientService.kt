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

@Singleton
class MCPClientService internal constructor(
    parentScope: CoroutineScope,
    private val clientFactory: () -> Client,
) {
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

    fun disconnect(name: String): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        connectionMutex.withLock {
            disconnectLocked(name)
        }
    }

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

    fun getAllTools(): List<Pair<String, Tool>> =
        connectionState.serverTools.flatMap { (serverName, tools) ->
            tools.map { serverName to it }
        }

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
