package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.*
import com.unscientificjszhai.tgp.service.ai.agent.*
import io.ktor.http.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * 消息轮询会话隔离与 token 生命周期的测试设计。
 */
class MessagePollerTest {
    private val tempDirectory = createTempDirectory("message-poller-test").toFile()
    private val parentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUp() = runBlocking {
        try {
            parentScope.coroutineContext.job.cancelAndJoin()
        } finally {
            tempDirectory.deleteRecursively()
        }
    }

    @Test
    fun `rapid token restoration joins the old long poll before starting a new generation`() = runBlocking {
        val fixture = fixture()
        val oldPollStarted = CompletableDeferred<Unit>()
        val oldPollCancelled = CompletableDeferred<Unit>()
        val replacementPollStarted = CompletableDeferred<Unit>()
        val pollCalls = AtomicInteger()
        val activePolls = AtomicInteger()
        val maxActivePolls = AtomicInteger()
        val replacementObservedCompletedOldSession = AtomicBoolean()
        lateinit var oldSessionJob: Job
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))
        fixture.updates.saveLastUpdateId("100", 7)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 8, 30) } coAnswers {
            val active = activePolls.incrementAndGet()
            maxActivePolls.updateAndGet { previous -> maxOf(previous, active) }
            try {
                when (pollCalls.incrementAndGet()) {
                    1 -> {
                        oldPollStarted.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            oldPollCancelled.complete(Unit)
                        }
                    }

                    2 -> {
                        replacementObservedCompletedOldSession.set(oldSessionJob.isCompleted)
                        replacementPollStarted.complete(Unit)
                        awaitCancellation()
                    }

                    else -> error("轮询替换完成前出现了意外的额外请求。")
                }
            } finally {
                activePolls.decrementAndGet()
            }
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { oldPollStarted.await() }
            oldSessionJob = currentSession(fixture.poller).scope.coroutineContext.job
            fixture.saveSettings(AppSettings(telegramToken = ""))
            fixture.saveSettings(AppSettings(telegramToken = "100:A"))
            withTimeout(2.seconds) { oldPollCancelled.await() }
            withTimeout(2.seconds) { replacementPollStarted.await() }
            assertTrue(
                replacementObservedCompletedOldSession.get(),
                "替代轮询启动前，旧会话根任务必须已经完成。",
            )
            assertEquals(1, maxActivePolls.get(), "旧轮询与替代轮询不得同时活跃。")
            assertEquals(1, activePolls.get(), "替代轮询启动后应当只有一个活跃长轮询。")
            coVerify(exactly = 2) { fixture.telegram.getUpdatesForToken("100:A", 8, 30) }
            assertEquals(3, fixture.settings.telegramTokenUpdateFlow.value.generation)
            verify(atLeast = 1) { fixture.agent.resetSession() }
        } finally {
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `model selection generation CAS rejects a change after ticket validation`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(123L, "private")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123", selectedModel = ""),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(
                Update(11, message = authorizedMessage(1, chat, text = "/model chosen-model")),
            ),
        ) andThen GetUpdatesResponse(ok = true)
        every { fixture.agent.availableModels } returns listOf("chosen-model")
        fixture.poller.beforeModelSelectionPersistForTesting = {
            fixture.settings.updateSettings { current ->
                current.copy(
                    ai = current.ai!!.copy(autoCleanContextIntervalMinutes = 17),
                )
            }
            fixture.barrier.completeSettingsThrough(fixture.settings.settingsUpdateFlow.value.switchGeneration)
        }

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertEquals("", fixture.settings.settingsFlow.value.ai?.selectedModel)
                assertEquals(17, fixture.settings.settingsFlow.value.ai?.autoCleanContextIntervalMinutes)
                coVerify(exactly = 0) { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) }
            }
        } finally {
            fixture.poller.beforeModelSelectionPersistForTesting = null
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `final agent turn retries offset commit without reentering agent`() = runBlocking {
        val file = tempDirectory.resolve("retry-final-agent-turn.json")
        val rejectFirstCompletion = AtomicBoolean(true)
        val updates = UpdatesRepository(file) { state ->
            val bot = state.bots["100"]
            if (
                bot?.lastUpdateId == 11L &&
                bot.pendingTelegramReplies.any { it.updateId == 11L } &&
                bot.agentTurnJournal.any { it.updateId == 11L && it.reply == "reply" } &&
                rejectFirstCompletion.compareAndSet(true, false)
            ) {
                throw IOException("injected completeAgentUpdate failure")
            }
        }
        val fixture = fixture(retryDelay = {}, updatesOverride = updates)
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "once"))),
        )
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("once") } returns "reply"
        coEvery { fixture.telegram.sendMessageForToken("100:token", "123", "reply", ReplyParameters(1)) } returns
                TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")

        fixture.poller.start()
        try {
            eventually {
                assertFalse(rejectFirstCompletion.get())
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                fixture.updates.getPendingTelegramReplies("100").single().let { reply ->
                    assertEquals(11, reply.updateId)
                    assertEquals("reply", reply.text)
                    assertEquals(ReplyParameters(1), reply.replyParameters)
                }
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("once") }
            }
        } finally {
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `stack overflow retries queue once then silently settles durable turn`() = runBlocking {
        val fixture = fixture(retryDelay = {})
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } returns GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "overflow"))),
        ) andThen GetUpdatesResponse(
            ok = true,
            result = listOf(Update(11, message = authorizedMessage(1, chat, text = "overflow"))),
        ) andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("overflow") } throws StackOverflowError("injected deeply nested JSON")

        fixture.poller.start()
        try {
            eventually {
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                coVerify(exactly = 1) { fixture.agent.sendMessage("overflow") }
            }
        } finally {
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `fatal queue consumer error terminates session and rejects later offers`() = runBlocking {
        val fixture = fixture(retryDelay = {})
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val pollStarted = CompletableDeferred<Unit>()
        val releaseFatalUpdate = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(agentEnabled = true, agentChatId = "123")
            )
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            pollStarted.complete(Unit)
            releaseFatalUpdate.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(Update(11, message = authorizedMessage(1, chat, text = "fatal"))),
            )
        }
        coEvery { fixture.telegram.sendChatActionForToken("100:token", "123", "typing") } returns mockk()
        coEvery { fixture.agent.sendMessage("fatal") } throws Error("injected fatal queue failure")

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { pollStarted.await() }
            val terminatedSession = currentSession(fixture.poller)
            releaseFatalUpdate.complete(Unit)
            withTimeout(2.seconds) { terminatedSession.scope.coroutineContext.job.join() }
            assertNull(currentSessionOrNull(fixture.poller))
            coVerify(exactly = 1) { fixture.agent.sendMessage("fatal") }
            fixture.poller.enqueueUpdateForTesting(
                Update(
                    12,
                    message = authorizedMessage(2, chat, text = "must-not-queue")
                )
            )
            coVerify(exactly = 1) { fixture.agent.sendMessage("fatal") }
            coVerify(exactly = 0) { fixture.agent.sendMessage("must-not-queue") }
            coVerify(exactly = 1) { fixture.telegram.getUpdatesForToken("100:token", 11, 30) }
        } finally {
            releaseFatalUpdate.complete(Unit)
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `full queue admission waits for barrier and returns Retry when feedback is rejected`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
        val pollRequestStarted = CompletableDeferred<Unit>()
        val blockingAgentStarted = CompletableDeferred<Unit>()
        val allowBlockingAgent = CompletableDeferred<Unit>()
        var switchGeneration: Long? = null
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(
                telegramToken = "100:token",
                ai = AISettings(
                    geminiApiKey = "test-key",
                    agentEnabled = true,
                    agentChatId = "123",
                ),
            ),
        )
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            pollRequestStarted.complete(Unit)
            awaitCancellation()
        }
        coEvery { fixture.agent.sendMessage("block") } coAnswers {
            blockingAgentStarted.complete(Unit)
            allowBlockingAgent.await()
            ""
        }
        coEvery {
            fixture.telegram.sendMessageForToken("100:token", "123", match { it.contains("处理队列已满") }, any())
        } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":true}""")

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { pollRequestStarted.await() }
            assertIs<UpdateAdmission.Enqueued>(
                fixture.poller.enqueueUpdateForTesting(
                    Update(100, message = authorizedMessage(100, chat, text = "block")),
                ),
            )
            withTimeout(2.seconds) { blockingAgentStarted.await() }
            (101L..110L).forEach { updateId ->
                assertIs<UpdateAdmission.Enqueued>(
                    fixture.poller.enqueueUpdateForTesting(
                        Update(
                            updateId,
                            message = authorizedMessage(updateId, chat, text = "queued"),
                        ),
                    ),
                )
            }

            switchGeneration = fixture.barrier.beginSwitch()
            val admission = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.poller.enqueueUpdateForTesting(
                    Update(11, message = authorizedMessage(11, chat, text = "full")),
                )
            }
            assertFalse(admission.isCompleted)
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
            coVerify(exactly = 0) {
                fixture.telegram.sendMessageForToken("100:token", "123", match { it.contains("处理队列已满") }, any())
            }

            fixture.barrier.complete(checkNotNull(switchGeneration))
            assertEquals(UpdateAdmission.Retry, withTimeout(2.seconds) { admission.await() })
            coVerify(exactly = 1) {
                fixture.telegram.sendMessageForToken(
                    "100:token",
                    "123",
                    match { it.contains("处理队列已满") },
                    any(),
                )
            }
            assertEquals(10, fixture.updates.getData("100").lastUpdateId)
        } finally {
            allowBlockingAgent.complete(Unit)
            switchGeneration?.let(fixture.barrier::complete)
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `real poll response persists retry checkpoint and applies one second backoff when queue is full`() =
        runBlocking {
            val retryStarted = CompletableDeferred<Duration>()
            val allowRetry = CompletableDeferred<Unit>()
            val fixture = fixture(
                retryDelay = { delayDuration ->
                    retryStarted.complete(delayDuration)
                    allowRetry.await()
                },
            )
            val chat = Chat(id = 123L, type = "private", firstName = "Authorized")
            val pollRequestStarted = CompletableDeferred<Unit>()
            val pollResponses = Channel<GetUpdatesResponse>(capacity = 1)
            val blockingAgentStarted = CompletableDeferred<Unit>()
            val allowBlockingAgent = CompletableDeferred<Unit>()
            fixture.updates.saveLastUpdateId("100", 10)
            fixture.saveSettings(
                AppSettings(
                    telegramToken = "100:token",
                    ai = AISettings(
                        geminiApiKey = "test-key",
                        agentEnabled = true,
                        agentChatId = "123",
                    ),
                ),
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
                pollRequestStarted.complete(Unit)
                pollResponses.receive()
            }
            coEvery { fixture.agent.sendMessage("block") } coAnswers {
                blockingAgentStarted.complete(Unit)
                allowBlockingAgent.await()
                ""
            }
            coEvery {
                fixture.telegram.sendMessageForToken(
                    "100:token",
                    "123",
                    match { it.contains("处理队列已满") },
                    any(),
                )
            } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":true}""")

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { pollRequestStarted.await() }
                assertIs<UpdateAdmission.Enqueued>(
                    fixture.poller.enqueueUpdateForTesting(
                        Update(100, message = authorizedMessage(100, chat, text = "block")),
                    ),
                )
                withTimeout(2.seconds) { blockingAgentStarted.await() }
                (101L..110L).forEach { updateId ->
                    assertIs<UpdateAdmission.Enqueued>(
                        fixture.poller.enqueueUpdateForTesting(
                            Update(
                                updateId,
                                message = authorizedMessage(updateId, chat, text = "queued"),
                            ),
                        ),
                    )
                }

                pollResponses.send(
                    GetUpdatesResponse(
                        ok = true,
                        result = listOf(
                            Update(11, message = authorizedMessage(11, chat, text = "full")),
                        ),
                    ),
                )

                assertEquals(1.seconds, withTimeout(2.seconds) { retryStarted.await() })
                val durableState = fixture.updates.getData("100")
                assertEquals(10, durableState.lastUpdateId)
                assertEquals(11, assertNotNull(durableState.retryCheckpoint).targetUpdateId)
                coVerify(exactly = 1) {
                    fixture.telegram.sendMessageForToken(
                        "100:token",
                        "123",
                        match { it.contains("处理队列已满") },
                        any(),
                    )
                }
            } finally {
                pollResponses.trySend(GetUpdatesResponse(ok = true))
                allowBlockingAgent.complete(Unit)
                allowRetry.complete(Unit)
                fixture.poller.closeAndJoin()
                pollResponses.cancel()
            }
        }


    @Test
    fun `start and close race under a pending barrier never polls Telegram`() = runBlocking {
        val barrier = ModelSwitchBarrier()
        val settings = SettingsChangeCoordinator.forTesting(tempDirectory.resolve("close-race-settings.json"), barrier)
        val updates = UpdatesRepository(tempDirectory.resolve("close-race-updates.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val generation = barrier.beginSwitch()
        settings.updateSettings { AppSettings(telegramToken = "100:token") }
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)

        poller.start()
        try {
            val stopped = async(start = CoroutineStart.UNDISPATCHED) { poller.closeAndJoin() }
            barrier.complete(generation)
            withTimeout(2.seconds) { stopped.await() }

            coVerify(exactly = 0) { telegram.getUpdatesForToken(any(), any(), any()) }
        } finally {
            barrier.complete(generation)
            poller.closeAndJoin()
        }
    }


    @Test
    fun `reset command clears queue and timer only after successful reset job`() = runBlocking {
        val fixture = fixture()
        val chat = Chat(id = 123L, type = "private", firstName = "Test")
        val pollStarted = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()
        val resetStarted = CompletableDeferred<Unit>()
        val resetJob = Job()
        val secondUpdateAdmitted = CompletableDeferred<Unit>()
        val availabilityChecks = AtomicInteger()
        val commandReplySent = CompletableDeferred<Unit>()
        fixture.updates.saveLastUpdateId("100", 10)
        fixture.saveSettings(
            AppSettings(telegramToken = "100:token", ai = AISettings(agentEnabled = true, agentChatId = "123")),
        )
        every { fixture.agent.isAiFeatureEnabled(any()) } answers {
            if (availabilityChecks.incrementAndGet() == 2) {
                secondUpdateAdmitted.complete(Unit)
            }
            true
        }
        every { fixture.agent.resetSession() } answers {
            resetStarted.complete(Unit)
            resetJob
        }
        coEvery { fixture.telegram.getUpdatesForToken("100:token", 11, 30) } coAnswers {
            pollStarted.complete(Unit)
            releaseBatch.await()
            GetUpdatesResponse(
                ok = true,
                result = listOf(
                    Update(11, message = authorizedMessage(1, chat, text = "/reset")),
                    Update(12, message = authorizedMessage(2, chat, text = "queued")),
                ),
            )
        } andThen GetUpdatesResponse(ok = true)
        coEvery { fixture.telegram.sendMessageForToken(any(), any(), any(), any()) } coAnswers {
            commandReplySent.complete(Unit)
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
        }

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { pollStarted.await() }
            val session = currentSession(fixture.poller)
            session.lastAiReplyAtMillis = 1234L
            releaseBatch.complete(Unit)
            withTimeout(2.seconds) { resetStarted.await() }
            withTimeout(2.seconds) { secondUpdateAdmitted.await() }
            resetJob.complete()

            eventually {
                assertEquals(12, fixture.updates.getData("100").lastUpdateId)
                assertNull(session.lastAiReplyAtMillis)
                assertTrue(commandReplySent.isCompleted)
                coVerify(exactly = 0) { fixture.agent.sendMessage("queued") }
                coVerify {
                    fixture.telegram.sendMessageForToken(
                        "100:token",
                        "123",
                        "会话已重置，待处理消息已清空。",
                        any(),
                    )
                }
            }
        } finally {
            releaseBatch.complete(Unit)
            resetJob.complete()
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `authentication reset keeps the barrier closed until the reset job finishes`() = runBlocking {
        val resetStarted = CompletableDeferred<Unit>()
        val resetJob = Job()
        val fixture = fixture()
        fixture.updates.saveLastUpdateId("100", 10)
        coEvery { fixture.telegram.getUpdatesForToken("100:A", 11, 30) } returns
                GetUpdatesResponse(ok = false, errorCode = 401, description = "Unauthorized")
        every { fixture.agent.resetSession() } answers {
            resetStarted.complete(Unit)
            resetJob
        }
        fixture.saveSettings(AppSettings(telegramToken = "100:A"))

        fixture.poller.start()
        try {
            withTimeout(2.seconds) { resetStarted.await() }
            assertTrue(fixture.barrier.isSwitching)

            val admitted = async(start = CoroutineStart.UNDISPATCHED) {
                fixture.barrier.runWhenReady { "admitted" }
            }
            assertFalse(admitted.isCompleted)

            resetJob.complete()
            assertEquals("admitted", withTimeout(2.seconds) { admitted.await() })
            withTimeout(2.seconds) { fixture.barrier.awaitReady() }
            assertFalse(fixture.barrier.isSwitching)
        } finally {
            resetJob.complete()
            fixture.poller.closeAndJoin()
        }
    }


    @Test
    fun `outbox write failure before delivery does not send Telegram request`() = runBlocking {
        val file = tempDirectory.resolve("failed-outbox-delivery-state.json")
        UpdatesRepository(file).completeAgentUpdate("100", 11, PendingTelegramReply(11, "123", "reply"))
        val writeAttempted = CompletableDeferred<Unit>()
        val updates = UpdatesRepository(file) {
            writeAttempted.complete(Unit)
            throw IOException("injected delivery state write failure")
        }
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsChangeCoordinator.forTesting(tempDirectory.resolve("failed-outbox-settings.json"), barrier)
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        val poller = MessagePoller(parentScope, telegram, agent, settings, updates, barrier)
        settings.updateSettings { AppSettings(telegramToken = "100:token") }
        barrier.complete(barrier.latestPendingGeneration())
        coEvery { telegram.getUpdatesForToken("100:token", 12, 30) } returns GetUpdatesResponse(ok = true)

        poller.start()
        try {
            withTimeout(2.seconds) { writeAttempted.await() }
            coVerify(exactly = 0) { telegram.sendMessageForToken("100:token", "123", "reply", null) }
            assertEquals(0, updates.getPendingTelegramReplies("100").single().deliveryAttempts)
        } finally {
            poller.closeAndJoin()
        }
    }


    @Test
    fun `retry gap reconciles durable journal before it can skip target`() = runBlocking {
        listOf("final", "in-progress").forEach { mode ->
            val file = tempDirectory.resolve("retry-gap-durable-$mode.json")
            UpdatesRepository(file).apply {
                saveLastUpdateId("100", 10)
                assertEquals(AgentTurnClaim.CLAIMED, claimAgentTurn("100", 11, "123", ReplyParameters(1)))
                if (mode == "final") {
                    assertNotNull(finalizeAgentTurn("100", 11, "saved"))
                }
                assertEquals(
                    RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
                    recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
                )
            }
            val nextRoundStarted = CompletableDeferred<Unit>()
            val releaseNextRound = CompletableDeferred<Unit>()
            val fixture = fixture(updatesOverride = UpdatesRepository(file))
            val chat = Chat(id = 123L, type = "private", firstName = "Test")
            fixture.saveSettings(AppSettings(telegramToken = "100:$mode"))
            coEvery { fixture.telegram.getUpdatesForToken("100:$mode", 11, 30) } returns GetUpdatesResponse(
                ok = true,
                result = listOf(Update(15, message = authorizedMessage(1, chat, text = "must-not-run"))),
            )
            coEvery { fixture.telegram.getUpdatesForToken("100:$mode", 12, 30) } coAnswers {
                nextRoundStarted.complete(Unit)
                releaseNextRound.await()
                GetUpdatesResponse(ok = true)
            }
            if (mode == "final") {
                coEvery {
                    fixture.telegram.sendMessageForToken("100:$mode", "123", "saved", ReplyParameters(1))
                } returns TelegramApiResponse(HttpStatusCode.InternalServerError, """{"ok":false}""")
            }

            fixture.poller.start()
            try {
                withTimeout(2.seconds) { nextRoundStarted.await() }
                assertEquals(11, fixture.updates.getData("100").lastUpdateId)
                assertNull(fixture.updates.getData("100").retryCheckpoint)
                assertTrue(fixture.updates.getData("100").agentTurnJournal.isEmpty())
                if (mode == "final") {
                    assertEquals("saved", fixture.updates.getPendingTelegramReplies("100").single().text)
                } else {
                    assertTrue(fixture.updates.getPendingTelegramReplies("100").isEmpty())
                }
                coVerify(exactly = 0) { fixture.agent.sendMessage(any()) }
            } finally {
                releaseNextRound.complete(Unit)
                fixture.poller.closeAndJoin()
            }
        }
    }


    private fun fixture(
        processingTimeout: Duration = 10.minutes,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
        updatesOverride: UpdatesRepository? = null,
    ): Fixture {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsChangeCoordinator.forTesting(tempDirectory.resolve("settings-${System.nanoTime()}.json"), barrier)
        val updates = updatesOverride ?: UpdatesRepository(tempDirectory.resolve("updates-${System.nanoTime()}.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = mockk<AgentService>(relaxed = true)
        every { agent.availability } returns MutableStateFlow(
            AgentAvailabilitySnapshot(
                state = AgentAvailabilityState.READY,
                sequence = 0,
                settingsVersion = -1,
            ),
        ).asStateFlow()
        every { agent.isAiFeatureEnabled(any()) } returns true
        every { agent.resetSession() } returns Job().apply { complete() }
        // 生产路径直接调用媒体重载；既有文本测试仍可只 stub 文本便利重载。
        coEvery { agent.sendMessage(any(), any()) } coAnswers {
            firstArg<String?>()?.let { agent.sendMessage(it) } ?: ""
        }
        coEvery { agent.withReadyService<Any?>(any()) } coAnswers {
            firstArg<suspend (AgentService) -> Any?>().invoke(agent)
        }
        return Fixture(
            barrier = barrier,
            settings = settings,
            updates = updates,
            telegram = telegram,
            agent = agent,
            poller = MessagePoller(
                parentScope,
                telegram,
                agent,
                settings,
                updates,
                barrier,
                processingTimeout,
                retryDelay,
                retryJitter,
            ),
        )
    }

    private fun Fixture.saveSettings(settings: AppSettings) {
        // 既有轮询测试以 `agentEnabled` 表示可用的默认 Gemini 测试配置；缺少密钥的行为由专门用例使用
        // SettingsChangeCoordinator 直接设置，以免该便利方法掩盖禁用分支。
        val enabledTestSettings = settings.copy(
            ai = settings.ai?.let { aiSettings ->
                if (
                    aiSettings.agentEnabled &&
                    aiSettings.provider == AIProvider.GEMINI &&
                    aiSettings.geminiApiKey.isBlank()
                ) {
                    aiSettings.copy(geminiApiKey = "test-key")
                } else {
                    aiSettings
                }
            },
        )
        this.settings.updateSettings { enabledTestSettings }
        // 只能完成本次设置写入创建的屏障；认证或 token 轮换中的外部代次必须继续保持封闭。
        barrier.completeSettingsThrough(this.settings.settingsUpdateFlow.value.switchGeneration)
    }

    private suspend fun eventually(timeout: Duration = 3.seconds, assertion: () -> Unit) {
        withTimeout(timeout) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    delay(20.milliseconds)
                }
            }
        }
    }

    /**
     * 一次消息轮询测试使用的完整依赖集合。
     *
     * @property barrier 测试控制的共享模型切换屏障。
     * @property settings 使用临时文件设置存储的设置变更协调器。
     * @property updates 使用临时文件的更新仓储。
     * @property telegram 测试替身 Telegram 服务。
     * @property agent 测试替身 Agent 服务。
     * @property poller 由上述真实协作者组装的轮询 facade。
     */
    private data class Fixture(
        val barrier: ModelSwitchBarrier,
        val settings: SettingsChangeCoordinator,
        val updates: UpdatesRepository,
        val telegram: TelegramService,
        val agent: AgentService,
        val poller: MessagePoller,
    )


    private fun runtime(poller: MessagePoller): MessagePollingRuntime =
        MessagePoller::class.java.getDeclaredField("runtime").apply { isAccessible = true }
            .get(poller) as MessagePollingRuntime

    private fun currentSession(poller: MessagePoller): PollingSession =
        assertNotNull(currentSessionOrNull(poller))

    private fun currentSessionOrNull(poller: MessagePoller): PollingSession? {
        val runtime = runtime(poller)
        return runtime.withSessionLock { runtime.currentSession }
    }

    /**
     * 通过 facade 实际组装的准入策略将工作送入当前会话，不构造或 mock 协作者。
     */
    private suspend fun MessagePoller.enqueueUpdateForTesting(update: Update): UpdateAdmission {
        val session = currentSessionOrNull(this) ?: return UpdateAdmission.Confirmed
        val policy = MessagePoller::class.java.getDeclaredField("admissionPolicy").apply { isAccessible = true }
            .get(this) as UpdateAdmissionPolicy
        return policy.enqueueUpdate(session, update)
    }

    /** 创建默认由该私聊用户本人发送的授权 Telegram 消息。 */
    private fun authorizedMessage(
        messageId: Long,
        chat: Chat,
        text: String? = null,
        voice: Voice? = null,
        caption: String? = null,
        from: User? = User(id = chat.id, isBot = false, firstName = chat.firstName ?: "Authorized"),
    ): Message = Message(messageId, chat, text, voice, caption, from)
}
