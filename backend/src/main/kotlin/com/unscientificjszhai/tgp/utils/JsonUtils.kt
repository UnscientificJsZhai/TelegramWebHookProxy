package com.unscientificjszhai.tgp.utils

import kotlinx.serialization.json.Json

/**
 * 用于持久化配置文件的 JSON 编解码器。
 *
 * 编码结果会格式化并包含默认值；解码时会忽略未知字段。
 */
val ConfigJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
