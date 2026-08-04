package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable
import kotlin.random.Random
import java.nio.charset.StandardCharsets

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
 * 校验技能字段及其 UTF-8 编码长度能否安全持久化和发送给模型。
 *
 * @param skill 要校验的完整技能。
 * @throws IllegalArgumentException 标识、描述或内容超过固定资源上限时抛出。
 */
fun validateSkill(skill: Skill) {
    require(skill.id.utf8Size() in 1..64) { "技能标识长度不合法。" }
    require(skill.description.utf8Size() <= 1024) { "技能描述不能超过 1024 字节。" }
    require(skill.content.utf8Size() <= 64 * 1024) { "技能内容不能超过 64 KiB。" }
}

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

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
