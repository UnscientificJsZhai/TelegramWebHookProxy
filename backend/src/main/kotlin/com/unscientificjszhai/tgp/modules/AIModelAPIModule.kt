package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.ai.agent.AgentConfigurationNotReadyException
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelDiscoveryConfigurationException
import com.unscientificjszhai.tgp.service.ai.agent.ModelDiscoveryService
import com.unscientificjszhai.tgp.service.ai.agent.ModelDiscoveryTimeoutException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import java.net.SocketTimeoutException

/** 只读模型目录；每次请求使用同一份已保存设置快照，不依赖 Agent 启用状态。 */
internal fun Application.aiModelAPIModule(
    settingsChangeCoordinator: SettingsChangeCoordinator,
    discovery: ModelDiscoveryService = ModelDiscoveryService(),
    agentService: AgentService? = null,
) {
    routing {
        get("/api/ai/models") {
            call.response.headers.append(HttpHeaders.CacheControl, "no-store")
            try {
                val snapshot = settingsChangeCoordinator.currentSettingsSnapshot()
                val models = discovery.listModels(snapshot.settings)
                // 保存值为空时，回显已就绪 Agent 的实际模型；读取不会启动 Agent 或刷新会话。
                val activeModel = if (models.currentModel.isBlank() && snapshot.settings.ai?.agentEnabled == true) {
                    try {
                        agentService?.currentModel
                    } catch (_: AgentConfigurationNotReadyException) {
                        null
                    }
                } else null
                val currentModel =
                    if (settingsChangeCoordinator.currentSettingsSnapshot().generation == snapshot.generation) {
                        models.currentModel.ifBlank { activeModel.orEmpty() }
                    } else models.currentModel
                call.respond(models.copy(currentModel = currentModel))
            } catch (_: ModelDiscoveryConfigurationException) {
                call.respond(HttpStatusCode.BadRequest, ModelListError("请先保存有效的 AI 服务凭据。"))
            } catch (_: ModelDiscoveryTimeoutException) {
                call.respond(HttpStatusCode.GatewayTimeout, ModelListError("获取模型列表超时，请重试。"))
            } catch (_: SocketTimeoutException) {
                call.respond(HttpStatusCode.GatewayTimeout, ModelListError("获取模型列表超时，请重试。"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                call.respond(HttpStatusCode.BadGateway, ModelListError("获取模型列表失败，请检查服务凭据与网络连接。"))
            }
        }
    }
}

@Serializable
private data class ModelListError(val error: String)
