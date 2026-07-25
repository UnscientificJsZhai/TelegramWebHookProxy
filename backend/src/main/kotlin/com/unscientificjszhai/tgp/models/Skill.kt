package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable
import kotlin.random.Random

/**
 * 可提供给 AI 代理使用的完整技能定义。
 *
 * @property id 技能唯一标识；默认值为正随机长整数字符串。
 * @property description 技能用途的简短描述。
 * @property content 技能的完整指令内容。
 */
@Serializable
data class Skill(
    val id: String = Random.nextLong(1, Long.MAX_VALUE).toString(),
    val description: String,
    val content: String
)

/**
 * 用于技能列表展示的摘要信息。
 *
 * @property id 技能唯一标识。
 * @property description 技能用途的简短描述。
 */
@Serializable
data class SkillBrief(
    val id: String,
    val description: String
)

/**
 * 分页查询结果。
 *
 * @param T 列表中元素的类型。
 * @property total 符合查询条件的元素总数；不得为负数。
 * @property items 当前页的元素列表；允许为空，元素顺序由生成该结果的查询方决定。
 */
@Serializable
data class PageResult<T>(
    val total: Int,
    val items: List<T>
)
