package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.*
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class MessagePollerAgentRecoveryTest {
    @Test
    fun `model refresh uses one ready admission when settings switch starts inside it`() = runBlocking {
        val directory = Files.createTempDirectory("poller-model-single-admission").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val barrier = ModelSwitchBarrier()
        val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
        val skillRepository = SkillRepository.forTesting(File(directory, "skills.json"))
        val updatesRepository = UpdatesRepository(File(directory, "updates.json"))
        val token = "110:model-token"
        settingsChangeCoordinator.replaceSettingsForTest(
            AppSettings(
                telegramToken = token,
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-api-key",
                    agentEnabled = true,
                    agentChatId = "42",
                ),
            ),
        )
        updatesRepository.saveLastUpdateId("110", 100)

        val candidates = AtomicInteger()
        val refreshes = AtomicInteger()
        val factory = object : AgentComponent.Factory {
            override fun create(): AgentComponent {
                val id = candidates.incrementAndGet()
                val provider = mockk<OpenAIAgentService> {
                    coEvery { initializeForPublication() } returns AgentInitializationResult.Ready
                    every { currentModel } returns "model-$id"
                    every { availableModels } returns listOf("model-$id")
                    every { isAiFeatureEnabled(any()) } returns true
                    every { switchModel(any()) } returns null
                    coEvery { updateModel() } coAnswers {
                        refreshes.incrementAndGet()
                        ModelSnapshot("model-$id", listOf("model-$id"))
                    }
                    every { resetSession() } returns completedJob()
                    every { close() } returns completedJob()
                }
                return mockk {
                    every { openAIAgentService } returns provider
                }
            }
        }
        val delegating = DelegatingAgentService(
            factory,
            settingsChangeCoordinator,
            skillRepository,
            barrier,
            scope,
        )
        val telegram = mockk<TelegramService>()
        val update = Update(
            updateId = 101,
            message = Message(
                messageId = 5,
                chat = Chat(id = 42, type = "private"),
                text = "/model",
                from = User(id = 42, isBot = false, firstName = "user"),
            ),
        )
        coEvery { telegram.getUpdatesForToken(token, 101, 30) } returns
                GetUpdatesResponse(ok = true, result = listOf(update))
        coEvery { telegram.getUpdatesForToken(token, 102, 30) } returns
                GetUpdatesResponse(ok = true, result = emptyList())

        var poller: MessagePoller? = null
        try {
            withTimeout(5.seconds) {
                delegating.availability.first { it.state == AgentAvailabilityState.READY }
            }
            val switched = AtomicBoolean()
            poller = MessagePoller(
                parentScope = scope,
                telegramService = telegram,
                agentService = delegating,
                settingsChangeCoordinator = settingsChangeCoordinator,
                updatesRepository = updatesRepository,
                modelSwitchBarrier = barrier,
                processingTimeout = 5.seconds,
                retryDelay = {},
                retryJitter = { kotlin.time.Duration.ZERO },
            ).also { service ->
                service.beforeModelRefreshForTesting = {
                    if (switched.compareAndSet(false, true)) {
                        settingsChangeCoordinator.replaceSettingsForTest(
                            settingsChangeCoordinator.settingsFlow.value.copy(
                                ai = settingsChangeCoordinator.settingsFlow.value.ai!!.copy(
                                    globalContext = "switch during admitted refresh",
                                ),
                            ),
                        )
                    }
                }
                service.start()
            }

            eventually {
                updatesRepository.getData("110").lastUpdateId == 101L &&
                        delegating.availability.value.state == AgentAvailabilityState.READY &&
                        candidates.get() == 2
            }
            assertEquals(1, refreshes.get())
        } finally {
            poller?.closeAndJoin()
            delegating.close().join()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `zero offset bootstrap checkpoints its returned update while Agent is recovering`() = runBlocking {
        val directory = Files.createTempDirectory("poller-agent-bootstrap-recovery").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val barrier = ModelSwitchBarrier()
        val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
        val updatesRepository = UpdatesRepository(File(directory, "updates.json"))
        val token = "111:test-token"
        settingsChangeCoordinator.replaceSettingsForTest(
            AppSettings(
                telegramToken = token,
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-api-key",
                    agentEnabled = true,
                    agentChatId = "42",
                ),
            ),
        )
        barrier.completeThrough(barrier.latestPendingGeneration())

        val agent = RecoveringAgentService(settingsChangeCoordinator.currentSettingsSnapshot().generation)
        val telegram = mockk<TelegramService>()
        val bootstrapCalls = AtomicInteger()
        val regularCalls = AtomicInteger()
        val update = Update(
            updateId = 101,
            message = Message(
                messageId = 6,
                chat = Chat(id = 42, type = "private"),
                text = "do not discard bootstrap",
                from = User(id = 42, isBot = false, firstName = "user"),
            ),
        )
        coEvery { telegram.getUpdatesForToken(token, -1, 0) } coAnswers {
            bootstrapCalls.incrementAndGet()
            GetUpdatesResponse(ok = true, result = listOf(update))
        }
        coEvery { telegram.getUpdatesForToken(token, 101, 30) } coAnswers {
            regularCalls.incrementAndGet()
            GetUpdatesResponse(ok = true, result = listOf(update))
        }
        coEvery { telegram.sendChatActionForToken(token, "42", "typing") } returns
                TelegramApiResponse(HttpStatusCode.OK, "{\"ok\":true}")
        coEvery { telegram.sendMessageForToken(token, "42", any(), any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, "{\"ok\":true}")

        val poller = MessagePoller(
            parentScope = scope,
            telegramService = telegram,
            agentService = agent,
            settingsChangeCoordinator = settingsChangeCoordinator,
            updatesRepository = updatesRepository,
            modelSwitchBarrier = barrier,
            processingTimeout = 5.seconds,
            retryDelay = {},
            retryJitter = { kotlin.time.Duration.ZERO },
        )
        try {
            poller.start()
            eventually {
                val data = updatesRepository.getData("111")
                bootstrapCalls.get() == 1 &&
                        data.lastUpdateId == 0L &&
                        data.retryCheckpoint?.targetUpdateId == 101L
            }
            delay(100)
            assertEquals(0, regularCalls.get())
            assertEquals(0, agent.turns.get())

            agent.ready = true
            agent.transition(AgentAvailabilityState.READY, sequence = 2)
            eventually {
                val data = updatesRepository.getData("111")
                data.lastUpdateId == 101L && data.retryCheckpoint == null && agent.turns.get() == 1
            }

            assertEquals(1, bootstrapCalls.get())
            assertEquals(1, regularCalls.get())
            assertEquals(1, agent.turns.get())
        } finally {
            poller.closeAndJoin()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `AI recovery keeps checkpoint stops getUpdates and processes original update once`() = runBlocking {
        val directory = Files.createTempDirectory("poller-agent-recovery").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val barrier = ModelSwitchBarrier()
        val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
        val updatesRepository = UpdatesRepository(File(directory, "updates.json"))
        val token = "123:test-token"
        settingsChangeCoordinator.replaceSettingsForTest(
            AppSettings(
                telegramToken = token,
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-api-key",
                    agentEnabled = true,
                    agentChatId = "42",
                ),
            ),
        )
        barrier.completeThrough(barrier.latestPendingGeneration())
        updatesRepository.saveLastUpdateId("123", 100)

        val settingsVersion = settingsChangeCoordinator.currentSettingsSnapshot().generation
        val agent = RecoveringAgentService(settingsVersion)
        val telegram = mockk<TelegramService>()
        val getUpdatesCalls = AtomicInteger()
        val update = Update(
            updateId = 101,
            message = Message(
                messageId = 7,
                chat = Chat(id = 42, type = "private"),
                text = "recover me",
                from = User(id = 42, isBot = false, firstName = "user"),
            ),
        )
        coEvery { telegram.getUpdatesForToken(token, 101, 30) } coAnswers {
            getUpdatesCalls.incrementAndGet()
            GetUpdatesResponse(ok = true, result = listOf(update))
        }
        coEvery { telegram.sendChatActionForToken(token, "42", "typing") } returns
                TelegramApiResponse(HttpStatusCode.OK, "{\"ok\":true}")
        coEvery { telegram.sendMessageForToken(token, "42", any(), any()) } returns
                TelegramApiResponse(HttpStatusCode.OK, "{\"ok\":true}")

        val poller = MessagePoller(
            parentScope = scope,
            telegramService = telegram,
            agentService = agent,
            settingsChangeCoordinator = settingsChangeCoordinator,
            updatesRepository = updatesRepository,
            modelSwitchBarrier = barrier,
            processingTimeout = 5.seconds,
            retryDelay = {},
            retryJitter = { kotlin.time.Duration.ZERO },
        )
        try {
            poller.start()
            eventually {
                getUpdatesCalls.get() == 1 &&
                        updatesRepository.getData("123").retryCheckpoint?.targetUpdateId == 101L
            }
            assertNotNull(updatesRepository.getData("123").retryCheckpoint)

            agent.transition(AgentAvailabilityState.RETRY_SCHEDULED, sequence = 2)
            delay(100)
            agent.transition(AgentAvailabilityState.INITIALIZING, sequence = 3)
            delay(100)
            assertEquals(1, getUpdatesCalls.get())
            assertEquals(0, agent.turns.get())

            agent.ready = true
            agent.transition(AgentAvailabilityState.READY, sequence = 4)
            eventually {
                val data = updatesRepository.getData("123")
                data.lastUpdateId == 101L && data.retryCheckpoint == null && agent.turns.get() == 1
            }

            assertEquals(1, agent.turns.get())
            assertNull(updatesRepository.getData("123").retryCheckpoint)
        } finally {
            poller.closeAndJoin()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `authorization change wakes Agent wait and rejudges the original update`() = runBlocking {
        val directory = Files.createTempDirectory("poller-agent-authorization-change").toFile()
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val barrier = ModelSwitchBarrier()
        val settingsChangeCoordinator = SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
        val updatesRepository = UpdatesRepository(File(directory, "updates.json"))
        val token = "456:test-token"
        settingsChangeCoordinator.replaceSettingsForTest(
            AppSettings(
                telegramToken = token,
                ai = AISettings(
                    provider = AIProvider.OPENAI,
                    openAiApiKey = "test-api-key",
                    agentEnabled = true,
                    agentChatId = "42",
                ),
            ),
        )
        barrier.completeThrough(barrier.latestPendingGeneration())
        updatesRepository.saveLastUpdateId("456", 100)
        val agent = RecoveringAgentService(settingsChangeCoordinator.currentSettingsSnapshot().generation)
        val telegram = mockk<TelegramService>()
        val getUpdatesCalls = AtomicInteger()
        val update = Update(
            updateId = 101,
            message = Message(
                messageId = 8,
                chat = Chat(id = 42, type = "private"),
                text = "must be reauthorized",
                from = User(id = 42, isBot = false, firstName = "user"),
            ),
        )
        coEvery { telegram.getUpdatesForToken(token, 101, 30) } coAnswers {
            getUpdatesCalls.incrementAndGet()
            GetUpdatesResponse(ok = true, result = listOf(update))
        }
        val poller = MessagePoller(
            parentScope = scope,
            telegramService = telegram,
            agentService = agent,
            settingsChangeCoordinator = settingsChangeCoordinator,
            updatesRepository = updatesRepository,
            modelSwitchBarrier = barrier,
            processingTimeout = 5.seconds,
            retryDelay = {},
            retryJitter = { kotlin.time.Duration.ZERO },
        )
        try {
            poller.start()
            eventually {
                getUpdatesCalls.get() == 1 &&
                        updatesRepository.getData("456").retryCheckpoint?.targetUpdateId == 101L
            }

            settingsChangeCoordinator.replaceSettingsForTest(
                settingsChangeCoordinator.settingsFlow.value.copy(
                    ai = settingsChangeCoordinator.settingsFlow.value.ai!!.copy(agentChatId = "99"),
                ),
            )
            barrier.completeThrough(barrier.latestPendingGeneration())
            agent.transition(AgentAvailabilityState.INITIALIZING, sequence = 2)

            eventually {
                val data = updatesRepository.getData("456")
                data.lastUpdateId == 101L && data.retryCheckpoint == null
            }
            assertEquals(0, agent.turns.get())
            assertEquals(2, getUpdatesCalls.get())
        } finally {
            poller.closeAndJoin()
            scope.cancel()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `token change cancels Agent wait and installs the new polling session without a published Agent`() =
        runBlocking {
            val directory = Files.createTempDirectory("poller-agent-token-change").toFile()
            val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            val barrier = ModelSwitchBarrier()
            val settingsChangeCoordinator =
                SettingsChangeCoordinator.forTesting(File(directory, "settings.json"), barrier)
            val updatesRepository = UpdatesRepository(File(directory, "updates.json"))
            val oldToken = "789:old-token"
            val newToken = "789:new-token"
            settingsChangeCoordinator.replaceSettingsForTest(
                AppSettings(
                    telegramToken = oldToken,
                    ai = AISettings(
                        provider = AIProvider.OPENAI,
                        openAiApiKey = "test-api-key",
                        agentEnabled = true,
                        agentChatId = "42",
                    ),
                ),
            )
            barrier.completeThrough(barrier.latestPendingGeneration())
            updatesRepository.saveLastUpdateId("789", 100)

            val agent = RecoveringAgentService(settingsChangeCoordinator.currentSettingsSnapshot().generation)
            val telegram = mockk<TelegramService>()
            val oldTokenCalls = AtomicInteger()
            val newTokenCalls = AtomicInteger()
            val update = Update(
                updateId = 101,
                message = Message(
                    messageId = 9,
                    chat = Chat(id = 42, type = "private"),
                    text = "survive token rotation",
                    from = User(id = 42, isBot = false, firstName = "user"),
                ),
            )
            coEvery { telegram.getUpdatesForToken(oldToken, 101, 30) } coAnswers {
                oldTokenCalls.incrementAndGet()
                GetUpdatesResponse(ok = true, result = listOf(update))
            }
            coEvery { telegram.getUpdatesForToken(newToken, 101, 30) } coAnswers {
                newTokenCalls.incrementAndGet()
                GetUpdatesResponse(ok = true, result = listOf(update))
            }
            coEvery { telegram.sendChatActionForToken(newToken, "42", "typing") } returns
                    TelegramApiResponse(HttpStatusCode.OK, "{\"ok\":true}")
            coEvery { telegram.sendMessageForToken(newToken, "42", any(), any()) } returns
                    TelegramApiResponse(HttpStatusCode.OK, "{\"ok\":true}")

            val poller = MessagePoller(
                parentScope = scope,
                telegramService = telegram,
                agentService = agent,
                settingsChangeCoordinator = settingsChangeCoordinator,
                updatesRepository = updatesRepository,
                modelSwitchBarrier = barrier,
                processingTimeout = 5.seconds,
                retryDelay = {},
                retryJitter = { kotlin.time.Duration.ZERO },
            )
            try {
                poller.start()
                eventually {
                    oldTokenCalls.get() == 1 &&
                            updatesRepository.getData("789").retryCheckpoint?.targetUpdateId == 101L
                }

                settingsChangeCoordinator.replaceSettingsForTest(
                    settingsChangeCoordinator.settingsFlow.value.copy(telegramToken = newToken),
                )
                barrier.completeThrough(barrier.latestPendingSettingsGeneration())

                eventually { newTokenCalls.get() == 1 }
                assertEquals(1, oldTokenCalls.get())
                assertEquals(0, agent.turns.get())

                agent.ready = true
                agent.transition(AgentAvailabilityState.READY, sequence = 2)
                eventually {
                    val data = updatesRepository.getData("789")
                    data.lastUpdateId == 101L && data.retryCheckpoint == null && agent.turns.get() == 1
                }
                assertEquals(1, agent.turns.get())
            } finally {
                poller.closeAndJoin()
                scope.cancel()
                directory.deleteRecursively()
            }
        }

    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(5.seconds) {
            while (!predicate()) delay(10)
        }
    }

    private fun completedJob(): Job = CompletableDeferred(Unit).also { it.complete(Unit) }

    private class RecoveringAgentService(settingsVersion: Long) : AgentService() {
        private val mutableAvailability = MutableStateFlow(
            AgentAvailabilitySnapshot(
                state = AgentAvailabilityState.INITIALIZING,
                sequence = 1,
                settingsVersion = settingsVersion,
                provider = AIProvider.OPENAI,
                attempt = 1,
            ),
        )
        override val availability: StateFlow<AgentAvailabilitySnapshot> = mutableAvailability.asStateFlow()
        override val currentModel: String = "test"
        override val availableModels: List<String> = listOf("test")
        val turns = AtomicInteger()

        @Volatile
        var ready: Boolean = false

        fun transition(state: AgentAvailabilityState, sequence: Long) {
            mutableAvailability.value = mutableAvailability.value.copy(
                state = state,
                sequence = sequence,
                failure = if (state == AgentAvailabilityState.RETRY_SCHEDULED) {
                    AgentFailure(AgentFailureKind.NETWORK, RecoveryDisposition.RETRY)
                } else {
                    null
                },
            )
        }

        override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean = ready
        override fun switchModel(modelName: String): Job? = null
        override suspend fun updateModel(): ModelSnapshot = ModelSnapshot(currentModel, availableModels)
        override fun resetSession(): Job = CompletableDeferred(Unit).also { it.complete(Unit) }
        override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String {
            turns.incrementAndGet()
            return "recovered reply"
        }
    }
}
