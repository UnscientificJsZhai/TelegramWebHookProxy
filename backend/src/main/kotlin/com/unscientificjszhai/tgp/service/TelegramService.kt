package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.plus
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramService @Inject constructor(
    parentScope: CoroutineScope,
    settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
) {
    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private var appSettings: AppSettings = settingsRepository.settingsFlow.value
    private var client: HttpClient = createClient()

    init {
        settingsRepository.settingsFlow
            .onEach { newSettings ->
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
            install(HttpTimeout) {
                requestTimeoutMillis = 40000
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 40000
            }
            engine {
                appSettings.proxy?.let { proxySettings ->
                    val proxyHost = proxySettings.host
                    val proxyPort = proxySettings.port
                    val proxyType =
                        when (proxySettings.type) {
                            ProxyType.HTTP -> Proxy.Type.HTTP
                            ProxyType.SOCKS -> Proxy.Type.SOCKS
                        }
                    proxy = Proxy(proxyType, InetSocketAddress(proxyHost, proxyPort))
                }
                config {
                    appSettings.proxy?.let { proxySettings ->
                        if (
                            proxySettings.type == ProxyType.HTTP &&
                            proxySettings.username?.isNotBlank() ?: false &&
                            proxySettings.password?.isNotBlank() ?: false
                        ) {
                            val credentials = Credentials.basic(proxySettings.username, proxySettings.password)
                            proxyAuthenticator { _, response ->
                                if (response.request.header("Proxy-Authorization") == null) {
                                    return@proxyAuthenticator response
                                        .request
                                        .newBuilder()
                                        .header("Proxy-Authorization", credentials)
                                        .build()
                                } else {
                                    return@proxyAuthenticator null
                                }
                            }
                        }
                    }
                }
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                    },
                )
            }
        }
    }

    suspend fun sendMessage(
        chatId: String,
        text: String,
        replyParameters: ReplyParameters? = null,
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

    suspend fun sendChatAction(
        chatId: String,
        action: String,
    ): HttpResponse {
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

    suspend fun getUpdates(
        offset: Long? = null,
        timeout: Int? = null,
    ): GetUpdatesResponse {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/getUpdates"

        return client
            .get(url) {
                offset?.let { parameter("offset", it) }
                timeout?.let { parameter("timeout", it) }
            }.body()
    }

    /**
     * 获取文件元数据。
     *
     * @param fileId 文件的唯一标识符。
     * @return 文件的元数据。
     */
    suspend fun getFile(fileId: String): FileResponse {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/bot$token/getFile"

        return client
            .get(url) {
                parameter("file_id", fileId)
            }.body()
    }

    /**
     * 下载文件字节流。
     *
     * @param filePath 文件的路径。
     * @return 文件的字节数组。
     */
    suspend fun downloadFile(filePath: String): ByteArray {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/file/bot$token/$filePath"

        return client.get(url).readRawBytes()
    }

    fun getSavedChats(): List<ChatInfo> = updatesRepository.chatsFlow.value

    fun deleteChat(chatId: String) {
        updatesRepository.deleteChat(chatId)
    }
}
