package com.unscientificjszhai.tgp.modules

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

            testApplication {
                application { configureSkillApi(skillRepository) }

                // 1. 初始状态：获取空列表
                client.get("/api/skills").apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val received = Json.decodeFromString<PageResult<Skill>>(bodyAsText())
                    assertEquals(0, received.total)
                    assertEquals(0, received.items.size)
                }

                // 2. POST：新增两个技能
                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"description":"Skill 1","content":"Content 1"}""")
                }.apply { assertEquals(HttpStatusCode.OK, status) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"description":"Skill 2","content":"Content 2"}""")
                }.apply { assertEquals(HttpStatusCode.OK, status) }

                // 3. GET：验证列表包含两个技能
                val skills = client.get("/api/skills").apply {
                    assertEquals(HttpStatusCode.OK, status)
                    val body = bodyAsText()
                    val received = Json.decodeFromString<PageResult<Skill>>(body).items
                    assertEquals(2, received.size)
                    assertTrue(received.any { it.description == "Skill 1" })
                    assertTrue(received.any { it.description == "Skill 2" })
                    Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray.forEach { item ->
                        val rawSkill = item.jsonObject
                        assertEquals("PENDING", rawSkill["status"]?.jsonPrimitive?.content)
                        assertEquals(0L, rawSkill["revision"]?.jsonPrimitive?.content?.toLong())
                    }
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

            testApplication {
                application { configureSkillApi(skillRepository) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"description":"ok","content":"${"x".repeat(64 * 1024 + 1)}"}""")
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

            testApplication {
                application { configureSkillApi(skillRepository) }

                client.post("/api/skills") {
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    setBody("""{"description":"ok","content":"${"x".repeat(128 * 1024)}"}""")
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

            testApplication {
                application { configureSkillApi(skillRepository) }

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

            testApplication {
                application { configureSkillApi(skillRepository) }

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

            testApplication {
                application { configureSkillApi(skillRepository) }

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

            testApplication {
                application { configureSkillApi(skillRepository) }

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

            testApplication {
                application { configureSkillApi(skillRepository) }

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

    /** 验证管理 API 不能由请求体注入批准状态，且批准和编辑都受版本 CAS 保护。 */
    @Test
    fun `skill approval API ignores injected status and rejects stale edits`() {
        val temporaryDirectory = createTempDirectory("skill-api-approval-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))

            testApplication {
                application { configureSkillApi(skillRepository) }
                val created = client.post("/api/skills") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"description":"trusted","content":"safe","status":"APPROVED"}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    Json.decodeFromString<Skill>(response.bodyAsText())
                }
                assertEquals("PENDING", created.status.name)
                assertEquals(0, created.revision)

                val approved = client.post("/api/skills/${created.id}/approve") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"revision":0}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    Json.decodeFromString<Skill>(response.bodyAsText())
                }
                assertEquals("APPROVED", approved.status.name)
                assertEquals(1, approved.revision)

                client.post("/api/skills") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"${created.id}","description":"replaced","content":"PROMPT_INJECTION_CANARY","revision":0}""")
                }.apply { assertEquals(HttpStatusCode.Conflict, status) }
                assertEquals("safe", skillRepository.getSkillById(created.id)?.content)

                val edited = client.post("/api/skills") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"${created.id}","description":"edited","content":"changed","revision":1,"status":"APPROVED"}""")
                }.let { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    Json.decodeFromString<Skill>(response.bodyAsText())
                }
                assertEquals("PENDING", edited.status.name)
                assertEquals(2, edited.revision)
                assertTrue(skillRepository.getApprovedSkillSummaries().isEmpty())

                client.delete("/api/skills/${edited.id}").apply { assertEquals(HttpStatusCode.OK, status) }
                client.post("/api/skills") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"id":"${edited.id}","description":"resurrect","content":"resurrect","revision":2}""")
                }.apply { assertEquals(HttpStatusCode.NotFound, status) }
            }
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    /** 验证批准与撤销端点继承技能 API 的请求体上限，且不会改变草稿状态。 */
    @Test
    fun `approval endpoints reject oversized bodies without changing the draft`() {
        val temporaryDirectory = createTempDirectory("skill-api-transition-limit-test").toFile()
        try {
            val skillRepository = SkillRepository.forTesting(File(temporaryDirectory, "skills.json"))
            val pending = skillRepository.saveSkill(Skill(id = "pending", description = "pending", content = "pending"))
            val oversizedBody = """{"revision":0,"padding":"${"x".repeat(128 * 1024)}"}"""

            testApplication {
                application { configureSkillApi(skillRepository) }
                listOf("approve", "revoke").forEach { action ->
                    client.post("/api/skills/${pending.id}/$action") {
                        contentType(ContentType.Application.Json)
                        setBody(oversizedBody)
                    }.apply { assertEquals(HttpStatusCode.PayloadTooLarge, status) }
                }
            }

            assertEquals(pending, skillRepository.getSkillById(pending.id))
        } finally {
            temporaryDirectory.deleteRecursively()
        }
    }

    private fun Application.configureSkillApi(skillRepository: SkillRepository) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        installApiErrorPages()
        skillAPIModule(skillRepository)
    }
}
