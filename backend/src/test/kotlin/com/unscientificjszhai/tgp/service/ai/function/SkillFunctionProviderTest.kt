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
import kotlinx.coroutines.test.runTest
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

    /** 验证读取函数只访问已批准查询接口。 */
    @Test
    fun `read skill only uses approved repository lookup`() = runTest {
        val skill =
            Skill(id = "123", description = "Test Skill", content = "Test Content", status = SkillStatus.APPROVED)
        every { skillRepository.getApprovedSkillById("123") } returns skill

        val result = provider.execute("read_skill", mapOf("id" to "123"))

        assertEquals("123", result["id"]?.jsonPrimitive?.content)
        assertEquals("Test Content", result["content"]?.jsonPrimitive?.content)
        verify { skillRepository.getApprovedSkillById("123") }
        verify(exactly = 0) { skillRepository.getSkillById(any()) }
    }

    /** 验证待审批技能对模型与不存在技能的响应完全一致。 */
    @Test
    fun `pending skill is not readable by the model`() = runTest {
        every { skillRepository.getApprovedSkillById("pending") } returns null

        val result = provider.execute("read_skill", mapOf("id" to "pending"))

        assertTrue(result.containsKey("error"))
        assertFalse(result.containsKey("content"))
        verify { skillRepository.getApprovedSkillById("pending") }
    }

    /** 验证模型写入只创建服务器标识的待审批草稿。 */
    @Test
    fun `write skill creates a pending draft without an id`() = runTest {
        val description = "New Skill"
        val content = "New Content"
        val draft =
            Skill(id = "server-draft", description = description, content = content, status = SkillStatus.PENDING)
        every { skillRepository.createPendingDraft(description, content) } returns draft

        val result = provider.execute("write_skill", mapOf("description" to description, "content" to content))

        assertEquals("pending_approval", result["status"]?.jsonPrimitive?.content)
        assertEquals("server-draft", result["id"]?.jsonPrimitive?.content)
        assertEquals("0", result["revision"]?.jsonPrimitive?.content)
        verify { skillRepository.createPendingDraft(description, content) }
        verify(exactly = 0) { skillRepository.saveSkill(any()) }
    }

    /** 验证模型无法通过额外字段覆盖、批准或为草稿指定管理员标识。 */
    @Test
    fun `write skill rejects id and approval fields before accessing the repository`() = runTest {
        listOf(
            mapOf("id" to "approved", "description" to "description", "content" to "content"),
            mapOf("approved" to true, "description" to "description", "content" to "content"),
            mapOf("revision" to 0, "description" to "description", "content" to "content"),
        ).forEach { args ->
            val result = provider.execute("write_skill", args)
            assertEquals("Unexpected draft field", result["error"]?.jsonPrimitive?.content)
        }
        verify(exactly = 0) { skillRepository.createPendingDraft(any(), any()) }
    }

    /** 验证读取参数仍在访问仓储前拒绝非法技能标识。 */
    @Test
    fun `invalid read id returns a safe error without repository access`() = runTest {
        val result = provider.execute("read_skill", mapOf("id" to "safe?x=1"))

        assertEquals("Invalid skill id", result["error"]?.jsonPrimitive?.content)
        verify(exactly = 0) { skillRepository.getApprovedSkillById(any()) }
    }

    /** 验证 DEBUG 日志只保留函数名称，绝不输出技能正文或参数映射。 */
    @Test
    fun `debug logging omits skill function arguments`() = runTest {
        val contentCanary = "SKILL_CONTENT_CANARY"
        every { skillRepository.createPendingDraft(any(), any()) } returns Skill(
            id = "draft",
            description = "description",
            content = contentCanary,
        )
        val logger = LoggerFactory.getLogger(SkillFunctionProvider::class.java) as Logger
        val previousLevel = logger.level
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            provider.execute("write_skill", mapOf("description" to "description", "content" to contentCanary))
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }

        assertTrue(appender.list.any { it.formattedMessage == "Processing skill function write_skill" })
        assertTrue(appender.list.none { it.formattedMessage.contains(contentCanary) })
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

    /** 验证隔离的技能仓储不会使模型函数抛出异常或伪装成可读取技能。 */
    @Test
    fun `isolated skill storage returns safe function errors`() = runTest {
        every { skillRepository.getApprovedSkillById("safe") } throws SkillStorageIsolationException()
        every { skillRepository.createPendingDraft(any(), any()) } throws SkillStorageIsolationException()

        val readResult = provider.execute("read_skill", mapOf("id" to "safe"))
        val writeResult = provider.execute("write_skill", mapOf("description" to "description", "content" to "content"))

        assertEquals("Skill storage is unavailable", readResult["error"]?.jsonPrimitive?.content)
        assertEquals("Skill storage is unavailable", writeResult["error"]?.jsonPrimitive?.content)
        assertNotNull(readResult["error"])
    }
}
