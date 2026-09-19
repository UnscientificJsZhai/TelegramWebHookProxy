package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.agent.AgentConfigurationNotReadyException
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelDiscoveryService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.mockk.coVerify
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Credentials
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds

class AIModelAPIModuleTest {
    @Test
    fun `disabled agent can discover models without changing settings and save using existing PATCH`() =
        withApi { server, settings ->
            server.enqueue(modelsResponse("model-a", "model-b", "model-a"))
            val before = settings.currentSettingsSnapshot()
            val response = client.get("/api/ai/models")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("OPENAI", json.getValue("provider").jsonPrimitive.content)
            assertEquals("removed-model", json.getValue("currentModel").jsonPrimitive.content)
            assertEquals(
                listOf("model-a", "model-b"),
                json.getValue("availableModels").jsonArray.map { it.jsonPrimitive.content })
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/gateway/v1/models", request.url.encodedPath)
            assertEquals("GET", request.method)
            assertEquals("Bearer test-key", request.headers["Authorization"])
            assertEquals(before, settings.currentSettingsSnapshot())

            val etag = client.get("/api/settings").headers[HttpHeaders.ETag]
            val saved = client.patch("/api/settings") {
                header(HttpHeaders.IfMatch, etag)
                contentType(ContentType.Application.Json)
                setBody("""{"ai":{"selectedModel":"model-b"}}""")
            }
            assertEquals(HttpStatusCode.OK, saved.status)
            assertEquals("model-b", settings.currentSettingsSnapshot().settings.ai?.selectedModel)
            assertNotEquals(etag, saved.headers[HttpHeaders.ETag])
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `missing configuration is rejected before upstream requests`() = withApi { server, settings ->
        for (ai in listOf(null, AISettings(), AISettings(provider = AIProvider.OPENAI))) {
            settings.replaceSettingsForTest(AppSettings(ai = ai))
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/ai/models").status)
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `model discovery uses saved HTTP proxy and its authentication`() = withApi { server, settings ->
        settings.replaceSettingsForTest(
            AppSettings(
                proxy = ProxySettings("127.0.0.1", server.port, ProxyType.HTTP, "proxy-user", "proxy-password"),
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-key",
                    openAiBaseUrl = "http://provider.example/v1"
                ),
            )
        )
        server.enqueue(MockResponse.Builder().code(407).setHeader("Proxy-Authenticate", "Basic realm=proxy").build())
        server.enqueue(modelsResponse("proxied-model"))
        assertEquals(HttpStatusCode.OK, client.get("/api/ai/models").status)
        val first = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val second = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertNull(first.headers["Proxy-Authorization"])
        assertEquals(Credentials.basic("proxy-user", "proxy-password"), second.headers["Proxy-Authorization"])
        assertEquals("Bearer test-key", second.headers["Authorization"])
        assertTrue(second.requestLine.contains("http://provider.example/v1/models"))
    }

    @Test
    fun `Gemini follows pagination and returns unique generateContent models`() = withApi { server, settings ->
        settings.replaceSettingsForTest(
            AppSettings(
                ai = AISettings(
                    geminiApiKey = "gemini-test-key",
                    selectedModel = "models/removed"
                )
            )
        )
        val before = settings.currentSettingsSnapshot()
        server.enqueue(
            MockResponse.Builder().body(
                """{
            "models":[
                {"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},
                {"name":"models/embedding","supportedGenerationMethods":["embedContent"]}
            ],"nextPageToken":"page-two"
        }"""
            ).build()
        )
        server.enqueue(
            MockResponse.Builder().body(
                """{"models":[
            {"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]},
            {"name":"models/gemini-b","supportedGenerationMethods":["generateContent"]}
        ]}"""
            ).build()
        )

        val response = client.get("/api/ai/models")
        assertEquals(HttpStatusCode.OK, response.status)
        val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("GEMINI", json.getValue("provider").jsonPrimitive.content)
        assertEquals("models/removed", json.getValue("currentModel").jsonPrimitive.content)
        assertEquals(
            listOf("models/gemini-a", "models/gemini-b"),
            json.getValue("availableModels").jsonArray.map { it.jsonPrimitive.content })
        val first = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val second = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1beta/models", first.url.encodedPath)
        assertEquals("gemini-test-key", first.url.queryParameter("key"))
        assertNull(first.url.queryParameter("pageToken"))
        assertEquals("page-two", second.url.queryParameter("pageToken"))
        assertEquals(before, settings.currentSettingsSnapshot())
    }

    @Test
    fun `empty model catalog is a successful response and preserves saved selection`() = withApi { server, settings ->
        val before = settings.currentSettingsSnapshot()
        server.enqueue(modelsResponse())
        val response = client.get("/api/ai/models")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            JsonArray(emptyList()),
            Json.parseToJsonElement(response.bodyAsText()).jsonObject["availableModels"]
        )
        assertEquals(before, settings.currentSettingsSnapshot())
    }

    @Test
    fun `model discovery follows gateway redirects like the Agent`() = withApi { server, _ ->
        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", server.url("/gateway/catalog")).build())
        server.enqueue(modelsResponse("redirected-model"))
        val response = client.get("/api/ai/models")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("redirected-model"))
        assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        val redirected = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/gateway/catalog", redirected.url.encodedPath)
        assertEquals("Bearer test-key", redirected.headers["Authorization"])
    }

    @Test
    fun `unsaved model selection reflects the running model without persisting it`() {
        val agent = mockk<AgentService>()
        every { agent.currentModel } returns "runtime-model"
        withApi(agentService = agent) { server, settings ->
            settings.updateSettings {
                it.copy(
                    ai = it.ai!!.copy(
                        selectedModel = "",
                        agentEnabled = true,
                        agentChatId = "42"
                    )
                )
            }
            val before = settings.currentSettingsSnapshot()
            server.enqueue(modelsResponse("runtime-model", "model-b"))
            val response = client.get("/api/ai/models")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "runtime-model",
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("currentModel").jsonPrimitive.content
            )
            assertEquals(before, settings.currentSettingsSnapshot())
            verify(exactly = 1) { agent.currentModel }
            coVerify(exactly = 0) { agent.updateModel() }
            verify(exactly = 0) { agent.resetSession() }
        }
    }

    @Test
    fun `saved selection takes precedence over a runtime model`() {
        val agent = mockk<AgentService>()
        every { agent.currentModel } returns "runtime-model"
        withApi(agentService = agent) { server, settings ->
            settings.updateSettings {
                it.copy(
                    ai = it.ai!!.copy(
                        selectedModel = "saved-model",
                        agentEnabled = true,
                        agentChatId = "42"
                    )
                )
            }
            server.enqueue(modelsResponse("saved-model", "runtime-model"))
            val response = client.get("/api/ai/models")
            assertEquals(
                "saved-model",
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("currentModel").jsonPrimitive.content
            )
            verify(exactly = 0) { agent.currentModel }
        }
    }

    @Test
    fun `unready Agent leaves the selection empty and does not block discovery`() {
        val agent = mockk<AgentService>()
        every { agent.currentModel } throws AgentConfigurationNotReadyException()
        withApi(agentService = agent) { server, settings ->
            settings.updateSettings {
                it.copy(
                    ai = it.ai!!.copy(
                        selectedModel = "",
                        agentEnabled = true,
                        agentChatId = "42"
                    )
                )
            }
            server.enqueue(modelsResponse("model-a"))
            val response = client.get("/api/ai/models")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(
                "",
                Json.parseToJsonElement(response.bodyAsText()).jsonObject.getValue("currentModel").jsonPrimitive.content
            )
        }
    }

    @Test
    fun `runtime selection from a newer configuration is not mixed into the catalog`() {
        val agent = mockk<AgentService>()
        withApi(agentService = agent) { server, settings ->
            settings.updateSettings {
                it.copy(
                    ai = it.ai!!.copy(
                        selectedModel = "",
                        agentEnabled = true,
                        agentChatId = "42"
                    )
                )
            }
            every { agent.currentModel } answers {
                settings.updateSettings {
                    it.copy(
                        ai = it.ai!!.copy(
                            provider = AIProvider.GEMINI,
                            geminiApiKey = "changed-key"
                        )
                    )
                }
                "models/new-provider-model"
            }
            server.enqueue(modelsResponse("model-a"))
            val response = client.get("/api/ai/models")
            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("OPENAI", json.getValue("provider").jsonPrimitive.content)
            assertEquals("", json.getValue("currentModel").jsonPrimitive.content)
        }
    }

    @Test
    fun `upstream failures and malformed catalogs return safe errors`() = withApi { server, settings ->
        val before = settings.currentSettingsSnapshot()
        for (upstream in listOf(
            MockResponse.Builder().code(401).body("secret-upstream-body").build(),
            MockResponse.Builder().code(503).body("secret-upstream-body").build(),
            MockResponse.Builder().body("""{"data":"secret-upstream-body"}""").build(),
            modelsResponse(" "),
        )) {
            server.enqueue(upstream)
            val response = client.get("/api/ai/models")
            assertEquals(HttpStatusCode.BadGateway, response.status)
            assertTrue(Json.parseToJsonElement(response.bodyAsText()).jsonObject.containsKey("error"))
            assertFalse(response.bodyAsText().contains("secret-upstream-body"))
            assertFalse(response.bodyAsText().contains("test-key"))
        }
        assertEquals(before, settings.currentSettingsSnapshot())
    }

    @Test
    fun `failed Gemini page does not return a partial model catalog`() = withApi { server, settings ->
        settings.replaceSettingsForTest(AppSettings(ai = AISettings(geminiApiKey = "gemini-test-key")))
        server.enqueue(
            MockResponse.Builder().body(
                """{"models":[
            {"name":"models/gemini-a","supportedGenerationMethods":["generateContent"]}
        ],"nextPageToken":"next"}"""
            ).build()
        )
        server.enqueue(MockResponse.Builder().code(503).build())
        val response = client.get("/api/ai/models")
        assertEquals(HttpStatusCode.BadGateway, response.status)
        assertFalse(response.bodyAsText().contains("models/gemini-a"))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `slow upstream times out and a later retry succeeds`() = withApi(timeoutMillis = 300) { server, settings ->
        val before = settings.currentSettingsSnapshot()
        server.enqueue(MockResponse.Builder().headersDelay(1, TimeUnit.SECONDS).body("""{"data":[]}""").build())
        assertEquals(HttpStatusCode.GatewayTimeout, client.get("/api/ai/models").status)
        server.enqueue(modelsResponse("retry-model"))
        assertEquals(HttpStatusCode.OK, client.get("/api/ai/models").status)
        assertEquals(before, settings.currentSettingsSnapshot())
    }

    private fun modelsResponse(vararg names: String) = MockResponse.Builder().body(buildJsonObject {
        put("data", JsonArray(names.map { name ->
            buildJsonObject {
                put("id", name)
                put("object", "model")
                put("created", 0)
                put("owned_by", "test")
            }
        }))
    }.toString()).build()

    private fun withApi(
        timeoutMillis: Int = 5000,
        agentService: AgentService? = null,
        block: suspend ApplicationTestBuilder.(MockWebServer, SettingsChangeCoordinator) -> Unit,
    ) {
        val directory = createTempDirectory("ai-model-api").toFile()
        val server = MockWebServer()
        try {
            server.start()
            val settings = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), ModelSwitchBarrier())
            settings.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test-key",
                        openAiBaseUrl = server.url("/gateway/v1").toString(),
                        selectedModel = "removed-model",
                        agentEnabled = false,
                    )
                )
            )
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    apiModule(settings, mockk<TelegramService>())
                    aiModelAPIModule(
                        settings,
                        ModelDiscoveryService(timeoutMillis.milliseconds, server.url("/").toString()),
                        agentService
                    )
                }
                block(server, settings)
            }
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }
}
