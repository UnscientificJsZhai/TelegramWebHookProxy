package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.repository.botIdFromTelegramToken
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.utils.io.readAvailable
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
 * 每个使用当前设置的默认请求都会在开始时与 Telegram token 生命周期串行地捕获 token；捕获后的
 * 在途请求可继续使用该 token 完成，保存操作返回后新开始的默认请求则会使用新 token。设置中的代理
 * 发生变化时，会先创建候选客户端，再在短同步临界区内替换当前客户端。正在使用已退休客户端的请求
 * 可以完成，最后一个请求结束后客户端只会关闭一次。历史配置中的非法代理只会为 Telegram 禁用代理，
 * 不会影响持久化设置或其他服务。
 */
class TelegramService private constructor(
    parentScope: CoroutineScope,
    private val settingsRepository: SettingsRepository,
    private val updatesRepository: UpdatesRepository,
    private val clientFactory: (ProxySettings?) -> HttpClient,
    private val clientInstalledObserver: (ProxySettings?) -> Unit,
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
    ) : this(parentScope, settingsRepository, updatesRepository, ::createDefaultClient, {}, Unit)

    internal constructor(
        parentScope: CoroutineScope,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        clientFactory: (ProxySettings?) -> HttpClient,
    ) : this(parentScope, settingsRepository, updatesRepository, clientFactory, {}, Unit)

    internal constructor(
        parentScope: CoroutineScope,
        settingsRepository: SettingsRepository,
        updatesRepository: UpdatesRepository,
        clientFactory: (ProxySettings?) -> HttpClient,
        clientInstalledObserver: (ProxySettings?) -> Unit,
    ) : this(parentScope, settingsRepository, updatesRepository, clientFactory, clientInstalledObserver, Unit)

    private val scope = parentScope + Dispatchers.IO + SupervisorJob(parentScope.coroutineContext[Job])
    private val logger = LoggerFactory.getLogger(TelegramService::class.java)
    private val clientLock = Any()
    private var activeClient: ClientLease? = null
    private var installedProxy: ProxySettings? = null
    private var invalidProxyWarningIssued = false
    private var closed = false
    private val settingsSubscription: Job

    init {
        val initialSettings = settingsRepository.settingsFlow.value
        val initialProxy = telegramProxyOrNull(
            proxy = initialSettings.proxy,
            hasHistoricalInvalidProxy = settingsRepository.hasHistoricalInvalidProxy,
        )
        activeClient = ClientLease(createClient(initialProxy))
        installedProxy = initialProxy
        settingsSubscription = settingsRepository.settingsFlow
            .onEach { newSettings ->
                try {
                    updateProxyClient(newSettings)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    logger.error("无法更新 Telegram HTTP 客户端；将继续监听后续设置变更。")
                }
            }.launchIn(scope)
    }

    /** 根据新设置协调 Telegram HTTP 客户端所使用的代理。 */
    private fun updateProxyClient(newSettings: AppSettings) {
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
            clientInstalledObserver(proxy)
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
     * 请求开始时在 Telegram token 生命周期锁内捕获当前设置中的机器人令牌，再在锁外发起
     * `sendMessage` 请求；捕获后的在途请求可使用该令牌完成。
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
    ): TelegramApiResponse = sendMessageForToken(currentTelegramToken(), chatId, text, replyParameters)

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

    /**
     * 向指定聊天发送临时聊天动作。
     *
     * 请求开始时在 Telegram token 生命周期锁内捕获当前设置中的机器人令牌，再在锁外发起
     * `sendChatAction` 请求；捕获后的在途请求可使用该令牌完成。
     *
     * @param chatId 目标聊天标识，不能为空。
     * @param action Telegram 支持的聊天动作名称，不能为空，例如 `typing`。
     * @return 已完整读取、不再依赖 HTTP 客户端的 Telegram 响应快照。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
    suspend fun sendChatAction(
        chatId: String,
        action: String,
    ): TelegramApiResponse = sendChatActionForToken(currentTelegramToken(), chatId, action)

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

    /**
     * 拉取机器人更新。
     *
     * 请求开始时在 Telegram token 生命周期锁内捕获当前设置中的机器人令牌，再在锁外发起
     * `getUpdates` 请求；捕获后的在途请求可使用该令牌完成。
     *
     * 每次请求都会固定附带 `limit=10`，避免 Telegram 受 1 MiB 响应上限截断大型积压批次后在同一
     * 偏移量反复失败。
     *
     * @param offset 可选的起始更新标识；为 `null` 时不传递该请求参数。
     * @param timeout 可选的长轮询等待秒数；为 `null` 时不传递该请求参数。
     * @return Telegram 返回的更新结果；`result` 可能为空列表。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
    suspend fun getUpdates(
        offset: Long? = null,
        timeout: Int? = null,
    ): GetUpdatesResponse = getUpdatesForToken(currentTelegramToken(), offset, timeout)

    /**
     * 供轮询会话使用，以会话捕获的 token 拉取最多 10 项更新。
     *
     * @param token 会话开始时捕获的有效 Telegram Bot token。
     * @param offset 可选的起始更新标识；为 `null` 时不传递该请求参数。
     * @param timeout 可选的长轮询等待秒数；为 `null` 时不传递该请求参数。
     * @return Telegram 返回的更新结果；`result` 可能为空列表。
     * @throws IllegalArgumentException 当 [token] 为空或格式无效时抛出。
     */
    internal suspend fun getUpdatesForToken(
        token: String,
        offset: Long? = null,
        timeout: Int? = null,
    ): GetUpdatesResponse {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/bot$token/getUpdates"

        return withClientLease { client ->
            val response = client.get(url) {
                offset?.let { parameter("offset", it) }
                timeout?.let { parameter("timeout", it) }
                parameter("limit", 10)
            }
            decodeTelegramJson(response.readTelegramBytes(MAX_TELEGRAM_API_BYTES))
        }
    }

    /**
     * 获取 Telegram 文件元数据。
     *
     * 请求开始时在 Telegram token 生命周期锁内捕获当前设置中的机器人令牌，再在锁外发起
     * `getFile` 请求；捕获后的在途请求可使用该令牌完成。
     *
     * @param fileId Telegram 分配的文件唯一标识，不能为空。
     * @return Telegram 返回的文件元数据；响应中的文件路径可能为 `null`。
     * @throws IllegalStateException 当前机器人令牌为空时抛出。
     */
    suspend fun getFile(fileId: String): FileResponse = getFileForToken(currentTelegramToken(), fileId)

    /** 供轮询会话使用，以会话捕获的 token 获取文件元数据。 */
    internal suspend fun getFileForToken(token: String, fileId: String): FileResponse {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/bot$token/getFile"

        return withClientLease { client ->
            val response = client.get(url) {
                parameter("file_id", fileId)
            }
            decodeTelegramJson(response.readTelegramBytes(MAX_TELEGRAM_API_BYTES))
        }
    }

    /** 在 Kotlin serialization 递归解码 Telegram DTO 前先限定不可信 JSON 的结构。 */
    private inline fun <reified T> decodeTelegramJson(bytes: ByteArray): T {
        JsonStructureLimits.validateUtf8(bytes)
        return telegramJson.decodeFromString(bytes.decodeToString())
    }

    /**
     * 下载 Telegram 文件的完整字节内容。
     *
     * 请求开始时在 Telegram token 生命周期锁内捕获当前设置中的机器人令牌，再在锁外发起文件下载
     * 请求；捕获后的在途请求可使用该令牌完成。
     *
     * @param filePath Telegram 返回的相对文件路径，不能为空。
     * @return 仅在 Telegram 文件下载响应为 HTTP `2xx` 时返回下载得到的字节数组；空文件返回空数组。
     * @throws IllegalStateException 当前机器人令牌为空，或文件下载响应不是 HTTP `2xx` 时抛出；后者的异常消息仅包含数值 HTTP 状态码。
     * @throws TelegramPayloadTooLargeException HTTP `2xx` 响应解压后的实际字节数超过文件下载上限时抛出。
     */
    suspend fun downloadFile(filePath: String): ByteArray = downloadFileForToken(currentTelegramToken(), filePath)

    /** 供轮询会话使用，以会话捕获的 token 下载文件。 */
    internal suspend fun downloadFileForToken(token: String, filePath: String): ByteArray {
        requireTelegramToken(token)
        val url = "https://api.telegram.org/file/bot$token/$filePath"

        return withClientLease { client ->
            val response = client.get(url)
            if (!response.status.isSuccess()) {
                val exception = IllegalStateException(
                    "Telegram file download failed with HTTP status ${response.status.value}.",
                )
                response.bodyAsChannel().cancel(exception)
                throw exception
            }
            response.readTelegramBytes(MAX_TELEGRAM_DOWNLOAD_BYTES)
        }
    }

    /**
     * 更新机器人的可用指令列表。
     *
     * [aiProvider] 非空时设置与该提供方匹配的模型及会话相关指令；为 `null` 时删除全部机器人指令。
     * 仅当 Telegram 返回 HTTP `2xx` 且顶层 JSON 对象的 `ok` 为 `true` 时本次更新才视为成功；其他
     * 状态、空或畸形正文、`ok:false` 与通信异常都会以不含正文或 token 的异常失败。
     *
     * @param token 用于本次请求的 Telegram 机器人令牌，不能为空。
     * @param aiProvider 当前有效的 AI 服务提供方；`null` 表示未启用 AI，不发布任何指令。
     * @return 已通过 Telegram 成功语义验证、且不再依赖 HTTP 客户端的响应快照。
     * @throws IllegalStateException [token] 为空，或 Telegram 未以 HTTP `2xx` 顶层 `ok:true` 响应确认更新时抛出；
     * 异常消息不包含响应正文或 token。
     */
    suspend fun updateBotCommands(
        token: String,
        aiProvider: AIProvider?,
    ): TelegramApiResponse {
        requireTelegramToken(token)

        return try {
            withClientLease { client ->
                val response = if (aiProvider != null) {
                    val url = "https://api.telegram.org/bot$token/setMyCommands"
                    val modelDescription = when (aiProvider) {
                        AIProvider.GEMINI -> "切换 Gemini 模型"
                        AIProvider.OPENAI -> "切换 OpenAI 模型"
                    }
                    client.post(url) {
                        contentType(ContentType.Application.Json)
                        setBody(
                            SetMyCommandsRequest(
                                commands = listOf(
                                    BotCommand("model", modelDescription),
                                    BotCommand("reset", "重置对话上下文"),
                                    BotCommand("keep", "延长上下文自动清理时间"),
                                ),
                            ),
                        )
                    }
                } else {
                    client.post("https://api.telegram.org/bot$token/deleteMyCommands")
                }
                response.requireSuccessfulBotCommandUpdate()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IllegalStateException("Telegram bot command update failed.")
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

    /**
     * 在 Telegram token 生命周期锁内捕获当前默认请求应使用的 token。
     *
     * 该方法只读取同步状态；调用方必须在锁外发起 HTTP 请求。
     *
     * @return 请求开始时当前设置中的 Telegram token；可能为空。
     */
    private fun currentTelegramToken(): String = settingsRepository.withTelegramTokenLifecycleLock {
        settingsRepository.settingsFlow.value.telegramToken
    }

    private suspend fun HttpResponse.toTelegramApiResponse(): TelegramApiResponse =
        TelegramApiResponse(status = status, body = readTelegramBytes(MAX_TELEGRAM_API_BYTES).decodeToString())

    /** 验证机器人命令更新的成功状态，并返回已完整读取的响应快照。 */
    private suspend fun HttpResponse.requireSuccessfulBotCommandUpdate(): TelegramApiResponse {
        if (!status.isSuccess()) {
            bodyAsChannel().cancel(CancellationException("Telegram bot command update failed."))
            throw IllegalStateException("Telegram bot command update failed.")
        }
        val bytes = readTelegramBytes(MAX_TELEGRAM_API_BYTES)
        JsonStructureLimits.validateUtf8(bytes)
        val payload = JsonStructureLimits.parseToJsonElement(telegramJson, bytes.decodeToString()) as? JsonObject
            ?: throw IllegalStateException("Telegram bot command update failed.")
        if (payload["ok"]?.jsonPrimitive?.booleanOrNull != true) {
            throw IllegalStateException("Telegram bot command update failed.")
        }
        return TelegramApiResponse(status = status, body = bytes.decodeToString())
    }

    private class ClientLease(
        val client: HttpClient,
        var activeRequests: Int = 0,
        var retired: Boolean = false,
        var closed: Boolean = false,
    )
}

private const val MAX_TELEGRAM_API_BYTES = 1024 * 1024
private const val MAX_TELEGRAM_DOWNLOAD_BYTES = 24 * 1024 * 1024
private val telegramJson = Json { ignoreUnknownKeys = true }

/** Telegram 响应在解压后的实际读取字节超过当前调用的硬上限。 */
class TelegramPayloadTooLargeException : IllegalStateException("Telegram 响应超过资源上限。")

private suspend fun HttpResponse.readTelegramBytes(limit: Int): ByteArray {
    val channel = bodyAsChannel()
    headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let { declared ->
        if (declared > limit) {
            val exception = TelegramPayloadTooLargeException()
            channel.cancel(exception)
            throw exception
        }
    }
    val bytes = ByteArray(limit + 1)
    var total = 0
    try {
        while (total < bytes.size) {
            val count = channel.readAvailable(bytes, total, bytes.size - total)
            if (count < 0) break
            total += count
        }
        if (total > limit) throw TelegramPayloadTooLargeException()
        return bytes.copyOf(total)
    } catch (e: TelegramPayloadTooLargeException) {
        channel.cancel(e)
        throw e
    }
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
            configureHttpProxyBasicAuthentication(proxySettingsToUse)
        }
    }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}
