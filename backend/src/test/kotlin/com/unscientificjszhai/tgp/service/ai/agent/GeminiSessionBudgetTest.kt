package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.MCPServerConfig
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.mockk.*
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class GeminiSessionBudgetTest {
    @Test
    fun `initialization rejects individually valid MCP pages whose combined config exceeds node budget`() =
        withFixture(toolCount = 12) { fixture ->
            val result = assertIs<AgentInitializationResult.Failed>(fixture.service.initializeForPublication())

            assertEquals(AgentFailureKind.CONFIGURATION, result.failure.kind)
            assertEquals(RecoveryDisposition.WAIT_FOR_CONFIGURATION, result.failure.disposition)
            assertEquals(3, fixture.discoveryPages.get())
            assertNull(rawSession(fixture.service))
            assertEquals(0, fixture.server.requestCount)
        }

    @Test
    fun `initialization rejects config that leaves insufficient nodes for the next turn`() =
        withFixture(toolCount = 8) { fixture ->
            val result = assertIs<AgentInitializationResult.Failed>(fixture.service.initializeForPublication())

            assertEquals(AgentFailureKind.CONFIGURATION, result.failure.kind)
            assertEquals(RecoveryDisposition.WAIT_FOR_CONFIGURATION, result.failure.disposition)
            assertEquals(2, fixture.discoveryPages.get())
            assertNull(rawSession(fixture.service))
        }

    @Test
    fun `failed empty history reset keeps the previous valid session and history usable`() =
        withFixture(toolCount = 6) { fixture ->
            fixture.server.enqueue(jsonResponse("""{"models":[{"name":"models/test-model","supportedGenerationMethods":["generateContent"]}]}"""))
            assertIs<AgentInitializationResult.Ready>(fixture.service.initializeForPublication())
            fixture.server.enqueue(replyResponse())
            assertEquals("reply", fixture.service.sendMessage("first question"))
            val previous = assertNotNull(rawSession(fixture.service))
            fixture.tools = tools(12)
            val oldSettings = fixture.settings.settingsFlow.value
            fixture.settings.replaceSettingsForTest(
                oldSettings.copy(
                    ai = assertNotNull(oldSettings.ai).copy(
                        mcpServers = listOf(MCPServerConfig("tools", "http://replacement.example/mcp")),
                    )
                ),
            )

            val reset = assertNotNull(fixture.service.resetSession())
            withTimeout(5.seconds) { reset.join() }

            assertTrue(reset.isCancelled)
            assertSame(previous, rawSession(fixture.service))
            fixture.server.enqueue(replyResponse())
            assertEquals("reply", fixture.service.sendMessage("second question"))
        }

    private fun rawSession(service: GeminiAgentService): Any? = GeminiAgentService::class.java
        .getDeclaredField("rawSession").apply { isAccessible = true }.get(service)

    private fun withFixture(toolCount: Int, block: suspend (Fixture) -> Unit) = runBlocking {
        val directory = createTempDirectory("gemini-session-budget-test").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val client = mockk<Client>()
        val pages = AtomicInteger()
        val settings = SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
        val mcp = MCPClientService(scope, clientFactory = { client })
        var service: GeminiAgentService? = null
        try {
            server.start()
            settings.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.GEMINI,
                        geminiApiKey = "test-key",
                        selectedModel = "models/test-model",
                        agentEnabled = true,
                        mcpServers = listOf(MCPServerConfig("tools", "http://tools.example/mcp")),
                    )
                )
            )
            val candidate = GeminiAgentService(
                scope, settings, SkillRepository.forTesting(directory.resolve("skills.json")), mcp,
                AgentExecutionDeadlines(), mockk(),
                baseUrlOverrideForTesting = server.url("/v1beta").toString().trimEnd('/'),
            ).also { service = it }
            val fixture = Fixture(candidate, settings, server, pages, tools(toolCount))
            coEvery { client.connect(any()) } just Runs
            coEvery { client.close() } just Runs
            coEvery { client.listTools(any()) } answers {
                val cursor = firstArg<io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest>().params?.cursor
                val start = cursor?.toInt() ?: 0
                val end = minOf(start + 4, fixture.tools.size)
                val result = ListToolsResult(
                    fixture.tools.subList(start, end),
                    nextCursor = if (end < fixture.tools.size) end.toString() else null,
                )
                JsonStructureLimits.validateElement(Json.encodeToJsonElement(result))
                pages.incrementAndGet()
                result
            }
            block(fixture)
        } finally {
            service?.close()?.join() ?: mcp.close().join()
            scope.coroutineContext[Job]?.cancelAndJoin()
            server.close()
            directory.deleteRecursively()
        }
    }

    private fun tools(count: Int): List<Tool> {
        val schema = ToolSchema(properties = buildJsonObject {
            repeat(100) { put("property_$it", buildJsonObject { put("type", "string") }) }
        })
        JsonStructureLimits.validateJsonString(schema.toString())
        return List(count) { Tool(name = "tool_$it", inputSchema = schema, description = "Test tool") }
    }

    private fun replyResponse() = jsonResponse(
        """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"reply"}]}}]}""",
    )

    private fun jsonResponse(body: String) = MockResponse.Builder()
        .setHeader("Content-Type", "application/json").body(body).build()

    private data class Fixture(
        val service: GeminiAgentService,
        val settings: SettingsChangeCoordinator,
        val server: MockWebServer,
        val discoveryPages: AtomicInteger,
        var tools: List<Tool>,
    )
}
