package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OpenAIStopResponseTest {
    @Test
    fun `STOP with empty null or omitted tool calls commits ordinary reply history`() =
        withInitializedService { server, service ->
            val previousReplies = mutableListOf<String>()
            for ((label, toolCalls) in listOf("empty" to "[]", "null" to "null", "omitted" to null)) {
                enqueueStopResponse(server, "reply-$label", toolCalls)
                assertEquals("reply-$label", service.sendMessage("question-$label"))
                val request = takeChatRequest(server)
                val assistantReplies = request.getValue("messages").jsonArray.map { it.jsonObject }
                    .filter { it["role"]?.jsonPrimitive?.content == "assistant" }
                    .map { it.getValue("content").jsonPrimitive.content }
                assertEquals(previousReplies, assistantReplies)
                previousReplies += "reply-$label"
            }
        }

    @Test
    fun `STOP with actual tool calls rejects reply and leaves history unchanged`() =
        withInitializedService { server, service ->
            val toolCalls =
                """[{"id":"call-1","type":"function","function":{"name":"must_not_run","arguments":"{}"}}]"""
            enqueueStopResponse(server, "unfinished-reply", toolCalls)
            assertFailsWith<AgentTurnFailedException> { service.sendMessage("failed-question") }
            takeChatRequest(server)

            enqueueStopResponse(server, "healthy-reply", null)
            assertEquals("healthy-reply", service.sendMessage("healthy-question"))
            val messages = takeChatRequest(server).getValue("messages").jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("healthy-question"), messages
                    .filter { it["role"]?.jsonPrimitive?.content == "user" }
                    .map { it.getValue("content").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content })
            assertEquals(emptyList(), messages.filter { it["role"]?.jsonPrimitive?.content == "assistant" })
        }

    private fun withInitializedService(block: suspend (MockWebServer, OpenAIAgentService) -> Unit) = runBlocking {
        val directory = Files.createTempDirectory("openai-stop-response-test").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        var service: OpenAIAgentService? = null
        try {
            server.start()
            server.enqueue(jsonResponse("""{"data":[{"id":"test-model","object":"model","created":1,"owned_by":"test"}]}"""))
            val settings = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), ModelSwitchBarrier())
            settings.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test-key",
                        openAiBaseUrl = server.url("/v1").toString().trimEnd('/'),
                        agentEnabled = true,
                    )
                ),
            )
            val candidate = OpenAIAgentService(
                scope,
                settings,
                SkillRepository.forTesting(File(directory, "skills.json")),
                MCPClientService(scope),
                scheduledTaskService = mockk(),
            ).also { service = it }
            assertIs<AgentInitializationResult.Ready>(candidate.initializeForPublication())
            assertEquals("/v1/models", assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)).url.encodedPath)
            block(server, candidate)
        } finally {
            service?.close()?.join()
            scope.coroutineContext[Job]?.cancelAndJoin()
            server.close()
            directory.deleteRecursively()
        }
    }

    private fun enqueueStopResponse(server: MockWebServer, content: String, toolCalls: String?) {
        val field = toolCalls?.let { ",\"tool_calls\":$it" }.orEmpty()
        server.enqueue(
            jsonResponse(
                """{"id":"reply","object":"chat.completion","created":1,"model":"test-model","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"$content"$field}}]}""",
            )
        )
    }

    private fun takeChatRequest(server: MockWebServer): JsonObject {
        val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/chat/completions", request.url.encodedPath)
        return Json.parseToJsonElement(assertNotNull(request.body).utf8()).jsonObject
    }

    private fun jsonResponse(body: String): MockResponse = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "application/json")
        .body(body)
        .build()
}
