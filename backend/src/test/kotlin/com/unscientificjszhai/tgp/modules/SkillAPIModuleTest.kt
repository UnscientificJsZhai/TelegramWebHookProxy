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
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
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

    private fun Application.configureSkillApi(appComponent: AppComponent) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(StatusPages) {
            exception<PayloadTooLargeException> { call, _ ->
                call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "payload too large"))
            }
        }
        skillAPIModule(appComponent)
    }
}
