package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.openai.core.JsonValue
import com.openai.models.FunctionDefinition
import com.openai.models.FunctionParameters
import kotlinx.serialization.json.*
import kotlin.jvm.optionals.getOrNull

/**
 * 本地功能提供者基类。
 */
abstract class LocalFunctionProvider {
    /**
     * 该提供者支持的所有函数声明。
     */
    abstract val providedFunctions: List<FunctionDeclaration>

    /**
     * 获取 OpenAI 格式的函数声明列表。
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
     * @param functionName 函数名。
     * @return 如果可以处理则返回 true。
     */
    open fun canHandle(functionName: String): Boolean = providedFunctions.any { it.name().orElse(null) == functionName }

    /**
     * 执行具体的函数逻辑。
     *
     * @param functionName 函数名。
     * @param args 参数列表。
     * @return 执行结果 JsonObject。
     */
    abstract suspend fun execute(
        functionName: String,
        args: Map<String, Any?>,
    ): JsonObject

    companion object {
        /**
         * 将 JsonObject 转换为 Map<String, Any?>。
         */
        fun JsonObject.toMap(): Map<String, Any?> =
            this.mapValues { (_, value) ->
                value.toAny()
            }

        private fun JsonElement.toAny(): Any? =
            when (this) {
                is JsonNull -> null
                is JsonPrimitive ->
                    if (this.isString) {
                        this.content
                    } else if (this.booleanOrNull != null) {
                        this.boolean
                    } else if (this.intOrNull != null) {
                        this.int
                    } else if (this.longOrNull != null) {
                        this.long
                    } else if (this.doubleOrNull != null) {
                        this.double
                    } else {
                        this.content
                    }

                is JsonArray -> this.map { it.toAny() }
                is JsonObject -> this.toMap()
            }
    }

    private fun convertGeminiSchemaToOpenAI(geminiSchema: Schema): FunctionParameters {
        val schemaMap = recursiveConvertSchema(geminiSchema)
        return FunctionParameters.builder()
            .putAllAdditionalProperties(schemaMap.mapValues { JsonValue.from(it.value) })
            .build()
    }

    private fun recursiveConvertSchema(geminiSchema: Schema): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()

        val type = geminiSchema.type()?.getOrNull()?.toString()
        if (type != null) {
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

        geminiSchema.description()?.ifPresent { map["description"] = it }

        geminiSchema.properties()?.ifPresent { props ->
            val propertiesMap = mutableMapOf<String, Any?>()
            props.forEach { (name, schema) ->
                propertiesMap[name] = recursiveConvertSchema(schema)
            }
            map["properties"] = propertiesMap
        }

        geminiSchema.required()?.ifPresent { required ->
            map["required"] = required
        }

        geminiSchema.items()?.ifPresent { items ->
            map["items"] = recursiveConvertSchema(items)
        }

        return map
    }
}
