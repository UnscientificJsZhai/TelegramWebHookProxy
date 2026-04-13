package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
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
                appSettings.proxy?.let { proxySettings ->
                    val proxyHost = proxySettings.host
                    val proxyPort = proxySettings.port
                    val proxyType = when (proxySettings.type) {
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

    suspend fun sendMessage(
        chatId: String,
        text: String,
        replyParameters: ReplyParameters? = null
    ): HttpResponse {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/sendMessage"

        return client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(SendTelegramMessageRequest(chatId, text, replyParameters))
        }
    }

    suspend fun sendChatAction(chatId: String, action: String): HttpResponse {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/sendChatAction"

        return client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(ChatActionRequest(chatId, action))
        }
    }

    suspend fun getUpdates(offset: Long? = null, timeout: Int? = null): GetUpdatesResponse {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/getUpdates"

        return client.get(url) {
            offset?.let { parameter("offset", it) }
            timeout?.let { parameter("timeout", it) }
        }.body()
    }

    fun getSavedChats(): List<ChatInfo> {
        return updatesRepository.chatsFlow.value
    }

    fun deleteChat(chatId: String) {
        updatesRepository.deleteChat(chatId)
    }
}