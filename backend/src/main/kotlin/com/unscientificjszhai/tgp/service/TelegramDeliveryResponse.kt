package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.ktor.http.isSuccess
import kotlinx.serialization.json.*

/** 富消息成功响应包含完整 blocks，节点预算必须容纳 HTTP 客户端已限制为 1 MiB 的响应。 */
private fun TelegramApiResponse.resultObject(): JsonObject? = try {
    JsonStructureLimits.parseToJsonElement(Json, body, JsonStructureLimits.Budget(maxNodes = 1024 * 1024)) as? JsonObject
} catch (_: Exception) {
    null
}

internal fun TelegramApiResponse.isTelegramAccepted(): Boolean =
    status.isSuccess() && (resultObject()?.get("ok") as? JsonPrimitive)?.booleanOrNull == true

/** 限流、超时和服务端临时错误不能用于推断富消息不受支持。 */
internal fun TelegramApiResponse.isPermanentTelegramRejection(): Boolean {
    if (status.value == 408 || status.value == 429 || status.value >= 500) return false
    if (status.value in 400..499) return true
    val payload = resultObject() ?: return false
    if ((payload["ok"] as? JsonPrimitive)?.booleanOrNull != false) return false
    val code = (payload["error_code"] as? JsonPrimitive)?.intOrNull ?: return false
    return code in 400..499 && code != 408 && code != 429
}
