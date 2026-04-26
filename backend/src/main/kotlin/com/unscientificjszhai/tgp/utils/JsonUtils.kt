package com.unscientificjszhai.tgp.utils

import kotlinx.serialization.json.Json

/**
 * 统一用于持久化配置文件的 Json 对象。
 */
val ConfigJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}
