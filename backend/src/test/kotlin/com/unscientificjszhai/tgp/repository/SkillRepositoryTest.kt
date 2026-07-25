package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.Skill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

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

        val skills = repository.getAllSkills().items
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

        val skills = repository.getAllSkills().items
        assertEquals(1, skills.size)
        assertEquals("New Description", skills[0].description)
        assertEquals("Old Content", skills[0].content)
    }

    @Test
    fun testDeleteSkill() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)
        assertEquals(1, repository.getAllSkills().items.size)

        repository.deleteSkill(skill.id)
        assertEquals(0, repository.getAllSkills().items.size)
    }

    @Test
    fun testSkillsUpdateEvent() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch {
            repository.skillsUpdateEvent.collect {
                events.add(it)
            }
        }
        yield()

        val skill = Skill(description = "Event Test", content = "Content")
        repository.saveSkill(skill)

        // Wait a bit for the event to be processed
        delay(100.milliseconds)
        assertEquals(1, events.size)

        repository.deleteSkill(skill.id)
        delay(100.milliseconds)
        assertEquals(2, events.size)

        job.cancel()
    }
}
