package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.repository.SkillRepository
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

/**
 * 技能函数提供者读取和写入行为的测试设计。
 */
class SkillFunctionProviderTest {

    private lateinit var skillRepository: SkillRepository
    private lateinit var provider: SkillFunctionProvider

    @BeforeTest
    fun setup() {
        skillRepository = mockk()
        provider = SkillFunctionProvider(skillRepository)
    }

    /**
     * 验证读取已有技能的设计。
     *
     * 验证函数返回技能标识、描述和内容。
     */
    @Test
    fun testReadSkill() = runTest {
        val skill = Skill(id = "123", description = "Test Skill", content = "Test Content")
        every { skillRepository.getSkillById("123") } returns skill

        val args = mapOf("id" to "123")
        val result = provider.execute("read_skill", args)

        assertEquals("123", result["id"]?.jsonPrimitive?.content)
        assertEquals("Test Skill", result["description"]?.jsonPrimitive?.content)
        assertEquals("Test Content", result["content"]?.jsonPrimitive?.content)
        verify { skillRepository.getSkillById("123") }
    }

    /**
     * 验证读取不存在技能的错误处理设计。
     *
     * 验证函数返回错误结果并查询目标标识。
     */
    @Test
    fun testReadSkillNotFound() = runTest {
        every { skillRepository.getSkillById(any()) } returns null

        val args = mapOf("id" to "456")
        val result = provider.execute("read_skill", args)

        assertTrue(result.containsKey("error"))
        verify { skillRepository.getSkillById("456") }
    }

    /**
     * 验证新增技能的函数调用设计。
     *
     * 验证函数保存新技能并返回成功状态和生成标识。
     */
    @Test
    fun testWriteNewSkill() = runTest {
        val description = "New Skill"
        val content = "New Content"
        justRun { skillRepository.saveSkill(any()) }

        val args = mapOf("description" to description, "content" to content)
        val result = provider.execute("write_skill", args)

        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertNotNull(result["id"]?.jsonPrimitive?.content)
        verify { skillRepository.saveSkill(match { it.description == description && it.content == content }) }
    }

    /**
     * 验证更新技能的函数调用设计。
     *
     * 验证函数使用给定标识保存更新内容并返回该标识。
     */
    @Test
    fun testWriteUpdateSkill() = runTest {
        val id = "789"
        val description = "Updated Skill"
        val content = "Updated Content"
        justRun { skillRepository.saveSkill(any()) }

        val args = mapOf("id" to id, "description" to description, "content" to content)
        val result = provider.execute("write_skill", args)

        assertEquals("success", result["status"]?.jsonPrimitive?.content)
        assertEquals(id, result["id"]?.jsonPrimitive?.content)
        verify { skillRepository.saveSkill(match { it.id == id && it.description == description && it.content == content }) }
    }
}
