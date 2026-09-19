package com.unscientificjszhai.tgp.service.ai.agent

import com.openai.core.jsonMapper
import com.openai.models.models.Model
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.nio.charset.StandardCharsets

private val modelListJson = Json { ignoreUnknownKeys = true }

/** 统一 Agent 和设置页使用的 Gemini 服务地址，保留环境变量及测试端点覆盖。 */
internal fun geminiModelBaseUrl(baseUrlOverride: String? = null): String {
    val configured = (baseUrlOverride ?: System.getenv("GOOGLE_GEMINI_BASE_URL"))
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf(String::isNotEmpty)
        ?: "https://generativelanguage.googleapis.com"
    return if (configured.endsWith("/v1beta")) configured else "$configured/v1beta"
}

/** 列出 OpenAI 兼容服务的模型，并通过 SDK 的 [Model] DTO 校验每个条目。 */
internal suspend fun fetchOpenAIModels(transport: CancellableOkHttpTransport, request: Request): List<Model> {
    val response = transport.execute(request)
    requireModelListSuccess(response)
    return try {
        val mapper = jsonMapper()
        JsonStructureLimits.validateJsonString(response.body)
        val root = mapper.readTree(response.body)
        val data = root.path("data")
        if (!data.isArray) throw AgentInvalidResponseException()
        data.map { node -> mapper.treeToValue(node, Model::class.java) }
    } catch (e: AgentInvalidResponseException) {
        throw e
    } catch (e: Exception) {
        throw AgentInvalidResponseException(e)
    }
}

/**
 * 刷新 Gemini 模型列表并只接受声明支持 `generateContent` 的非空名称。
 *
 * 原生 API 分页令牌、页数、条目数和重复模型名称均受固定预算限制；任一页失败时不返回部分列表。
 */
internal suspend fun fetchGeminiModelNames(
    transport: CancellableOkHttpTransport,
    baseUrl: String,
    apiKey: String,
): List<String> {
    val discoveredModels = mutableListOf<String>()
    val discoveredNames = mutableSetOf<String>()
    val seenPageTokens = mutableSetOf<String>()
    var pageToken: String? = null
    var pages = 0
    var entries = 0
    var tokenBytes = 0
    var duplicateNames = 0
    do {
        currentCoroutineContext().ensureActive()
        check(++pages <= MAX_GEMINI_MODEL_DISCOVERY_PAGES) {
            "Gemini 模型发现页数超过限制。"
        }
        val url = "$baseUrl/models".toHttpUrl().newBuilder()
            .addQueryParameter("key", apiKey)
            .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
            .build()
        val response = transport.execute(Request.Builder().url(url).get().build())
        requireModelListSuccess(response)
        val root = JsonStructureLimits.parseToJsonElement(modelListJson, response.body).jsonObject
        val pageModels = root["models"] as? JsonArray
            ?: throw IllegalArgumentException("Gemini 模型列表响应缺少 models 数组。")
        pageModels.forEach { entry ->
            check(++entries <= MAX_GEMINI_MODEL_DISCOVERY_ENTRIES) {
                "Gemini 模型发现条目超过限制。"
            }
            val model = entry as? JsonObject ?: return@forEach
            val name = (model["name"] as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf(String::isNotBlank)
                ?: return@forEach
            val supportedMethods = model["supportedGenerationMethods"] as? JsonArray
            if (supportedMethods?.any { method ->
                    (method as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content == "generateContent"
                } != true
            ) {
                return@forEach
            }
            if (discoveredNames.add(name)) {
                discoveredModels += name
            } else {
                check(++duplicateNames <= MAX_GEMINI_MODEL_DISCOVERY_DUPLICATES) {
                    "Gemini 模型发现重复名称超过限制。"
                }
            }
        }
        pageToken = root["nextPageToken"]?.let { token ->
            val value = (token as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("Gemini 模型分页令牌不合法。")
            tokenBytes += value.toByteArray(StandardCharsets.UTF_8).size
            check(tokenBytes <= MAX_GEMINI_MODEL_DISCOVERY_TOKEN_BYTES) {
                "Gemini 模型分页令牌超过限制。"
            }
            check(seenPageTokens.add(value)) { "Gemini 模型分页令牌重复。" }
            value
        }
    } while (pageToken != null)
    return discoveredModels
}

private fun requireModelListSuccess(response: HttpResult) {
    if (response.statusCode !in 200..299) {
        throw AgentUpstreamHttpException.fromResponse(response.statusCode, response.headers)
    }
}

internal const val MAX_GEMINI_MODEL_DISCOVERY_PAGES = 16
internal const val MAX_GEMINI_MODEL_DISCOVERY_ENTRIES = 256
internal const val MAX_GEMINI_MODEL_DISCOVERY_TOKEN_BYTES = 8 * 1024
internal const val MAX_GEMINI_MODEL_DISCOVERY_DUPLICATES = 32
