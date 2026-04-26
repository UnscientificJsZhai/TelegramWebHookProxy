package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import javax.inject.Provider
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.*

class ScheduleTaskFunctionProvider(
    private val taskSchedulerService: Provider<TaskSchedulerService>,
    private val settingsRepository: SettingsRepository
) : LocalFunctionProvider() {

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
                    put("description", "The time to execute the task, in 'yyyy-MM-dd HH:mm:ss' format or a relative time like '+1h', '+30m'.")
                })
                put("loopMode", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The loop mode of the task. Available values: ONCE, HOURLY, DAILY, WEEKLY. Default is ONCE.")
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

    override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject =
        when (functionName) {
            "create_scheduled_task" -> createScheduledTask(args)
            "list_scheduled_tasks" -> listScheduledTasks()
            "cancel_scheduled_task" -> cancelScheduledTask(args)
            else -> buildJsonObject { put("error", "Unsupported function: $functionName") }
        }

    private fun createScheduledTask(args: Map<String, Any?>): JsonObject {
        val instruction = args["instruction"] as? String ?: return buildJsonObject { put("error", "Missing instruction") }
        val executionTimeStr = args["executionTime"] as? String ?: return buildJsonObject { put("error", "Missing executionTime") }
        val loopModeStr = (args["loopMode"] as? String)?.uppercase() ?: "ONCE"

        val loopMode = try {
            LoopMode.valueOf(loopModeStr)
        } catch (_: Exception) {
            return buildJsonObject { put("error", "Invalid loopMode: $loopModeStr") }
        }

        val executionTime = try {
            parseExecutionTime(executionTimeStr)
        } catch (_: Exception) {
            return buildJsonObject { put("error", "Invalid executionTime format: $executionTimeStr. Expected 'yyyy-MM-dd HH:mm:ss' or relative time like '+1h'.") }
        }

        val agentChatId = settingsRepository.settingsFlow.value.ai?.agentChatId
            ?: return buildJsonObject { put("error", "Agent Chat ID is not configured. Please set it in settings first.") }

        val taskId = taskSchedulerService.get().createTask(instruction, executionTime, loopMode, agentChatId)
        return buildJsonObject {
            put("status", "success")
            put("taskId", taskId)
            put("message", "Task created successfully. Next execution at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(executionTime))}")
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
        val success = taskSchedulerService.get().cancelTask(taskId)
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
