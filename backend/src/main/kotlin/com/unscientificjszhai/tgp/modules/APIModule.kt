package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.HistoricalInvalidMcpConfigurationException
import com.unscientificjszhai.tgp.repository.HistoricalInvalidOpenAiBaseUrlConfigurationException
import com.unscientificjszhai.tgp.repository.SettingsRevisionMismatchException
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SettingsUpdateResult
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.bodylimit.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.statuspages.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets

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
                call.respondCompleteSettings(snapshot.settings)
            }
            route("/settings") {
                install(RequestBodyLimit) { bodyLimit { ResourceLimits.SETTINGS_REQUEST_BYTES } }
                put {
                    call.handleFullSettingsUpdate(settingsRepository, telegramService)
                }
                // 保留既有 POST 路径，且与 PUT 使用相同的严格完整替换契约。
                post {
                    call.handleFullSettingsUpdate(settingsRepository, telegramService)
                }
                patch {
                    val expectedRevision = call.requiredSettingsRevision() ?: return@patch
                    val patch = call.readSettingsJsonObject() ?: return@patch
                    try {
                        patch.validateSettingsPatch()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        call.respondSettingsError(HttpStatusCode.BadRequest, "设置请求格式不合法。")
                        return@patch
                    }
                    val update = call.commitSettingsUpdate(
                        settingsRepository,
                        telegramService,
                        expectedRevision,
                        patch.explicitlyReplacesHistoricalMcpServers(),
                        patch.explicitlyReplacesHistoricalOpenAiBaseUrl(),
                    ) { current ->
                        current.mergeSettingsPatch(patch)
                    } ?: return@patch
                    call.respondSettingsUpdate(update)
                }
            }
            route("/settings/chat") {
                install(RequestBodyLimit) { bodyLimit { ResourceLimits.CHAT_SETTINGS_REQUEST_BYTES } }
                post {
                    val expectedRevision = call.requiredSettingsRevision() ?: return@post
                    val request = call.readSettingsJsonObject() ?: return@post
                    val chatId = try {
                        request.requireExactKeys(CHAT_SETTINGS_FIELDS)
                        request.getValue("chatId").requireNonNullJsonValue()
                        strictSettingsJson.decodeFromJsonElement<ChatSettingsRequest>(request).chatId
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        call.respondSettingsError(HttpStatusCode.BadRequest, "聊天设置请求格式不合法。")
                        return@post
                    }
                    val update =
                        call.commitSettingsUpdate(settingsRepository, telegramService, expectedRevision) { current ->
                            current.copy(chatId = chatId)
                        } ?: return@post
                    call.respondSettingsUpdate(update)
                }
            }
            route("/send-message") {
                install(RequestBodyLimit) { bodyLimit { ResourceLimits.SEND_MESSAGE_REQUEST_BYTES } }
                post {
                    val messageField = call.singleCustomFieldName("messagefield", "text") ?: return@post
                    val chatIdField = call.singleCustomFieldName("chatidfield", "chatId") ?: return@post
                    if (messageField.utf8Size() > 64 || chatIdField.utf8Size() > 64) {
                        call.respondApiInputError("自定义字段名过长")
                        return@post
                    }

                    val contentType = call.request.contentType()
                    val (requestChatId, requestText) = when {
                        contentType.match(ContentType.Application.Json) -> {
                            val json = call.receive<JsonObject>()
                            val (chatId, text) = try {
                                json.optionalStringValue(chatIdField) to json.requiredStringValue(messageField)
                            } catch (_: IllegalArgumentException) {
                                call.respondApiInputError("消息请求格式不合法")
                                return@post
                            }
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

                    if (requestText.utf8Size() > ResourceLimits.SEND_MESSAGE_REQUEST_BYTES ||
                        (requestChatId?.utf8Size() ?: 0) > 64
                    ) {
                        call.respondApiInputError("消息或聊天标识超过限制")
                        return@post
                    }
                    if (requestText.isBlank()) {
                        call.respondApiInputError("Message text is required")
                        return@post
                    }

                    try {
                        val chatId = (requestChatId ?: "").ifBlank { settingsRepository.settingsFlow.value.chatId }
                        if (chatId.isBlank()) {
                            call.respondApiInputError("Chat ID is required")
                            return@post
                        }
                        val response = telegramService.sendMessage(chatId, requestText)
                        call.respond(response.status, response.body)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadGateway, "消息发送失败。")
                    }
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

/**
 * 注册 API 请求体的固定错误响应。
 *
 * 请求体超过各路由限制时优先响应 `413`；JSON 转换、序列化或其 Ktor 包装失败时响应不含异常详情的 `400`。
 * 此方法应在注册使用请求体的 API 路由前调用一次。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 */
internal fun Application.installApiErrorPages() {
    install(StatusPages) {
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "请求体超过限制。"))
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "请求格式不合法。"))
        }
        exception<SerializationException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "请求格式不合法。"))
        }
        // Ktor 会将部分 JSON 解码失败包装为 BadRequestException；同样不能把包装后的详情返回给客户端。
        exception<BadRequestException> { call, _ ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "请求格式不合法。"))
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

private val strictSettingsJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = true
    encodeDefaults = true
}

private suspend fun ApplicationCall.singleCustomFieldName(parameter: String, defaultValue: String): String? {
    val values = request.queryParameters.getAll(parameter) ?: return defaultValue
    if (values.size != 1 || values.single().isBlank()) {
        respondApiInputError("自定义字段名不合法")
        return null
    }
    return values.single()
}

private fun JsonObject.requiredStringValue(field: String): String =
    this[field]?.asStringValue(field)
        ?: throw IllegalArgumentException("$field must be present as a JSON string.")

private fun JsonObject.optionalStringValue(field: String): String? = when (val value = this[field]) {
    null, JsonNull -> null
    else -> value.asStringValue(field)
}

private fun JsonElement.asStringValue(field: String): String =
    (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: throw IllegalArgumentException("$field must be a JSON string.")

private suspend fun ApplicationCall.respondApiInputError(message: String) {
    respond(HttpStatusCode.BadRequest, mapOf("error" to message))
}

private val APP_SETTINGS_FIELDS = setOf("telegramToken", "chatId", "proxy", "ai")
private val CHAT_SETTINGS_FIELDS = setOf("chatId")
private val PROXY_SETTINGS_FIELDS = setOf("host", "port", "type", "username", "password")
private val AI_SETTINGS_FIELDS = setOf(
    "provider",
    "geminiApiKey",
    "openAiApiKey",
    "openAiBaseUrl",
    "selectedModel",
    "agentEnabled",
    "agentChatId",
    "globalContext",
    "autoCleanContextIntervalMinutes",
    "silentContextCleanup",
    "mcpServers",
    "httpToolSettings",
)
private val MCP_SERVER_FIELDS = setOf("name", "url", "headers")
private val HTTP_TOOL_SETTINGS_FIELDS = setOf("enabled", "targets", "requestTimeoutMillis", "maxConcurrentRequests")
private val HTTP_CALL_TARGET_FIELDS = setOf("id", "scheme", "host", "port", "path", "method", "allowedCidrs")

@Serializable
private data class ChatSettingsRequest(val chatId: String)

@Serializable
private data class SettingsErrorResponse(val error: String)

private fun String.utf8Size(): Long = toByteArray(StandardCharsets.UTF_8).size.toLong()

/** 读取并严格解析设置请求的原始 JSON 对象；不会使用应用全局的宽松 JSON 配置。 */
private suspend fun ApplicationCall.readSettingsJsonObject(): JsonObject? = try {
    strictSettingsJson.parseToJsonElement(receiveText()) as? JsonObject
        ?: throw IllegalArgumentException("Settings request root must be a JSON object.")
} catch (e: CancellationException) {
    throw e
} catch (e: PayloadTooLargeException) {
    throw e
} catch (_: Exception) {
    respondSettingsError(HttpStatusCode.BadRequest, "设置请求格式不合法。")
    null
}

/** 读取单个强 ETag；缺失或格式不合法时写入安全的结构化错误响应。 */
private suspend fun ApplicationCall.requiredSettingsRevision(): String? = when (
    val parsed = request.headers.parseSingleStrongETag()
) {
    IfMatchResult.Missing -> {
        respondSettingsError(PRECONDITION_REQUIRED, "必须提供 If-Match 设置修订值。")
        null
    }

    IfMatchResult.Invalid -> {
        respondSettingsError(HttpStatusCode.BadRequest, "If-Match 必须是单个合法强 ETag。")
        null
    }

    is IfMatchResult.Valid -> parsed.revision
}

/** 处理严格完整替换的 PUT 与兼容 POST 设置请求。 */
private suspend fun ApplicationCall.handleFullSettingsUpdate(
    settingsRepository: SettingsRepository,
    telegramService: TelegramService,
) {
    val expectedRevision = requiredSettingsRevision() ?: return
    val request = readSettingsJsonObject() ?: return
    val settings = try {
        request.validateCompleteAppSettings()
        strictSettingsJson.decodeFromJsonElement<AppSettings>(request)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        respondSettingsError(HttpStatusCode.BadRequest, "设置请求格式不合法。")
        return
    }
    val update = commitSettingsUpdate(
        settingsRepository,
        telegramService,
        expectedRevision,
        replacesHistoricalInvalidMcpServers = true,
        replacesHistoricalInvalidOpenAiBaseUrl = true,
    ) { settings } ?: return
    respondSettingsUpdate(update)
}

/**
 * 在仓储的锁内提交设置变换，并为 PUT、PATCH 与兼容聊天更新统一应用模型选择及机器人命令副作用。
 */
private suspend fun ApplicationCall.commitSettingsUpdate(
    settingsRepository: SettingsRepository,
    telegramService: TelegramService,
    expectedRevision: String,
    replacesHistoricalInvalidMcpServers: Boolean = false,
    replacesHistoricalInvalidOpenAiBaseUrl: Boolean = false,
    transform: (AppSettings) -> AppSettings,
): SettingsUpdateResult? {
    val update = try {
        settingsRepository.updateSettings(
            expectedRevision,
            replacesHistoricalInvalidMcpServers,
            replacesHistoricalInvalidOpenAiBaseUrl,
        ) { current ->
            transform(current).clearSelectedModelWhenProviderOrApiKeyChanges(current)
        }
    } catch (_: SettingsRevisionMismatchException) {
        respondSettingsError(HttpStatusCode.PreconditionFailed, "设置已被其他操作修改。")
        return null
    } catch (_: HistoricalInvalidMcpConfigurationException) {
        respondSettingsError(HttpStatusCode.Conflict, "历史 MCP 配置需要在本次请求中显式替换。")
        return null
    } catch (_: HistoricalInvalidOpenAiBaseUrlConfigurationException) {
        respondSettingsError(HttpStatusCode.Conflict, "历史 OpenAI 基础地址需要在本次请求中显式替换。")
        return null
    } catch (_: IllegalArgumentException) {
        respondSettingsError(HttpStatusCode.BadRequest, "设置内容不合法。")
        return null
    } catch (_: IllegalStateException) {
        respondSettingsError(HttpStatusCode.Conflict, "设置存储需要恢复后重试。")
        return null
    }

    val oldSettings = update.previous.settings
    val savedSettings = update.current.settings
    val oldEffectiveProvider = oldSettings.ai?.takeIf { it.agentEnabled }?.provider
    val newEffectiveProvider = savedSettings.ai?.takeIf { it.agentEnabled }?.provider
    if (savedSettings.telegramToken != oldSettings.telegramToken || newEffectiveProvider != oldEffectiveProvider) {
        if (savedSettings.telegramToken.isNotBlank()) {
            try {
                telegramService.updateBotCommands(savedSettings.telegramToken, newEffectiveProvider)
            } catch (e: Exception) {
                application.log.error(
                    "Failed to update bot commands; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
            }
        }
    }
    return update
}

/** 返回提交后的完整设置及其用于下一次条件写入的强 ETag。 */
private suspend fun ApplicationCall.respondSettingsUpdate(update: SettingsUpdateResult) {
    response.headers.append(HttpHeaders.ETag, update.current.revision.toStrongETag())
    respondCompleteSettings(update.current.settings, HttpStatusCode.OK)
}

/** 使用完整严格 JSON 表示返回设置，使响应可作为下一次 PUT 的完整请求体。 */
private suspend fun ApplicationCall.respondCompleteSettings(
    settings: AppSettings,
    status: HttpStatusCode = HttpStatusCode.OK
) {
    respondText(
        strictSettingsJson.encodeToString(settings),
        ContentType.Application.Json,
        status,
    )
}

/** 返回不包含请求体、密钥或异常文本的结构化设置错误。 */
private suspend fun ApplicationCall.respondSettingsError(status: HttpStatusCode, message: String) {
    respond(status, SettingsErrorResponse(message))
}

private fun JsonObject.validateCompleteAppSettings() {
    requireExactKeys(APP_SETTINGS_FIELDS)
    getValue("telegramToken").requireNonNullJsonValue()
    getValue("chatId").requireNonNullJsonValue()
    validateNullableObject("proxy", JsonObject::validateCompleteProxySettings)
    validateNullableObject("ai", JsonObject::validateCompleteAiSettings)
}

private fun JsonObject.validateCompleteProxySettings() {
    requireExactKeys(PROXY_SETTINGS_FIELDS)
    getValue("host").requireNonNullJsonValue()
    getValue("port").requireNonNullJsonValue()
    getValue("type").requireNonNullJsonValue()
    // 用户名和密码可以显式为 null，表示不提供代理认证。
}

private fun JsonObject.validateCompleteAiSettings() {
    requireExactKeys(AI_SETTINGS_FIELDS)
    (AI_SETTINGS_FIELDS - setOf("mcpServers", "httpToolSettings")).forEach { field ->
        getValue(field).requireNonNullJsonValue()
    }
    getValue("mcpServers").requireJsonArray().forEach { it.requireJsonObject().validateCompleteMcpServer() }
    getValue("httpToolSettings").requireJsonObject().validateCompleteHttpToolSettings()
}

private fun JsonObject.validateCompleteMcpServer() {
    requireExactKeys(MCP_SERVER_FIELDS)
    getValue("name").requireNonNullJsonValue()
    getValue("url").requireNonNullJsonValue()
    getValue("headers").requireJsonObject().validateHeaders()
}

private fun JsonObject.validateCompleteHttpToolSettings() {
    requireExactKeys(HTTP_TOOL_SETTINGS_FIELDS)
    getValue("enabled").requireNonNullJsonValue()
    getValue("requestTimeoutMillis").requireNonNullJsonValue()
    getValue("maxConcurrentRequests").requireNonNullJsonValue()
    getValue("targets").requireJsonArray().forEach { it.requireJsonObject().validateCompleteHttpCallTarget() }
}

private fun JsonObject.validateCompleteHttpCallTarget() {
    requireExactKeys(HTTP_CALL_TARGET_FIELDS)
    values.forEach(JsonElement::requireNonNullJsonValue)
}

private fun JsonObject.validateSettingsPatch() {
    requireKnownKeys(APP_SETTINGS_FIELDS)
    forEach { (field, value) ->
        when (field) {
            "telegramToken", "chatId" -> value.requireNonNullJsonValue()
            "proxy" -> value.validateNullablePatchObject(JsonObject::validateProxySettingsPatch)
            "ai" -> value.validateNullablePatchObject(JsonObject::validateAiSettingsPatch)
        }
    }
}

private fun JsonObject.validateProxySettingsPatch() {
    requireKnownKeys(PROXY_SETTINGS_FIELDS)
    forEach { (field, value) ->
        if (field !in setOf("username", "password")) {
            value.requireNonNullJsonValue()
        }
    }
}

private fun JsonObject.validateAiSettingsPatch() {
    requireKnownKeys(AI_SETTINGS_FIELDS)
    forEach { (field, value) ->
        when (field) {
            "mcpServers" -> value.requireJsonArray().forEach { it.requireJsonObject().validateCompleteMcpServer() }
            "httpToolSettings" -> value.requireJsonObject().validateHttpToolSettingsPatch()
            else -> value.requireNonNullJsonValue()
        }
    }
}

private fun JsonObject.validateHttpToolSettingsPatch() {
    requireKnownKeys(HTTP_TOOL_SETTINGS_FIELDS)
    forEach { (field, value) ->
        if (field == "targets") {
            value.requireJsonArray().forEach { it.requireJsonObject().validateCompleteHttpCallTarget() }
        } else {
            value.requireNonNullJsonValue()
        }
    }
}

private fun JsonObject.validateHeaders() {
    values.forEach(JsonElement::requireNonNullJsonValue)
}

private fun AppSettings.mergeSettingsPatch(patch: JsonObject): AppSettings {
    val current = strictSettingsJson.encodeToJsonElement(this).jsonObject
    val merged = current.mergePatch(patch, APP_SETTINGS_NESTED_FIELDS)
    merged.validateCompleteAppSettings()
    return strictSettingsJson.decodeFromJsonElement(merged)
}

private val APP_SETTINGS_NESTED_FIELDS = setOf("proxy", "ai")
private val AI_SETTINGS_NESTED_FIELDS = setOf("httpToolSettings")

private fun JsonObject.mergePatch(patch: JsonObject, nestedFields: Set<String>): JsonObject = buildJsonObject {
    this@mergePatch.forEach { (key, value) -> put(key, value) }
    patch.forEach { (key, patchValue) ->
        val currentValue = this@mergePatch[key]
        if (key in nestedFields && currentValue is JsonObject && patchValue is JsonObject) {
            val nested = when (key) {
                "ai" -> currentValue.mergePatch(patchValue, AI_SETTINGS_NESTED_FIELDS)
                else -> currentValue.mergePatch(patchValue, emptySet())
            }
            put(key, nested)
        } else {
            put(key, patchValue)
        }
    }
}

private fun JsonObject.validateNullableObject(field: String, validator: JsonObject.() -> Unit) {
    val value = getValue(field)
    if (value !is JsonNull) {
        value.requireJsonObject().validator()
    }
}

private fun JsonElement.validateNullablePatchObject(validator: JsonObject.() -> Unit) {
    if (this !is JsonNull) {
        requireJsonObject().validator()
    }
}

private fun JsonObject.requireExactKeys(expected: Set<String>) {
    require(keys == expected) { "Settings object fields are incomplete or unknown." }
}

private fun JsonObject.requireKnownKeys(expected: Set<String>) {
    require(keys.all(expected::contains)) { "Settings object contains unknown fields." }
}

private fun JsonElement.requireJsonObject(): JsonObject = this as? JsonObject
    ?: throw IllegalArgumentException("Settings field must be an object.")

private fun JsonElement.requireJsonArray(): JsonArray = this as? JsonArray
    ?: throw IllegalArgumentException("Settings field must be an array.")

private fun JsonElement.requireNonNullJsonValue() {
    require(this !is JsonNull) { "Settings field must not be null." }
}

private fun JsonObject.explicitlyReplacesHistoricalMcpServers(): Boolean =
    when (val ai = this["ai"]) {
        is JsonNull -> true
        is JsonObject -> ai.containsKey("mcpServers")
        else -> false
    }

private fun JsonObject.explicitlyReplacesHistoricalOpenAiBaseUrl(): Boolean =
    when (val ai = this["ai"]) {
        is JsonNull -> true
        is JsonObject -> ai.containsKey("openAiBaseUrl")
        else -> false
    }
