package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration

/**
 * 本地功能提供者基类。
 */
abstract class LocalFunctionProvider {

    /**
     * 该提供者支持的所有函数声明。
     */
    abstract val providedFunctions: List<FunctionDeclaration>

    /**
     * 检查该提供者是否可以处理指定的函数。
     *
     * @param functionName 函数名。
     * @return 如果可以处理则返回 true。
     */
    open fun canHandle(functionName: String): Boolean {
        return providedFunctions.any { it.name().orElse(null) == functionName }
    }

    /**
     * 执行具体的函数逻辑。
     *
     * @param functionName 函数名。
     * @param args 参数列表。
     * @return 执行结果 Map。
     */
    abstract suspend fun execute(functionName: String, args: Map<String, Any?>): Map<String, Any?>
}
