package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillStatus
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.JsonStorageDurabilityUnknownException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

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

    /** 技能入口不把非法 UTF-8 或未知版本误当历史非法标识隔离，也不会改写原始字节。 */
    @Test
    fun `skills load preserves malformed UTF8 and future version without isolation`() {
        val cases = listOf(
            "malformed-utf8" to ("[{\"id\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\"}]".encodeToByteArray()),
            "future-version" to """{"schemaVersion":2,"data":[]}""".encodeToByteArray(),
        )

        cases.forEach { (name, original) ->
            val file = File(tempDirectory, "$name-skills.json")
            Files.write(file.toPath(), original)

            val failure = assertFailsWith<IllegalStateException> { SkillRepository.forTesting(file) }
            assertFalse(failure is SkillStorageIsolationException)
            assertEquals(original.toList(), Files.readAllBytes(file.toPath()).toList())
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


    @Test
    fun `skill collection validator rejects duplicate IDs`() {
        val candidate = listOf(
            Skill(
                id = "duplicate",
                description = "first approved",
                content = "first",
                status = SkillStatus.APPROVED,
                revision = 3,
            ),
            Skill(
                id = "duplicate",
                description = "second pending",
                content = "second",
                status = SkillStatus.PENDING,
                revision = 4,
            ),
        )

        assertFailsWith<SkillStorageIsolationException> { validateSkillCollection(candidate) }
    }

    @Test
    fun `pending drafts are model-isolated and approval uses compare and set`() {
        val managed = repository.saveSkill(Skill(id = "managed", description = "trusted", content = "trusted"))
        val approved = repository.approveSkill(managed.id, managed.revision)
        val draft = repository.createPendingDraft("untrusted", "PROMPT_INJECTION_CANARY")

        assertEquals(listOf("managed"), repository.getApprovedSkillSummaries().map { it.id })
        assertNull(repository.getApprovedSkillById(draft.id))
        assertEquals("trusted", repository.getSkillById(approved.id)?.content)
        assertFailsWith<SkillRevisionConflictException> {
            repository.approveSkill(draft.id, draft.revision + 1)
        }
        val approvedDraft = repository.approveSkill(draft.id, draft.revision)
        assertEquals(listOf("managed", draft.id), repository.getApprovedSkillSummaries().map { it.id })
        assertEquals(SkillStatus.APPROVED, approvedDraft.status)
    }

    /** 验证缺少或含未知审批状态的历史数据都按字段默认值保守降级为待审批。 */
    @Test
    fun `legacy skill status is fail closed`() {
        skillsFile.writeText("""[{"id":"legacy","description":"legacy","content":"LEGACY_CANARY"}]""")
        repository = SkillRepository.forTesting(skillsFile)

        assertEquals(SkillStatus.PENDING, repository.getSkillById("legacy")?.status)
        assertTrue(repository.getApprovedSkillSummaries().isEmpty())

        skillsFile.writeText("""[{"id":"unknown","description":"unknown","content":"UNKNOWN_CANARY","status":"BYPASS"}]""")
        repository = SkillRepository.forTesting(skillsFile)
        assertEquals(SkillStatus.PENDING, repository.getSkillById("unknown")?.status)
        assertTrue(repository.getApprovedSkillSummaries().isEmpty())
        assertTrue(skillsFile.readText().contains("UNKNOWN_CANARY"))
    }

    /** 验证技能元素缺少无默认值的必填字段时中断仓储构造并保留原文件。 */
    @Test
    fun `missing required skill field aborts construction`() {
        val original = """[{"id":"required","content":"content"}]"""
        skillsFile.writeText(original)

        assertFailsWith<IllegalStateException> { SkillRepository.forTesting(skillsFile) }
        assertEquals(original, skillsFile.readText())
    }

}
