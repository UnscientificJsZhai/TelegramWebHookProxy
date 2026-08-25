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
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class OpenAIInitializationRecoveryTest {
    @Test
    fun `mock upstream HTTP failure maps to safe common initialization failure`() = runBlocking {
        val directory = Files.createTempDirectory("openai-initialization-recovery").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(429)
                    .setHeader("Retry-After", "11")
                    .body("response-body-secret".padEnd(MAX_RAW_RESPONSE_BYTES + 1, 'x'))
                    .build(),
            )
            val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(
                File(directory, "settings.json"),
                ModelSwitchBarrier(),
            )
            val apiKey = "api-key-secret"
            val baseUrl = server.url("/v1").toString().trimEnd('/')
            settingsChangeCoordinator.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = apiKey,
                        openAiBaseUrl = baseUrl,
                        agentEnabled = true,
                    ),
                ),
            )
            val skillRepository = SkillRepository.forTesting(File(directory, "skills.json"))
            val service = OpenAIAgentService(
                scope,
                settingsChangeCoordinator,
                skillRepository,
                MCPClientService(scope),
                scheduledTaskService = mockk(),
            )

            val failed = assertIs<AgentInitializationResult.Failed>(service.initializeForPublication())

            assertEquals(AgentFailureKind.RATE_LIMITED, failed.failure.kind)
            assertEquals(RecoveryDisposition.RETRY, failed.failure.disposition)
            assertEquals(429, failed.failure.httpStatus)
            assertEquals(11.seconds, failed.failure.retryAfter)
            assertFalse(failed.toString().contains("response-body-secret"))
            assertFalse(failed.toString().contains(apiKey))
            assertFalse(failed.toString().contains(baseUrl))
            service.close().join()
        } finally {
            scope.coroutineContext[Job]?.cancel()
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `empty successful model response uses low frequency recovery`() = runBlocking {
        val directory = Files.createTempDirectory("openai-empty-model-recovery").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("{\"data\":[]}")
                    .build(),
            )
            val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(
                File(directory, "settings.json"),
                ModelSwitchBarrier(),
            )
            settingsChangeCoordinator.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test",
                        openAiBaseUrl = server.url("/v1").toString().trimEnd('/'),
                        agentEnabled = true,
                    ),
                ),
            )
            val service = OpenAIAgentService(
                scope,
                settingsChangeCoordinator,
                SkillRepository.forTesting(File(directory, "skills.json")),
                MCPClientService(scope),
                scheduledTaskService = mockk(),
            )

            val failed = assertIs<AgentInitializationResult.Failed>(service.initializeForPublication())

            assertEquals(AgentFailureKind.EMPTY_MODEL_LIST, failed.failure.kind)
            assertEquals(RecoveryDisposition.RETRY_LOW_FREQUENCY, failed.failure.disposition)
            service.close().join()
        } finally {
            scope.coroutineContext[Job]?.cancel()
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `blank model identifier is an invalid response and cannot publish`() = runBlocking {
        val directory = Files.createTempDirectory("openai-blank-model-recovery").toFile()
        val server = MockWebServer()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        try {
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body("""{"data":[{"id":"","object":"model","created":0,"owned_by":"test"}]}""")
                    .build(),
            )
            val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(
                File(directory, "settings.json"),
                ModelSwitchBarrier(),
            )
            settingsChangeCoordinator.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test",
                        openAiBaseUrl = server.url("/v1").toString().trimEnd('/'),
                        agentEnabled = true,
                    ),
                ),
            )
            val service = OpenAIAgentService(
                scope,
                settingsChangeCoordinator,
                SkillRepository.forTesting(File(directory, "skills.json")),
                MCPClientService(scope),
                scheduledTaskService = mockk(),
            )

            val failed = assertIs<AgentInitializationResult.Failed>(service.initializeForPublication())

            assertEquals(AgentFailureKind.INVALID_RESPONSE, failed.failure.kind)
            assertEquals(RecoveryDisposition.RETRY_LOW_FREQUENCY, failed.failure.disposition)
            service.close().join()
        } finally {
            scope.coroutineContext[Job]?.cancel()
            server.close()
            directory.deleteRecursively()
        }
    }
}
