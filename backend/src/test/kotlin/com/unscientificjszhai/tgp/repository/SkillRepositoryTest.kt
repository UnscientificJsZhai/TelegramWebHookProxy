package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * 技能仓储持久化与变更事件的测试设计。
 */
class SkillRepositoryTest {

    private lateinit var repository: SkillRepository
    private val tempDirectory = createTempDirectory("skill-repository-test").toFile()
    private val skillsFile = File(tempDirectory, "skills.json")

    @BeforeTest
    fun setup() {
        repository = SkillRepository.forTesting(skillsFile)
    }

    @AfterTest
    fun teardown() {
        tempDirectory.deleteRecursively()
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

    /**
     * 验证交错的完整读改写操作不会丢失技能。
     */
    @Test
    fun `concurrent saves retain every skill`() = runTest {
        (1..40).map { index ->
            async {
                repository.saveSkill(Skill(id = index.toString(), description = "skill-$index", content = "content-$index"))
            }
        }.forEach { it.await() }

        assertEquals(40, repository.getAllSkills(size = 50).total)
    }

    /**
     * 验证主替换失败时既不改变技能文件，也不发布变更事件。
     */
    @Test
    fun `failed skill persistence does not publish an event`() = runTest {
        val original = listOf(Skill(id = "old", description = "old", content = "old"))
        skillsFile.writeText(ConfigJson.encodeToString(original))
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == skillsFile.toPath()) {
                    throw IOException("injected primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
        repository = SkillRepository.forTesting(skillsFile, fileOperations)
        val events = mutableListOf<Unit>()
        val job = launch {
            repository.skillsUpdateEvent.collect { events += it }
        }
        yield()

        assertFailsWith<IOException> {
            repository.saveSkill(Skill(id = "new", description = "new", content = "new"))
        }
        delay(100.milliseconds)

        assertEquals(original, repository.getAllSkills(size = 10).items)
        assertTrue(events.isEmpty())
        job.cancel()
    }

    /**
     * 验证语义损坏的主文件会由有效备份原样恢复后供读取使用。
     */
    @Test
    fun `damaged skills primary recovers from backup`() {
        val original = listOf(Skill(id = "backup", description = "backup", content = "content"))
        val backupContent = ConfigJson.encodeToString(original)
        skillsFile.writeText("[ invalid")
        File(tempDirectory, "skills.json.bak").writeText(backupContent)
        repository = SkillRepository.forTesting(skillsFile)

        assertEquals(original, repository.getAllSkills(size = 10).items)
        assertEquals(backupContent, skillsFile.readText())
    }

    /**
     * 验证主技能文件缺失时，读取会恢复有效备份而不会把它当作空列表。
     */
    @Test
    fun `missing skills primary restores valid backup`() {
        val original = listOf(Skill(id = "backup", description = "backup", content = "content"))
        val backupContent = ConfigJson.encodeToString(original)
        File(tempDirectory, "skills.json.bak").writeText(backupContent)
        repository = SkillRepository.forTesting(skillsFile)

        assertEquals(original, repository.getAllSkills(size = 10).items)
        assertEquals(backupContent, skillsFile.readText())
        assertEquals(backupContent, File(tempDirectory, "skills.json.bak").readText())
    }

    /**
     * 验证技能备份有效但恢复主文件失败时，读返回安全空结果且写入不会覆盖任何现场文件。
     */
    @Test
    fun `failed skills recovery rejects mutations without touching primary or backup`() {
        val original = listOf(Skill(id = "backup", description = "backup", content = "content"))
        val validBackup = ConfigJson.encodeToString(original)
        val damagedPrimary = "[ invalid"
        skillsFile.writeText(damagedPrimary)
        val backupFile = File(tempDirectory, "skills.json.bak")
        backupFile.writeText(validBackup)
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == skillsFile.toPath()) {
                    throw IOException("injected recovery replacement failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
        repository = SkillRepository.forTesting(skillsFile, fileOperations)

        assertTrue(repository.getAllSkills(size = 10).items.isEmpty())
        assertFailsWith<IllegalStateException> {
            repository.saveSkill(Skill(id = "new", description = "new", content = "new"))
        }
        assertEquals(damagedPrimary, skillsFile.readText())
        assertEquals(validBackup, backupFile.readText())
    }

    /**
     * 验证主文件和备份均损坏时，读取提供安全空结果但所有后续写入都会拒绝并保留现场。
     */
    @Test
    fun `double damaged skills files are preserved and mutations are rejected`() {
        val damagedPrimary = "[ invalid"
        val damagedBackup = "{ invalid"
        skillsFile.writeText(damagedPrimary)
        File(tempDirectory, "skills.json.bak").writeText(damagedBackup)
        repository = SkillRepository.forTesting(skillsFile)

        assertTrue(repository.getAllSkills(size = 10).items.isEmpty())
        assertFailsWith<IllegalStateException> {
            repository.saveSkill(Skill(id = "new", description = "new", content = "new"))
        }
        assertFailsWith<IllegalStateException> { repository.deleteSkill("new") }
        assertEquals(damagedPrimary, skillsFile.readText())
        assertEquals(damagedBackup, File(tempDirectory, "skills.json.bak").readText())
    }
}
