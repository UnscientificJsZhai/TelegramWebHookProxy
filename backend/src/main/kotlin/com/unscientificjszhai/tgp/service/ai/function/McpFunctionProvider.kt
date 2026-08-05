package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.MAX_MCP_TOOL_SCHEMA_BYTES
import com.unscientificjszhai.tgp.service.ai.McpToolResultTooLargeException
import com.unscientificjszhai.tgp.service.ai.validateMcpToolResult
import com.unscientificjszhai.tgp.utils.SafeLogging
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Base64
import java.util.Collections
import java.util.ArrayDeque
import java.util.IdentityHashMap

/**
 * 为 MCP 服务器工具生成模型可见安全别名的策略。
 *
 * 实现必须为同一 `serverName` 与 `rawToolName` 返回稳定名称。测试可注入固定实现以验证碰撞、非法名称及
 * 生成失败的 fail-closed 行为。
 */
fun interface McpToolAliasGenerator {
    /**
     * 生成一个 MCP 工具别名。
     *
     * @param serverName 工具所属 MCP 服务器的已校验名称。
     * @param rawToolName MCP 服务器原始声明的工具名称；可包含模型函数名不支持的字符。
     * @return 面向模型的别名；必须匹配 `[A-Za-z0-9_-]{1,64}`，默认实现返回 `mcp_` 后接 43 个 Base64URL
     * 无填充字符。
     */
    fun alias(serverName: String, rawToolName: String): String
}

/**
 * 将已连接 MCP 服务器的工具暴露为模型可调用函数的提供者。
 *
 * 每个函数名称均是固定长度的 SHA-256 Base64URL 别名，不直接暴露服务器或原始工具名称。读取
 * [providedFunctions] 会发布包含函数声明和真实 MCP 目标的同一不可变快照；[canHandle] 与 [execute]
 * 只读取最近一次已发布快照。只有输入架构完整属于 Gemini 与 OpenAI 均可保真的子集时，工具才会进入
 * 该快照；无法安全转换的工具不会发布声明或执行绑定。
 *
 * @param mcpClientService 提供 MCP 连接、工具快照和工具调用能力的服务。
 * @param toolAliasGenerator 生成安全模型函数名的策略；默认使用不可逆的固定 SHA-256 别名。
 */
class McpFunctionProvider(
    private val mcpClientService: MCPClientService,
    private val toolAliasGenerator: McpToolAliasGenerator = McpToolAliasGenerator(::defaultMcpToolAlias),
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

    /** 将 JSON schema 节点转换为 Gemini 格式时使用的显式后序工作项。 */
    private sealed interface SchemaConversionWork {
        data class Visit(val value: JsonElement, val parentKey: String?) : SchemaConversionWork
        data class BuildObject(val value: JsonObject) : SchemaConversionWork
        data class BuildArray(val value: JsonArray) : SchemaConversionWork
    }

    /** 内联 MCP 本地 `$defs` 引用时使用的显式工作项。 */
    private sealed interface DefinitionInliningWork {
        data class Schema(
            val source: JsonElement,
            val depth: Int,
            val definitionPath: Set<String>,
            val output: JsonResult,
        ) : DefinitionInliningWork

        data class SchemaMap(
            val source: JsonElement,
            val depth: Int,
            val definitionPath: Set<String>,
            val output: JsonResult,
        ) : DefinitionInliningWork

        data class SchemaArray(
            val source: JsonElement,
            val depth: Int,
            val definitionPath: Set<String>,
            val output: JsonResult,
        ) : DefinitionInliningWork

        data class BuildObject(
            val entries: LinkedHashMap<String, JsonResult>,
            val output: JsonResult,
        ) : DefinitionInliningWork

        data class BuildArray(
            val entries: List<JsonResult>,
            val output: JsonResult,
        ) : DefinitionInliningWork
    }

    /** 显式后序内联工作项之间传递 JSON 结果的槽位。 */
    private class JsonResult {
        lateinit var value: JsonElement
    }

    @Volatile
    private var toolSnapshot = ToolSnapshot(emptyList(), emptyMap())

    /**
     * 获取当前所有 MCP 工具转换而成的函数声明。
     *
     * 无法在资源预算内安全转换、名称冲突或无法生成合法别名的工具不会声明，也不会保留可调用绑定。
     *
     * @return 每个可安全转换的已发现 MCP 工具对应一个函数声明；未连接服务器、无工具或所有工具均无法安全
     * 转换时返回空列表。
     */
    override val providedFunctions: List<FunctionDeclaration>
        get() {
            val refreshedSnapshot = try {
                createSnapshot(mcpClientService.getAllTools())
            } catch (e: Exception) {
                logger.warn(
                    "Unable to refresh MCP tool declarations; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
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
            // 测试替身可绕过 MCPClientService 的入站校验；在序列化结果前再次确认其 JSON 内容仍在边界内。
            validateMcpToolResult(result)
            buildJsonObject {
                put("result", json.encodeToJsonElement(result).also(JsonStructureLimits::validateElement))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: McpToolResultTooLargeException) {
            toolResultTooLargeError()
        } catch (_: Exception) {
            logger.warn("MCP tool execution failed with a safe local error category.")
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
        val candidates = mcpTools.mapNotNull { (serverName, mcpTool) ->
            try {
                val schema = mcpTool.toInlinedGeminiSchema()
                toolAliasGenerator.alias(serverName, mcpTool.name)
                    .also { functionName -> require(MCP_TOOL_ALIAS_PATTERN.matches(functionName)) }
                    .let { functionName ->
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Ignoring MCP tool declaration that could not be represented safely; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
                null
            }
        }
        val groupedCandidates = candidates.groupBy { it.first }
        val unambiguousCandidates = groupedCandidates.values
            .filter { it.size == 1 }
            .map { it.single() }
            .sortedBy { it.first }
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

    /** 返回不包含服务器响应内容的 MCP 工具结果过大错误。 */
    private fun toolResultTooLargeError(): JsonObject = buildJsonObject {
        put("error", MCP_TOOL_RESULT_TOO_LARGE)
    }

    /**
     * 构造仅含 Gemini 可表示字段、且 schema 位置不包含 `$defs` 或 `$ref` 的 MCP 输入架构。
     *
     * MCP 输入架构的根定义表只允许出现在根节点；引用会在固定结构与展开预算内以内联副本替换。任何
     * 无法无损验证的引用形式均拒绝当前工具，而不把悬空约束交给模型 SDK。
     */
    private fun Tool.toInlinedGeminiSchema(): Schema {
        val source = buildJsonObject {
            put("type", "object")
            put("properties", inputSchema.properties ?: JsonObject(emptyMap()))
            val required = inputSchema.required ?: emptyList()
            if (required.isNotEmpty()) {
                put("required", buildJsonArray { required.forEach { add(it) } })
            }
            inputSchema.defs?.let { definitions -> put(LOCAL_DEFINITIONS_KEY, definitions) }
        }
        JsonStructureLimits.validateElement(source, MCP_SCHEMA_STRUCTURE_BUDGET)
        require(source.toString().toByteArray(Charsets.UTF_8).size <= MAX_MCP_TOOL_SCHEMA_BYTES) {
            "MCP 工具输入架构超过限制。"
        }

        val inlined = source.inlineLocalDefinitions()
        JsonStructureLimits.validateElement(inlined, MCP_SCHEMA_STRUCTURE_BUDGET)
        val schemaJson = inlined.toGeminiSchemaJson().toString()
        JsonStructureLimits.validateJsonString(schemaJson, MCP_SCHEMA_STRUCTURE_BUDGET)
        require(schemaJson.toByteArray(Charsets.UTF_8).size <= MAX_MCP_TOOL_SCHEMA_BYTES) {
            "内联后的 MCP 工具输入架构超过限制。"
        }
        return requireNotNull(Schema.fromJson(schemaJson)) {
            "MCP 工具输入架构无法转换为 Gemini schema。"
        }
    }

    /**
     * 在不使用调用栈的前提下展开根 `$defs` 中的纯本地引用。
     *
     * 每个定义名称只在当前展开路径中用于循环检测，因此同一无环定义可在多个参数位置重复使用。输出
     * 通过显式节点、深度与引用预算限制，避免少量重复引用放大为大量分配。
     */
    private fun JsonObject.inlineLocalDefinitions(): JsonObject {
        val definitions = this[LOCAL_DEFINITIONS_KEY]?.let { value ->
            value as? JsonObject ?: throw IllegalArgumentException($$"MCP $defs 必须是 JSON 对象。")
        } ?: JsonObject(emptyMap())
        val root = JsonObject(filterKeys { it != LOCAL_DEFINITIONS_KEY })
        val budget = InliningBudget()
        val rootResult = JsonResult()
        val work = ArrayDeque<DefinitionInliningWork>()
        work.addLast(DefinitionInliningWork.Schema(root, 0, emptySet(), rootResult))
        while (work.isNotEmpty()) {
            when (val item = work.removeLast()) {
                is DefinitionInliningWork.Schema -> {
                    val source = item.source
                    require(source is JsonObject) { "MCP schema 节点必须是 JSON 对象。" }
                    source[LOCAL_DEFINITIONS_KEY]?.let {
                        throw IllegalArgumentException($$"MCP schema 不允许嵌套 $defs。")
                    }
                    val reference = source[LOCAL_REFERENCE_KEY]
                    if (reference != null) {
                        require(source.size == 1) { $$"MCP $ref 不能与其他 schema 字段并存。" }
                        val definitionName = parseLocalDefinitionReference(reference)
                        require(item.definitionPath.addsNoDuplicate(definitionName)) { $$"MCP $ref 存在循环。" }
                        budget.claimReference()
                        val target = definitions[definitionName]
                            ?: throw IllegalArgumentException($$"MCP $ref 指向不存在的 $defs 条目。")
                        require(target is JsonObject) { $$"MCP $ref 目标必须是 JSON schema 对象。" }
                        work.addLast(
                            DefinitionInliningWork.Schema(
                                source = target,
                                depth = item.depth,
                                definitionPath = item.definitionPath + definitionName,
                                output = item.output,
                            ),
                        )
                        continue
                    }

                    validateGeminiOpenAiSchemaNode(source)
                    budget.claimObject(source.size, item.depth)
                    val entries = LinkedHashMap<String, JsonResult>(source.size)
                    work.addLast(DefinitionInliningWork.BuildObject(entries, item.output))
                    source.forEach { (key, value) ->
                        val child = JsonResult()
                        entries[key] = child
                        when (key) {
                            "properties", "patternProperties", "dependentSchemas" ->
                                work.addLast(
                                    DefinitionInliningWork.SchemaMap(
                                        source = value,
                                        depth = item.depth + 1,
                                        definitionPath = item.definitionPath,
                                        output = child,
                                    ),
                                )

                            "items" -> work.addLast(
                                if (value is JsonArray) {
                                    DefinitionInliningWork.SchemaArray(
                                        source = value,
                                        depth = item.depth + 1,
                                        definitionPath = item.definitionPath,
                                        output = child,
                                    )
                                } else {
                                    DefinitionInliningWork.Schema(
                                        source = value,
                                        depth = item.depth + 1,
                                        definitionPath = item.definitionPath,
                                        output = child,
                                    )
                                },
                            )

                            "additionalProperties", "contains", "not", "if", "then", "else",
                            "propertyNames", "unevaluatedProperties",
                                -> work.addLast(
                                DefinitionInliningWork.Schema(
                                    source = value,
                                    depth = item.depth + 1,
                                    definitionPath = item.definitionPath,
                                    output = child,
                                ),
                            )

                            "allOf", "anyOf", "oneOf", "prefixItems" ->
                                work.addLast(
                                    DefinitionInliningWork.SchemaArray(
                                        source = value,
                                        depth = item.depth + 1,
                                        definitionPath = item.definitionPath,
                                        output = child,
                                    ),
                                )

                            else -> child.value = copyRawSchemaValue(value, item.depth + 1, budget)
                        }
                    }
                }

                is DefinitionInliningWork.SchemaMap -> {
                    val source = item.source
                    require(source is JsonObject) { "MCP schema 映射字段必须是 JSON 对象。" }
                    budget.claimObject(source.size, item.depth)
                    val entries = LinkedHashMap<String, JsonResult>(source.size)
                    work.addLast(DefinitionInliningWork.BuildObject(entries, item.output))
                    source.forEach { (key, value) ->
                        val child = JsonResult()
                        entries[key] = child
                        work.addLast(
                            DefinitionInliningWork.Schema(
                                source = value,
                                depth = item.depth + 1,
                                definitionPath = item.definitionPath,
                                output = child,
                            ),
                        )
                    }
                }

                is DefinitionInliningWork.SchemaArray -> {
                    val source = item.source
                    require(source is JsonArray) { "MCP schema 数组字段必须是 JSON 数组。" }
                    budget.claimArray(source.size, item.depth)
                    val entries = List(source.size) { JsonResult() }
                    work.addLast(DefinitionInliningWork.BuildArray(entries, item.output))
                    source.forEachIndexed { index, value ->
                        work.addLast(
                            DefinitionInliningWork.Schema(
                                source = value,
                                depth = item.depth + 1,
                                definitionPath = item.definitionPath,
                                output = entries[index],
                            ),
                        )
                    }
                }

                is DefinitionInliningWork.BuildObject -> {
                    item.output.value = JsonObject(LinkedHashMap<String, JsonElement>(item.entries.size).apply {
                        item.entries.forEach { (key, value) -> put(key, value.value) }
                    })
                }

                is DefinitionInliningWork.BuildArray -> item.output.value = JsonArray(item.entries.map { it.value })
            }
        }
        return rootResult.value as? JsonObject ?: error("MCP schema 根节点必须是对象。")
    }

    /** 复制不会携带 schema 语义的值，同时把该副本计入输出结构预算。 */
    private fun copyRawSchemaValue(
        source: JsonElement,
        depth: Int,
        budget: InliningBudget,
    ): JsonElement {
        data class Pending(val value: JsonElement, val depth: Int)

        val pending = ArrayDeque<Pending>()
        pending.addLast(Pending(source, depth))
        while (pending.isNotEmpty()) {
            val (value, currentDepth) = pending.removeLast()
            when (value) {
                is JsonObject -> {
                    budget.claimObject(value.size, currentDepth)
                    value.values.forEach { pending.addLast(Pending(it, currentDepth + 1)) }
                }

                is JsonArray -> {
                    budget.claimArray(value.size, currentDepth)
                    value.forEach { pending.addLast(Pending(it, currentDepth + 1)) }
                }

                is JsonPrimitive,
                JsonNull,
                    -> budget.claimScalar()
            }
        }
        return source
    }

    /** 解析严格的 `#/$defs/<RFC6901 token>` 引用。 */
    private fun parseLocalDefinitionReference(reference: JsonElement): String {
        val text = (reference as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?: throw IllegalArgumentException($$"MCP $ref 必须是字符串。")
        require(!text.contains('%') && text.startsWith(LOCAL_DEFINITIONS_REFERENCE_PREFIX)) {
            $$"MCP $ref 必须是纯本地 $defs 引用。"
        }
        val token = text.removePrefix(LOCAL_DEFINITIONS_REFERENCE_PREFIX)
        require(token.isNotEmpty() && !token.contains('/')) { $$"MCP $ref 路径不合法。" }
        val decoded = StringBuilder(token.length)
        var index = 0
        while (index < token.length) {
            val character = token[index]
            if (character != '~') {
                decoded.append(character)
                index++
                continue
            }
            require(index + 1 < token.length) { $$"MCP $ref 转义不完整。" }
            when (token[index + 1]) {
                '0' -> decoded.append('~')
                '1' -> decoded.append('/')
                else -> throw IllegalArgumentException($$"MCP $ref 包含非法 RFC6901 转义。")
            }
            index += 2
        }
        return decoded.toString()
    }

    /**
     * 验证单个 schema 节点是否完全属于 Gemini 与 OpenAI 共同可保真的子集。
     *
     * 调用方已通过 [DefinitionInliningWork] 的显式栈逐个访问根属性、引用目标及其子 schema，并累积
     * 结构预算；本方法只检查当前对象，不递归遍历，以避免不可信 schema 使用调用栈。`properties` 映射
     * 的键以及 `default`、`example`、`enum` 等数据关键字的内容都不会被当作 schema 节点；这些关键字
     * 若不在白名单中，会由包含它们的 schema 节点直接拒绝。其余未列出的 JSON Schema 关键字即使 Gemini
     * SDK 能读取，也可能在 OpenAI 转换时丢失，因此必须拒绝整个 MCP 工具。字符串 `enum` 以及数值
     * `minimum`、`maximum` 会由两条转换链共同保留，属于允许的参数约束。
     */
    private fun validateGeminiOpenAiSchemaNode(source: JsonObject) {
        source.forEach { (key, value) ->
            when (key) {
                "type" -> requireSupportedSchemaType(value)
                "properties" -> require(value is JsonObject) {
                    "MCP schema 的 properties 必须是 JSON 对象。"
                }

                "required" -> require(value is JsonArray && value.all { required ->
                    required is JsonPrimitive && required.isString
                }) {
                    "MCP schema 的 required 必须是字符串数组。"
                }

                "items" -> require(value is JsonObject) {
                    "MCP schema 的 items 必须是 JSON 对象。"
                }

                "description", "pattern" -> require(value is JsonPrimitive && value.isString) {
                    "MCP schema 的 $key 必须是字符串。"
                }

                "enum" -> require(value is JsonArray && value.isNotEmpty() && value.all { enumValue ->
                    enumValue is JsonPrimitive && enumValue.isString
                }) {
                    "MCP schema 的 enum 必须是非空字符串数组。"
                }

                "minLength", "maxLength" -> require(
                    value is JsonPrimitive && !value.isString && value.longOrNull?.let { it >= 0 } == true,
                ) {
                    "MCP schema 的 $key 必须是非负整数。"
                }

                "minimum", "maximum" -> require(
                    value.isExactlyRepresentableSchemaNumber(),
                ) {
                    "MCP schema 的 $key 必须是可由模型 schema 无损表示的有限数字。"
                }

                else -> throw IllegalArgumentException("MCP schema 包含无法无损转换的 $key 关键字。")
            }
        }
    }

    /** 验证 JSON 数值在 Gemini [Schema] 的 `Double` 字段中往返后仍保持相同十进制值。 */
    private fun JsonElement.isExactlyRepresentableSchemaNumber(): Boolean {
        val primitive = (this as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString) ?: return false
        val number = primitive.doubleOrNull?.takeIf(Double::isFinite) ?: return false
        return try {
            BigDecimal(primitive.content).compareTo(BigDecimal.valueOf(number)) == 0
        } catch (_: NumberFormatException) {
            false
        }
    }

    /** 验证 Gemini 与 OpenAI 参数链路都会保留的 JSON Schema 基本类型。 */
    private fun requireSupportedSchemaType(value: JsonElement) {
        val type = (value as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
        require(type in GEMINI_OPENAI_SCHEMA_TYPES) {
            "MCP schema 包含不受支持的 type。"
        }
    }

    /** 用于展开后 schema 的累积资源预算。 */
    private class InliningBudget {
        private var nodes = 0
        private var references = 0

        fun claimObject(size: Int, depth: Int) {
            checkDepth(depth)
            claimNodes(1 + size)
        }

        fun claimArray(size: Int, depth: Int) {
            checkDepth(depth)
            claimNodes(1)
            require(size <= MCP_SCHEMA_STRUCTURE_BUDGET.maxNodes - nodes) { "MCP schema 节点数超过限制。" }
        }

        fun claimScalar() = claimNodes(1)

        fun claimReference() {
            require(++references <= MAX_MCP_SCHEMA_REFERENCE_EXPANSIONS) { $$"MCP $ref 展开次数超过限制。" }
        }

        private fun checkDepth(depth: Int) {
            require(depth + 1 <= MCP_SCHEMA_STRUCTURE_BUDGET.maxDepth) { "MCP schema 嵌套深度超过限制。" }
        }

        private fun claimNodes(increment: Int) {
            require(increment >= 0 && increment <= MCP_SCHEMA_STRUCTURE_BUDGET.maxNodes - nodes) {
                "MCP schema 节点数超过限制。"
            }
            nodes += increment
        }
    }

    private fun Set<String>.addsNoDuplicate(definitionName: String): Boolean = definitionName !in this

    /**
     * 将 MCP 的 JSON Schema 转换为 Gemini 兼容的格式（主要是 Type 大写）。
     */
    private fun JsonElement.toGeminiSchemaJson(): JsonElement {
        JsonStructureLimits.validateElement(this)
        val converted = IdentityHashMap<JsonElement, JsonElement>()
        val work = ArrayDeque<SchemaConversionWork>()
        work.addLast(SchemaConversionWork.Visit(this, null))
        while (work.isNotEmpty()) {
            when (val item = work.removeLast()) {
                is SchemaConversionWork.Visit -> when (val value = item.value) {
                    is JsonObject -> {
                        work.addLast(SchemaConversionWork.BuildObject(value))
                        value.forEach { (key, child) ->
                            work.addLast(SchemaConversionWork.Visit(child, key))
                        }
                    }

                    is JsonArray -> {
                        work.addLast(SchemaConversionWork.BuildArray(value))
                        for (child in value) {
                            work.addLast(SchemaConversionWork.Visit(child, null))
                        }
                    }

                    is JsonPrimitive -> {
                        converted[value] = if (item.parentKey == "type" && value.isString) {
                            JsonPrimitive(value.content.uppercase())
                        } else {
                            value
                        }
                    }

                    JsonNull -> converted[value] = value
                }

                is SchemaConversionWork.BuildObject -> {
                    converted[item.value] = JsonObject(
                        LinkedHashMap<String, JsonElement>().apply {
                            item.value.forEach { (key, child) -> put(key, converted.getValue(child)) }
                        },
                    )
                }

                is SchemaConversionWork.BuildArray -> {
                    converted[item.value] = JsonArray(item.value.map { child -> converted.getValue(child) })
                }
            }
        }
        return converted.getValue(this).also(JsonStructureLimits::validateElement)
    }

    private companion object {
        const val MCP_TOOL_UNAVAILABLE = "mcp_tool_unavailable"
        const val MCP_TOOL_RESULT_TOO_LARGE = "mcp_tool_result_too_large"
        const val LOCAL_DEFINITIONS_KEY = $$"$defs"
        const val LOCAL_REFERENCE_KEY = $$"$ref"
        const val LOCAL_DEFINITIONS_REFERENCE_PREFIX = $$"#/$defs/"
        const val MAX_MCP_SCHEMA_REFERENCE_EXPANSIONS = 128
        val MCP_TOOL_ALIAS_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
        val GEMINI_OPENAI_SCHEMA_TYPES = setOf("object", "array", "string", "number", "integer", "boolean")
        val MCP_SCHEMA_STRUCTURE_BUDGET = JsonStructureLimits.Budget(
            maxDepth = JsonStructureLimits.MAX_DEPTH,
            maxNodes = JsonStructureLimits.MAX_NODES,
        )
    }
}

private fun defaultMcpToolAlias(serverName: String, rawToolName: String): String {
    val serverBytes = serverName.toByteArray(Charsets.UTF_8)
    val toolBytes = rawToolName.toByteArray(Charsets.UTF_8)
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(serverBytes.size.toFourByteLength())
    digest.update(serverBytes)
    digest.update(toolBytes.size.toFourByteLength())
    digest.update(toolBytes)
    return "mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
}

private fun Int.toFourByteLength(): ByteArray = byteArrayOf(
    (this ushr 24).toByte(),
    (this ushr 16).toByte(),
    (this ushr 8).toByte(),
    toByte(),
)
