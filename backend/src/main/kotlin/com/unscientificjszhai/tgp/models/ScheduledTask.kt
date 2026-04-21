package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable

@Serializable
enum class LoopMode {
    ONCE, HOURLY, DAILY, WEEKLY
}

@Serializable
data class ScheduledTask(
    val id: String,
    val instruction: String,
    val executionTime: Long,
    val loopMode: LoopMode,
    val agentChatId: String
)
