package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.configureHttpProxyBasicAuthentication
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** currentModel 是用于回显的已保存选择，路由可在其为空时补充就绪 Agent 的实际模型。 */
@Serializable
internal data class ModelListResponse(
    val provider: AIProvider,
    val availableModels: List<String>,
    val currentModel: String,
)

internal class ModelDiscoveryConfigurationException : IllegalArgumentException()
internal class ModelDiscoveryTimeoutException : Exception()

/** 根据保存的凭据查询模型；不创建 Agent、不修改模型选择或会话。 */
internal class ModelDiscoveryService(
    private val timeout: Duration = 30.seconds,
    private val geminiBaseUrl: String? = null,
) {
    private val requests = Semaphore(2)

    suspend fun listModels(settings: AppSettings): ModelListResponse {
        val ai = settings.ai ?: throw ModelDiscoveryConfigurationException()
        val apiKey = when (ai.provider) {
            AIProvider.GEMINI -> ai.geminiApiKey
            AIProvider.OPENAI -> ai.openAiApiKey
        }
        if (apiKey.isBlank()) throw ModelDiscoveryConfigurationException()
        val baseUrl = try {
            when (ai.provider) {
                AIProvider.GEMINI -> geminiModelBaseUrl(geminiBaseUrl)
                AIProvider.OPENAI -> openAiBaseUrlForRequests(ai.openAiBaseUrl)
            }.also { it.toHttpUrl() }
        } catch (_: IllegalArgumentException) {
            throw ModelDiscoveryConfigurationException()
        }

        return withTimeoutOrNull(timeout) {
            requests.withPermit {
                val builder = OkHttpClient.Builder()
                settings.proxy?.let { proxy ->
                    builder.proxy(
                        Proxy(
                            if (proxy.type == ProxyType.HTTP) Proxy.Type.HTTP else Proxy.Type.SOCKS,
                            InetSocketAddress(proxy.host, proxy.port),
                        )
                    )
                    builder.configureHttpProxyBasicAuthentication(proxy)
                }
                val client = builder.build()
                val transport = CancellableOkHttpTransport(client)
                try {
                    val models = when (ai.provider) {
                        AIProvider.GEMINI -> fetchGeminiModelNames(transport, baseUrl, apiKey)
                        AIProvider.OPENAI -> fetchOpenAIModels(
                            transport, Request.Builder()
                                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("models").build())
                                .header("Authorization", "Bearer $apiKey")
                                .header("Accept", "application/json")
                                .get().build()
                        ).map { it.id() }
                    }
                    if (models.any(String::isBlank)) throw AgentInvalidResponseException()
                    ModelListResponse(ai.provider, models.distinct(), ai.selectedModel)
                } finally {
                    transport.close()
                    client.dispatcher.executorService.shutdown()
                }
            }
        } ?: throw ModelDiscoveryTimeoutException()
    }
}
