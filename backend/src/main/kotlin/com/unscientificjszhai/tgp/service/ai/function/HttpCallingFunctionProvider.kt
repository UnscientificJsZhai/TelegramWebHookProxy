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
 * 提供通过模型函数调用 HTTP API 的能力。
 *
 * 单次请求最长等待 60 秒。函数调用错误会以 JSON 中的 `error` 字段返回，协程取消会继续向上抛出。
 */
class HttpCallingFunctionProvider : LocalFunctionProvider() {
    private val logger = LoggerFactory.getLogger(HttpCallingFunctionProvider::class.java)

    private val httpClient =
        HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 60000
            }
        }

    /**
     * 获取 `call_http_api` 函数的声明。
     *
     * 该函数要求提供 URL，可选请求方法、请求头和文本请求体。
     */
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

    /**
     * 执行 HTTP 函数调用。
     *
     * 调用会发起网络 I/O，协程取消会停止等待并向上抛出取消异常。
     *
     * @param functionName 要执行的函数名称；仅 `call_http_api` 会发起请求。
     * @param args 函数参数映射。`call_http_api` 要求 `url` 为字符串；可选 `method`、`headers` 和
     * `body` 字段会按函数声明处理。
     * @return 成功时包含 HTTP 状态码和响应正文的 JSON 对象；函数不受支持或请求失败时包含
     * `error` 字段的 JSON 对象。
     * @throws CancellationException 当调用方取消协程时抛出。
     */
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
