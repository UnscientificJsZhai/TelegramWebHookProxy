package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

/**
 * 定时任务的重复执行模式。
 *
 * 调度器在副作用前原子预消费一次到期实例：错过多个周期时只执行一次并跳到下一次未来时刻，不追赶历史周期。
 * [HOURLY] 保持 Unix epoch 小时相位；[DAILY] 和 [WEEKLY] 保持服务器时区中的本地日历锚点，并由调度器按
 * DST gap 的首个有效时间及 overlap 的较早偏移量解析。
 */
@Serializable
enum class LoopMode {
    /** 仅执行一次；预消费持久化成功后立即删除，之后的失败或取消也不会重试。 */
    ONCE,

    /** 每小时重复执行，按 Unix epoch 的固定一小时相位跳过已错过的周期。 */
    HOURLY,

    /** 每天在服务器时区的相同本地时间重复执行，并跳过已错过的日期。 */
    DAILY,

    /** 每周在服务器时区的相同星期和本地时间重复执行，并跳过已错过的周。 */
    WEEKLY,
}

/**
 * 由代理创建并持久化的定时任务。
 *
 * @property id 任务标识；调度器据此查找、更新和取消任务。
 * @property instruction 到达执行时间后交给代理处理的指令；允许为空字符串并按原样保存。
 * @property executionTime 下次执行的 Unix 时间戳，单位为毫秒；到期实例会在任何 Agent 或 Telegram 副作用前
 * 原子预消费，单次任务删除、循环任务推进到一个严格未来时刻。
 * @property loopMode 任务预消费后的重复调度方式；错过周期只执行一次而不逐期补跑。
 * @property agentChatId 接收任务执行结果的 Telegram 聊天标识；允许为空字符串并按原样保存。
 * @property calendarAnchorTimeMillis 日/周循环任务创建时的服务器本地时刻，自当天 `00:00` 起的毫秒数，范围为
 * `0..86399999`。调度器用它跨 DST gap 保持原始本地日历锚点：gap 当次可延后至首个有效时刻，但后续周期会
 * 恢复该锚点。`null` 兼容旧 JSON，调度器会在首次预消费时从 [executionTime] 安全推导并持久化；非空但越界的
 * 旧数据会被调度器安全删除，避免错误执行或永久重试。非日/周任务应为 `null`。
 */
@Serializable
data class ScheduledTask(
    val id: String,
    val instruction: String,
    val executionTime: Long,
    val loopMode: LoopMode,
    val agentChatId: String,
    val calendarAnchorTimeMillis: Int? = null,
)
