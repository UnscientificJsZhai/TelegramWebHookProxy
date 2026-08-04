package com.unscientificjszhai.tgp.models

import kotlinx.serialization.Serializable
import kotlin.random.Random
import java.nio.charset.StandardCharsets

/** 技能标识允许的完整正则表达式。 */
const val SKILL_ID_PATTERN = "^[A-Za-z0-9_-]{1,64}$"

private val skillIdRegex = Regex(SKILL_ID_PATTERN)

/**
 * 判断技能标识是否可在模型参数、持久化文件和 HTTP 路径中安全使用。
 *
 * @param id 要检查的技能标识。
 * @return 当且仅当 [id] 由 `1..64` 个 ASCII 字母、数字、下划线或连字符组成时为 `true`。
 */
fun isValidSkillId(id: String): Boolean = skillIdRegex.matches(id)

/**
 * 可提供给 AI 代理使用的完整技能定义。
 *
 * @property id 技能唯一标识，必须匹配 [SKILL_ID_PATTERN]；默认值为正随机长整数字符串。
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
 * 校验技能字段能否安全持久化、发送给模型及用作 HTTP 路径标识。
 *
 * @param skill 要校验的完整技能。
 * @throws IllegalArgumentException 标识不匹配 [SKILL_ID_PATTERN]，或描述、内容超过固定资源上限时抛出。
 */
fun validateSkill(skill: Skill) {
    require(isValidSkillId(skill.id)) { "技能标识必须匹配 $SKILL_ID_PATTERN。" }
    require(skill.description.utf8Size() <= 1024) { "技能描述不能超过 1024 字节。" }
    require(skill.content.utf8Size() <= 64 * 1024) { "技能内容不能超过 64 KiB。" }
}

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

/**
 * 用于技能列表展示的摘要信息。
 *
 * @property id 技能唯一标识，必须匹配 [SKILL_ID_PATTERN]。
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
