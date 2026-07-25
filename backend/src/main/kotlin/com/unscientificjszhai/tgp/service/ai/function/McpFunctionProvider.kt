package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.Type
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * 将已连接 MCP 服务器的工具暴露为模型可调用函数的提供者。
 *
 * 每个函数名称由服务器名称和工具名称组合而成，函数声明会随 [MCPClientService] 的当前工具快照变化。
 *
 * @param mcpClientService 提供 MCP 连接、工具快照和工具调用能力的服务。
 */
class McpFunctionProvider(
    private val mcpClientService: MCPClientService,
) : LocalFunctionProvider() {
    private val logger = LoggerFactory.getLogger(McpFunctionProvider::class.java)

    /**
     * 获取当前所有 MCP 工具转换而成的函数声明。
     *
     * @return 每个已发现 MCP 工具对应一个函数声明；未连接服务器或无工具时返回空列表。
     */
    override val providedFunctions: List<FunctionDeclaration>
        get() {
            val mcpTools = mcpClientService.getAllTools()
            return mcpTools.map { (serverName, mcpTool) ->
                val schemaJson =
                    buildJsonObject {
                        put("type", "OBJECT")
                        put(
                            "properties",
                            (mcpTool.inputSchema.properties ?: JsonObject(emptyMap())).toGeminiSchemaJson(),
                        )
                        val required = mcpTool.inputSchema.required ?: emptyList()
                        if (required.isNotEmpty()) {
                            put(
                                "required",
                                buildJsonArray {
                                    required.forEach { add(it) }
                                },
                            )
                        }
                    }.toString()

                val schema = Schema.fromJson(schemaJson) ?: Schema.builder().type(Type(Type.Known.OBJECT)).build()

                FunctionDeclaration
                    .builder()
                    .name("${serverName}_${mcpTool.name}")
                    .description(mcpTool.description ?: "")
                    .parameters(schema)
                    .build()
            }
        }

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    /**
     * 调用函数名称对应的 MCP 工具。
     *
     * 调用会发起网络 I/O；工具调用失败会转换为 `error` 字段，协程取消会继续向上抛出。
     *
     * @param functionName 由 [providedFunctions] 生成的函数名称。
     * @param args 传递给 MCP 工具的参数映射；值可为 `null`，其结构必须符合目标工具的输入架构。
     * @return 调用成功时在 `result` 字段包含 MCP 响应的 JSON 对象；调用失败时在 `error` 字段
     * 包含错误信息的 JSON 对象。
     * @throws CancellationException 当调用方取消协程时抛出。
     */
    override suspend fun execute(
        functionName: String,
        args: Map<String, Any?>,
    ): JsonObject {
        val serverName = functionName.substringBefore('_')
        val toolName = functionName.substringAfter('_')

        return try {
            val result = mcpClientService.callTool(serverName, toolName, args)
            buildJsonObject {
                put("result", json.encodeToJsonElement(result))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error executing MCP tool $functionName", e)
            buildJsonObject {
                put("error", (e.message ?: "Unknown error"))
            }
        }
    }

    /**
     * 将 MCP 的 JSON Schema 转换为 Gemini 兼容的格式（主要是 Type 大写）。
     */
    private fun JsonElement.toGeminiSchemaJson(): JsonElement =
        when (this) {
            is JsonObject -> {
                buildJsonObject {
                    forEach { (key, value) ->
                        if (key == "type" && value is JsonPrimitive && value.isString) {
                            put(key, value.content.uppercase())
                        } else {
                            put(key, value.toGeminiSchemaJson())
                        }
                    }
                }
            }

            is JsonArray -> {
                buildJsonArray {
                    forEach { add(it.toGeminiSchemaJson()) }
                }
            }

            else -> {
                this
            }
        }
}
