package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.SetChatIdRequest
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

fun Application.apiModule(appComponent: AppComponent) {
    val settingsRepository = appComponent.settingsRepository
    val telegramService = appComponent.telegramService

    routing {
        route("/api") {
            get("/settings") {
                call.respond(settingsRepository.settingsFlow.value)
            }
            post("/settings") {
                val newSettings = call.receive<AppSettings>()
                val oldSettings = settingsRepository.settingsFlow.value
                val settingsToSave = newSettings.clearSelectedModelWhenProviderOrApiKeyChanges(oldSettings)
                settingsRepository.saveSettings(settingsToSave)

                if (settingsToSave.telegramToken != oldSettings.telegramToken ||
                    settingsToSave.ai?.agentEnabled != oldSettings.ai?.agentEnabled
                ) {
                    if (settingsToSave.telegramToken.isNotBlank()) {
                        try {
                            telegramService.updateBotCommands(
                                settingsToSave.telegramToken,
                                settingsToSave.ai?.agentEnabled == true,
                            )
                        } catch (e: Exception) {
                            application.log.error("Failed to update bot commands: ${e.message}")
                        }
                    }
                }
                call.respond(HttpStatusCode.OK)
            }
            post("/settings/chat") {
                val request = call.receive<SetChatIdRequest>()
                val currentSettings = settingsRepository.settingsFlow.value
                val newSettings = currentSettings.copy(chatId = request.chatId)
                settingsRepository.saveSettings(newSettings)
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
                    call.respond(response.status, response.bodyAsText())
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
