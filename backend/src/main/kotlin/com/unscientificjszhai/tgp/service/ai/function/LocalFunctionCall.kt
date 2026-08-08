package com.unscientificjszhai.tgp.service.ai.function

import kotlinx.serialization.json.JsonObject

/**
 * 表示已绑定到单个模型回合的本地函数调用。
 */
internal fun interface LocalFunctionCall {
    /**
     * 使用当前回合提供的参数执行已绑定的函数目标。
     *
     * @param args 函数参数映射；值可为 `null`，格式由声明的函数输入架构决定。
     * @return 函数执行结果。
     */
    suspend fun execute(args: Map<String, Any?>): JsonObject
}
