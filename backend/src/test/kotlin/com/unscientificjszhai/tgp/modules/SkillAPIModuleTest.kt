package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.module
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.*

class SkillAPIModuleTest {

    private val skillsFile = File("config/skills.json")

    @BeforeTest
    fun setup() {
        if (skillsFile.exists()) {
            skillsFile.delete()
        }
    }

    @AfterTest
    fun teardown() {
        if (skillsFile.exists()) {
            skillsFile.delete()
        }
    }

    @Test
    fun testSkillsApi() = testApplication {
        application {
            module()
        }

        // 1. 初始状态：获取空列表
        client.get("/api/skills").apply {
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("[]", bodyAsText())
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
            val received = Json.decodeFromString<List<Skill>>(bodyAsText())
            assertEquals(2, received.size)
            assertTrue(received.any { it.description == "Skill 1" })
            assertTrue(received.any { it.description == "Skill 2" })
        }.let { Json.decodeFromString<List<Skill>>(it.bodyAsText()) }

        // 4. DELETE：删除第一个技能
        client.delete("/api/skills/${skills[0].id}").apply {
            assertEquals(HttpStatusCode.OK, status)
        }

        // 5. GET：验证只剩下一个技能
        client.get("/api/skills").apply {
            val received = Json.decodeFromString<List<Skill>>(bodyAsText())
            assertEquals(1, received.size)
            assertEquals(skills[1].description, received[0].description)
        }
    }
}
