package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentToolExecutionContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import javax.inject.Provider

/**
 * 提供创建、查询和取消 AI 定时任务的模型函数。
 *
 * 所有任务操作均委托给 [TaskSchedulerService]。Agent 回合中创建任务时使用准入时固定的代理会话；
 * 直接调用创建任务时使用当前 AI 设置中的代理会话标识。到期实例由调度器在
 * Agent 与 Telegram 副作用前原子预消费：单次任务删除，循环任务推进到一个未来时刻；因此崩溃、失败或取消
 * 不会重放该次，但提交与副作用之间中断可能遗漏一次执行。绝对时间和日/周循环均解释为服务器时区，错过的
 * 循环周期不会逐期追赶。
 *
 * @param taskSchedulerService 延迟提供定时任务调度服务，以避免初始化循环依赖。
 * @param settingsRepository 为没有 Agent 回合上下文的直接调用提供代理会话标识的设置仓库。
 * @param clock 提供当前时间及默认时区的时钟；默认使用系统时钟。
 * @param zoneId 解释和展示绝对执行时间的时区；必须与 [clock] 的时区相同，默认使用该时区。
 */
class ScheduleTaskFunctionProvider(
    private val taskSchedulerService: Provider<TaskSchedulerService>,
    private val settingsRepository: SettingsRepository,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val zoneId: ZoneId = clock.zone,
) : LocalFunctionProvider() {
    init {
        require(zoneId == clock.zone) { "定时任务时区必须与时钟时区一致。" }
    }

    private companion object {
        val EXECUTION_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)
        val RELATIVE_EXECUTION_TIME = Regex("""\+([1-9]\d*)([smhd])""")
        const val MAX_RELATIVE_AMOUNT = Int.MAX_VALUE.toLong()
    }

    /**
     * 获取创建、列出和取消定时任务的函数声明。
     *
     * @return 包含 `create_scheduled_task`、`list_scheduled_tasks` 和 `cancel_scheduled_task` 的列表。
     */
    override val providedFunctions: List<FunctionDeclaration> by lazy {
        val createScheduledTaskSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("instruction", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The instruction for the LLM to execute when the task is triggered.")
                })
                put("executionTime", buildJsonObject {
                    put("type", "STRING")
                    put(
                        "description",
                        "A strict 'yyyy-MM-dd HH:mm:ss' local time in the server time zone, or a relative " +
                                "'+<1..2147483647><s|m|h|d>' value such as '+1h' or '+30m'."
                    )
                })
                put("loopMode", buildJsonObject {
                    put("type", "STRING")
                    put(
                        "description",
                        "The loop mode of the task. Available values: ONCE, HOURLY, DAILY, WEEKLY. Default is ONCE."
                    )
                })
            })
            put("required", buildJsonArray {
                add("instruction")
                add("executionTime")
            })
        }.toString()

        val listScheduledTasksSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {})
        }.toString()

        val cancelScheduledTaskSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("taskId", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The ID of the task to cancel.")
                })
            })
            put("required", buildJsonArray { add("taskId") })
        }.toString()

        listOf(
            FunctionDeclaration.builder()
                .name("create_scheduled_task")
                .description("Create a new scheduled task.")
                .parameters(Schema.fromJson(createScheduledTaskSchemaJson))
                .build(),
            FunctionDeclaration.builder()
                .name("list_scheduled_tasks")
                .description("List all currently scheduled tasks.")
                .parameters(Schema.fromJson(listScheduledTasksSchemaJson))
                .build(),
            FunctionDeclaration.builder()
                .name("cancel_scheduled_task")
                .description("Cancel a scheduled task by ID.")
                .parameters(Schema.fromJson(cancelScheduledTaskSchemaJson))
                .build()
        )
    }

    /**
     * 执行定时任务相关函数。
     *
     * @param functionName 要执行的函数名称；非 [providedFunctions] 中声明的名称会得到 `error` 结果。
     * @param args 函数参数映射；创建任务要求字符串 `instruction` 和 `executionTime`。绝对执行时间必须是
     * 当前服务器时区中严格的 `yyyy-MM-dd HH:mm:ss`，夏令时不存在的本地时间会被拒绝，重叠时间采用较早
     * 偏移量；相对时间必须是 `+<1..2147483647><s|m|h|d>`。可选 `loopMode` 必须为 `ONCE`、`HOURLY`、
     * `DAILY` 或 `WEEKLY`；取消任务要求字符串 `taskId`，列出任务时忽略该映射。
     * Agent 回合上下文存在时，创建操作只使用其中固定的代理会话，不会重新读取当前设置。
     *
     * @return 操作结果的 JSON 对象；参数缺失、格式错误、未配置会话或不支持的函数名称时包含 `error` 字段。
     */
    override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
        val executionContext = currentCoroutineContext()[AgentToolExecutionContext]
        return when (functionName) {
            "create_scheduled_task" -> createScheduledTask(args, agentChatIdForCreate(executionContext))
            "list_scheduled_tasks" -> listScheduledTasks()
            "cancel_scheduled_task" -> cancelScheduledTask(args)
            else -> buildJsonObject { put("error", "Unsupported function: $functionName") }
        }
    }

    private fun createScheduledTask(
        args: Map<String, Any?>,
        configuredAgentChatId: String?,
    ): JsonObject {
        val instruction =
            args["instruction"] as? String ?: return buildJsonObject { put("error", "Missing instruction") }
        val executionTimeStr =
            args["executionTime"] as? String ?: return buildJsonObject { put("error", "Missing executionTime") }
        val loopModeStr = (args["loopMode"] as? String)?.uppercase() ?: "ONCE"

        val loopMode = try {
            LoopMode.valueOf(loopModeStr)
        } catch (_: Exception) {
            return buildJsonObject { put("error", "Invalid loopMode: $loopModeStr") }
        }

        val executionTime = try {
            parseExecutionTime(executionTimeStr)
        } catch (_: Exception) {
            return buildJsonObject {
                put(
                    "error",
                    "Invalid executionTime format: $executionTimeStr. Expected strict 'yyyy-MM-dd HH:mm:ss' " +
                            "or '+<1..2147483647><s|m|h|d>'."
                )
            }
        }

        val agentChatId = configuredAgentChatId?.takeIf { it.isNotBlank() }
            ?: return buildJsonObject {
                put(
                    "error",
                    "Agent Chat ID is not configured. Please set it in settings first."
                )
            }

        val taskId = try {
            taskSchedulerService.get().createTask(instruction, executionTime, loopMode, agentChatId)
        } catch (e: Exception) {
            return buildJsonObject {
                put("error", "Failed to persist scheduled task: ${e.message ?: e::class.simpleName}")
            }
        }
        return buildJsonObject {
            put("status", "success")
            put("taskId", taskId)
            put(
                "message",
                "Task created successfully. Next execution at: ${formatExecutionTime(executionTime)}"
            )
        }
    }

    private fun listScheduledTasks(): JsonObject {
        val tasks = taskSchedulerService.get().listTasks().map {
            buildJsonObject {
                put("id", it.id)
                put("instruction", it.instruction)
                put("executionTime", formatExecutionTime(it.executionTime))
                put("loopMode", it.loopMode.name)
            }
        }
        return buildJsonObject {
            put("tasks", buildJsonArray {
                tasks.forEach { add(it) }
            })
        }
    }

    private fun cancelScheduledTask(
        args: Map<String, Any?>,
    ): JsonObject {
        val taskId = args["taskId"] as? String ?: return buildJsonObject { put("error", "Missing taskId") }
        val success = try {
            taskSchedulerService.get().cancelTask(taskId)
        } catch (e: Exception) {
            return buildJsonObject {
                put("error", "Failed to persist task cancellation: ${e.message ?: e::class.simpleName}")
            }
        }
        return if (success) {
            buildJsonObject {
                put("status", "success")
                put("message", "Task $taskId cancelled.")
            }
        } else {
            buildJsonObject {
                put("error", "Task $taskId not found.")
            }
        }
    }

    private fun formatExecutionTime(executionTime: Long): String =
        Instant.ofEpochMilli(executionTime).atZone(zoneId).format(EXECUTION_TIME_FORMATTER)

    private fun parseExecutionTime(timeStr: String): Long {
        RELATIVE_EXECUTION_TIME.matchEntire(timeStr)?.let { match ->
            val amount = match.groupValues[1].toLong()
            require(amount <= MAX_RELATIVE_AMOUNT) { "Relative time is too large." }
            val executionTime = ZonedDateTime.now(clock)
            val scheduledTime = when (match.groupValues[2]) {
                "s" -> executionTime.plusSeconds(amount)
                "m" -> executionTime.plusMinutes(amount)
                "h" -> executionTime.plusHours(amount)
                "d" -> executionTime.plusDays(amount)
                else -> error("Relative time pattern returned an unsupported unit.")
            }
            return scheduledTime.toInstant().toEpochMilli()
        }

        val localExecutionTime = LocalDateTime.parse(timeStr, EXECUTION_TIME_FORMATTER)
        val validOffsets = zoneId.rules.getValidOffsets(localExecutionTime)
        require(validOffsets.isNotEmpty()) { "Execution time does not exist in the configured time zone." }
        return ZonedDateTime.ofLocal(localExecutionTime, zoneId, validOffsets.first())
            .toInstant()
            .toEpochMilli()
    }

    private fun agentChatIdForCreate(
        executionContext: AgentToolExecutionContext?,
    ): String? =
        when (executionContext) {
            null -> settingsRepository.settingsFlow.value.ai?.agentChatId
            else -> executionContext.taskAgentChatId
        }
}
