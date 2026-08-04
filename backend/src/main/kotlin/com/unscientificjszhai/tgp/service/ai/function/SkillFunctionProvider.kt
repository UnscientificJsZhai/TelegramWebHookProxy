package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN
import com.unscientificjszhai.tgp.models.isValidSkillId
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.SkillStorageIsolationException
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * 提供读取和保存技能内容的模型函数。
 *
 * 写入操作会直接持久化到 [SkillRepository]，因此调用方应仅传入允许保存的技能内容。
 *
 * @param skillRepository 用于读取和保存技能的仓库。
 */
class SkillFunctionProvider(
    private val skillRepository: SkillRepository
) : LocalFunctionProvider() {

    private val logger = LoggerFactory.getLogger(this::class.java)

    /**
     * 获取读取与保存技能的函数声明。
     *
     * @return 包含 `read_skill` 和 `write_skill` 的函数声明列表。
     */
    override val providedFunctions: List<FunctionDeclaration> by lazy {
        val readSkillSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The ID of the skill to read. It must match the configured skill ID pattern.")
                    put("pattern", SKILL_ID_PATTERN)
                    put("minLength", 1)
                    put("maxLength", 64)
                })
            })
            put("required", buildJsonArray { add("id") })
        }.toString()

        val writeSkillSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The ID of the skill to update. If omitted, a new skill will be created.")
                    put("pattern", SKILL_ID_PATTERN)
                    put("minLength", 1)
                    put("maxLength", 64)
                })
                put("description", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The description of the skill.")
                })
                put("content", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The detailed content of the skill.")
                })
            })
            put("required", buildJsonArray {
                add("description")
                add("content")
            })
        }.toString()

        listOf(
            FunctionDeclaration.builder()
                .name("read_skill")
                .description("Read the detailed content of a skill by its ID. Try to read skill before do anything.")
                .parameters(Schema.fromJson(readSkillSchemaJson))
                .build(),
            FunctionDeclaration.builder()
                .name("write_skill")
                .description("Create or update a skill. Use this to remember things or store knowledge.")
                .parameters(Schema.fromJson(writeSkillSchemaJson))
                .build()
        )
    }

    /**
     * 执行技能读取或保存函数。
     *
     * @param functionName 要执行的函数名称；非 [providedFunctions] 中声明的名称会得到 `error` 结果。
     * @param args 函数参数映射。读取要求匹配 [SKILL_ID_PATTERN] 的字符串 `id`；保存要求字符串
     * `description` 和 `content`，可选的 `id` 必须匹配该格式，用于更新既有技能。
     * @return 查询成功时包含技能字段、保存成功时包含 `status` 和 `id` 的 JSON 对象；参数无效、
     * 技能不存在、存储被隔离或函数不受支持时包含不泄露存储内容的 `error` 字段。
     */
    override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
        logger.debug("Processing function {} {}", functionName, args)

        return when (functionName) {
            "read_skill" -> {
                val id = args["id"] as? String ?: return buildJsonObject { put("error", "Missing or invalid id") }
                if (!isValidSkillId(id)) return buildJsonObject { put("error", "Invalid skill id") }
                val skill = try {
                    skillRepository.getSkillById(id)
                } catch (_: SkillStorageIsolationException) {
                    return buildJsonObject { put("error", "Skill storage is unavailable") }
                }
                if (skill != null) {
                    buildJsonObject {
                        put("id", skill.id)
                        put("description", skill.description)
                        put("content", skill.content)
                    }
                } else {
                    buildJsonObject {
                        put("error", "Skill with ID $id not found.")
                    }
                }
            }

            "write_skill" -> {
                val id = args["id"] as? String
                if (args.containsKey("id") && (id == null || !isValidSkillId(id))) {
                    return buildJsonObject { put("error", "Invalid skill id") }
                }
                val description =
                    args["description"] as? String ?: return buildJsonObject { put("error", "Missing description") }
                val content = args["content"] as? String ?: return buildJsonObject { put("error", "Missing content") }

                val skill = if (id != null) {
                    Skill(id = id, description = description, content = content)
                } else {
                    Skill(description = description, content = content)
                }

                try {
                    skillRepository.saveSkill(skill)
                } catch (_: IllegalArgumentException) {
                    return buildJsonObject { put("error", "Invalid skill") }
                } catch (_: SkillStorageIsolationException) {
                    return buildJsonObject { put("error", "Skill storage is unavailable") }
                }
                buildJsonObject {
                    put("status", "success")
                    put("id", skill.id)
                    put("message", "Skill saved successfully.")
                }
            }

            else -> buildJsonObject { put("error", "Unsupported function: $functionName") }
        }
    }
}
