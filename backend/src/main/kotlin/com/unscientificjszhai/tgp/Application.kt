package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.TelegramService
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File

fun main() {
    embeddedServer(Netty, port = 10178, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

@Serializable
data class SendMessageRequest(val chatId: String, val text: String)

@Serializable
data class UpdateChatIdRequest(val chatId: String)

fun Application.module() {
    val settingsRepository = SettingsRepository()
    val updatesRepository = UpdatesRepository()
    val telegramService = TelegramService(settingsRepository, updatesRepository)

    install(ContentNegotiation) {
        json()
    }

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
                val request = call.receive<UpdateChatIdRequest>()
                val currentSettings = settingsRepository.settingsFlow.value
                val newSettings = currentSettings.copy(chatId = request.chatId)
                settingsRepository.saveSettings(newSettings)
                call.respond(HttpStatusCode.OK)
            }
            post("/send-message") {
                val request = call.receive<SendMessageRequest>()
                try {
                    val response = telegramService.sendMessage(request.chatId, request.text)
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

        singlePageApplication {
            staticResources("/", "static") {
                default("index.html")
            }
        }
    }
}
