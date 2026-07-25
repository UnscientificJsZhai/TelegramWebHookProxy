package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 定时任务的重复执行模式。
 */
@Serializable
enum class LoopMode {
    /** 仅执行一次，完成后不再保留任务。 */
    ONCE,

    /** 每小时重复执行。 */
    HOURLY,

    /** 每天重复执行。 */
    DAILY,

    /** 每周重复执行。 */
    WEEKLY,
}

/**
 * 由代理创建并持久化的定时任务。
 *
 * @property id 任务标识；调度器据此查找、更新和取消任务。
 * @property instruction 到达执行时间后交给代理处理的指令；允许为空字符串并按原样保存。
 * @property executionTime 下次执行的 Unix 时间戳，单位为毫秒。
 * @property loopMode 任务执行后的重复调度方式。
 * @property agentChatId 接收任务执行结果的 Telegram 聊天标识；允许为空字符串并按原样保存。
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val instruction: String,
    val executionTime: Long,
    val loopMode: LoopMode,
    val agentChatId: String
)
