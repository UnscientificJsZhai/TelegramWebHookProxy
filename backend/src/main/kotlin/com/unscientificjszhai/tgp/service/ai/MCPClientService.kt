package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.sse.*
import io.ktor.client.request.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MCPClientService @Inject constructor() {
    private val logger = LoggerFactory.getLogger(MCPClientService::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient =
        HttpClient(OkHttp) {
            install(SSE)
            install(HttpTimeout) {
                requestTimeoutMillis = 300000
            }
        }

    // Server Name -> Client
    private val clients = mutableMapOf<String, Client>()

    // Server Name -> Tools
    private val serverTools = mutableMapOf<String, List<Tool>>()

    suspend fun connect(configs: List<MCPServerConfig>) {
        val newNames = configs.map { it.name }.toSet()
        val toRemove = clients.keys - newNames
        toRemove.forEach { disconnect(it) }

        for (config in configs) {
            if (!clients.containsKey(config.name)) {
                try {
                    connectToServer(config)
                } catch (e: Exception) {
                    logger.error("Failed to connect to MCP server: ${config.name}", e)
                }
            }
        }
    }

    private suspend fun connectToServer(config: MCPServerConfig) {
        val client =
            Client(
                Implementation(name = "telegram-webhook-proxy", version = "1.0.0"),
            )

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
        client.connect(transport)

        clients[config.name] = client
        val toolsList = client.listTools()
        serverTools[config.name] = toolsList.tools
        logger.info("Connected to MCP server: ${config.name}, discovered ${toolsList.tools.size} tools.")
    }

    fun disconnect(name: String) {
        val client = clients.remove(name)
        if (client != null) {
            scope.launch {
                try {
                    client.close()
                } catch (e: Exception) {
                    logger.error("Error closing MCP client: $name", e)
                }
            }
        }
        serverTools.remove(name)
    }

    fun disconnectAll() {
        clients.keys.toList().forEach { disconnect(it) }
    }

    fun getAllTools(): List<Pair<String, Tool>> =
        serverTools.flatMap { (serverName, tools) ->
            tools.map { serverName to it }
        }

    suspend fun callTool(
        serverName: String,
        toolName: String,
        args: Map<String, Any?>,
    ): Any {
        val client = clients[serverName] ?: throw IllegalStateException("MCP server $serverName not connected")
        val result = client.callTool(toolName, args)
        return result
    }
}
