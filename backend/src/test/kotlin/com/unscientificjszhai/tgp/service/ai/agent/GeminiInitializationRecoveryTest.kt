package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import io.mockk.mockk
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import javax.inject.Provider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class GeminiInitializationRecoveryTest {
    @Test
    fun `mock Gemini HTTP failure maps to safe common initialization failure`() = runBlocking {
        val directory = Files.createTempDirectory("gemini-initialization-recovery").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .body("gemini-response-body-secret".padEnd(MAX_RAW_RESPONSE_BYTES + 1, 'x'))
                    .build(),
            )
            val settingsRepository = SettingsRepository.forTesting(
                File(directory, "settings.json"),
                ModelSwitchBarrier(),
            )
            val apiKey = "gemini-api-key-secret"
            settingsRepository.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.GEMINI,
                        geminiApiKey = apiKey,
                        agentEnabled = true,
                    ),
                ),
            )
            val service = GeminiAgentService(
                parentScope = scope,
                settingsRepository = settingsRepository,
                skillRepository = SkillRepository.forTesting(File(directory, "skills.json")),
                mcpClientService = MCPClientService(scope),
                deadlines = AgentExecutionDeadlines(),
                taskSchedulerServiceProvider = Provider<TaskSchedulerService> { mockk() },
                baseUrlOverrideForTesting = server.url("/v1beta").toString().trimEnd('/'),
            )

            val failed = assertIs<AgentInitializationResult.Failed>(service.initializeForPublication())

            assertEquals(AgentFailureKind.UPSTREAM_HTTP, failed.failure.kind)
            assertEquals(RecoveryDisposition.RETRY, failed.failure.disposition)
            assertEquals(503, failed.failure.httpStatus)
            assertFalse(failed.toString().contains("gemini-response-body-secret"))
            assertFalse(failed.toString().contains(apiKey))
            service.close().join()
        } finally {
            scope.coroutineContext[Job]?.cancel()
            server.close()
            directory.deleteRecursively()
        }
    }
}
