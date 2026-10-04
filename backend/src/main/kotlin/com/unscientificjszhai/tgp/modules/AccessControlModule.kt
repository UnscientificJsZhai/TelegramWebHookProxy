package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import com.unscientificjszhai.tgp.models.AccessControlSettings
import com.unscientificjszhai.tgp.models.allows
import com.unscientificjszhai.tgp.service.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

internal val accessControlServiceKey = AttributeKey<AccessControlService>("access-control-service")
internal const val ACCESS_LOSS_HEADER = "X-Confirm-Access-Loss"

/** request.local 永远是实际连接，不受 Forwarded/X-Forwarded-For 影响。 */
internal fun ApplicationCall.peerIp(): String = request.local.remoteAddress.substringBefore('%')
internal fun ApplicationCall.accessLossConfirmed(): Boolean =
    request.headers.getAll(ACCESS_LOSS_HEADER) == listOf("true")

internal fun Application.installAccessControl(coordinator: SettingsChangeCoordinator) {
    val service = AccessControlService(coordinator)
    attributes.put(accessControlServiceKey, service)
    intercept(ApplicationCallPipeline.Plugins) {
        val sendsMessage = call.request.httpMethod == HttpMethod.Post && call.request.path() == "/api/send-message"
        if (!sendsMessage) {
            val allowed = try {
                service.override.isPresent() || coordinator.currentSettingsSnapshot().settings.accessControl.allows(call.peerIp())
            } catch (_: Exception) {
                LoggerFactory.getLogger("AccessControl").error("Access override check failed; category=file-operation")
                call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "无法检查访问限制覆盖文件。"))
                finish()
                return@intercept
            }
            if (!allowed) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "当前连接来源无管理访问权限。"))
                finish()
            }
        }
    }
    routing {
        route("/api/access-control") {
            install(RequestBodyLimit) { bodyLimit { 16 * 1024L } }
            get {
                call.response.headers.append(HttpHeaders.CacheControl, "no-store")
                call.respond(
                    service.info(
                        call.peerIp(),
                        engine.resolvedConnectors().map { connector -> "${connector.host}:${connector.port}" })
                )
            }
            post("/check") {
                try {
                    val proposed = Json.decodeFromJsonElement<AccessControlSettings>(
                        JsonStructureLimits.parseToJsonElement(
                            Json,
                            call.receiveText()
                        )
                    )
                    call.respond(service.check(proposed, call.peerIp()))
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "访问限制设置不合法。"))
                }
            }
            delete("/override") {
                val revision = call.requiredSettingsRevision() ?: return@delete
                try {
                    synchronized(coordinator) {
                        val snapshot = coordinator.currentSettingsSnapshot()
                        if (snapshot.revision != revision) throw SettingsRevisionMismatchException()
                        if (service.override.isPresent() && !snapshot.settings.accessControl.allows(call.peerIp()) && !call.accessLossConfirmed()) {
                            throw AccessLossConfirmationRequired()
                        }
                        service.override.delete()
                    }
                    call.respond(mapOf("overridePresent" to false, "settingsApplied" to true))
                } catch (_: SettingsRevisionMismatchException) {
                    call.respond(HttpStatusCode.PreconditionFailed, mapOf("error" to "设置已变更，请重新读取。"))
                } catch (_: AccessLossConfirmationRequired) {
                    call.respondAccessLossConfirmation()
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "无法删除访问限制覆盖文件。"))
                }
            }
        }
    }
}

internal suspend fun ApplicationCall.respondAccessLossConfirmation() {
    respond(
        HttpStatusCode.Conflict,
        mapOf("error" to "操作会使当前来源失去管理访问权限。", "code" to "ACCESS_LOSS_CONFIRMATION_REQUIRED")
    )
}
