package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Provider

/**
 * 提供创建、查询和取消 AI 定时任务的模型函数。
 *
 * 所有任务操作均委托给 [TaskSchedulerService]，创建任务时使用当前 AI 设置中的代理会话标识。
 *
 * @param taskSchedulerService 延迟提供定时任务调度服务，以避免初始化循环依赖。
 * @param settingsRepository 提供创建任务所需代理会话标识的设置仓库。
 */
class ScheduleTaskFunctionProvider(
    private val taskSchedulerService: Provider<TaskSchedulerService>,
    private val settingsRepository: SettingsRepository
) : LocalFunctionProvider() {

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
                        "The time to execute the task, in 'yyyy-MM-dd HH:mm:ss' format or a relative time like '+1h', '+30m'."
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
     * @param args 函数参数映射；创建任务要求字符串 `instruction` 和 `executionTime`，后者格式必须为
     * `yyyy-MM-dd HH:mm:ss` 或 `+<整数><s|m|h|d>`。可选 `loopMode` 必须为 `ONCE`、`HOURLY`、
     * `DAILY` 或 `WEEKLY`；取消任务要求字符串 `taskId`，列出任务时忽略该映射。
     * @return 操作结果的 JSON 对象；参数缺失、格式错误、未配置会话或不支持的函数名称时包含 `error` 字段。
     */
    override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject =
        when (functionName) {
            "create_scheduled_task" -> createScheduledTask(args)
            "list_scheduled_tasks" -> listScheduledTasks()
            "cancel_scheduled_task" -> cancelScheduledTask(args)
            else -> buildJsonObject { put("error", "Unsupported function: $functionName") }
        }

    private fun createScheduledTask(args: Map<String, Any?>): JsonObject {
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
                    "Invalid executionTime format: $executionTimeStr. Expected 'yyyy-MM-dd HH:mm:ss' or relative time like '+1h'."
                )
            }
        }

        val agentChatId = settingsRepository.settingsFlow.value.ai?.agentChatId
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
                "Task created successfully. Next execution at: ${
                    SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(
                        Date(executionTime)
                    )
                }"
            )
        }
    }

    private fun listScheduledTasks(): JsonObject {
        val tasks = taskSchedulerService.get().listTasks().map {
            buildJsonObject {
                put("id", it.id)
                put("instruction", it.instruction)
                put("executionTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(it.executionTime)))
                put("loopMode", it.loopMode.name)
            }
        }
        return buildJsonObject {
            put("tasks", buildJsonArray {
                tasks.forEach { add(it) }
            })
        }
    }

    private fun cancelScheduledTask(args: Map<String, Any?>): JsonObject {
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

    private fun parseExecutionTime(timeStr: String): Long {
        if (timeStr.startsWith("+")) {
            val amount = timeStr.substring(1, timeStr.length - 1).toLong()
            val unit = timeStr.last()
            val calendar = Calendar.getInstance()
            when (unit) {
                's' -> calendar.add(Calendar.SECOND, amount.toInt())
                'm' -> calendar.add(Calendar.MINUTE, amount.toInt())
                'h' -> calendar.add(Calendar.HOUR_OF_DAY, amount.toInt())
                'd' -> calendar.add(Calendar.DAY_OF_YEAR, amount.toInt())
                else -> throw IllegalArgumentException("Unknown time unit: $unit")
            }
            return calendar.timeInMillis
        } else {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(timeStr).time
        }
    }
}
