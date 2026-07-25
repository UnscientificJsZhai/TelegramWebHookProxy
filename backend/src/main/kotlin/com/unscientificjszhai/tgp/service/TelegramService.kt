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
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * 封装 Telegram Bot API 的 HTTP 调用，并根据当前代理设置维护客户端。
 *
 * 设置中的代理发生变化时会关闭旧客户端并创建新客户端；所有请求都读取当前机器人令牌，令牌为空时
 * 请求会失败。
 *
 * @constructor 创建 Telegram API 服务并订阅设置变更。
 * @param parentScope 持有设置订阅协程的父作用域；取消该作用域会停止订阅。
 * @param settingsRepository 提供机器人令牌和代理设置的仓储。
 * @param updatesRepository 提供本地已保存聊天信息的仓储。
 */
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

    /**
     * 向指定聊天发送文本消息。
     *
     * 使用当前设置中的机器人令牌发起 `sendMessage` 请求。
     *
     * @param chatId 目标聊天标识，不能为空。
     * @param text 要发送的文本，不能为空。
     * @param replyParameters 可选的回复参数；为 `null` 时发送独立消息。
     * @return Telegram API 的原始 HTTP 响应。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
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

    /**
     * 向指定聊天发送临时聊天动作。
     *
     * 使用当前设置中的机器人令牌发起 `sendChatAction` 请求。
     *
     * @param chatId 目标聊天标识，不能为空。
     * @param action Telegram 支持的聊天动作名称，不能为空，例如 `typing`。
     * @return Telegram API 的原始 HTTP 响应。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
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

    /**
     * 拉取机器人更新。
     *
     * 使用当前设置中的机器人令牌发起 `getUpdates` 请求。
     *
     * @param offset 可选的起始更新标识；为 `null` 时不传递该请求参数。
     * @param timeout 可选的长轮询等待秒数；为 `null` 时不传递该请求参数。
     * @return Telegram 返回的更新结果；`result` 可能为空列表。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
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
     * 获取 Telegram 文件元数据。
     *
     * 使用当前设置中的机器人令牌发起 `getFile` 请求。
     *
     * @param fileId Telegram 分配的文件唯一标识，不能为空。
     * @return Telegram 返回的文件元数据；响应中的文件路径可能为 `null`。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
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
     * 下载 Telegram 文件的完整字节内容。
     *
     * 使用当前设置中的机器人令牌发起文件下载请求。
     *
     * @param filePath Telegram 返回的相对文件路径，不能为空。
     * @return 下载得到的字节数组；空文件返回空数组。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
    suspend fun downloadFile(filePath: String): ByteArray {
        val token = appSettings.telegramToken
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
        val url = "https://api.telegram.org/file/bot$token/$filePath"

        return client.get(url).readRawBytes()
    }

    /**
     * 更新机器人的可用指令列表。
     *
     * AI 启用时设置模型和会话相关指令；未启用时删除全部机器人指令。
     *
     * @param token 用于本次请求的 Telegram 机器人令牌，不能为空。
     * @param enableAI `true` 时设置 AI 指令，`false` 时删除现有指令。
     * @return Telegram API 的原始 HTTP 响应。
     * @throws IllegalStateException [token] 为空时抛出。
     */
    suspend fun updateBotCommands(
        token: String,
        enableAI: Boolean,
    ): HttpResponse {
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }

        return if (enableAI) {
            val url = "https://api.telegram.org/bot$token/setMyCommands"
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(
                    SetMyCommandsRequest(
                        commands =
                            listOf(
                                BotCommand("model", "切换 Gemini 模型"),
                                BotCommand("reset", "重置对话上下文"),
                                BotCommand("keep", "延长上下文自动清理时间"),
                            ),
                    ),
                )
            }
        } else {
            val url = "https://api.telegram.org/bot$token/deleteMyCommands"
            client.post(url)
        }
    }

    /**
     * 获取当前已保存聊天的快照。
     *
     * @return 聊天信息列表；没有已保存聊天时为空列表。
     */
    fun getSavedChats(): List<ChatInfo> = updatesRepository.chatsFlow.value

    /**
     * 删除本地保存的指定聊天。
     *
     * @param chatId 要删除的聊天标识，不能为空；不存在时不会删除其他聊天。
     */
    fun deleteChat(chatId: String) {
        updatesRepository.deleteChat(chatId)
    }
}
