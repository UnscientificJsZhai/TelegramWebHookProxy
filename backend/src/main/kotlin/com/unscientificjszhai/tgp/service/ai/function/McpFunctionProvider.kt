package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.Type
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.Collections

/**
 * 将已连接 MCP 服务器的工具暴露为模型可调用函数的提供者。
 *
 * 每个函数名称由服务器名称和工具名称组合而成。读取 [providedFunctions] 会发布包含函数声明和真实 MCP
 * 目标的同一不可变快照；[canHandle] 与 [execute] 只读取最近一次已发布快照。
 *
 * @param mcpClientService 提供 MCP 连接、工具快照和工具调用能力的服务。
 */
class McpFunctionProvider(
    private val mcpClientService: MCPClientService,
) : LocalFunctionProvider() {
    private val logger = LoggerFactory.getLogger(McpFunctionProvider::class.java)

    private data class ToolBinding(
        val serverName: String,
        val toolName: String,
    )

    /**
     * 同时发布模型声明与其真实 MCP 目标，避免声明刷新与函数执行之间出现名称解析歧义。
     */
    private data class ToolSnapshot(
        val declarations: List<FunctionDeclaration>,
        val bindings: Map<String, ToolBinding>,
    )

    @Volatile
    private var toolSnapshot = ToolSnapshot(emptyList(), emptyMap())

    /**
     * 获取当前所有 MCP 工具转换而成的函数声明。
     *
     * @return 每个已发现 MCP 工具对应一个函数声明；未连接服务器或无工具时返回空列表。
     */
    override val providedFunctions: List<FunctionDeclaration>
        get() {
            val refreshedSnapshot = try {
                createSnapshot(mcpClientService.getAllTools())
            } catch (e: Exception) {
                logger.warn("Unable to refresh MCP tool declarations", e)
                ToolSnapshot(emptyList(), emptyMap())
            }
            toolSnapshot = refreshedSnapshot
            return refreshedSnapshot.declarations
        }

    private val json by lazy { Json { ignoreUnknownKeys = true } }

    /**
     * 调用函数名称对应的 MCP 工具。
     *
     * 调用会发起网络 I/O；没有已发布绑定、服务已关闭或工具调用失败时均会转换为固定、无敏感信息的
     * `error` 字段，协程取消会继续向上抛出。
     *
     * @param functionName 由 [providedFunctions] 生成的函数名称。
     * @param args 传递给 MCP 工具的参数映射；值可为 `null`，其结构必须符合目标工具的输入架构。
     * @return 调用成功时在 `result` 字段包含 MCP 响应的 JSON 对象；调用失败时在 `error` 字段
     * 包含固定错误代码的 JSON 对象。
     * @throws CancellationException 当调用方取消协程时抛出。
     */
    override suspend fun execute(
        functionName: String,
        args: Map<String, Any?>,
    ): JsonObject {
        val binding = toolSnapshot.bindings[functionName] ?: return unavailableToolError()
        return executeBinding(binding, args)
    }

    /**
     * 捕获最近一次声明刷新中与函数名称对应的真实 MCP 目标。
     *
     * @param functionName 要捕获的函数名称；必须是最近一次 [providedFunctions] 发布的名称。
     * @return 与当前快照绑定的调用；函数不存在时返回 `null`。
     */
    override fun snapshotCall(functionName: String): LocalFunctionCall? =
        toolSnapshot.bindings[functionName]?.let { binding ->
            LocalFunctionCall { args -> executeBinding(binding, args) }
        }

    private suspend fun executeBinding(binding: ToolBinding, args: Map<String, Any?>): JsonObject {
        return try {
            val result = mcpClientService.callTool(binding.serverName, binding.toolName, args)
            buildJsonObject {
                put("result", json.encodeToJsonElement(result))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("MCP tool execution failed", e)
            unavailableToolError()
        }
    }

    /**
     * 判断指定函数是否存在于最近一次声明刷新发布的快照。
     *
     * @param functionName 要检查的函数名称；空字符串不会匹配任何有效工具。
     * @return 最近一次 [providedFunctions] 刷新已发布同名绑定时返回 `true`，否则返回 `false`。
     */
    override fun canHandle(functionName: String): Boolean = toolSnapshot.bindings.containsKey(functionName)

    private fun createSnapshot(mcpTools: List<Pair<String, Tool>>): ToolSnapshot {
        val candidates = mcpTools.map { (serverName, mcpTool) ->
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
            val functionName = "${serverName}_${mcpTool.name}"
            functionName to Pair(
                FunctionDeclaration
                    .builder()
                    .name(functionName)
                    .description(mcpTool.description ?: "")
                    .parameters(schema)
                    .build(),
                ToolBinding(serverName, mcpTool.name),
            )
        }
        val groupedCandidates = candidates.groupBy { it.first }
        val unambiguousCandidates = groupedCandidates.values.filter { it.size == 1 }.map { it.single() }
        val collidingNames = groupedCandidates.filterValues { it.size > 1 }.keys
        if (collidingNames.isNotEmpty()) {
            logger.warn("Ignoring {} colliding MCP tool declaration(s)", collidingNames.size)
        }
        return ToolSnapshot(
            declarations = Collections.unmodifiableList(unambiguousCandidates.map { it.second.first }),
            bindings = Collections.unmodifiableMap(unambiguousCandidates.associate { it.first to it.second.second }),
        )
    }

    private fun unavailableToolError(): JsonObject = buildJsonObject {
        put("error", MCP_TOOL_UNAVAILABLE)
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

    private companion object {
        const val MCP_TOOL_UNAVAILABLE = "mcp_tool_unavailable"
    }
}
