package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable
import kotlin.random.Random

@Serializable
data class Skill(
    val id: String = Random.nextLong(1, Long.MAX_VALUE).toString(),
    val description: String,
    val content: String
)

@Serializable
data class SkillBrief(
    val id: String,
    val description: String
)

@Serializable
data class PageResult<T>(
    val total: Int,
    val items: List<T>
)
