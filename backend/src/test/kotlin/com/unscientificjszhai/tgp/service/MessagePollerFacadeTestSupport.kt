package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.Chat
import com.unscientificjszhai.tgp.models.Message
import com.unscientificjszhai.tgp.models.User
import com.unscientificjszhai.tgp.models.Voice
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilitySnapshot
import com.unscientificjszhai.tgp.service.ai.agent.AgentAvailabilityState
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

internal abstract class MessagePollerFacadeTestSupport {
    protected val tempDirectory: File = createTempDirectory("message-poller-facade-test").toFile()
    protected val parentScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUpFacadeTestSupport() {
        parentScope.cancel()
        tempDirectory.deleteRecursively()
    }

    protected fun fixture(
        processingTimeout: Duration = 10.minutes,
        retryDelay: suspend (Duration) -> Unit = { delay(it) },
        retryJitter: (Duration) -> Duration = { Duration.ZERO },
        updatesOverride: UpdatesRepository? = null,
        agentOverride: AgentService? = null,
    ): Fixture {
        val barrier = ModelSwitchBarrier()
        val settings =
            SettingsChangeCoordinator.forTesting(tempDirectory.resolve("settings-${System.nanoTime()}.json"), barrier)
        val updates = updatesOverride ?: UpdatesRepository(tempDirectory.resolve("updates-${System.nanoTime()}.json"))
        val telegram = mockk<TelegramService>(relaxed = true)
        val agent = agentOverride ?: mockk<AgentService>(relaxed = true).also { service ->
            every { service.availability } returns MutableStateFlow(
                AgentAvailabilitySnapshot(
                    state = AgentAvailabilityState.READY,
                    sequence = 0,
                    settingsVersion = -1,
                ),
            ).asStateFlow()
            every { service.isAiFeatureEnabled(any()) } returns true
            every { service.resetSession() } returns Job().apply { complete() }
            coEvery { service.sendMessage(any(), any()) } coAnswers {
                firstArg<String?>()?.let { service.sendMessage(it) } ?: ""
            }
            coEvery { service.withReadyService<Any?>(any()) } coAnswers {
                firstArg<suspend (AgentService) -> Any?>().invoke(service)
            }
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

    protected fun Fixture.saveSettings(settings: AppSettings) {
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
        saveRawSettings(enabledTestSettings)
    }

    protected fun Fixture.saveRawSettings(settings: AppSettings) {
        this.settings.updateSettings { settings }
        barrier.completeSettingsThrough(this.settings.settingsUpdateFlow.value.switchGeneration)
    }

    protected suspend fun eventually(timeout: Duration = 3.seconds, assertion: () -> Unit) {
        kotlinx.coroutines.withTimeout(timeout) {
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

    protected fun currentSession(poller: MessagePoller): PollingSession =
        assertNotNull(currentSessionOrNull(poller))

    protected fun currentSessionOrNull(poller: MessagePoller): PollingSession? {
        val runtime = runtime(poller)
        return runtime.withSessionLock { runtime.currentSession }
    }

    protected fun runtime(poller: MessagePoller): MessagePollingRuntime =
        MessagePoller::class.java.getDeclaredField("runtime").apply { isAccessible = true }
            .get(poller) as MessagePollingRuntime

    protected fun sessionToken(session: PollingSession): String = session.token

    protected fun sessionJob(session: PollingSession): Job =
        assertNotNull(session.scope.coroutineContext[Job])

    protected fun authorizedMessage(
        messageId: Long,
        chat: Chat,
        text: String? = null,
        voice: Voice? = null,
        caption: String? = null,
        from: User? = User(id = chat.id, isBot = false, firstName = chat.firstName ?: "Authorized"),
    ): Message = Message(messageId, chat, text, voice, caption, from)

    /**
     * 一次 facade 测试使用的完整依赖集合。
     *
     * @property barrier 测试控制的共享模型切换屏障。
     * @property settings 使用临时文件设置存储的设置变更协调器。
     * @property updates 使用临时文件的更新仓储。
     * @property telegram 测试替身 Telegram 服务。
     * @property agent 测试替身 Agent 服务。
     * @property poller 由上述真实协作者组装的轮询 facade。
     */
    protected data class Fixture(
        val barrier: ModelSwitchBarrier,
        val settings: SettingsChangeCoordinator,
        val updates: UpdatesRepository,
        val telegram: TelegramService,
        val agent: AgentService,
        val poller: MessagePoller,
    )
}
