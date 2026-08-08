package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * MCP 函数提供者的声明快照、路由及失败关闭测试设计。
 */
class McpFunctionProviderTest {
    /**
     * 验证名称中的下划线通过安全别名绑定保留，不会被执行路径重新拆分。
     */
    @Test
    fun `underscored server and tool names use the published binding`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server_name" to tool("tool_name"))
        coEvery {
            mcpClientService.callTool(
                "server_name",
                "tool_name",
                emptyMap()
            )
        } returns CallToolResult(emptyList())
        val provider = McpFunctionProvider(mcpClientService)

        val alias = provider.providedFunctions.single().name().get()
        assertTrue(alias.matches(Regex("mcp_[A-Za-z0-9_-]{43}")))
        provider.execute(alias, emptyMap())

        coVerify(exactly = 1) { mcpClientService.callTool("server_name", "tool_name", emptyMap()) }
    }

    /**
     * 验证 Unicode、空格和超长原始工具名仅以固定长度别名暴露，并仍绑定到原始工具调用。
     */
    @Test
    fun `unsafe raw MCP tool names use fixed safe aliases and retain their binding`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        val rawToolName = "工具 名称 " + "x".repeat(256)
        every { mcpClientService.getAllTools() } returns listOf("服务 名称" to tool(rawToolName))
        coEvery { mcpClientService.callTool("服务 名称", rawToolName, emptyMap()) } returns CallToolResult(emptyList())
        val provider = McpFunctionProvider(mcpClientService)

        val alias = provider.providedFunctions.single().name().get()
        assertEquals(47, alias.length)
        assertTrue(alias.matches(Regex("mcp_[A-Za-z0-9_-]{43}")))
        assertFalse(alias.contains("服务"))
        provider.execute(alias, emptyMap())

        coVerify(exactly = 1) { mcpClientService.callTool("服务 名称", rawToolName, emptyMap()) }
    }

    /**
     * 验证只读取最近一次声明刷新发布的 MCP 快照，检查名称不会隐式刷新。
     */
    @Test
    fun `canHandle does not refresh and a refresh retires stale bindings`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        var tools = listOf("server" to tool("old_tool"))
        every { mcpClientService.getAllTools() } answers { tools }
        val provider = McpFunctionProvider(mcpClientService)

        val oldAlias = provider.providedFunctions.single().name().get()
        tools = listOf("server" to tool("new_tool"))

        assertTrue(provider.canHandle(oldAlias))
        verify(exactly = 1) { mcpClientService.getAllTools() }

        val newAlias = provider.providedFunctions.single().name().get()
        assertFalse(provider.canHandle(oldAlias))
        assertTrue(provider.canHandle(newAlias))
        assertEquals(
            "mcp_tool_unavailable",
            provider.execute(oldAlias, emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
    }

    /**
     * 验证别名声明按别名稳定排序，与 MCP 工具发现顺序无关。
     */
    @Test
    fun `MCP aliases are declared in stable alias order`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        var tools = listOf("server" to tool("second"), "server" to tool("first"))
        every { mcpClientService.getAllTools() } answers { tools }
        val provider = McpFunctionProvider(mcpClientService) { _, rawToolName ->
            if (rawToolName == "first") "alias_a" else "alias_z"
        }

        assertEquals(listOf("alias_a", "alias_z"), provider.providedFunctions.map { it.name().get() })
        tools = tools.reversed()
        assertEquals(listOf("alias_a", "alias_z"), provider.providedFunctions.map { it.name().get() })
    }

    /**
     * 验证异常或不符合模型函数名规则的别名只移除对应候选，不影响其他 MCP 工具。
     */
    @Test
    fun `invalid or failed MCP alias generation skips only its candidate`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server" to tool("invalid"),
            "server" to tool("failed"),
            "server" to tool("safe"),
        )
        val provider = McpFunctionProvider(mcpClientService) { _, rawToolName ->
            when (rawToolName) {
                "invalid" -> "invalid alias"
                "failed" -> throw IllegalStateException("alias generator failure")
                else -> "safe_alias"
            }
        }

        assertEquals(listOf("safe_alias"), provider.providedFunctions.map { it.name().get() })
    }

    /** 验证恶意深层 MCP schema 在递归 SDK 转换前被拒绝，且不会阻断其他刷新路径。 */
    @Test
    fun `deep MCP schema is rejected without stack overflow`() = runTest {
        var nested: JsonElement = JsonPrimitive("leaf")
        repeat(JsonStructureLimits.MAX_DEPTH + 1) { index ->
            nested = JsonObject(linkedMapOf("level-$index" to nested))
        }
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server" to Tool("deep", ToolSchema(properties = JsonObject(linkedMapOf("nested" to nested)))),
        )
        val provider = McpFunctionProvider(mcpClientService)

        assertTrue(provider.providedFunctions.isEmpty())
        coVerify(exactly = 0) { mcpClientService.callTool(any(), any(), any()) }
    }

    /**
     * 验证根 `$defs` 的多层本地引用会以内联约束同时抵达 Gemini 与 OpenAI 声明链路。
     */
    @Test
    fun `local definitions are inlined for Gemini and OpenAI declarations`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to definitionTool("valid"))
        val provider = McpFunctionProvider(mcpClientService) { _, rawName -> rawName }

        val geminiSchema = provider.providedFunctions.single().parameters().get().toJson()
        val geminiJson = Json.parseToJsonElement(geminiSchema).jsonObject
        assertFalse(geminiSchema.contains("\"\$ref\""))
        assertFalse(geminiSchema.contains("\"\$defs\""))
        assertTrue(geminiJson.toString().contains("\"required\""))
        assertTrue(geminiJson.toString().contains("\"items\""))
        assertTrue(geminiJson.toString().contains("\"name\""))

        val openAiParameters = provider.providedOpenAIFunctions.single().parameters().get()._additionalProperties()
        val openAiSchema = openAiParameters.toString()
        assertFalse(openAiSchema.contains("\$ref"))
        assertFalse(openAiSchema.contains("\$defs"))
        assertTrue(openAiSchema.contains("items"))
        assertTrue(openAiSchema.contains("name"))
    }

    /**
     * 验证 Home Assistant 开关枚举与灯光数值范围不会过滤，且两条模型声明链均保留约束。
     */
    @Test
    fun `Home Assistant enum and numeric bounds survive Gemini and OpenAI declarations`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns homeAssistantTools().map { "home-assistant" to it }
        val provider = McpFunctionProvider(mcpClientService) { _, rawName -> rawName }

        val geminiFunctions = provider.providedFunctions.associateBy { it.name().get() }
        assertEquals(setOf("HassTurnOn", "HassLightSet"), geminiFunctions.keys)
        assertTrue(provider.canHandle("HassTurnOn"))
        assertTrue(provider.canHandle("HassLightSet"))

        fun geminiProperty(toolName: String, propertyName: String): JsonObject = Json.parseToJsonElement(
            checkNotNull(geminiFunctions[toolName]).parameters().get().toJson(),
        ).jsonObject.getValue("properties").jsonObject.getValue(propertyName).jsonObject

        val geminiDeviceClasses = geminiProperty("HassTurnOn", "device_class")
        assertEquals(
            listOf("outlet", "switch"),
            geminiDeviceClasses.getValue("items").jsonObject.getValue("enum")
                .jsonArray.map { it.jsonPrimitive.content },
        )
        val geminiBrightness = geminiProperty("HassLightSet", "brightness")
        assertEquals(0.0, geminiBrightness.getValue("minimum").jsonPrimitive.content.toDouble())
        assertEquals(100.0, geminiBrightness.getValue("maximum").jsonPrimitive.content.toDouble())

        val openAiFunctions = provider.providedOpenAIFunctions.associateBy { it.name() }

        @Suppress("UNCHECKED_CAST")
        fun openAiProperty(toolName: String, propertyName: String): Map<String, Any?> {
            val parameters = checkNotNull(openAiFunctions[toolName]).parameters().orElseThrow()._additionalProperties()
            val properties = parameters.getValue("properties").convert(Map::class.java) as Map<String, Any?>
            return properties.getValue(propertyName) as Map<String, Any?>
        }

        @Suppress("UNCHECKED_CAST")
        val openAiDeviceClassItems = openAiProperty("HassTurnOn", "device_class")["items"] as Map<String, Any?>
        assertEquals(listOf("outlet", "switch"), openAiDeviceClassItems["enum"])
        val openAiBrightness = openAiProperty("HassLightSet", "brightness")
        assertEquals(0.0, (openAiBrightness["minimum"] as Number).toDouble())
        assertEquals(100.0, (openAiBrightness["maximum"] as Number).toDouble())
    }

    /**
     * 验证空枚举、非字符串枚举成员以及非数值边界仍会仅过滤对应 MCP 工具。
     */
    @Test
    fun `invalid enum and numeric bound values skip only their MCP tools`() = runTest {
        val invalidTools = listOf(
            constrainedTool("empty-enum", "enum", buildJsonArray {}),
            constrainedTool(
                "non-string-enum",
                "enum",
                buildJsonArray {
                    add(JsonPrimitive("light"))
                    add(JsonPrimitive(1))
                },
            ),
            constrainedTool("string-minimum", "minimum", JsonPrimitive("0"), type = "integer"),
            constrainedTool("boolean-maximum", "maximum", JsonPrimitive(true), type = "integer"),
            constrainedTool(
                "imprecise-minimum",
                "minimum",
                JsonPrimitive(9_007_199_254_740_993L),
                type = "integer",
            ),
            constrainedTool("overflow-maximum", "maximum", Json.parseToJsonElement("1e400"), type = "number"),
            constrainedTool("underflow-minimum", "minimum", Json.parseToJsonElement("1e-400"), type = "number"),
        )
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns invalidTools.map { "server" to it } + ("server" to tool("safe"))
        val provider = McpFunctionProvider(mcpClientService) { _, rawName -> rawName }

        assertEquals(listOf("safe"), provider.providedFunctions.map { it.name().get() })
        invalidTools.forEach { assertFalse(provider.canHandle(it.name)) }
    }

    /**
     * 验证 RFC6901 中的 `~0` 和 `~1` 会解析为定义表中的原始名称。
     */
    @Test
    fun `local definition references decode RFC6901 escaped names`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to escapedDefinitionTool())
        val provider = McpFunctionProvider(mcpClientService)

        val schema = provider.providedFunctions.single().parameters().get().toJson()

        assertFalse(schema.contains("\$ref"))
        assertFalse(schema.contains("\$defs"))
        assertTrue(schema.contains("escapedValue"))
    }

    /** 不可由 Gemini 与 OpenAI 参数链共同保真的 `default` schema 关键字会拒绝当前工具。 */
    @Test
    fun `default schema keywords skip only their MCP tool`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server" to Tool(
                "literal-default",
                ToolSchema(
                    properties = buildJsonObject {
                        put(
                            "value",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "default",
                                    buildJsonObject {
                                        put("\$ref", "not-a-schema-reference")
                                        put("\$defs", buildJsonObject { put("literal", true) })
                                    },
                                )
                            },
                        )
                    },
                ),
            ),
            "server" to tool("safe"),
        )
        val provider = McpFunctionProvider(mcpClientService) { _, rawName -> rawName }

        assertEquals(listOf("safe"), provider.providedFunctions.map { it.name().get() })
        assertFalse(provider.canHandle("literal-default"))
    }

    /**
     * 验证无法在固定资源内无损内联的候选只会移除自身，且不会留下可调用绑定。
     */
    @Test
    fun `invalid local definition references skip only their MCP tools`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        val invalidTools = listOf(
            invalidReferenceTool("external", "https://example.test/schema"),
            invalidReferenceTool("fragment", "#/definitions/Foo"),
            invalidReferenceTool("percent", "#/\$defs/Foo%20"),
            invalidReferenceTool("invalid-escape", "#/\$defs/Foo~2"),
            invalidReferenceTool("sibling", "#/\$defs/Foo", sibling = true),
            invalidReferenceTool("missing", "#/\$defs/Missing"),
            primitiveDefinitionTool("primitive-target"),
            nestedDefinitionsTool("nested-definitions"),
            cyclicDefinitionTool("direct-cycle", indirect = false),
            cyclicDefinitionTool("indirect-cycle", indirect = true),
            referenceExpansionBudgetTool("reference-budget"),
            unsupportedDefinitionKeywordTool("definition-any-of", "anyOf"),
            allOfWrappedDefinitionTool("all-of-wrapper"),
        )
        every { mcpClientService.getAllTools() } returns invalidTools.map { "server" to it } + ("server" to tool("safe"))
        val provider = McpFunctionProvider(mcpClientService) { _, rawName -> rawName }

        assertEquals(listOf("safe"), provider.providedFunctions.map { it.name().get() })
        invalidTools.forEach { invalid ->
            assertFalse(provider.canHandle(invalid.name))
            assertEquals(
                "mcp_tool_unavailable",
                provider.execute(invalid.name, emptyMap())["error"]?.toString()?.removeSurrounding("\""),
            )
        }
    }

    /**
     * 验证内部名称碰撞会从声明和执行路由中整体移除。
     */
    @Test
    fun `internal MCP name collisions fail closed`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf(
            "server_part" to tool("tool"),
            "server" to tool("part_tool"),
        )
        val provider = McpFunctionProvider(mcpClientService) { _, _ -> collidingAlias }

        assertTrue(provider.providedFunctions.isEmpty())
        assertFalse(provider.canHandle(collidingAlias))
        assertEquals(
            "mcp_tool_unavailable",
            provider.execute(collidingAlias, emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
        coVerify(exactly = 0) { mcpClientService.callTool(any(), any(), any()) }
    }

    /**
     * 验证关闭或底层异常不会向模型泄露连接细节。
     */
    @Test
    fun `missing and failed MCP calls return the same stable error`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        coEvery { mcpClientService.callTool("server", "tool", any()) } throws IllegalStateException("secret endpoint")
        val provider = McpFunctionProvider(mcpClientService)

        assertEquals(
            "mcp_tool_unavailable",
            provider.execute("missing", emptyMap())["error"]?.toString()?.removeSurrounding("\"")
        )
        val alias = provider.providedFunctions.single().name().get()
        val failed = provider.execute(alias, emptyMap())

        assertEquals("mcp_tool_unavailable", failed["error"]?.toString()?.removeSurrounding("\""))
        assertFalse(failed.toString().contains("secret endpoint"))
    }

    /**
     * 验证 MCP 工具调用取消会原样传递给函数调用方。
     */
    @Test
    fun `MCP tool cancellation propagates to the caller`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        val provider = McpFunctionProvider(mcpClientService)
        val alias = provider.providedFunctions.single().name().get()
        coEvery { mcpClientService.callTool("server", "tool", any()) } throws CancellationException("调用已取消")

        assertFailsWith<CancellationException> {
            provider.execute(alias, emptyMap())
        }
    }

    /**
     * 验证 MCP 声明不会按列表顺序覆盖其他本地提供者的同名声明。
     */
    @Test
    fun `MCP and local provider collisions are not declared or executed`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        every { mcpClientService.getAllTools() } returns listOf("server" to tool("tool"))
        val mcpProvider = McpFunctionProvider(mcpClientService) { _, _ -> collidingAlias }
        val localProvider = FixedFunctionProvider(collidingAlias)
        val router = LocalFunctionRouter(listOf(mcpProvider, localProvider))
        val routeSnapshot = router.refresh()

        assertTrue(routeSnapshot.providedFunctions().isEmpty())
        assertFalse(routeSnapshot.canHandle(collidingAlias))
        assertFailsWith<IllegalArgumentException> {
            routeSnapshot.execute(collidingAlias, emptyMap())
        }
        coVerify(exactly = 0) { mcpClientService.callTool(any(), any(), any()) }
        assertFalse(localProvider.executed)
    }

    /**
     * 验证已向模型声明的函数在后续刷新后仍调用原始 MCP 服务器和工具名称。
     */
    @Test
    fun `a route snapshot keeps the MCP binding declared for its model turn`() = runTest {
        val mcpClientService = mockk<MCPClientService>()
        var tools = listOf("server_old" to tool("tool"))
        every { mcpClientService.getAllTools() } answers { tools }
        coEvery { mcpClientService.callTool("server_old", "tool", emptyMap()) } returns CallToolResult(emptyList())
        coEvery { mcpClientService.callTool("server", "old_tool", emptyMap()) } returns CallToolResult(emptyList())
        val router = LocalFunctionRouter(listOf(McpFunctionProvider(mcpClientService)))
        val declarationPublished = CompletableDeferred<LocalFunctionRouteSnapshot>()
        val allowToolInvocation = CompletableDeferred<Unit>()
        val modelTurn = async {
            val declaredTurn = router.refresh()
            declarationPublished.complete(declaredTurn)
            allowToolInvocation.await()
            declaredTurn.execute(declaredTurn.providedFunctions().single().name().get(), emptyMap())
        }

        declarationPublished.await()
        tools = listOf("server" to tool("old_tool"))
        router.refresh()
        allowToolInvocation.complete(Unit)

        modelTurn.await()

        coVerify(exactly = 1) { mcpClientService.callTool("server_old", "tool", emptyMap()) }
        coVerify(exactly = 0) { mcpClientService.callTool("server", "old_tool", any()) }
    }

    private class FixedFunctionProvider(
        name: String,
    ) : LocalFunctionProvider() {
        override val providedFunctions: List<FunctionDeclaration> = listOf(
            FunctionDeclaration.builder()
                .name(name)
                .parameters(Schema.builder().build())
                .build(),
        )
        var executed = false

        override suspend fun execute(functionName: String, args: Map<String, Any?>) = buildJsonObject {
            executed = true
        }
    }

    private companion object {
        val collidingAlias = "mcp_" + "a".repeat(43)

        fun tool(name: String): Tool = Tool(name, ToolSchema())

        fun homeAssistantTools(): List<Tool> = listOf(
            Tool(
                name = "HassTurnOn",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                        put("domain", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        })
                        put("device_class", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    add(JsonPrimitive("outlet"))
                                    add(JsonPrimitive("switch"))
                                })
                            })
                        })
                    },
                ),
            ),
            Tool(
                name = "HassLightSet",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        put("name", buildJsonObject { put("type", "string") })
                        put("brightness", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 100)
                        })
                    },
                ),
            ),
        )

        fun constrainedTool(
            name: String,
            keyword: String,
            value: JsonElement,
            type: String = "string",
        ): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("value", buildJsonObject {
                        put("type", type)
                        put(keyword, value)
                    })
                },
            ),
        )

        fun definitionTool(name: String): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("request", buildJsonObject { put("\$ref", "#/\$defs/Request") })
                    put("items", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("\$ref", "#/\$defs/Item") })
                    })
                    put("again", buildJsonObject { put("\$ref", "#/\$defs/Item") })
                },
                required = listOf("request"),
                defs = buildJsonObject {
                    put("Request", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("item", buildJsonObject { put("\$ref", "#/\$defs/Item") })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("item")) })
                    })
                    put("Item", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("name")) })
                    })
                },
            ),
        )

        fun escapedDefinitionTool(): Tool = Tool(
            name = "escaped",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("escaped", buildJsonObject { put("\$ref", "#/\$defs/slash~1name~0part") })
                },
                defs = buildJsonObject {
                    put("slash/name~part", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("escapedValue", buildJsonObject { put("type", "string") })
                        })
                    })
                },
            ),
        )

        fun invalidReferenceTool(name: String, reference: String, sibling: Boolean = false): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("value", buildJsonObject {
                        put("\$ref", reference)
                        if (sibling) put("description", "must reject")
                    })
                },
                defs = buildJsonObject {
                    put("Foo", buildJsonObject { put("type", "string") })
                },
            ),
        )

        fun primitiveDefinitionTool(name: String): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("value", buildJsonObject { put("\$ref", "#/\$defs/Foo") }) },
                defs = buildJsonObject { put("Foo", JsonPrimitive("not a schema")) },
            ),
        )

        fun nestedDefinitionsTool(name: String): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("value", buildJsonObject { put("\$ref", "#/\$defs/Foo") }) },
                defs = buildJsonObject {
                    put("Foo", buildJsonObject {
                        put("\$defs", buildJsonObject { put("Nested", buildJsonObject { put("type", "string") }) })
                        put("type", "object")
                    })
                },
            ),
        )

        fun cyclicDefinitionTool(name: String, indirect: Boolean): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject { put("value", buildJsonObject { put("\$ref", "#/\$defs/A") }) },
                defs = buildJsonObject {
                    put("A", buildJsonObject { put("\$ref", if (indirect) "#/\$defs/B" else "#/\$defs/A") })
                    if (indirect) put("B", buildJsonObject { put("\$ref", "#/\$defs/A") })
                },
            ),
        )

        fun referenceExpansionBudgetTool(name: String): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    repeat(129) { index -> put("value$index", buildJsonObject { put("\$ref", "#/\$defs/Item") }) }
                },
                defs = buildJsonObject {
                    put("Item", buildJsonObject { put("type", "string") })
                },
            ),
        )

        fun unsupportedDefinitionKeywordTool(name: String, keyword: String): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("value", buildJsonObject { put("\$ref", "#/\$defs/Foo") })
                },
                defs = buildJsonObject {
                    put("Foo", buildJsonObject {
                        put("type", "string")
                        when (keyword) {
                            "anyOf" -> put(
                                keyword,
                                buildJsonArray {
                                    add(buildJsonObject { put("type", "string") })
                                    add(buildJsonObject { put("type", "integer") })
                                },
                            )

                            else -> error("测试仅支持受拒绝的 schema 关键字。")
                        }
                    })
                },
            ),
        )

        fun allOfWrappedDefinitionTool(name: String): Tool = Tool(
            name = name,
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    put("value", buildJsonObject {
                        put(
                            "allOf",
                            buildJsonArray {
                                add(buildJsonObject { put("\$ref", "#/\$defs/Foo") })
                            },
                        )
                    })
                },
                defs = buildJsonObject {
                    put("Foo", buildJsonObject { put("type", "string") })
                },
            ),
        )
    }
}
