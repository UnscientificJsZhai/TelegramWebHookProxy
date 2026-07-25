package com.unscientificjszhai.tgp.service.ai.function

import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.repository.SkillRepository
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

class SkillFunctionProvider(
    private val skillRepository: SkillRepository
) : LocalFunctionProvider() {

    private val logger = LoggerFactory.getLogger(this::class.java)

    override val providedFunctions: List<FunctionDeclaration> by lazy {
        val readSkillSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The ID of the skill to read.")
                })
            })
            put("required", buildJsonArray { add("id") })
        }.toString()

        val writeSkillSchemaJson = buildJsonObject {
            put("type", "OBJECT")
            put("properties", buildJsonObject {
                put("id", buildJsonObject {
                    put("type", "STRING")
                    put("description", "The ID of the skill to update. If null, a new skill will be created.")
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

    override suspend fun execute(functionName: String, args: Map<String, Any?>): JsonObject {
        logger.debug("Processing function {} {}", functionName, args)

        return when (functionName) {
            "read_skill" -> {
                val id = args["id"] as? String ?: return buildJsonObject { put("error", "Missing or invalid id") }
                val skill = skillRepository.getSkillById(id)
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
                val description =
                    args["description"] as? String ?: return buildJsonObject { put("error", "Missing description") }
                val content = args["content"] as? String ?: return buildJsonObject { put("error", "Missing content") }

                val skill = if (id != null) {
                    Skill(id = id, description = description, content = content)
                } else {
                    Skill(description = description, content = content)
                }

                skillRepository.saveSkill(skill)
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
