package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.replaceSettingsForTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

class DelegatingAgentLifecycleGuardTest {
    @Test
    fun `same version skill rebuild rejects model operations on the retained service`() = runBlocking {
        val fixture = Fixture(this)
        val secondInitialization = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var delegating: DelegatingAgentService? = null
        try {
            fixture.installEnabledSettings()
            val created = AtomicInteger()
            val factory = fixture.factory { id ->
                mockedService(
                    id = id,
                    initialize = {
                        if (id == 2) {
                            secondStarted.complete(Unit)
                            secondInitialization.await()
                        }
                        AgentInitializationResult.Ready
                    },
                ).also { created.incrementAndGet() }
            }
            val service = fixture.delegating(factory)
            delegating = service
            val firstReady = withTimeout(5.seconds) {
                service.availability.first { it.state == AgentAvailabilityState.READY }
            }
            assertEquals("model-1", service.currentModel)

            val pendingSkill = fixture.skillRepository.saveSkill(
                Skill(
                    id = "approved-skill",
                    description = "approved",
                    content = "content",
                ),
            )
            fixture.skillRepository.approveSkill(pendingSkill.id, pendingSkill.revision)
            withTimeout(5.seconds) {
                service.availability.first {
                    it.sequence > firstReady.sequence && it.state == AgentAvailabilityState.INITIALIZING
                }
            }
            withTimeout(5.seconds) { secondStarted.await() }
            assertEquals(2, created.get())
            assertFailsWith<AgentConfigurationNotReadyException> { service.currentModel }
            assertFailsWith<AgentConfigurationNotReadyException> { service.availableModels }
            assertNull(service.switchModel("model-1"))
            assertIs<AgentConfigurationNotReadyException>(
                runCatching { service.updateModel() }.exceptionOrNull(),
            )

            secondInitialization.complete(Unit)
            withTimeout(5.seconds) {
                service.availability.first {
                    it.sequence > firstReady.sequence && it.state == AgentAvailabilityState.READY
                }
            }
            assertEquals("model-2", service.currentModel)
        } finally {
            secondInitialization.complete(Unit)
            delegating?.close()?.join()
            fixture.close()
        }
    }

    @Test
    fun `two unfinished retired closes pause creation of another candidate`() = runBlocking {
        val fixture = Fixture(this)
        var delegating: DelegatingAgentService? = null
        val retiredCloseGates = (1..3).associateWith { CompletableDeferred<Unit>() }
        try {
            fixture.installEnabledSettings()
            val created = AtomicInteger()
            val factory = fixture.factory { id ->
                created.incrementAndGet()
                mockedService(
                    id = id,
                    closeJob = retiredCloseGates[id] ?: completedJob(),
                )
            }
            val service = fixture.delegating(factory)
            delegating = service
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)

            fixture.updateGlobalContext("first rebuild")
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)
            fixture.updateGlobalContext("second rebuild")
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)
            assertEquals(3, created.get())

            fixture.updateGlobalContext("must wait for retirement capacity")
            val targetVersion = fixture.settingsRepository.currentSettingsSnapshot().generation
            withTimeout(5.seconds) {
                service.availability.first {
                    it.settingsVersion == targetVersion && it.state == AgentAvailabilityState.INITIALIZING
                }
            }
            delay(100)
            assertEquals(3, created.get())

            retiredCloseGates.getValue(1).complete(Unit)
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)
            assertEquals(4, created.get())

            retiredCloseGates.getValue(2).complete(Unit)
            retiredCloseGates.getValue(3).complete(Unit)
            Unit
        } finally {
            retiredCloseGates.values.forEach { it.complete(Unit) }
            delegating?.close()?.join()
            fixture.close()
        }
    }

    @Test
    fun `retired and failed candidates share the same two cleanup capacity`() = runBlocking {
        val fixture = Fixture(this)
        var delegating: DelegatingAgentService? = null
        val retiredClose = CompletableDeferred<Unit>()
        val failedClose = CompletableDeferred<Unit>()
        try {
            fixture.installEnabledSettings()
            val created = AtomicInteger()
            val factory = fixture.factory { id ->
                created.incrementAndGet()
                mockedService(
                    id = id,
                    initialize = {
                        if (id == 3) {
                            AgentInitializationResult.Failed(
                                AgentFailure(AgentFailureKind.NETWORK, RecoveryDisposition.RETRY),
                            )
                        } else {
                            AgentInitializationResult.Ready
                        }
                    },
                    closeJob = when (id) {
                        1 -> retiredClose
                        3 -> failedClose
                        else -> completedJob()
                    },
                )
            }
            val service = fixture.delegating(factory)
            delegating = service
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)

            fixture.updateGlobalContext("publish replacement")
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)
            fixture.updateGlobalContext("create failed candidate")
            withTimeout(5.seconds) {
                service.availability.first {
                    it.settingsVersion == fixture.settingsRepository.currentSettingsSnapshot().generation &&
                            it.state == AgentAvailabilityState.RETRY_SCHEDULED
                }
            }
            assertEquals(3, created.get())

            fixture.updateGlobalContext("must wait for shared cleanup capacity")
            val targetVersion = fixture.settingsRepository.currentSettingsSnapshot().generation
            withTimeout(5.seconds) {
                service.availability.first {
                    it.settingsVersion == targetVersion && it.state == AgentAvailabilityState.INITIALIZING
                }
            }
            delay(100)
            assertEquals(3, created.get())

            retiredClose.complete(Unit)
            awaitReadyForCurrentSettings(service, fixture.settingsRepository)
            assertEquals(4, created.get())
        } finally {
            retiredClose.complete(Unit)
            failedClose.complete(Unit)
            delegating?.close()?.join()
            fixture.close()
        }
    }

    private class Fixture(parentScope: CoroutineScope) {
        private val directory = Files.createTempDirectory("delegating-agent-lifecycle-guard").toFile()
        private val parentJob = SupervisorJob(parentScope.coroutineContext[Job])
        private val scope = CoroutineScope(parentScope.coroutineContext + parentJob)
        val barrier = ModelSwitchBarrier()
        val settingsRepository = SettingsRepository.forTesting(File(directory, "settings.json"), barrier)
        val skillRepository = SkillRepository.forTesting(File(directory, "skills.json"))

        fun installEnabledSettings() {
            settingsRepository.replaceSettingsForTest(
                AppSettings(
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test-key",
                        agentEnabled = true,
                    ),
                ),
            )
        }

        fun updateGlobalContext(value: String) {
            settingsRepository.replaceSettingsForTest(
                settingsRepository.settingsFlow.value.copy(
                    ai = settingsRepository.settingsFlow.value.ai!!.copy(globalContext = value),
                ),
            )
        }

        fun factory(service: (Int) -> OpenAIAgentService): AgentComponent.Factory {
            val nextId = AtomicInteger()
            return object : AgentComponent.Factory {
                override fun create(): AgentComponent {
                    val candidate = service(nextId.incrementAndGet())
                    return mockk<AgentComponent> {
                        every { openAIAgentService } returns candidate
                    }
                }
            }
        }

        fun delegating(factory: AgentComponent.Factory): DelegatingAgentService = DelegatingAgentService(
            factory,
            settingsRepository,
            skillRepository,
            barrier,
            scope,
        )

        suspend fun close() {
            parentJob.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private fun mockedService(
        id: Int,
        initialize: suspend () -> AgentInitializationResult = { AgentInitializationResult.Ready },
        closeJob: Job = completedJob(),
    ): OpenAIAgentService = mockk {
        coEvery { initializeForPublication() } coAnswers { initialize() }
        every { currentModel } returns "model-$id"
        every { availableModels } returns listOf("model-$id")
        every { isAiFeatureEnabled(any()) } returns true
        every { switchModel(any()) } returns completedJob()
        coEvery { updateModel() } returns ModelSnapshot("model-$id", listOf("model-$id"))
        every { resetSession() } returns completedJob()
        every { close() } returns closeJob
    }

    private suspend fun awaitReadyForCurrentSettings(
        delegating: DelegatingAgentService,
        settingsRepository: SettingsRepository,
    ) {
        val version = settingsRepository.currentSettingsSnapshot().generation
        withTimeout(5.seconds) {
            delegating.availability.first {
                it.settingsVersion == version && it.state == AgentAvailabilityState.READY
            }
        }
    }

    private companion object {
        fun completedJob(): Job = CompletableDeferred(Unit).also { it.complete(Unit) }
    }
}
