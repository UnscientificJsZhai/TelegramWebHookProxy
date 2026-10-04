package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.models.AccessControlSettings
import com.unscientificjszhai.tgp.service.*
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import io.mockk.mockk
import kotlinx.serialization.json.*
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AccessControlModuleTest {
    @Test
    fun `restricts management and assets while send message remains reachable and headers cannot authorize`() =
        withApi { coordinator, _ ->
            coordinator.updateSettings { it.copy(accessControl = AccessControlSettings(true, listOf("192.0.2.1"))) }
            listOf(
                "/",
                "/assets/test.js",
                "/api/settings",
                "/api/access-control",
                "/api/send-message"
            ).forEach { path ->
                assertEquals(HttpStatusCode.Forbidden, client.get(path) {
                    header("X-Forwarded-For", "192.0.2.1"); header("Forwarded", "for=192.0.2.1")
                }.status, path)
            }
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/send-message") {
                contentType(ContentType.Application.Json); setBody("{}")
            }.status)
            coordinator.accessControlOverride.create()
            assertEquals(HttpStatusCode.OK, client.get("/api/settings").status)
            coordinator.accessControlOverride.delete()
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/access-control").status)
        }

    @Test
    fun `save and restore require confirmation and current revision with immediate enforcement`() =
        withApi { coordinator, _ ->
            val settings = client.get("/api/settings")
            val etag = settings.headers[HttpHeaders.ETag]!!
            val proposed = """{"enabled":true,"rules":[]}"""
            val preview =
                client.post("/api/access-control/check") { contentType(ContentType.Application.Json); setBody(proposed) }
            assertEquals(HttpStatusCode.OK, preview.status)
            assertTrue(Json.parseToJsonElement(preview.bodyAsText()).jsonObject.getValue("requiresConfirmation").jsonPrimitive.boolean)
            assertFalse(coordinator.currentSettingsSnapshot().settings.accessControl.enabled)
            val patch = """{"accessControl":$proposed}"""
            assertEquals(HttpStatusCode.Conflict, client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, etag); contentType(ContentType.Application.Json); setBody(patch)
            }.also { assertTrue(it.bodyAsText().contains("ACCESS_LOSS_CONFIRMATION_REQUIRED")) }.status)
            assertEquals(HttpStatusCode.OK, client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, etag); header(
                ACCESS_LOSS_HEADER,
                "true"
            ); contentType(ContentType.Application.Json); setBody(patch)
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/").status)
            coordinator.accessControlOverride.create()
            val currentEtag = client.get("/api/settings").headers[HttpHeaders.ETag]!!
            assertEquals(
                HttpStatusCode.PreconditionFailed,
                client.delete("/api/access-control/override") { header(HttpHeaders.IfMatch, etag) }.status
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client.delete("/api/access-control/override") { header(HttpHeaders.IfMatch, currentEtag) }.status
            )
            assertTrue(coordinator.accessControlOverride.isPresent())
            assertEquals(HttpStatusCode.OK, client.delete("/api/access-control/override") {
                header(HttpHeaders.IfMatch, currentEtag); header(ACCESS_LOSS_HEADER, "true")
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/settings").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/api/send-message") { contentType(ContentType.Application.Json); setBody("{}") }.status
            )
        }

    @Test
    fun `invalid rules do not save and override checks report settings after removal`() = withApi { coordinator, _ ->
        coordinator.accessControlOverride.create()
        val etag = client.get("/api/settings").headers[HttpHeaders.ETag]!!
        assertEquals(HttpStatusCode.BadRequest, client.patch("/api/settings") {
            header(HttpHeaders.IfMatch, etag); contentType(ContentType.Application.Json)
            setBody("""{"accessControl":{"enabled":false,"rules":["bad"]}}""")
        }.status)
        assertEquals(etag, client.get("/api/settings").headers[HttpHeaders.ETag])
        val preview = client.post("/api/access-control/check") {
            contentType(ContentType.Application.Json); setBody("""{"enabled":true,"rules":[]}""")
        }
        val result = Json.parseToJsonElement(preview.bodyAsText()).jsonObject
        assertTrue(result.getValue("allowedAfterSubmit").jsonPrimitive.boolean)
        assertFalse(result.getValue("allowedByProposedSettings").jsonPrimitive.boolean)
        assertFalse(result.getValue("requiresConfirmation").jsonPrimitive.boolean)
        assertEquals(
            HttpStatusCode.OK,
            client.delete("/api/access-control/override") { header(HttpHeaders.IfMatch, etag) }.status
        )
    }

    @Test
    fun `marker IO failures refuse management and failed deletion preserves override`() =
        withApi { coordinator, directory ->
            val etag = client.get("/api/settings").headers[HttpHeaders.ETag]!!
            val marker = directory.resolve("disable-access-control")
            marker.mkdir()
            marker.resolve("child").writeText("test")
            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                client.delete("/api/access-control/override") { header(HttpHeaders.IfMatch, etag) }.status
            )
            assertTrue(coordinator.accessControlOverride.isPresent())
            assertEquals(HttpStatusCode.OK, client.get("/api/settings").status)
            directory.deleteRecursively()
            directory.writeText("parent is not a directory")
            assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/settings").status)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.post("/api/send-message") { contentType(ContentType.Application.Json); setBody("{}") }.status
            )
        }

    private fun withApi(block: suspend ApplicationTestBuilder.(SettingsChangeCoordinator, File) -> Unit) {
        val directory = createTempDirectory().toFile()
        try {
            val coordinator =
                SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    installApiErrorPages()
                    installAccessControl(coordinator)
                    apiModule(coordinator, mockk(relaxed = true))
                    routing { get("/") { call.respondText("page") }; get("/assets/test.js") { call.respondText("asset") } }
                }
                block(coordinator, directory)
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
