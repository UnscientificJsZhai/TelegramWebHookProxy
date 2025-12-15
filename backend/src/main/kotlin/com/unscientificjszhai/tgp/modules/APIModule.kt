package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.SendMessageRequest
import com.unscientificjszhai.tgp.models.SetChatIdRequest
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.TelegramService
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.apiModule() {
    val settingsRepository = SettingsRepository()
    val updatesRepository = UpdatesRepository()
    val telegramService = TelegramService(settingsRepository, updatesRepository)

    routing {
        route("/api") {
            get("/settings") {
                call.respond(settingsRepository.settingsFlow.value)
            }
            post("/settings") {
                val newSettings = call.receive<AppSettings>()
                settingsRepository.saveSettings(newSettings)
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
                val request = call.receive<SendMessageRequest>()
                try {
                    val chatId = (request.chatId ?: "").ifBlank { settingsRepository.settingsFlow.value.chatId }
                    if (chatId.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Chat ID is required")
                        return@post
                    }
                    val response = telegramService.sendMessage(chatId, request.text)
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
            post("/chats/refresh") {
                try {
                    val chats = telegramService.refreshChats()
                    call.respond(chats)
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, e.message ?: "An error occurred refreshing chats")
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