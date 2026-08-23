package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DelegatingAgentRecoveryIntegrationTest {
    @Test
    fun `transient OpenAI startup failure recovers with a new component without settings resave`() = runBlocking {
        val directory = Files.createTempDirectory("delegating-agent-recovery").toFile()
        val server = MockWebServer()
        val parentJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parentJob)
        var delegating: DelegatingAgentService? = null
        try {
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(503)
                    .body("first-candidate-response-secret")
                    .build(),
            )
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"data":[{"id":"gpt-5.6-luna","object":"model","created":0,"owned_by":"test"}]}""",
                    )
                    .build(),
            )
            val barrier = ModelSwitchBarrier()
            val settingsChangeCoordinator =
                SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
            val skillRepository = SkillRepository.forTesting(File(directory, "skills.json"))
            settingsChangeCoordinator.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test-key",
                        openAiBaseUrl = server.url("/v1").toString().trimEnd('/'),
                        agentEnabled = true,
                    ),
                ),
            )
            val components = CopyOnWriteArrayList<TestAgentComponent>()
            val factory = object : AgentComponent.Factory {
                override fun create(): AgentComponent = TestAgentComponent(
                    scope,
                    settingsChangeCoordinator,
                    skillRepository,
                ).also(components::add)
            }
            delegating = DelegatingAgentService(
                factory,
                settingsChangeCoordinator,
                skillRepository,
                barrier,
                scope,
            )

            val retry = withTimeout(5.seconds) {
                delegating.availability.first { it.state == AgentAvailabilityState.RETRY_SCHEDULED }
            }
            assertEquals(AgentFailureKind.UPSTREAM_HTTP, retry.failure?.kind)
            val emptyReset = checkNotNull(delegating.resetSession())
            emptyReset.join()
            assertFalse(emptyReset.isCancelled)
            val ready = withTimeout(8.seconds) {
                delegating.availability.first { it.state == AgentAvailabilityState.READY }
            }

            assertEquals(2, ready.attempt)
            assertEquals(2, components.size)
            assertEquals(2, server.requestCount)
            assertTrue(delegating.isAiFeatureEnabled(settingsChangeCoordinator.settingsFlow.value.ai!!))
        } finally {
            delegating?.close()?.join()
            parentJob.cancelAndJoin()
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `disabling Agent waits for an admitted request before closing the published service`() = runBlocking {
        val directory = Files.createTempDirectory("delegating-agent-disable-drain").toFile()
        val server = MockWebServer()
        val parentJob = SupervisorJob(coroutineContext[Job])
        val scope = CoroutineScope(coroutineContext + parentJob)
        var delegating: DelegatingAgentService? = null
        try {
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .code(200)
                    .setHeader("Content-Type", "application/json")
                    .body(
                        """{"data":[{"id":"gpt-5.6-luna","object":"model","created":0,"owned_by":"test"}]}""",
                    )
                    .build(),
            )
            val barrier = ModelSwitchBarrier()
            val settingsChangeCoordinator =
                SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
            val skillRepository = SkillRepository.forTesting(File(directory, "skills.json"))
            settingsChangeCoordinator.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test-key",
                        openAiBaseUrl = server.url("/v1").toString().trimEnd('/'),
                        agentEnabled = true,
                    ),
                ),
            )
            val factory = object : AgentComponent.Factory {
                override fun create(): AgentComponent = TestAgentComponent(
                    scope,
                    settingsChangeCoordinator,
                    skillRepository,
                )
            }
            delegating = DelegatingAgentService(
                factory,
                settingsChangeCoordinator,
                skillRepository,
                barrier,
                scope,
            )
            withTimeout(5.seconds) {
                delegating.availability.first { it.state == AgentAvailabilityState.READY }
            }

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val inFlight = async {
                delegating.withReadyService { readyService ->
                    entered.complete(Unit)
                    release.await()
                    val reset = checkNotNull(readyService.resetSession())
                    reset.join()
                    !reset.isCancelled
                }
            }
            entered.await()

            settingsChangeCoordinator.replaceSettingsForTest(
                settingsChangeCoordinator.settingsFlow.value.copy(
                    ai = settingsChangeCoordinator.settingsFlow.value.ai!!.copy(agentEnabled = false),
                ),
            )
            withTimeout(5.seconds) {
                delegating.availability.first { it.state == AgentAvailabilityState.DISABLED }
            }
            assertTrue(barrier.isSwitching)

            release.complete(Unit)
            assertTrue(withTimeout(5.seconds) { inFlight.await() })
            withTimeout(5.seconds) {
                while (barrier.isSwitching) delay(10)
            }
            assertFalse(delegating.isAiFeatureEnabled(settingsChangeCoordinator.settingsFlow.value.ai!!))
        } finally {
            delegating?.close()?.join()
            parentJob.cancelAndJoin()
            server.close()
            directory.deleteRecursively()
        }
    }

    private class TestAgentComponent(
        scope: CoroutineScope,
        settingsChangeCoordinator: SettingsChangeCoordinator,
        skillRepository: SkillRepository,
    ) : AgentComponent {
        override val mcpClientService = MCPClientService(scope)
        override val geminiAgentService = GeminiAgentService(
            scope,
            settingsChangeCoordinator,
            skillRepository,
            mcpClientService,
            scheduledTaskService = mockk(),
        )
        override val openAIAgentService = OpenAIAgentService(
            scope,
            settingsChangeCoordinator,
            skillRepository,
            mcpClientService,
            scheduledTaskService = mockk(),
        )
    }
}
