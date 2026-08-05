package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import kotlinx.serialization.json.*
import java.util.ArrayDeque
import kotlin.jvm.optionals.getOrNull

/**
 * 定义可供 AI 模型调用的本地函数提供者契约。
 *
 * 实现类通过 [providedFunctions] 描述其支持的函数，并通过 [execute] 处理与函数声明匹配
 * 的调用。
 */
abstract class LocalFunctionProvider {
    /**
     * 获取该提供者支持的所有函数声明。
     *
     * 函数名称应在同一提供者内唯一，并与 [execute] 可处理的名称保持一致。
     */
    abstract val providedFunctions: List<FunctionDeclaration>

    /**
     * 将 [providedFunctions] 转换为 OpenAI 格式的函数声明列表。
     *
     * @return 与 [providedFunctions] 顺序一致的 OpenAI 函数定义；未提供函数时返回空列表。
     */
    val providedOpenAIFunctions: List<FunctionDefinition>
        get() = providedFunctions.map { func ->
            FunctionDefinition.builder()
                .name(func.name().get())
                .apply { func.description().ifPresent { description(it) } }
                .parameters(convertGeminiSchemaToOpenAI(func.parameters().get()))
                .build()
        }

    /**
     * 检查该提供者是否可以处理指定的函数。
     *
     * @param functionName 要检查的函数名称；空字符串在未声明同名函数时返回 `false`。
     * @return [providedFunctions] 中包含 [functionName] 时返回 `true`，否则返回 `false`。
     */
    open fun canHandle(functionName: String): Boolean = providedFunctions.any { it.name().orElse(null) == functionName }

    /**
     * 执行指定函数的业务逻辑。
     *
     * @param functionName 要执行的函数名称；调用方通常应传入满足 [canHandle] 的名称，未匹配名称
     * 的处理方式由实现类定义。
     * @param args 函数参数映射；值可为 `null`，键和值必须满足该函数声明的输入架构。
     * @return 函数执行结果的 JSON 对象；具体字段由实现类定义。
     */
    abstract suspend fun execute(
        functionName: String,
        args: Map<String, Any?>,
    ): JsonObject

    /**
     * 捕获当前声明函数的不可变执行绑定。
     *
     * 默认实现会在函数存在时绑定当前提供者和函数名称。具有动态路由状态的实现应覆盖此方法，捕获与
     * 当前声明对应的真实目标，避免后续刷新改变已向模型声明的调用含义。
     *
     * @param functionName 要捕获的函数名称；必须是当前提供者已声明的名称。
     * @return 可执行的不可变绑定；函数未声明时返回 `null`。
     */
    internal open fun snapshotCall(functionName: String): LocalFunctionCall? =
        functionName.takeIf(::canHandle)?.let { name ->
            LocalFunctionCall { args -> execute(name, args) }
        }

    /**
     * 提供函数参数格式转换工具。
     */
    companion object {
        /**
         * 将 JSON 对象转换为 Kotlin 参数映射。
         *
         * @receiver 要转换的 JSON 对象。
         * @return 键与原 JSON 对象一致的映射；JSON `null` 转换为 Kotlin `null`，数组和嵌套对象
         * 分别转换为列表和嵌套映射。
         */
        fun JsonObject.toMap(): Map<String, Any?> = JsonStructureLimits.toKotlinMap(this)

        internal fun convertGeminiSchemaToOpenAI(geminiSchema: Schema): FunctionParameters {
            val schemaMap = convertGeminiSchemaToOpenAiMap(geminiSchema)
            return FunctionParameters.builder()
                .putAllAdditionalProperties(schemaMap.mapValues { JsonValue.from(it.value) })
                .build()
        }

        /** 迭代转换 SDK Schema，避免不可信 MCP schema 通过递归耗尽调用栈。 */
        private fun convertGeminiSchemaToOpenAiMap(geminiSchema: Schema): Map<String, Any?> {
            data class Pending(
                val schema: Schema,
                val output: MutableMap<String, Any?>,
                val depth: Int,
            )

            val root = LinkedHashMap<String, Any?>()
            val pending = ArrayDeque<Pending>()
            pending.addLast(Pending(geminiSchema, root, 1))
            var nodes = 0
            while (pending.isNotEmpty()) {
                val (schema, map, depth) = pending.removeLast()
                if (++nodes > JsonStructureLimits.MAX_NODES || depth > JsonStructureLimits.MAX_DEPTH) {
                    throw JsonStructureLimitsExceededSchemaException()
                }
                schema.type()?.getOrNull()?.toString()?.let { type ->
                    map["type"] = when (type.uppercase()) {
                        "OBJECT" -> "object"
                        "ARRAY" -> "array"
                        "STRING" -> "string"
                        "NUMBER" -> "number"
                        "INTEGER" -> "integer"
                        "BOOLEAN" -> "boolean"
                        else -> "string"
                    }
                }
                schema.description()?.ifPresent { map["description"] = it }
                schema.enum_()?.ifPresent { map["enum"] = it }
                schema.pattern()?.ifPresent { map["pattern"] = it }
                schema.minLength()?.ifPresent { map["minLength"] = it }
                schema.maxLength()?.ifPresent { map["maxLength"] = it }
                schema.minimum()?.ifPresent { map["minimum"] = it }
                schema.maximum()?.ifPresent { map["maximum"] = it }
                schema.required()?.ifPresent { map["required"] = it }
                schema.items()?.getOrNull()?.let { items ->
                    val itemsMap = LinkedHashMap<String, Any?>()
                    map["items"] = itemsMap
                    pending.addLast(Pending(items, itemsMap, depth + 1))
                }
                schema.properties()?.getOrNull()?.let { properties ->
                    if (properties.size > JsonStructureLimits.MAX_NODES - nodes) {
                        throw JsonStructureLimitsExceededSchemaException()
                    }
                    val propertiesMap = LinkedHashMap<String, Any?>()
                    map["properties"] = propertiesMap
                    properties.forEach { (name, child) ->
                        val childMap = LinkedHashMap<String, Any?>()
                        propertiesMap[name] = childMap
                        pending.addLast(Pending(child, childMap, depth + 1))
                    }
                }
            }
            return root
        }
    }
}

/** 本地函数声明的 schema 超出 JSON 结构边界时拒绝转换。 */
private class JsonStructureLimitsExceededSchemaException : IllegalArgumentException("函数 schema 超出 JSON 结构限制。")
