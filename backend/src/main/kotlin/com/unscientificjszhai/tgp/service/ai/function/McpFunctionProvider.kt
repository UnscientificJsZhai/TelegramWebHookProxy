package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.Type
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * 提供 MCP (Model Context Protocol) 工具调用能力的本地功能提供者。
 */
class McpFunctionProvider(
    private val mcpClientService: MCPClientService,
) : LocalFunctionProvider() {
    private val logger = LoggerFactory.getLogger(McpFunctionProvider::class.java)

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

    override suspend fun execute(
        functionName: String,
        args: Map<String, Any?>,
    ): Map<String, Any?> {
        val serverName = functionName.substringBefore('_')
        val toolName = functionName.substringAfter('_')

        return try {
            val result = mcpClientService.callTool(serverName, toolName, args)
            mapOf("result" to result)
        } catch (e: Exception) {
            logger.error("Error executing MCP tool $functionName", e)
            mapOf("error" to (e.message ?: "Unknown error"))
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
