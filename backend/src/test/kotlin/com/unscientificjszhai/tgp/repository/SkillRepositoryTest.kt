package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.Skill
import java.io.File
import kotlin.test.*

class SkillRepositoryTest {

    private lateinit var repository: SkillRepository
    private val skillsFile = File("config/skills.json")

    @BeforeTest
    fun setup() {
        if (skillsFile.exists()) {
            skillsFile.delete()
        }
        repository = SkillRepository()
    }

    @AfterTest
    fun teardown() {
        if (skillsFile.exists()) {
            skillsFile.delete()
        }
    }

    @Test
    fun testSaveAndGetAllSkills() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)
        
        val skills = repository.getAllSkills()
        assertEquals(1, skills.size)
        assertEquals("Test Description", skills[0].description)
        assertEquals("Test Content", skills[0].content)
    }

    @Test
    fun testGetSkillById() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)
        
        val retrieved = repository.getSkillById(skill.id)
        assertNotNull(retrieved)
        assertEquals(skill.id, retrieved.id)
        assertEquals(skill.description, retrieved.description)
    }

    @Test
    fun testUpdateSkill() {
        val skill = Skill(description = "Old Description", content = "Old Content")
        repository.saveSkill(skill)
        
        val updatedSkill = skill.copy(description = "New Description")
        repository.saveSkill(updatedSkill)
        
        val skills = repository.getAllSkills()
        assertEquals(1, skills.size)
        assertEquals("New Description", skills[0].description)
        assertEquals("Old Content", skills[0].content)
    }

    @Test
    fun testDeleteSkill() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)
        assertEquals(1, repository.getAllSkills().size)
        
        repository.deleteSkill(skill.id)
        assertEquals(0, repository.getAllSkills().size)
    }
}
