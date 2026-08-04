package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.repository.botIdFromTelegramToken
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
import org.slf4j.LoggerFactory
import java.net.InetSocketAddress
import java.net.Proxy
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 已在 Telegram HTTP 客户端租约内完整读取的响应快照。
 *
 * @property status Telegram HTTP 响应状态。
 * @property body 已读取的响应正文；空响应返回空字符串。
 */
data class TelegramApiResponse(
    val status: HttpStatusCode,
    val body: String,
)

@Singleton
/**
 * 封装 Telegram Bot API 的 HTTP 调用，并根据当前代理设置维护客户端。
 *
 * 设置中的代理发生变化时，会先创建候选客户端，再在短同步临界区内替换当前客户端。正在使用已退休
 * 客户端的请求可以完成，最后一个请求结束后客户端只会关闭一次。历史配置中的非法代理只会为
 * Telegram 禁用代理，不会影响持久化设置或其他服务。
 */
class TelegramService private constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
    private val clientFactory: (ProxySettings?) -> HttpClient,
    @Suppress("UNUSED_PARAMETER") testConstructorMarker: Unit,
) : AutoCloseable {
    /**
     * 创建 Telegram API 服务并订阅设置变更。
     *
     * @constructor 创建使用默认 Ktor HTTP 客户端的服务。
     * @param parentScope 持有设置订阅协程的父作用域；取消该作用域会停止订阅。
     * @param settingsRepository 提供机器人令牌和代理设置的仓储。
     * @param updatesRepository 提供本地已保存聊天信息的仓储。
     */
    @Inject
    constructor(
        parentScope: CoroutineScope,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
    ) : this(parentScope, settingsRepository, updatesRepository, ::createDefaultClient, Unit)

    internal constructor(
        parentScope: CoroutineScope,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        clientFactory: (ProxySettings?) -> HttpClient,
    ) : this(parentScope, settingsRepository, updatesRepository, clientFactory, Unit)

    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private val logger = LoggerFactory.getLogger(TelegramService::class.java)
    private val clientLock = Any()

    @Volatile
    private var requestSettings: AppSettings = settingsRepository.settingsFlow.value
    private var activeClient: ClientLease? = null
    private var installedProxy: ProxySettings? = null
    private var invalidProxyWarningIssued = false
    private var closed = false
    private val settingsSubscription: Job

    init {
        val initialProxy = telegramProxyOrNull(
            proxy = requestSettings.proxy,
            hasHistoricalInvalidProxy = settingsRepository.hasHistoricalInvalidProxy,
        )
        activeClient = ClientLease(createClient(initialProxy))
        installedProxy = initialProxy
        settingsSubscription = settingsRepository.settingsFlow
            .onEach { newSettings ->
                try {
                    updateSettings(newSettings)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    logger.error("无法更新 Telegram HTTP 客户端；将继续监听后续设置变更。")
                }
            }.launchIn(scope)
    }

    private fun updateSettings(newSettings: AppSettings) {
        requestSettings = newSettings
        val desiredProxy = telegramProxyOrNull(
            proxy = newSettings.proxy,
            hasHistoricalInvalidProxy = settingsRepository.hasHistoricalInvalidProxy,
        )
        val needsRecreate = synchronized(clientLock) {
            desiredProxy != installedProxy
        }
        if (!needsRecreate) {
            return
        }

        val candidate = createClient(desiredProxy)
        installCandidate(candidate, desiredProxy)
    }

    private fun telegramProxyOrNull(
        proxy: ProxySettings?,
        hasHistoricalInvalidProxy: Boolean,
    ): ProxySettings? {
        if (hasHistoricalInvalidProxy) {
            warnInvalidTelegramProxyOnce()
            return null
        }
        return try {
            validateProxySettings(proxy)
            proxy
        } catch (_: IllegalArgumentException) {
            warnInvalidTelegramProxyOnce()
            null
        }
    }

    private fun warnInvalidTelegramProxyOnce() {
        if (!invalidProxyWarningIssued) {
            invalidProxyWarningIssued = true
            logger.warn("Telegram 检测到历史代理设置不合法，已仅为 Telegram 禁用代理。")
        }
    }

    private fun createClient(proxy: ProxySettings?): HttpClient {
        validateProxySettings(proxy)
        return clientFactory(proxy)
    }

    private fun installCandidate(candidate: HttpClient, proxy: ProxySettings?) {
        var retiredClient: HttpClient? = null
        var closeCandidate = false
        synchronized(clientLock) {
            if (closed) {
                closeCandidate = true
            } else {
                val previous = checkNotNull(activeClient)
                activeClient = ClientLease(candidate)
                installedProxy = proxy
                previous.retired = true
                retiredClient = closeIfRetiredAndUnusedLocked(previous)
            }
        }
        if (closeCandidate) {
            candidate.close()
        } else {
            retiredClient?.close()
        }
    }

    private suspend fun <T> withClientLease(action: suspend (HttpClient) -> T): T {
        val lease = synchronized(clientLock) {
            check(!closed) { "Telegram service is closed." }
            checkNotNull(activeClient).also { it.activeRequests++ }
        }

        try {
            return action(lease.client)
        } finally {
            val retiredClient = synchronized(clientLock) {
                lease.activeRequests--
                closeIfRetiredAndUnusedLocked(lease)
            }
            retiredClient?.close()
        }
    }

    private fun closeIfRetiredAndUnusedLocked(lease: ClientLease): HttpClient? {
        if (!lease.retired || lease.activeRequests != 0 || lease.closed) {
            return null
        }
        lease.closed = true
        return lease.client
    }

    /**
     * 停止设置订阅并关闭所有已退休且没有在途请求的 Telegram 客户端。
     *
     * 此方法可重复调用。首次调用后不再接受新的 HTTP 请求租约；已取得租约的请求可以完成，
     * 其客户端会在最后一个租约释放时关闭。
     */
    override fun close() {
        val retiredClient = synchronized(clientLock) {
            if (closed) {
                return
            }
            closed = true
            activeClient?.let { client ->
                client.retired = true
                activeClient = null
                closeIfRetiredAndUnusedLocked(client)
            }
        }
        settingsSubscription.cancel()
        scope.cancel()
        retiredClient?.close()
    }

    /**
     * 向指定聊天发送文本消息。
     *
     * 使用当前设置中的机器人令牌发起 `sendMessage` 请求。
     *
     * @param chatId 目标聊天标识，不能为空。
     * @param text 要发送的文本，不能为空。
     * @param replyParameters 可选的回复参数；为 `null` 时发送独立消息。
     * @return 已完整读取、不再依赖 HTTP 客户端的 Telegram 响应快照。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
    suspend fun sendMessage(
        chatId: String,
        text: String,
        replyParameters: ReplyParameters? = null,
    ): TelegramApiResponse = sendMessageForToken(requestSettings.telegramToken, chatId, text, replyParameters)

    /** 供轮询会话使用，以会话捕获的 token 发送文本消息。 */
    internal suspend fun sendMessageForToken(
        token: String,
        chatId: String,
        text: String,
        replyParameters: ReplyParameters? = null,
    ): TelegramApiResponse {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/bot$token/sendMessage"

        return withClientLease { client ->
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(SendTelegramMessageRequest(chatId, text, replyParameters))
            }.toTelegramApiResponse()
        }
    }

    /** 供轮询会话使用，以会话捕获的 token 发送聊天动作。 */
    internal suspend fun sendChatActionForToken(
        token: String,
        chatId: String,
        action: String,
    ): TelegramApiResponse {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/bot$token/sendChatAction"

        return withClientLease { client ->
            client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(ChatActionRequest(chatId, action))
            }.toTelegramApiResponse()
        }
    }

    /** 供轮询会话使用，以会话捕获的 token 拉取更新。 */
    internal suspend fun getUpdatesForToken(
        token: String,
        offset: Long? = null,
        timeout: Int? = null,
    ): GetUpdatesResponse {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/bot$token/getUpdates"

        return withClientLease { client ->
            client.get(url) {
                offset?.let { parameter("offset", it) }
                timeout?.let { parameter("timeout", it) }
            }.body()
        }
    }

    /** 供轮询会话使用，以会话捕获的 token 获取文件元数据。 */
    internal suspend fun getFileForToken(token: String, fileId: String): FileResponse {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/bot$token/getFile"

        return withClientLease { client ->
            client.get(url) {
                parameter("file_id", fileId)
            }.body()
        }
    }

    /** 供轮询会话使用，以会话捕获的 token 下载文件。 */
    internal suspend fun downloadFileForToken(token: String, filePath: String): ByteArray {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/file/bot$token/$filePath"

        return withClientLease { client -> client.get(url).readRawBytes() }
    }

    /**
     * 更新机器人的可用指令列表。
     *
     * AI 启用时设置模型和会话相关指令；未启用时删除全部机器人指令。
     *
     * @param token 用于本次请求的 Telegram 机器人令牌，不能为空。
     * @param enableAI `true` 时设置 AI 指令，`false` 时删除现有指令。
     * @return 已完整读取、不再依赖 HTTP 客户端的 Telegram 响应快照。
     * @throws IllegalStateException [token] 为空时抛出。
     */
    suspend fun updateBotCommands(
        token: String,
        enableAI: Boolean,
    ): TelegramApiResponse {
        requireTelegramToken(token)

        return withClientLease { client ->
            if (enableAI) {
                val url = "https://api.telegram.org/bot$token/setMyCommands"
                client.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(
                        SetMyCommandsRequest(
                            commands = listOf(
                                BotCommand("model", "切换 Gemini 模型"),
                                BotCommand("reset", "重置对话上下文"),
                                BotCommand("keep", "延长上下文自动清理时间"),
                            ),
                        ),
                    )
                }.toTelegramApiResponse()
            } else {
                client.post("https://api.telegram.org/bot$token/deleteMyCommands").toTelegramApiResponse()
            }
        }
    }

    /**
     * 获取当前已保存聊天的快照。
     *
     * 请求开始时会捕获当前 token 的 bot 标识；令牌为空或无效时不会访问任何共享状态。
     *
     * @return 当前机器人已保存的聊天信息列表；没有有效令牌或没有保存聊天时为空列表。
     */
    fun getSavedChats(): List<ChatInfo> {
        val botId = settingsRepository.settingsFlow.value.telegramToken.botIdFromTelegramToken()
            ?: return emptyList()
        return updatesRepository.getChats(botId)
    }

    /**
     * 删除本地保存的指定聊天。
     *
     * 请求开始时会捕获当前 token 的 bot 标识；令牌为空或无效时不会删除任何状态。
     *
     * @param chatId 要删除的聊天标识，不能为空；不存在时不会删除其他聊天。
     */
    fun deleteChat(chatId: String) {
        updatesRepository.deleteChat(
            botId = settingsRepository.settingsFlow.value.telegramToken.botIdFromTelegramToken() ?: return,
            chatId = chatId,
        )
    }

    private fun requireTelegramToken(token: String) {
        if (token.isBlank()) {
            throw IllegalStateException("Telegram token is not set.")
        }
    }

    private suspend fun HttpResponse.toTelegramApiResponse(): TelegramApiResponse =
        TelegramApiResponse(status = status, body = bodyAsText())

    private class ClientLease(
        val client: HttpClient,
        var activeRequests: Int = 0,
        var retired: Boolean = false,
        var closed: Boolean = false,
    )
}

private fun createDefaultClient(proxySettingsToUse: ProxySettings?): HttpClient = HttpClient(OkHttp) {
    install(HttpTimeout) {
        requestTimeoutMillis = 40000
        connectTimeoutMillis = 10000
        socketTimeoutMillis = 40000
    }
    engine {
        proxySettingsToUse?.let { proxySettings ->
            val proxyType = when (proxySettings.type) {
                ProxyType.HTTP -> Proxy.Type.HTTP
                ProxyType.SOCKS -> Proxy.Type.SOCKS
            }
            proxy = Proxy(proxyType, InetSocketAddress(proxySettings.host, proxySettings.port))
        }
        config {
            proxySettingsToUse?.let { proxySettings ->
                val username = proxySettings.username
                val password = proxySettings.password
                if (
                    proxySettings.type == ProxyType.HTTP &&
                    !username.isNullOrBlank() &&
                    !password.isNullOrBlank()
                ) {
                    val credentials = Credentials.basic(username, password)
                    proxyAuthenticator { _, response ->
                        if (response.request.header("Proxy-Authorization") == null) {
                            response.request.newBuilder().header("Proxy-Authorization", credentials).build()
                        } else {
                            null
                        }
                    }
                }
            }
        }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
