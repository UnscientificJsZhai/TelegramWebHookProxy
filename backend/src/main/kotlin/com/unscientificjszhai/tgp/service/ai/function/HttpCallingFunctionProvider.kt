package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * 提供 HTTP API 调用能力的本地功能提供者。
 */
class HttpCallingFunctionProvider : LocalFunctionProvider() {
    private val logger = LoggerFactory.getLogger(HttpCallingFunctionProvider::class.java)

    private val httpClient =
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60000
            }
        }

    override val providedFunctions: List<FunctionDeclaration> by lazy {
        val callHttpApiSchemaJson =
            buildJsonObject {
                put("type", "OBJECT")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "url",
                            buildJsonObject {
                                put("type", "STRING")
                                put("description", "The URL to call.")
                            },
                        )
                        put(
                            "method",
                            buildJsonObject {
                                put("type", "STRING")
                                put("description", "HTTP method (GET, POST, etc.). Default is GET.")
                            },
                        )
                        put(
                            "headers",
                            buildJsonObject {
                                put("type", "OBJECT")
                                put("description", "HTTP headers as a key-value object.")
                            },
                        )
                        put(
                            "body",
                            buildJsonObject {
                                put("type", "STRING")
                                put("description", "HTTP request body.")
                            },
                        )
                    },
                )
                put("required", buildJsonArray { add("url") })
            }.toString()

        listOf(
            FunctionDeclaration
                .builder()
                .name("call_http_api")
                .description("Call a local or remote HTTP API. Useful for triggering webhooks or fetching local data.")
                .parameters(Schema.fromJson(callHttpApiSchemaJson))
                .build(),
        )
    }

    override suspend fun execute(
        functionName: String,
        args: Map<String, Any?>,
    ): JsonObject =
        when (functionName) {
            "call_http_api" -> callHttpApi(args)
            else -> buildJsonObject { put("error", "Unsupported function: $functionName") }
        }

    private suspend fun callHttpApi(args: Map<String, Any?>): JsonObject =
        try {
            val url = args["url"] as? String ?: throw IllegalArgumentException("Missing URL")
            val method = (args["method"] as? String) ?: "GET"
            val headers =
                (args["headers"] as? Map<*, *>)
                    ?.mapKeys { it.key.toString() }
                    ?.mapValues { it.value.toString() } ?: emptyMap()
            val body = args["body"] as? String

            val response =
                httpClient.request(url) {
                    this.method = HttpMethod.parse(method.uppercase())
                    headers.forEach { (key, value) ->
                        header(key, value)
                    }
                    if (body != null) {
                        setBody(body)
                        if (headers.keys.none { it.equals(HttpHeaders.ContentType, ignoreCase = true) }) {
                            header(HttpHeaders.ContentType, ContentType.Application.Json)
                        }
                    }
                }

            buildJsonObject {
                put("status", response.status.value)
                put("body", response.bodyAsText())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Error calling HTTP API", e)
            buildJsonObject {
                put("error", (e.message ?: "Unknown error"))
            }
        }
}
