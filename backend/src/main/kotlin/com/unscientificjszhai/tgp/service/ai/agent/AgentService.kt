package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.models.SkillBrief
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 一次模型列表刷新得到的一致性快照。
 *
 * @property currentModel 刷新完成后当前实际使用的模型名称。
 * @property availableModels 刷新完成后可供选择的模型名称列表；列表可为空。
 */
data class ModelSnapshot(
    val currentModel: String,
    val availableModels: List<String>,
)

/** 显式候选初始化的安全结果；失败值不包含上游正文、URL 或凭据。 */
sealed interface AgentInitializationResult {
    data object Ready : AgentInitializationResult
    data class Failed(val failure: AgentFailure) : AgentInitializationResult
}

/** 委派 Agent 对当前设置版本的可用状态。 */
enum class AgentAvailabilityState {
    DISABLED,
    INITIALIZING,
    RETRY_SCHEDULED,
    BLOCKED,
    READY,
    CLOSED,
}

/**
 * 可供无丢失唤醒地观察 Agent 可用性变化的不可变快照。
 *
 * [nextAttemptAtMillis] 使用恢复控制器的单调时钟，仅用于同一进程内诊断，不能持久化或解释为墙上时间。
 */
data class AgentAvailabilitySnapshot(
    val state: AgentAvailabilityState,
    val sequence: Long,
    val settingsVersion: Long,
    val provider: AIProvider? = null,
    val attempt: Int = 0,
    val failure: AgentFailure? = null,
    val nextAttemptAtMillis: Long? = null,
)

private val ALWAYS_READY_AGENT_AVAILABILITY = MutableStateFlow(
    AgentAvailabilitySnapshot(
        state = AgentAvailabilityState.READY,
        sequence = 0,
        settingsVersion = -1,
    ),
).asStateFlow()

internal const val MAX_TOOL_CALL_ROUNDS = 10
internal const val MAX_TOOL_CALLS_PER_MODEL_RESPONSE = 8
internal const val MAX_TOOL_CALLS_PER_TURN = 16
internal const val MAX_AGENT_HISTORY_BYTES = 8 * 1024 * 1024
internal const val MAX_AGENT_HISTORY_ENTRIES = 64
internal const val MAX_AGENT_TURN_RESERVATION_BYTES = 4 * 1024 * 1024
internal const val MAX_AGENT_TEXT_BYTES = 64 * 1024
internal const val MAX_AGENT_INLINE_MEDIA_BYTES = 2 * 1024 * 1024

/** Telegram OGG 语音在提交转写前允许的最大字节数。 */
const val MAX_AUDIO_TRANSCRIPTION_BYTES = 24 * 1024 * 1024

internal class ToolCallLimitExceededException : IllegalStateException(
    "工具调用轮次超过上限（$MAX_TOOL_CALL_ROUNDS 轮）。",
)

/**
 * 表示代理的一次回合未能完成，因而其会话历史未被提交。
 *
 * 调用方可以在之后重试相同任务；已由本次回合触发的外部工具副作用无法撤销，因此重试具有至少一次
 * 语义。
 *
 * @param message 对未完成原因的稳定说明，不包含提供商返回的敏感内容。
 * @param cause 导致回合未完成的底层异常；没有底层异常时为 `null`。
 */
class AgentTurnFailedException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * 当前设置对应的代理尚未完成配置，因此拒绝开始新的代理操作。
 *
 * 此异常不携带提供商、凭据或网络失败详情。调用方应等待后续设置更新恢复可用性，而不是回退到已退休的
 * 代理实例。
 */
internal class AgentConfigurationNotReadyException : IllegalStateException(
    "当前 AI 配置尚未就绪。",
)

/**
 * 语音文件超过转写服务允许的本地请求大小。
 *
 * 异常在创建 multipart 请求前抛出，因此不会读取、写入或上传临时文件。调用方可提示用户发送更短的语音，
 * 但不得将底层文件内容或路径回显给用户。
 */
class AudioTranscriptionTooLargeException : IllegalArgumentException(
    "语音文件超过 ${MAX_AUDIO_TRANSCRIPTION_BYTES / (1024 * 1024)} MiB 转写限制。",
)

/**
 * 语音转写未能取得可用于模型对话的文本。
 *
 * 异常不会包含提供商响应体或音频内容；本次代理回合的会话历史不会提交，调用方可在稍后重试。
 *
 * @param cause 转写请求、协议解析或空文本结果的底层原因；可为 `null`，且不应直接回显给最终用户。
 */
class AudioTranscriptionFailedException(
    cause: Throwable? = null,
) : IllegalStateException("语音转写失败。", cause)

/**
 * 确保本轮工具调用没有超过上限，避免模型持续请求工具而无法生成最终回复。
 *
 * @param toolCallRounds 已完成的工具调用轮次数；达到 [MAX_TOOL_CALL_ROUNDS] 时抛出异常。
 * @throws ToolCallLimitExceededException 当 [toolCallRounds] 不小于允许上限时抛出。
 */
internal fun ensureToolCallRoundIsAllowed(toolCallRounds: Int) {
    if (toolCallRounds >= MAX_TOOL_CALL_ROUNDS) {
        throw ToolCallLimitExceededException()
    }
}

/** 确保单个模型响应与整个回合不会执行过多工具调用。 */
internal fun ensureToolCallCountIsAllowed(responseCount: Int, completedTurnCount: Int) {
    if (responseCount > MAX_TOOL_CALLS_PER_MODEL_RESPONSE ||
        completedTurnCount + responseCount > MAX_TOOL_CALLS_PER_TURN
    ) {
        throw ToolCallLimitExceededException()
    }
}

/**
 * 定义 AI 对话服务的模型管理、会话管理与消息交互契约。
 *
 * 实现类应在 [close] 返回的任务完成后释放所持有的资源；关闭后的服务不应再用于发送消息。
 */
abstract class AgentService {
    /**
     * 当前服务的可用性。普通直接实现始终视为就绪；委派实现会覆盖为真实恢复状态流。
     */
    open val availability: StateFlow<AgentAvailabilitySnapshot>
        get() = ALWAYS_READY_AGENT_AVAILABILITY

    /**
     * 获取当前会话实际使用的模型名称。
     */
    abstract val currentModel: String

    /**
     * 获取当前可供选择的模型名称列表。
     *
     * 列表可为空；模型列表通常由 [updateModel] 刷新。
     */
    abstract val availableModels: List<String>

    /**
     * 判断给定设置是否足以启用此服务对应的 AI 功能。
     *
     * @param aiSettings 要检查的 AI 设置。
     * @return 设置启用了该服务支持的提供商且包含所需凭据时返回 `true`，否则返回 `false`。
     */
    abstract fun isAiFeatureEnabled(aiSettings: AISettings): Boolean

    /**
     * 切换当前会话使用的模型。
     *
     * 模型切换会重置会话；返回任务的正常完成、取消和失败语义完全由具体提供商定义。调用方必须阅读
     * 当前实现类的文档后再决定是否等待 [Job.join] 或检查 [Job.isCancelled]；具体实现可通过任务
     * 状态报告候选会话是否已原子提交。
     *
     * @param modelName 要切换到的模型名称，必须存在于 [availableModels]。
     * @return 已开始切换时返回异步重置会话的任务；模型未改变或服务不可用时返回 `null`。
     * @throws IllegalArgumentException 当 [modelName] 不在 [availableModels] 中时抛出。
     */
    abstract fun switchModel(modelName: String): Job?

    /**
     * 从提供商刷新可用模型列表，并在当前模型失效时选择实现定义的回退模型。
     *
     * @return 获取成功后的模型快照；刷新失败或结果已过期时返回 `null`。
     */
    abstract suspend fun updateModel(): ModelSnapshot?

    /**
     * 异步重置当前会话，清空历史记录并重新应用系统提示词。
     *
     * 返回任务的正常完成、取消和失败语义完全由具体提供商定义。调用方必须阅读当前实现类的文档后再
     * 决定是否等待 [Job.join] 或检查 [Job.isCancelled]；具体实现可要求将 `null` 或已取消任务视为
     * 失败，并保证候选会话仅在任务正常完成时原子提交。
     *
     * @return 已开始重置时返回其异步任务；服务不可用或无需重置时返回 `null`。
     */
    abstract fun resetSession(): Job?

    /**
     * 显式执行此候选发布前的单次初始化。
     *
     * 普通本地实现无需额外初始化并立即返回 [AgentInitializationResult.Ready]；提供商实现通过
     * [ProviderAgentService] 保证每个实例最多执行一次真实初始化。
     */
    open suspend fun initializeForPublication(): AgentInitializationResult = AgentInitializationResult.Ready

    /**
     * 发送文本消息并获取回复。
     *
     * 正常返回表示当前实现定义的代理回合已完成；模型返回以 `Error:` 开头的文本仍是正常的模型回复，
     * 而非失败信号。实现会将未完成且未提交历史的回合报告为 [AgentTurnFailedException]；取消会原样向上传播。
     * 成功提交时，为给后续回合预留资源，实现可能压缩最早的完整历史回合。
     *
     * @param text 要发送的文本消息；允许为空字符串，具体处理方式由实现决定。
     * @return AI 的回复文本；未生成可返回内容时返回空字符串。
     * @throws AgentTurnFailedException 当实现无法完成本次回合且未提交其会话历史时抛出。
     * @throws IllegalStateException 当服务已关闭或当前会话不可用，且具体实现选择以异常报告时抛出。
     * @throws Exception 当具体实现以其自身语义报告失败时抛出。
     * @throws kotlinx.coroutines.CancellationException 当调用协程或底层 I/O 被取消时原样抛出。
     */
    open suspend fun sendMessage(text: String): String = sendMessage(text, emptyList())

    /**
     * 发送包含媒体数据的消息并获取回复。
     *
     * 正常返回表示当前实现定义的代理回合已完成；模型返回以 `Error:` 开头的文本仍是正常的模型回复，
     * 而非失败信号。实现会将未完成且未提交历史的回合报告为 [AgentTurnFailedException]；取消会原样向上传播。
     * 成功提交时，为给后续回合预留资源，实现可能压缩最早的完整历史回合。
     *
     * @param text 可选的配文或指令内容；为 `null` 时仅发送 [mediaData]。
     * @param mediaData 要发送的媒体数据列表；可为空，元素的 MIME 类型必须与具体实现支持的格式匹配。
     * @return AI 的回复文本；未生成可返回内容时返回空字符串。
     * @throws AudioTranscriptionTooLargeException 当 OpenAI 实现收到超过 [MAX_AUDIO_TRANSCRIPTION_BYTES] 的 OGG
     * 语音时，在上传前抛出。
     * @throws AudioTranscriptionFailedException 当 OpenAI OGG 语音转写失败或返回空文本时抛出。
     * @throws AgentTurnFailedException 当实现无法完成本次回合且未提交其会话历史时抛出。
     * @throws IllegalStateException 当服务已关闭或当前会话不可用，且具体实现选择以异常报告时抛出。
     * @throws Exception 当具体实现以其自身语义报告失败时抛出。
     * @throws kotlinx.coroutines.CancellationException 当调用协程或底层 I/O 被取消时原样抛出。
     */
    abstract suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String

    /**
     * 在当前可安全使用的服务实例上执行一个完整的挂起操作。
     *
     * 默认实现将当前服务实例传给 [block]。委派实现会在其模型切换屏障内选择当前底层服务，因而调用方可将
     * 一个需要与服务选择保持一致的完整操作放入同一个作用域。`block` 只能在同步作用域内使用传入的服务，
     * 不得将它逸出到返回值、字段、后台协程或其他延后执行的位置，也不得对传入服务递归委派本方法；否则
     * 会破坏实现提供的就绪和生命周期保证。
     *
     * @param T [block] 的返回类型。
     * @param block 使用本次选定服务完成操作的挂起代码块；不得逸出或递归委派传入的服务。
     * @return [block] 的返回值。
     */
    internal open suspend fun <T> withReadyService(block: suspend (AgentService) -> T): T = block(this)

    /**
     * 异步关闭服务并释放资源。
     *
     * 应用停止时由唯一的 `ApplicationStopPreparing` 编排器在轮询与定时 worker 已关闭准入并完成终态后调用；
     * 调用方必须等待非空返回任务，才能认为 Agent 组件拥有的网络和 MCP 资源已释放。具体实现应保证重复调用
     * 不重复启动资源清理。
     *
     * @return 有异步清理工作时返回对应任务；无需清理时返回 `null`。调用方可等待该任务作为服务重建或应用停止
     * 资源释放屏障。
     */
    open fun close(): Job? = null

    /**
     * 生成注入到系统提示词中的技能说明。
     *
     * @param skills 已由调用方过滤为已批准状态的技能摘要列表；列表可为空，未批准技能不得传入。
     * @return 包含技能标识和描述的英文提示词；当 [skills] 为空时返回空字符串。
     */
    protected fun getSkillPrompt(skills: List<SkillBrief>): String {
        return if (skills.isNotEmpty()) {
            "Before doing anything, first try calling the read_skill tool to confirm the correct process. Available Skills:\n" + skills.joinToString(
                "\n"
            ) { "- ID: ${it.id}, Description: ${it.description}" } + "\n\n"
        } else {
            ""
        }
    }
}
