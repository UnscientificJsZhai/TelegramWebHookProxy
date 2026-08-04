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
     * 验证技能字段与分页参数在仓储边界即被拒绝，避免将不受控文本写入持久化文件。
     */
    @Test
    fun `resource bounds reject oversized skills and invalid page sizes`() {
        repository.saveSkill(Skill(id = "a", description = "ok", content = "ok"))
        repository.saveSkill(Skill(id = "a".repeat(64), description = "ok", content = "ok"))
        assertFailsWith<IllegalArgumentException> {
            repository.saveSkill(Skill(id = "x".repeat(65), description = "ok", content = "ok"))
        }
        listOf(
            "",
            "safe?x=1",
            "safe/path",
            "safe#fragment",
            "safe%value",
            "safe.value",
            "safe value",
            "技能"
        ).forEach { invalidId ->
            assertFailsWith<IllegalArgumentException> {
                repository.saveSkill(Skill(id = invalidId, description = "ok", content = "ok"))
            }
            assertFailsWith<IllegalArgumentException> { repository.getSkillById(invalidId) }
            assertFailsWith<IllegalArgumentException> { repository.deleteSkill(invalidId) }
        }
        assertFailsWith<IllegalArgumentException> {
            repository.saveSkill(Skill(id = "ok", description = "ok", content = "x".repeat(64 * 1024 + 1)))
        }
        assertFailsWith<IllegalArgumentException> { repository.getAllSkills(page = 0) }
        assertFailsWith<IllegalArgumentException> { repository.getAllSkills(size = 51) }
        assertEquals(2, repository.getAllSkills(size = 50).total)
        assertTrue(repository.getAllSkills(page = Int.MAX_VALUE, size = 50).items.isEmpty())
    }

    /**
     * 验证集合上限在写入前生效，避免写出读取时必然会拒绝的第 65 条技能。
     */
    @Test
    fun `skill collection limit preserves a readable store`() {
        repeat(64) { index ->
            repository.saveSkill(Skill(id = "skill-$index", description = "ok", content = "ok"))
        }

        assertFailsWith<IllegalArgumentException> {
            repository.saveSkill(Skill(id = "skill-64", description = "ok", content = "ok"))
        }
        assertEquals(64, repository.getAllSkills(size = 50).total)
        assertEquals(64, SkillRepository.forTesting(skillsFile).getAllSkills(size = 50).total)
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
                repository.saveSkill(
                    Skill(
                        id = index.toString(),
                        description = "skill-$index",
                        content = "content-$index"
                    )
                )
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
     * 验证语义损坏的主文件安全返回空结果，且不会访问遗留 `.bak` 文件。
     */
    @Test
    fun `damaged skills primary ignores legacy bak`() {
        val original = listOf(Skill(id = "backup", description = "backup", content = "content"))
        val backupContent = ConfigJson.encodeToString(original)
        skillsFile.writeText("[ invalid")
        File(tempDirectory, "skills.json.bak").writeText(backupContent)
        repository = SkillRepository.forTesting(skillsFile, rejectBakOperations())

        assertTrue(repository.getAllSkills(size = 10).items.isEmpty())
        assertEquals("[ invalid", skillsFile.readText())
        assertEquals(backupContent, File(tempDirectory, "skills.json.bak").readText())
    }

    /**
     * 验证主技能文件缺失时返回空列表，且不会访问遗留 `.bak` 文件。
     */
    @Test
    fun `missing skills primary ignores legacy bak`() {
        val original = listOf(Skill(id = "backup", description = "backup", content = "content"))
        val backupContent = ConfigJson.encodeToString(original)
        File(tempDirectory, "skills.json.bak").writeText(backupContent)
        repository = SkillRepository.forTesting(skillsFile, rejectBakOperations())

        assertTrue(repository.getAllSkills(size = 10).items.isEmpty())
        assertFalse(skillsFile.exists())
        assertEquals(backupContent, File(tempDirectory, "skills.json.bak").readText())
    }

    /**
     * 验证主文件损坏时写入被拒绝，且遗留 `.bak` 文件不会被访问。
     */
    @Test
    fun `damaged skills primary rejects mutations without touching legacy bak`() {
        val original = listOf(Skill(id = "backup", description = "backup", content = "content"))
        val validBackup = ConfigJson.encodeToString(original)
        val damagedPrimary = "[ invalid"
        skillsFile.writeText(damagedPrimary)
        val backupFile = File(tempDirectory, "skills.json.bak")
        backupFile.writeText(validBackup)
        repository = SkillRepository.forTesting(skillsFile, rejectBakOperations())

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

    /**
     * 验证可解析但标识非法的主文件会被隔离，不能借由合法备份恢复或被任意读写路径改写。
     */
    @Test
    fun `invalid skill id in primary isolates storage without recovery or events`() = runTest {
        val invalidPrimary = ConfigJson.encodeToString(
            listOf(Skill(id = "safe?x=1", description = "invalid", content = "invalid")),
        )
        val validBackup = ConfigJson.encodeToString(
            listOf(Skill(id = "backup", description = "backup", content = "backup")),
        )
        val backupFile = File(tempDirectory, "skills.json.bak")
        skillsFile.writeText(invalidPrimary)
        backupFile.writeText(validBackup)
        repository = SkillRepository.forTesting(skillsFile)
        val events = mutableListOf<Unit>()
        val job = launch { repository.skillsUpdateEvent.collect { events += it } }
        yield()

        assertFailsWith<SkillStorageIsolationException> { repository.getAllSkills(size = 10) }
        assertFailsWith<SkillStorageIsolationException> { repository.getSkillSummaries() }
        assertFailsWith<SkillStorageIsolationException> { repository.getSkillById("backup") }
        assertFailsWith<SkillStorageIsolationException> {
            repository.saveSkill(Skill(id = "new", description = "new", content = "new"))
        }
        assertFailsWith<SkillStorageIsolationException> { repository.deleteSkill("backup") }
        delay(100.milliseconds)

        assertEquals(invalidPrimary, skillsFile.readText())
        assertEquals(validBackup, backupFile.readText())
        assertTrue(events.isEmpty())
        job.cancel()
    }

    /**
     * 验证遗留 `.bak` 中的非法标识不会影响可读主文件，且文件不会被访问。
     */
    @Test
    fun `invalid skill id in legacy bak does not isolate readable primary`() {
        val validPrimary = ConfigJson.encodeToString(
            listOf(Skill(id = "safe", description = "safe", content = "safe")),
        )
        val invalidBackup = ConfigJson.encodeToString(
            listOf(Skill(id = "safe#old", description = "invalid", content = "invalid")),
        )
        val backupFile = File(tempDirectory, "skills.json.bak")
        skillsFile.writeText(validPrimary)
        backupFile.writeText(invalidBackup)
        repository = SkillRepository.forTesting(skillsFile, rejectBakOperations())

        assertEquals(
            listOf(Skill(id = "safe", description = "safe", content = "safe")),
            repository.getAllSkills(size = 10).items
        )
        repository.saveSkill(Skill(id = "new", description = "new", content = "new"))

        assertTrue(skillsFile.readText().contains("\"new\""))
        assertEquals(invalidBackup, backupFile.readText())
    }

    /**
     * 验证主文件缺失或语法损坏时，遗留 `.bak` 中的非法标识不会影响主文件行为。
     */
    @Test
    fun `missing or damaged primary ignores invalid skills legacy bak`() = runTest {
        listOf("missing" to null, "damaged" to "[ invalid").forEach { (name, primaryContent) ->
            val primaryFile = File(tempDirectory, "$name-skills.json")
            val backupFile = File(tempDirectory, "$name-skills.json.bak")
            val invalidBackup = ConfigJson.encodeToString(
                listOf(Skill(id = "safe?backup", description = "invalid", content = "invalid")),
            )
            primaryContent?.let(primaryFile::writeText)
            backupFile.writeText(invalidBackup)
            val isolatedRepository = SkillRepository.forTesting(primaryFile, rejectBakOperations())
            val events = mutableListOf<Unit>()
            val job = launch { isolatedRepository.skillsUpdateEvent.collect { events += it } }
            yield()

            assertTrue(isolatedRepository.getAllSkills(size = 10).items.isEmpty())
            if (primaryContent == null) {
                isolatedRepository.saveSkill(Skill(id = "new", description = "new", content = "new"))
                assertEquals("new", isolatedRepository.getSkillById("new")?.id)
            } else {
                assertFailsWith<IllegalStateException> {
                    isolatedRepository.saveSkill(Skill(id = "new", description = "new", content = "new"))
                }
            }
            delay(100.milliseconds)

            if (primaryContent != null) {
                assertEquals(primaryContent, primaryFile.readText())
            }
            assertEquals(invalidBackup, backupFile.readText())
            job.cancel()
        }
    }

    private fun rejectBakOperations(): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be read" }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }

            override fun writeAndForce(path: Path, bytes: ByteArray) {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be written" }
                DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
            }
        }
}
