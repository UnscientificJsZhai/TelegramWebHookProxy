package com.unscientificjszhai.tgp.service.ai.function

import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.SkillStorageIsolationException
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

    /**
     * 验证函数执行边界在访问仓储前拒绝不安全的技能标识。
     */
    @Test
    fun `invalid skill ids return safe errors without accessing the repository`() = runTest {
        val readResult = provider.execute("read_skill", mapOf("id" to "safe?x=1"))
        val writeResult = provider.execute(
            "write_skill",
            mapOf("id" to "safe/path", "description" to "description", "content" to "content"),
        )

        assertEquals("Invalid skill id", readResult["error"]?.jsonPrimitive?.content)
        assertEquals("Invalid skill id", writeResult["error"]?.jsonPrimitive?.content)
        verify(exactly = 0) { skillRepository.getSkillById(any()) }
        verify(exactly = 0) { skillRepository.saveSkill(any()) }
    }

    /**
     * 验证显式提供的空值或非字符串标识不会被当作省略标识而创建新技能。
     */
    @Test
    fun `explicit null or non-string write id returns an error without saving`() = runTest {
        val nullIdResult = provider.execute(
            "write_skill",
            mapOf<String, Any?>("id" to null, "description" to "description", "content" to "content"),
        )
        val nonStringIdResult = provider.execute(
            "write_skill",
            mapOf("id" to 1, "description" to "description", "content" to "content"),
        )

        assertEquals("Invalid skill id", nullIdResult["error"]?.jsonPrimitive?.content)
        assertEquals("Invalid skill id", nonStringIdResult["error"]?.jsonPrimitive?.content)
        verify(exactly = 0) { skillRepository.saveSkill(any()) }
    }

    /**
     * 验证转换后的 OpenAI 函数声明仍保留技能标识的正则与长度约束。
     */
    @Test
    fun `OpenAI skill schemas retain id pattern and length constraints`() {
        val functions = provider.providedOpenAIFunctions.associateBy { it.name() }

        listOf("read_skill", "write_skill").forEach { functionName ->
            val parameters = checkNotNull(functions[functionName]).parameters().orElseThrow()._additionalProperties()

            @Suppress("UNCHECKED_CAST")
            val properties = parameters.getValue("properties").convert(Map::class.java) as Map<String, Any?>

            @Suppress("UNCHECKED_CAST")
            val idSchema = properties.getValue("id") as Map<String, Any?>

            assertEquals(SKILL_ID_PATTERN, idSchema["pattern"])
            assertEquals(1, (idSchema["minLength"] as Number).toInt())
            assertEquals(64, (idSchema["maxLength"] as Number).toInt())
        }
    }

    /**
     * 验证隔离的技能仓储不会使模型函数抛出异常或伪装成不存在的技能。
     */
    @Test
    fun `isolated skill storage returns safe function errors`() = runTest {
        every { skillRepository.getSkillById("safe") } throws SkillStorageIsolationException()
        every { skillRepository.saveSkill(any()) } throws SkillStorageIsolationException()

        val readResult = provider.execute("read_skill", mapOf("id" to "safe"))
        val writeResult = provider.execute(
            "write_skill",
            mapOf("id" to "safe", "description" to "description", "content" to "content"),
        )

        assertEquals("Skill storage is unavailable", readResult["error"]?.jsonPrimitive?.content)
        assertEquals("Skill storage is unavailable", writeResult["error"]?.jsonPrimitive?.content)
    }
}
