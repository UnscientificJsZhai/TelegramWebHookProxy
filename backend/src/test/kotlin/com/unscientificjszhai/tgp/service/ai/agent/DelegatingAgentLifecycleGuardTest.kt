package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.replaceSettingsForTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    fun `retired cleanup completion lets an issued lifecycle target reach READY`() = runBlocking {
        val fixture = Fixture(this)
        var delegating: DelegatingAgentService? = null
        val retiredCloseGates = (1..3).associateWith { CompletableDeferred<Unit>() }
        val closeStarted = Channel<Int>(Channel.UNLIMITED)
        try {
            fixture.installEnabledSettings()
            val created = AtomicInteger()
            val factory = fixture.factory { id ->
                created.incrementAndGet()
                mockedService(
                    id = id,
                    closeJob = retiredCloseGates[id] ?: completedJob(),
                    onClose = { check(closeStarted.trySend(id).isSuccess) },
                )
            }
            val service = fixture.delegating(factory)
            delegating = service
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)

            fixture.updateGlobalContext("first rebuild")
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)
            assertEquals(1, withTimeout(5.seconds) { closeStarted.receive() })
            fixture.updateGlobalContext("second rebuild")
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)
            assertEquals(2, withTimeout(5.seconds) { closeStarted.receive() })
            assertEquals(3, created.get())

            fixture.updateGlobalContext("must wait for retirement capacity")
            val targetVersion = fixture.settingsChangeCoordinator.currentSettingsSnapshot().generation
            withTimeout(5.seconds) {
                service.availability.first {
                    it.settingsVersion == targetVersion && it.state == AgentAvailabilityState.INITIALIZING
                }
            }

            retiredCloseGates.getValue(1).complete(Unit)
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)
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
    fun `failed cleanup completion lets an issued target reach READY alongside a registered retirement`() = runBlocking {
        val fixture = Fixture(this)
        var delegating: DelegatingAgentService? = null
        val retiredClose = CompletableDeferred<Unit>()
        val failedClose = CompletableDeferred<Unit>()
        val closeStarted = Channel<Int>(Channel.UNLIMITED)
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
                    onClose = { check(closeStarted.trySend(id).isSuccess) },
                )
            }
            val service = fixture.delegating(factory)
            delegating = service
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)

            fixture.updateGlobalContext("publish replacement")
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)
            assertEquals(1, withTimeout(5.seconds) { closeStarted.receive() })
            fixture.updateGlobalContext("create failed candidate")
            withTimeout(5.seconds) {
                service.availability.first {
                    it.settingsVersion == fixture.settingsChangeCoordinator.currentSettingsSnapshot().generation &&
                            it.state == AgentAvailabilityState.RETRY_SCHEDULED
                }
            }
            assertEquals(3, withTimeout(5.seconds) { closeStarted.receive() })
            assertEquals(3, created.get())

            fixture.updateGlobalContext("must wait for shared cleanup capacity")
            val targetVersion = fixture.settingsChangeCoordinator.currentSettingsSnapshot().generation
            withTimeout(5.seconds) {
                service.availability.first {
                    it.settingsVersion == targetVersion && it.state == AgentAvailabilityState.INITIALIZING
                }
            }

            failedClose.complete(Unit)
            awaitReadyForCurrentSettings(service, fixture.settingsChangeCoordinator)
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
        val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
        val skillRepository = SkillRepository.forTesting(File(directory, "skills.json"))

        fun installEnabledSettings() {
            settingsChangeCoordinator.replaceSettingsForTest(
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
            settingsChangeCoordinator.replaceSettingsForTest(
                settingsChangeCoordinator.settingsFlow.value.copy(
                    ai = settingsChangeCoordinator.settingsFlow.value.ai!!.copy(globalContext = value),
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
            settingsChangeCoordinator,
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
        onClose: () -> Unit = {},
    ): OpenAIAgentService = mockk {
        coEvery { initializeForPublication() } coAnswers { initialize() }
        every { currentModel } returns "model-$id"
        every { availableModels } returns listOf("model-$id")
        every { isAiFeatureEnabled(any()) } returns true
        every { switchModel(any()) } returns completedJob()
        coEvery { updateModel() } returns ModelSnapshot("model-$id", listOf("model-$id"))
        every { resetSession() } returns completedJob()
        every { close() } answers {
            onClose()
            closeJob
        }
    }

    private suspend fun awaitReadyForCurrentSettings(
        delegating: DelegatingAgentService,
        settingsChangeCoordinator: SettingsChangeCoordinator,
    ) {
        val version = settingsChangeCoordinator.currentSettingsSnapshot().generation
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
