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

    private fun Application.configureSkillApi(appComponent: AppComponent) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        skillAPIModule(appComponent)
    }
}
