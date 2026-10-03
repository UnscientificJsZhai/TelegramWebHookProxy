package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class NativeAgentHttpTimeoutTest {
    @Test
    fun `OpenAI HTTP transport configures nine minute call and read timeouts`() =
        withInitializedService(AIProvider.OPENAI) { _, service ->
            val transport = OpenAIAgentService::class.java.getDeclaredField("rawTransport")
                .apply { isAccessible = true }.get(service) as CancellableOkHttpTransport
            val client = CancellableOkHttpTransport::class.java.getDeclaredField("client")
                .apply { isAccessible = true }.get(transport) as OkHttpClient
            assertEquals(Duration.ofMinutes(9).toMillis().toInt(), client.callTimeoutMillis)
            assertEquals(Duration.ofMinutes(9).toMillis().toInt(), client.readTimeoutMillis)
        }

    @Test
    fun `Gemini HTTP transport configures nine minute call and read timeouts`() =
        withInitializedService(AIProvider.GEMINI) { _, service ->
            val transport = GeminiAgentService::class.java.getDeclaredField("rawTransport")
                .apply { isAccessible = true }.get(service) as CancellableOkHttpTransport
            val client = CancellableOkHttpTransport::class.java.getDeclaredField("client")
                .apply { isAccessible = true }.get(transport) as OkHttpClient
            assertEquals(Duration.ofMinutes(9).toMillis().toInt(), client.callTimeoutMillis)
            assertEquals(Duration.ofMinutes(9).toMillis().toInt(), client.readTimeoutMillis)
        }

    @Test
    fun `OpenAI request cancellation releases session and preserves uncommitted history`() =
        verifyGenerationCancellation(AIProvider.OPENAI)

    @Test
    fun `Gemini request cancellation releases session and preserves uncommitted history`() =
        verifyGenerationCancellation(AIProvider.GEMINI)

    private fun verifyGenerationCancellation(provider: AIProvider) =
        withInitializedService(provider) { server, service ->
            coroutineScope {
                server.enqueue(MockResponse.Builder().headersDelay(11, TimeUnit.SECONDS).body("{}").build())
                val pending = async { service.sendMessage("cancelled question") }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })

                withTimeout(2.seconds) { pending.cancelAndJoin() }

                val reply = when (provider) {
                    AIProvider.OPENAI ->
                        """{"id":"reply","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"healthy reply"}}]}"""

                    AIProvider.GEMINI ->
                        """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"healthy reply"}]}}]}"""
                }
                server.enqueue(MockResponse.Builder().setHeader("Content-Type", "application/json").body(reply).build())
                assertEquals("healthy reply", withTimeout(5.seconds) { service.sendMessage("healthy question") })
                val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                val body = Json.parseToJsonElement(assertNotNull(request.body).utf8())
                assertFalse(body.toString().contains("cancelled question"))
            }
        }

    private fun withInitializedService(
        provider: AIProvider,
        block: suspend (MockWebServer, AgentService) -> Unit,
    ) = runBlocking {
        val directory = createTempDirectory("native-agent-timeout-test").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        var service: AgentService? = null
        try {
            server.start()
            val settings =
                SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            settings.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = provider,
                        openAiApiKey = "test-key",
                        openAiBaseUrl = server.url("/v1").toString().trimEnd('/'),
                        geminiApiKey = "test-key",
                        selectedModel = "test-model",
                        agentEnabled = true,
                    ),
                ),
            )
            server.enqueue(
                MockResponse.Builder().setHeader("Content-Type", "application/json").body(
                    when (provider) {
                        AIProvider.OPENAI -> """{"data":[{"id":"test-model","object":"model","created":1,"owned_by":"test"}]}"""
                        AIProvider.GEMINI -> """{"models":[{"name":"models/test-model","supportedGenerationMethods":["generateContent"]}]}"""
                    },
                ).build(),
            )
            val skills = SkillRepository.forTesting(directory.resolve("skills.json"))
            val mcp = MCPClientService(scope)
            val candidate: AgentService = when (provider) {
                AIProvider.OPENAI -> OpenAIAgentService(scope, settings, skills, mcp, scheduledTaskService = mockk())
                AIProvider.GEMINI -> GeminiAgentService(
                    scope, settings, skills, mcp, AgentExecutionDeadlines(), mockk(),
                    baseUrlOverrideForTesting = server.url("/v1beta").toString().trimEnd('/'),
                )
            }
            service = candidate
            assertIs<AgentInitializationResult.Ready>(candidate.initializeForPublication())
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            block(server, candidate)
        } finally {
            service?.close()?.join()
            scope.coroutineContext[Job]?.cancelAndJoin()
            server.close()
            directory.deleteRecursively()
        }
    }
}
