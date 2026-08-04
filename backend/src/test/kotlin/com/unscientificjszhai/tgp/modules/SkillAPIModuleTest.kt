package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.repository.SkillRepository
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.*

/**
 * 技能 HTTP API 的测试设计。
 */
class SkillAPIModuleTest {
    /**
     * 验证技能 API 的增删查流程设计。
     *
     * 验证空列表、新增多个技能、读取列表及删除技能后的结果均正确。
     */
    @Test
    fun testSkillsApi() {
        val temporaryDirectory = createTempDirectory("skill-api-module-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                // 1. 初始状态：获取空列表
                client.get("/api/skills").apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val received = Json.decodeFromString<PageResult<Skill>>(bodyAsText())
                    assertEquals(0, received.total)
                    assertEquals(0, received.items.size)
                }

                val testSkill1 = Skill(description = "Skill 1", content = "Content 1")
                val testSkill2 = Skill(description = "Skill 2", content = "Content 2")

                // 2. POST：新增两个技能
                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(Json.encodeToString(testSkill1))
                }.apply { assertEquals(HttpStatusCode.OK, status) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(Json.encodeToString(testSkill2))
                }.apply { assertEquals(HttpStatusCode.OK, status) }

                // 3. GET：验证列表包含两个技能
                val skills = client.get("/api/skills").apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val received = Json.decodeFromString<PageResult<Skill>>(bodyAsText()).items
                    assertEquals(2, received.size)
                    assertTrue(received.any { it.description == "Skill 1" })
                    assertTrue(received.any { it.description == "Skill 2" })
                }.let { Json.decodeFromString<PageResult<Skill>>(it.bodyAsText()).items }

                // 4. DELETE：删除第一个技能
                client.delete("/api/skills/${skills[0].id}").apply {
                    assertEquals(HttpStatusCode.OK, status)
                }

                // 5. GET：验证只剩下一个技能
                client.get("/api/skills").apply {
                    val received = Json.decodeFromString<PageResult<Skill>>(bodyAsText()).items
                    assertEquals(1, received.size)
                    assertEquals(skills[1].description, received[0].description)
                }
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证小于请求体阈值但超过技能字段限制的 JSON 会作为客户端错误拒绝。
     */
    @Test
    fun `oversized skill field returns bad request instead of a server error`() {
        val temporaryDirectory = createTempDirectory("skill-api-resource-limit-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        Json.encodeToString(
                            Skill(
                                id = "oversized",
                                description = "ok",
                                content = "x".repeat(64 * 1024 + 1)
                            )
                        )
                    )
                }.apply {
                    assertEquals(HttpStatusCode.BadRequest, status)
                }
                assertEquals(0, skillRepository.getAllSkills(size = 50).total)
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证路由级请求体限制会在 JSON 解码和持久化前返回 413。
     */
    @Test
    fun `skill request body over the route limit returns payload too large`() {
        val temporaryDirectory = createTempDirectory("skill-api-body-limit-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        Json.encodeToString(
                            Skill(
                                id = "large-request",
                                description = "ok",
                                content = "x".repeat(128 * 1024)
                            )
                        )
                    )
                }.apply {
                    assertEquals(HttpStatusCode.PayloadTooLarge, status)
                }
                assertEquals(0, skillRepository.getAllSkills(size = 50).total)
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /** 验证损坏的技能 JSON 只得到不泄露序列化实现细节的固定结构 400。 */
    @Test
    fun `malformed skill JSON returns a safe bad request`() {
        val temporaryDirectory = createTempDirectory("skill-api-malformed-json-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                client.post("/api/skills") {
                    contentType(ContentType.Application.Json)
                    setBody("{\"id\":")
                }.apply {
                    assertEquals(HttpStatusCode.BadRequest, status)
                    val body = bodyAsText()
                    assertTrue(Json.parseToJsonElement(body).jsonObject.containsKey("error"))
                    assertFalse(body.contains("kotlinx"))
                }
            }
            assertEquals(0, skillRepository.getAllSkills(size = 50).total)
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证不安全的技能标识会在新增 API 的仓储边界被作为客户端错误拒绝。
     */
    @Test
    fun `invalid skill id returns bad request without persistence`() {
        val temporaryDirectory = createTempDirectory("skill-api-id-validation-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(
                        Json.encodeToString(
                            Skill(
                                id = "safe?legacy",
                                description = "invalid",
                                content = "invalid"
                            )
                        )
                    )
                }.apply {
                    assertEquals(HttpStatusCode.BadRequest, status)
                }
                assertEquals(0, skillRepository.getAllSkills(size = 10).total)
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证删除路由拒绝查询参数和非法路径标识，且不会删除已有技能。
     */
    @Test
    fun `delete rejects query parameters and invalid ids without deleting a skill`() {
        val temporaryDirectory = createTempDirectory("skill-api-delete-validation-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            skillRepository.saveSkill(Skill(id = "safe", description = "safe", content = "safe"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                client.delete("/api/skills/safe?x=1").apply {
                    assertEquals(HttpStatusCode.BadRequest, status)
                }
                client.delete("/api/skills/safe%3Fother").apply {
                    assertEquals(HttpStatusCode.BadRequest, status)
                }

                assertEquals(listOf("safe"), skillRepository.getAllSkills(size = 10).items.map(Skill::id))
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证隔离的技能仓储在读取、新增和删除 API 中都返回相同的安全服务不可用响应。
     */
    @Test
    fun `isolated skill storage returns the same safe response from every endpoint`() {
        val temporaryDirectory = createTempDirectory("skill-api-isolation-test").toFile()
        try {
            val skillsFile = File(temporaryDirectory, "skills.json")
            val invalidSkills = """[{"id":"safe?legacy","description":"invalid","content":"invalid"}]"""
            skillsFile.writeText(invalidSkills)
            val skillRepository = SkillRepository.forTesting(skillsFile)
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                val getResponse = client.get("/api/skills")
                val postResponse = client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody(Json.encodeToString(Skill(id = "safe", description = "safe", content = "safe")))
                }
                val deleteResponse = client.delete("/api/skills/safe")
                val responses = listOf(getResponse, postResponse, deleteResponse)

                assertTrue(responses.all { it.status == HttpStatusCode.ServiceUnavailable })
                assertEquals(1, responses.map { it.bodyAsText() }.toSet().size)
                assertEquals(invalidSkills, skillsFile.readText())
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /**
     * 验证分页参数只接受单个十进制范围内值，并且最大页码不会溢出为首页。
     */
    @Test
    fun `skills pagination rejects malformed values and handles maximum page without overflow`() {
        val temporaryDirectory = createTempDirectory("skill-api-pagination-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            skillRepository.saveSkill(Skill(id = "first", description = "first", content = "first"))
            skillRepository.saveSkill(Skill(id = "second", description = "second", content = "second"))
            val appComponent = mockk<AppComponent>()
            every { appComponent.skillRepository } returns skillRepository

            testApplication {
                application { configureSkillApi(appComponent) }

                client.get("/api/skills?page=2147483647&size=50").apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val page = Json.decodeFromString<PageResult<Skill>>(bodyAsText())
                    assertEquals(2, page.total)
                    assertTrue(page.items.isEmpty())
                }
                listOf(
                    "/api/skills?page=0",
                    "/api/skills?page=abc",
                    "/api/skills?page=",
                    "/api/skills?page=1&page=2",
                    "/api/skills?page=2147483648",
                    "/api/skills?size=0",
                    "/api/skills?size=abc",
                    "/api/skills?size=",
                    "/api/skills?size=1&size=2",
                    "/api/skills?size=51",
                ).forEach { url ->
                    client.get(url).apply {
                        assertEquals(HttpStatusCode.BadRequest, status)
                        assertTrue(Json.parseToJsonElement(bodyAsText()).jsonObject.containsKey("error"))
                    }
                }
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun Application.configureSkillApi(appComponent: AppComponent) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        installApiErrorPages()
        skillAPIModule(appComponent)
    }
}
