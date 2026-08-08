package com.unscientificjszhai.tgp.service.ai.function

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillStatus
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.SkillStorageIsolationException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.*

/** 验证模型技能函数只能读取已批准技能并创建待审批草稿。 */
class SkillFunctionProviderTest {
    private lateinit var skillRepository: SkillRepository
    private lateinit var provider: SkillFunctionProvider

    @BeforeTest
    fun setup() {
        skillRepository = mockk()
        provider = SkillFunctionProvider(skillRepository)
    }







    /** 验证 OpenAI 函数 schema 只允许 read_skill 带标识，write_skill 不暴露覆盖入口。 */
    @Test
    fun `OpenAI write schema omits id while read schema retains id constraints`() {
        val functions = provider.providedOpenAIFunctions.associateBy { it.name() }
        fun properties(name: String): Map<String, Any?> {
            val parameters = checkNotNull(functions[name]).parameters().orElseThrow()._additionalProperties()
            @Suppress("UNCHECKED_CAST")
            return parameters.getValue("properties").convert(Map::class.java) as Map<String, Any?>
        }

        val readProperties = properties("read_skill")

        @Suppress("UNCHECKED_CAST")
        val idSchema = readProperties.getValue("id") as Map<String, Any?>
        assertEquals(SKILL_ID_PATTERN, idSchema["pattern"])
        assertEquals(1, (idSchema["minLength"] as Number).toInt())
        assertFalse(properties("write_skill").containsKey("id"))
    }

}
