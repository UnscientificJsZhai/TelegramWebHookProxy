package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.repository.SkillRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class SkillFunctionProviderTest {

    private lateinit var skillRepository: SkillRepository
    private lateinit var provider: SkillFunctionProvider

    @BeforeTest
    fun setup() {
        skillRepository = mockk()
        provider = SkillFunctionProvider(skillRepository)
    }

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

    @Test
    fun testReadSkillNotFound() = runTest {
        every { skillRepository.getSkillById(any()) } returns null
        
        val args = mapOf("id" to "456")
        val result = provider.execute("read_skill", args)
        
        assertTrue(result.containsKey("error"))
        verify { skillRepository.getSkillById("456") }
    }

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
