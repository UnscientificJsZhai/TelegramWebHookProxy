package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 本地函数提供者转换 OpenAI 函数定义的测试设计。
 */
class LocalFunctionProviderTest {
    /**
     * 为函数声明转换测试提供固定输入的本地函数提供者。
     *
     * 用于验证转换结果保留声明中的名称、描述和参数架构。
     */

    private class TestFunctionProvider : LocalFunctionProvider() {
        override val providedFunctions: List<FunctionDeclaration> = listOf(
            FunctionDeclaration.builder()
                .name("get_weather")
                .description("Get current weather in a location")
                .parameters(
                    Schema.fromJson(
                        """
                        {
                            "type": "OBJECT",
                            "properties": {
                                "location": {
                                    "type": "STRING",
                                    "description": "The city and state, e.g. San Francisco, CA"
                                }
                            },
                            "required": ["location"]
                        }
                    """.trimIndent()
                    )
                )
                .build()
        )

        override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
            return buildJsonObject { }
        }
    }

    /**
     * 验证本地函数定义转换的设计。
     *
     * 验证转换结果包含函数名称、描述及参数架构的关键字段。
     */
    @Test
    fun testProvidedOpenAIFunctionsConversion() {
        val provider = TestFunctionProvider()
        val openAIFunctions = provider.providedOpenAIFunctions

        assertEquals(1, openAIFunctions.size)
        val weatherFunc = openAIFunctions.first()

        assertEquals("get_weather", weatherFunc.name())
        assertEquals("Get current weather in a location", weatherFunc.description().getOrNull())

        val params = weatherFunc.parameters()
        val paramsString = params.toString()

        // 验证转换后的参数架构包含关键字段。
        assertTrue(paramsString.contains("location"), "Params should contain 'location'")
        assertTrue(paramsString.contains("object"), "Params should contain 'object'")
        assertTrue(paramsString.contains("string"), "Params should contain 'string'")
        assertTrue(paramsString.contains("The city and state"), "Params should contain description")
        assertTrue(paramsString.contains("required"), "Params should contain 'required'")
    }
}
