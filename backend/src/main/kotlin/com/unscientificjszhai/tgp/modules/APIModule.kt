package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.SetChatIdRequest
import com.unscientificjszhai.tgp.repository.SettingsRevisionMismatchException
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 注册应用设置、聊天记录和消息发送的 HTTP API 路由。
 *
 * 该方法会向接收者追加路由，并在处理请求时读写设置、聊天记录和 Telegram 服务。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param appComponent 提供路由所需应用级依赖的组件。
 */
fun Application.apiModule(appComponent: AppComponent) {
    val settingsRepository = appComponent.settingsRepository
    val telegramService = appComponent.telegramService

    routing {
        route("/api") {
            get("/settings") {
                val snapshot = settingsRepository.currentSettingsSnapshot()
                call.response.headers.append(HttpHeaders.ETag, snapshot.revision.toStrongETag())
                call.respond(snapshot.settings)
            }
            post("/settings") {
                val expectedRevision = when (val parsed = call.request.headers.parseSingleStrongETag()) {
                    IfMatchResult.Missing -> {
                        call.respond(PRECONDITION_REQUIRED, "必须提供 If-Match 设置修订值。")
                        return@post
                    }

                    IfMatchResult.Invalid -> {
                        call.respond(HttpStatusCode.BadRequest, "If-Match 必须是单个合法强 ETag。")
                        return@post
                    }

                    is IfMatchResult.Valid -> parsed.revision
                }
                val newSettings = try {
                    call.receive<AppSettings>()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "设置请求格式不合法。")
                    return@post
                }
                val update = try {
                    settingsRepository.updateSettings(expectedRevision) { currentSettings ->
                        newSettings.clearSelectedModelWhenProviderOrApiKeyChanges(currentSettings)
                    }
                } catch (_: SettingsRevisionMismatchException) {
                    call.respond(HttpStatusCode.PreconditionFailed, "设置已被其他操作修改。")
                    return@post
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "代理设置不合法。")
                    return@post
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, e.message ?: "设置存储需要恢复后重试。")
                    return@post
                }

                val oldSettings = update.previous.settings
                val savedSettings = update.current.settings
                val oldEffectiveProvider = oldSettings.ai?.takeIf { it.agentEnabled }?.provider
                val newEffectiveProvider = savedSettings.ai?.takeIf { it.agentEnabled }?.provider
                if (savedSettings.telegramToken != oldSettings.telegramToken ||
                    newEffectiveProvider != oldEffectiveProvider
                ) {
                    if (savedSettings.telegramToken.isNotBlank()) {
                        try {
                            telegramService.updateBotCommands(
                                savedSettings.telegramToken,
                                newEffectiveProvider,
                            )
                        } catch (e: Exception) {
                            application.log.error("Failed to update bot commands: ${e.message}")
                        }
                    }
                }
                call.response.headers.append(HttpHeaders.ETag, update.current.revision.toStrongETag())
                call.respond(HttpStatusCode.OK, savedSettings)
            }
            post("/settings/chat") {
                val request = try {
                    call.receive<SetChatIdRequest>()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, "聊天设置请求格式不合法。")
                    return@post
                }
                try {
                    settingsRepository.updateSettings { currentSettings ->
                        currentSettings.copy(chatId = request.chatId)
                    }
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, e.message ?: "代理设置不合法。")
                    return@post
                } catch (e: IllegalStateException) {
                    call.respond(HttpStatusCode.Conflict, e.message ?: "设置存储需要恢复后重试。")
                    return@post
                }
                call.respond(HttpStatusCode.OK)
            }
            post("/send-message") {
                val messageField = call.request.queryParameters["messagefield"] ?: "text"
                val chatIdField = call.request.queryParameters["chatidfield"] ?: "chatId"

                val contentType = call.request.contentType()
                val (requestChatId, requestText) = when {
                    contentType.match(ContentType.Application.Json) -> {
                        val json = call.receive<JsonObject>()
                        val chatId = json[chatIdField]?.jsonPrimitive?.content
                        val text = json[messageField]?.jsonPrimitive?.content ?: ""
                        chatId to text
                    }

                    contentType.match(ContentType.Application.FormUrlEncoded) -> {
                        val parameters = call.receiveParameters()
                        val chatId = parameters[chatIdField]
                        val text = parameters[messageField] ?: ""
                        chatId to text
                    }

                    else -> {
                        call.respond(HttpStatusCode.UnsupportedMediaType)
                        return@post
                    }
                }

                if (requestText.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, "Message text is required")
                    return@post
                }

                try {
                    val chatId = (requestChatId ?: "").ifBlank { settingsRepository.settingsFlow.value.chatId }
                    if (chatId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Chat ID is required")
                        return@post
                    }
                    val response = telegramService.sendMessage(chatId, requestText)
                    call.respond(response.status, response.body)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred")
                }
            }
            get("/chats") {
                try {
                    val chats = telegramService.getSavedChats()
                    call.respond(chats)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred fetching chats")
                }
            }
            delete("/chats/{id}") {
                val id = call.parameters["id"]
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing chat ID")
                    return@delete
                }
                try {
                    telegramService.deleteChat(id)
                    call.respond(HttpStatusCode.OK)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred deleting chat")
                }
            }
        }
    }
}

private val PRECONDITION_REQUIRED = HttpStatusCode(428, "Precondition Required")
private val STRONG_ETAG = Regex("\"([0-9a-f]{64})\"")

private sealed interface IfMatchResult {
    data object Missing : IfMatchResult
    data object Invalid : IfMatchResult
    data class Valid(val revision: String) : IfMatchResult
}

private fun Headers.parseSingleStrongETag(): IfMatchResult {
    val values = getAll(HttpHeaders.IfMatch) ?: return IfMatchResult.Missing
    if (values.size != 1) {
        return IfMatchResult.Invalid
    }
    val match = STRONG_ETAG.matchEntire(values.single().trim()) ?: return IfMatchResult.Invalid
    return IfMatchResult.Valid(match.groupValues[1])
}

private fun String.toStrongETag(): String = "\"$this\""

private fun AppSettings.clearSelectedModelWhenProviderOrApiKeyChanges(oldSettings: AppSettings): AppSettings {
    val newAiSettings = ai ?: return this
    val oldAiSettings = oldSettings.ai
    val providerChanged = oldAiSettings?.provider != newAiSettings.provider
    val apiKeyChanged = when (newAiSettings.provider) {
        AIProvider.GEMINI -> oldAiSettings?.geminiApiKey != newAiSettings.geminiApiKey
        AIProvider.OPENAI -> oldAiSettings?.openAiApiKey != newAiSettings.openAiApiKey
    }

    return if (providerChanged || apiKeyChanged) {
        copy(ai = newAiSettings.copy(selectedModel = ""))
    } else {
        this
    }
}
