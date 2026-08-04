package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.openai.models.FunctionDefinition
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.Collections
import kotlin.jvm.optionals.getOrNull

/**
 * 一次模型回合可使用的本地函数声明和执行绑定快照。
 *
 * 声明、名称检查和执行调用均读取同一份不可变路由表；后续工具刷新不会改变本快照已声明函数的目标。
 */
internal class LocalFunctionRouteSnapshot internal constructor(
    private val declarations: List<FunctionDeclaration>,
    private val routes: Map<String, LocalFunctionCall>,
) {
    /**
     * 获取本回合可向模型声明的函数。
     *
     * @return 无名称冲突的不可修改函数声明列表。
     */
    fun providedFunctions(): List<FunctionDeclaration> = declarations

    /**
     * 获取本回合可向 OpenAI 声明的函数。
     *
     * @return 与 [providedFunctions] 顺序一致的 OpenAI 函数定义。
     */
    fun providedOpenAIFunctions(): List<FunctionDefinition> = declarations.map { it.toOpenAIFunction() }

    /**
     * 检查本回合是否可执行指定函数。
     *
     * @param functionName 要检查的函数名称；空字符串不会匹配任何函数。
     * @return 本快照包含 [functionName] 的唯一执行绑定时返回 `true`，否则返回 `false`。
     */
    fun canHandle(functionName: String): Boolean = routes.containsKey(functionName)

    /**
     * 使用本回合已捕获的真实目标执行函数。
     *
     * @param functionName 要执行的函数名称；必须存在于本快照。
     * @param args 要传给函数的参数映射；值可为 `null`，格式由函数声明决定。
     * @return 目标函数返回的 JSON 对象。
     * @throws IllegalArgumentException 当函数未声明或声明名称存在冲突时抛出。
     */
    suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
        val call = routes[functionName]
            ?: throw IllegalArgumentException("No unambiguous local function is declared for $functionName")
        return call.execute(args)
    }
}

/**
 * 将多个本地函数提供者的声明和执行绑定收敛为单回合不可变快照。
 *
 * 同名声明会从快照中整体移除，且不会被执行；任一提供者均不能依靠列表顺序覆盖另一个提供者的函数。
 *
 * @param providers 要汇总的本地函数提供者；列表中的提供者及其声明均可动态变化。
 */
internal class LocalFunctionRouter(
    private val providers: List<LocalFunctionProvider>,
) {
    private data class Candidate(
        val name: String,
        val declaration: FunctionDeclaration,
        val provider: LocalFunctionProvider,
    )

    private val logger = LoggerFactory.getLogger(LocalFunctionRouter::class.java)

    /**
     * 刷新所有提供者，并生成供一个模型回合独占使用的声明与真实调用目标。
     *
     * 刷新期间串行化提供者的声明和绑定捕获，使动态提供者无法在两者之间替换已声明名称的目标。
     *
     * @return 本次刷新的不可变路由快照。
     */
    @Synchronized
    fun refresh(): LocalFunctionRouteSnapshot {
        val candidates = providers.flatMap { provider ->
            provider.providedFunctions.mapNotNull { declaration ->
                declaration.name().getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { name -> Candidate(name, declaration, provider) }
            }
        }
        val groups = candidates.groupBy { it.name }
        val collidingNames = groups.filterValues { it.size > 1 }.keys
        if (collidingNames.isNotEmpty()) {
            logger.warn("Ignoring {} colliding local function declaration(s)", collidingNames.size)
        }
        val routes = groups.values.filter { it.size == 1 }.mapNotNull { group ->
            val candidate = group.single()
            candidate.provider.snapshotCall(candidate.name)?.let { call -> candidate to call }
        }
        return LocalFunctionRouteSnapshot(
            declarations = Collections.unmodifiableList(routes.map { it.first.declaration }),
            routes = Collections.unmodifiableMap(routes.associate { it.first.name to it.second }),
        )
    }

    /**
     * 判断此路由器是否对应指定提供者列表实例。
     *
     * @param candidates 要比较的提供者列表实例。
     * @return 两个列表为同一实例时返回 `true`，否则返回 `false`。
     */
    fun uses(candidates: List<LocalFunctionProvider>): Boolean = providers === candidates
}

private fun FunctionDeclaration.toOpenAIFunction(): FunctionDefinition =
    FunctionDefinition.builder()
        .name(name().get())
        .apply { description().ifPresent { description(it) } }
        .parameters(LocalFunctionProvider.convertGeminiSchemaToOpenAI(parameters().get()))
        .build()
