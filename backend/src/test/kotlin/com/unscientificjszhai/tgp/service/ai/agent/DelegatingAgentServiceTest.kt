package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

class DelegatingAgentServiceTest {
    @Test
    fun `StateFlow 初始订阅只创建一次组件且技能更新会重置会话`() = runBlocking {
        val settingsRepository = mockk<SettingsRepository>()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val agentComponent = mockk<AgentComponent>()
        val openAIAgentService = mockk<OpenAIAgentService>(relaxed = true)
        val initialSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "test-api-key",
                agentEnabled = true,
            ),
        )
        val settingsFlow = ValueReadTrackingStateFlow(MutableStateFlow(initialSettings))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val componentCreated = CompletableDeferred<Unit>()
        val sessionReset = CompletableDeferred<Unit>()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns settingsFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } answers {
            componentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.resetSession() } answers {
            sessionReset.complete(Unit)
            null
        }
        every { openAIAgentService.close() } returns null

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) {
                componentCreated.await()
            }
            withTimeout(5.seconds) {
                skillsUpdateEvent.subscriptionCount.first { it > 0 }
            }

            verify(exactly = 1) { agentComponentFactory.create() }
            verify(exactly = 0) { openAIAgentService.resetSession() }
            assertEquals(0, settingsFlow.valueReadCount.get())

            skillsUpdateEvent.emit(Unit)

            withTimeout(5.seconds) {
                sessionReset.await()
            }
            verify(exactly = 1) { openAIAgentService.resetSession() }
        } finally {
            delegatingAgentService.close()?.join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 保持 StateFlow 的订阅语义，仅额外记录显式读取当前值的次数。
     */
    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class ValueReadTrackingStateFlow<T>(
        private val delegate: StateFlow<T>,
    ) : StateFlow<T> by delegate {
        val valueReadCount = AtomicInteger()

        override val value: T
            get() {
                valueReadCount.incrementAndGet()
                return delegate.value
            }
    }
}
