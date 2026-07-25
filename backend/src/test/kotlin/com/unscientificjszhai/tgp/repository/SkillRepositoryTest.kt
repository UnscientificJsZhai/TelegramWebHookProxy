package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.Skill
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * 技能仓储持久化与变更事件的测试设计。
 */
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

    /**
     * 验证保存和分页读取技能的设计。
     *
     * 验证保存后可读取到技能及正确的总数。
     */
    @Test
    fun testSaveAndGetAllSkills() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)

        val skills = repository.getAllSkills().items
        assertEquals(1, skills.size)
        assertEquals("Test Description", skills[0].description)
        assertEquals("Test Content", skills[0].content)
    }

    /**
     * 验证按标识查询技能的设计。
     *
     * 验证保存的技能可由其标识准确读取。
     */
    @Test
    fun testGetSkillById() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)

        val retrieved = repository.getSkillById(skill.id)
        assertNotNull(retrieved)
        assertEquals(skill.id, retrieved.id)
        assertEquals(skill.description, retrieved.description)
    }

    /**
     * 验证同标识技能的覆盖保存设计。
     *
     * 验证更新后读取到的是新内容且不会新增重复项。
     */
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

    /**
     * 验证删除技能的设计。
     *
     * 验证删除后列表不再包含目标技能。
     */
    @Test
    fun testDeleteSkill() {
        val skill = Skill(description = "Test Description", content = "Test Content")
        repository.saveSkill(skill)
        assertEquals(1, repository.getAllSkills().items.size)

        repository.deleteSkill(skill.id)
        assertEquals(0, repository.getAllSkills().items.size)
    }

    /**
     * 验证技能变更事件的发布设计。
     *
     * 验证保存技能后订阅者会收到变更事件。
     */
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

        // 等待订阅协程接收并记录变更事件。
        delay(100.milliseconds)
        assertEquals(1, events.size)

        repository.deleteSkill(skill.id)
        delay(100.milliseconds)
        assertEquals(2, events.size)

        job.cancel()
    }
}
