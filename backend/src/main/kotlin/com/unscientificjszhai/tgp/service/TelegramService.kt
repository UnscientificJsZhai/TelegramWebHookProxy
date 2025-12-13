package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Proxy

class TelegramService(
    settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var appSettings: AppSettings = settingsRepository.settingsFlow.value
    private var client: HttpClient = createClient()

    init {
        settingsRepository.settingsFlow.onEach { newSettings ->
            val needRecreate = appSettings.proxy != newSettings.proxy
            appSettings = newSettings
            if (needRecreate) {
                client.close()
                client = createClient()
            }
        }.launchIn(scope)
    }

    private fun createClient(): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                if (appSettings.proxy.enabled) {
                    val proxyHost = appSettings.proxy.host
                    val proxyPort = appSettings.proxy.port
                    val proxyType = when (appSettings.proxy.type) {
                        ProxyType.HTTP -> Proxy.Type.HTTP
                        ProxyType.SOCKS -> Proxy.Type.SOCKS
                    }
                    proxy = Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort))
                }
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    }

    suspend fun sendMessage(chatId: String, text: String): HttpResponse {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/sendMessage"

        return client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(SendMessageRequest(chatId, text))
        }
    }

    suspend fun refreshChats(): List<ChatInfo> {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/getUpdates"

        val response: GetUpdatesResponse = client.get(url).body()
        
        if (!response.ok) {
            throw IllegalStateException("Failed to get updates from Telegram")
        }

        val chats = updatesRepository.chatsFlow.value.associateBy { it.id }.toMutableMap()

        response.result.forEach { update ->
            val chat = update.message?.chat 
                ?: update.channel_post?.chat 
                ?: update.my_chat_member?.chat
            
            if (chat != null) {
                val title = chat.title 
                    ?: chat.username 
                    ?: "${chat.first_name ?: ""} ${chat.last_name ?: ""}".trim()
                
                chats[chat.id.toString()] = ChatInfo(
                    id = chat.id.toString(),
                    title = title,
                    type = chat.type
                )
            }
        }
        
        val chatList = chats.values.toList()
        updatesRepository.saveChats(chatList)
        return chatList
    }
    
    fun getSavedChats(): List<ChatInfo> {
        return updatesRepository.chatsFlow.value
    }

    fun deleteChat(chatId: String) {
        updatesRepository.deleteChat(chatId)
    }
}

@Serializable
data class ChatInfo(
    val id: String,
    val title: String,
    val type: String
)

@Serializable
private data class SendMessageRequest(
    val chat_id: String,
    val text: String
)

@Serializable
private data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<Update>
)

@Serializable
private data class Update(
    val update_id: Long,
    val message: Message? = null,
    val channel_post: Message? = null,
    val my_chat_member: ChatMemberUpdated? = null
)

@Serializable
private data class ChatMemberUpdated(
    val chat: Chat
)

@Serializable
private data class Message(
    val chat: Chat
)

@Serializable
private data class Chat(
    val id: Long,
    val type: String,
    val title: String? = null,
    val username: String? = null,
    val first_name: String? = null,
    val last_name: String? = null
)
