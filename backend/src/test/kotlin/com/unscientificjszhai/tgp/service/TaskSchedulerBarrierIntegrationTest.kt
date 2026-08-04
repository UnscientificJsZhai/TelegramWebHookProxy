package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.DelegatingAgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.File
import javax.inject.Provider
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 定时任务与真实委派 Agent 模型切换屏障的集成时序测试。
 */
class TaskSchedulerBarrierIntegrationTest {
    private val temporaryDirectories = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temporaryDirectories.forEach(File::deleteRecursively)
        temporaryDirectories.clear()
    }

    /**
     * 验证已准入的任务会让真实模型切换屏障等待完整 Agent 回合及 Telegram 投递，并始终使用旧服务和旧 token。
     */
    @Test
    fun `admitted task holds the real barrier through delivery and uses old agent and token`() = runBlocking {
        val fixture = newFixture(replacementReadiness = null)
        val deliveryStarted = CompletableDeferred<Unit>()
        val allowDelivery = CompletableDeferred<Unit>()
        try {
            fixture.initialize()
            coEvery { fixture.oldAgent.sendMessage(any()) } returns "old agent result"
            coEvery { fixture.telegramService.sendMessageForToken(any(), any(), any(), any()) } coAnswers {
                deliveryStarted.complete(Unit)
                allowDelivery.await()
                TelegramApiResponse(HttpStatusCode.OK, """{"ok":true}""")
            }
            fixture.scheduler.createTask(
                "barrier protected",
                System.currentTimeMillis() - 1_000,
                LoopMode.ONCE,
                "chat-a",
            )

            val execution = async { fixture.scheduler.scanAndExecute() }
            withTimeout(TEST_TIMEOUT_MILLIS) { deliveryStarted.await() }

            fixture.settingsRepository.saveSettings(settingsFor(BOT_B_TOKEN, "key-b"))
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (!fixture.barrier.isSwitching) {
                    yield()
                }
            }
            yield()
            assertFalse(fixture.replacementCreated.isCompleted, "switch must wait for Telegram delivery")

            allowDelivery.complete(Unit)
            withTimeout(TEST_TIMEOUT_MILLIS) { execution.await() }
            withTimeout(TEST_TIMEOUT_MILLIS) { fixture.replacementCreated.await() }
            awaitBarrierReady(fixture.barrier)

            coVerify(exactly = 1) { fixture.oldAgent.sendMessage(match { it.contains("barrier protected") }) }
            coVerify(exactly = 1) {
                fixture.telegramService.sendMessageForToken(BOT_A_TOKEN, "chat-a", any(), any())
            }
            coVerify(exactly = 0) { fixture.newAgent.sendMessage(any()) }
            assertTrue(fixture.scheduler.listTasks().isEmpty())
        } finally {
            allowDelivery.complete(Unit)
            fixture.close()
        }
    }

    /**
     * 验证已准入任务在总时限到期后停止完整回合、不投递迟到结果，并释放真实模型切换屏障。
     */
    @Test
    fun `timed out admitted task releases switch barrier without delivery`() = runBlocking {
        val fixture = newFixture(
            replacementReadiness = null,
            deadlines = AgentExecutionDeadlines(
                mcpBatch = 1.seconds,
                candidateInitialization = 1.seconds,
                scheduledTurn = 100.milliseconds,
            ),
        )
        val agentStarted = CompletableDeferred<Unit>()
        try {
            fixture.initialize()
            coEvery { fixture.oldAgent.sendMessage(any()) } coAnswers {
                agentStarted.complete(Unit)
                awaitCancellation()
            }
            fixture.scheduler.createTask(
                "must not outlive scheduled deadline",
                System.currentTimeMillis() - 1_000,
                LoopMode.ONCE,
                "chat-a",
            )

            val execution = async { fixture.scheduler.scanAndExecute() }
            withTimeout(TEST_TIMEOUT_MILLIS) { agentStarted.await() }

            fixture.settingsRepository.saveSettings(settingsFor(BOT_B_TOKEN, "key-b"))
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (!fixture.barrier.isSwitching) {
                    yield()
                }
            }
            withTimeout(TEST_TIMEOUT_MILLIS) { execution.await() }
            withTimeout(TEST_TIMEOUT_MILLIS) { fixture.replacementCreated.await() }
            awaitBarrierReady(fixture.barrier)

            coVerify(exactly = 1) { fixture.oldAgent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.telegramService.sendMessageForToken(any(), any(), any(), any()) }
            assertTrue(fixture.scheduler.listTasks().isEmpty(), "precommitted task must not be retried")
        } finally {
            fixture.close()
        }
    }

    /**
     * 验证候选代理无法就绪时，调度器会在预消费前跳过任务；后续有效配置恢复后任务仅执行一次。
     */
    @Test
    fun `unready replacement skips task before precommit and later recovery executes it once`() = runBlocking {
        val failedReadiness = Job().apply { cancel() }
        val fixture = newFixture(
            replacementReadiness = failedReadiness,
            recoveryReadiness = null,
        )
        try {
            fixture.initialize()
            val taskId = fixture.scheduler.createTask(
                "must wait for a ready replacement",
                System.currentTimeMillis() - 1_000,
                LoopMode.ONCE,
                "chat-b",
            )

            fixture.settingsRepository.saveSettings(settingsFor(BOT_B_TOKEN, "key-b"))
            withTimeout(TEST_TIMEOUT_MILLIS) { fixture.replacementCreated.await() }
            awaitBarrierReady(fixture.barrier)

            fixture.scheduler.scanAndExecute()

            coVerify(exactly = 0) { fixture.oldAgent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.newAgent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.recoveryAgent.sendMessage(any()) }
            coVerify(exactly = 0) { fixture.telegramService.sendMessageForToken(any(), any(), any(), any()) }
            assertEquals(listOf(taskId), fixture.scheduler.listTasks().map { it.id })

            coEvery { fixture.recoveryAgent.sendMessage(any()) } returns ""
            fixture.settingsRepository.saveSettings(settingsFor(BOT_B_TOKEN, "key-c"))
            withTimeout(TEST_TIMEOUT_MILLIS) { fixture.recoveryCreated.await() }
            awaitBarrierReady(fixture.barrier)

            fixture.scheduler.scanAndExecute()

            coVerify(exactly = 1) {
                fixture.recoveryAgent.sendMessage(match { it.contains("must wait for a ready replacement") })
            }
            assertTrue(fixture.scheduler.listTasks().isEmpty())
        } finally {
            fixture.close()
        }
    }

    private fun newFixture(
        replacementReadiness: Job?,
        recoveryReadiness: Job? = null,
        deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
    ): SchedulerBarrierFixture {
        val tempDirectory = createTempDirectory("task-scheduler-barrier").toFile().also(temporaryDirectories::add)
        val barrier = ModelSwitchBarrier()
        val settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        settingsRepository.saveSettings(settingsFor(BOT_A_TOKEN, "key-a"))
        val skillRepository = SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val firstComponent = mockk<AgentComponent>()
        val secondComponent = mockk<AgentComponent>()
        val thirdComponent = mockk<AgentComponent>()
        val oldAgent = mockk<OpenAIAgentService>()
        val newAgent = mockk<OpenAIAgentService>()
        val recoveryAgent = mockk<OpenAIAgentService>()
        val telegramService = mockk<TelegramService>()
        val firstCreated = CompletableDeferred<Unit>()
        val replacementCreated = CompletableDeferred<Unit>()
        val recoveryCreated = CompletableDeferred<Unit>()
        var componentCount = 0

        every { agentComponentFactory.create() } answers {
            when (componentCount++) {
                0 -> {
                    firstCreated.complete(Unit)
                    firstComponent
                }

                1 -> {
                    replacementCreated.complete(Unit)
                    secondComponent
                }

                else -> {
                    recoveryCreated.complete(Unit)
                    thirdComponent
                }
            }
        }
        every { firstComponent.openAIAgentService } returns oldAgent
        every { secondComponent.openAIAgentService } returns newAgent
        every { thirdComponent.openAIAgentService } returns recoveryAgent
        every { oldAgent.initializationJob() } returns null
        every { newAgent.initializationJob() } returns replacementReadiness
        every { recoveryAgent.initializationJob() } returns recoveryReadiness
        every { oldAgent.close() } returns Job().apply { complete() }
        every { newAgent.close() } returns Job().apply { complete() }
        every { recoveryAgent.close() } returns Job().apply { complete() }

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delegatingAgent = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            scope,
        )
        val scheduler = TaskSchedulerService(
            CoroutineScope(SupervisorJob()),
            telegramService,
            Provider { delegatingAgent },
            settingsRepository,
            File(tempDirectory, "schedule.json"),
            deadlines = deadlines,
        )
        return SchedulerBarrierFixture(
            barrier,
            settingsRepository,
            telegramService,
            oldAgent,
            newAgent,
            recoveryAgent,
            delegatingAgent,
            scheduler,
            scope,
            firstCreated,
            replacementCreated,
            recoveryCreated,
        )
    }

    private suspend fun SchedulerBarrierFixture.initialize() {
        withTimeout(TEST_TIMEOUT_MILLIS) { firstCreated.await() }
        awaitBarrierReady(barrier)
    }

    private suspend fun awaitBarrierReady(barrier: ModelSwitchBarrier) {
        withTimeout(TEST_TIMEOUT_MILLIS) {
            while (barrier.isSwitching) {
                yield()
            }
        }
    }

    private fun settingsFor(token: String, apiKey: String): AppSettings = AppSettings(
        telegramToken = token,
        ai = AISettings(
            provider = AIProvider.OPENAI,
            openAiApiKey = apiKey,
            agentEnabled = true,
            agentChatId = if (token == BOT_A_TOKEN) "chat-a" else "chat-b",
        ),
    )

    private data class SchedulerBarrierFixture(
        val barrier: ModelSwitchBarrier,
        val settingsRepository: SettingsRepository,
        val telegramService: TelegramService,
        val oldAgent: AgentService,
        val newAgent: AgentService,
        val recoveryAgent: AgentService,
        val delegatingAgent: DelegatingAgentService,
        val scheduler: TaskSchedulerService,
        val scope: CoroutineScope,
        val firstCreated: CompletableDeferred<Unit>,
        val replacementCreated: CompletableDeferred<Unit>,
        val recoveryCreated: CompletableDeferred<Unit>,
    ) {
        fun close() {
            scheduler.close()
            delegatingAgent.close()
            scope.cancel()
        }
    }
}

private const val BOT_A_TOKEN = "100:token-a"
private const val BOT_B_TOKEN = "200:token-b"
private const val TEST_TIMEOUT_MILLIS = 5_000L
