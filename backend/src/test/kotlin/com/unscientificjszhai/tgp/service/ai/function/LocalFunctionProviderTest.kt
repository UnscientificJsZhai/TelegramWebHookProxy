package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import kotlin.jvm.optionals.getOrNull
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalFunctionProviderTest {

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

        // Verify that the converted schema contains key elements
        assertTrue(paramsString.contains("location"), "Params should contain 'location'")
        assertTrue(paramsString.contains("object"), "Params should contain 'object'")
        assertTrue(paramsString.contains("string"), "Params should contain 'string'")
        assertTrue(paramsString.contains("The city and state"), "Params should contain description")
        assertTrue(paramsString.contains("required"), "Params should contain 'required'")
    }
}
