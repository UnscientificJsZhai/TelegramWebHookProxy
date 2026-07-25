package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SettingsUpdate
import com.unscientificjszhai.tgp.repository.SkillRepository
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * 代理委派服务对设置流和模型切换屏障的协作测试设计。
 */
class DelegatingAgentServiceTest {
    /**
     * 验证初始设置订阅和技能更新的处理设计。
     *
     * 验证初始订阅仅创建一次组件，技能更新会重置会话。
     */
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
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val componentCreated = CompletableDeferred<Unit>()
        val sessionReset = CompletableDeferred<Unit>()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns settingsFlow
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
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
        every { openAIAgentService.close() } returns Job().apply { complete() }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            ModelSwitchBarrier(),
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
     * 验证模型选择设置变更的处理设计。
     *
     * 验证设置流会切换模型，且不会额外重置会话。
     */
    @Test
    fun `模型选择变更由设置流切换模型且不会额外重置会话`() = runBlocking {
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
        val settingsFlow = MutableStateFlow(initialSettings)
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val componentCreated = CompletableDeferred<Unit>()
        val modelSwitched = CompletableDeferred<Unit>()
        val modelSwitchJob = Job()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns settingsFlow
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } answers {
            componentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.switchModel("models/gemini-next") } answers {
            modelSwitched.complete(Unit)
            modelSwitchJob
        }
        every { openAIAgentService.close() } returns Job().apply { complete() }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) {
                componentCreated.await()
            }
            val switchedSettings = initialSettings.copy(
                ai = initialSettings.ai!!.copy(selectedModel = "models/gemini-next"),
            )
            settingsFlow.value = switchedSettings
            settingsUpdateFlow.value = SettingsUpdate(switchedSettings, 1, barrier.beginSwitch())
            withTimeout(5.seconds) {
                modelSwitched.await()
            }
            assertTrue(barrier.isSwitching)
            modelSwitchJob.complete()
            withTimeout(5.seconds) {
                while (barrier.isSwitching) yield()
            }

            verify(exactly = 1) { openAIAgentService.switchModel("models/gemini-next") }
            verify(exactly = 0) { openAIAgentService.resetSession() }
        } finally {
            delegatingAgentService.close()?.join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证消息发送等待最新模型切换代次的设计。
     *
     * 验证屏障未完成时发送操作会等待对应切换完成。
     */
    @Test
    fun `sendMessage waits for the latest model switch generation`() = runBlocking {
        val settingsRepository = mockk<SettingsRepository>()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val agentComponent = mockk<AgentComponent>()
        val openAIAgentService = mockk<OpenAIAgentService>()
        val initialSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "test-api-key",
                agentEnabled = true,
            ),
        )
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val componentCreated = CompletableDeferred<Unit>()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } answers {
            componentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.close() } returns Job().apply { complete() }
        coEvery { openAIAgentService.sendMessage("hello", emptyList()) } returns "reply"

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { componentCreated.await() }

            val firstGeneration = barrier.beginSwitch()
            val secondGeneration = barrier.beginSwitch()
            val response = async { delegatingAgentService.sendMessage("hello") }

            yield()
            coVerify(exactly = 0) { openAIAgentService.sendMessage("hello", emptyList()) }

            barrier.complete(firstGeneration)
            yield()
            assertFalse(response.isCompleted)
            coVerify(exactly = 0) { openAIAgentService.sendMessage("hello", emptyList()) }

            barrier.complete(secondGeneration)
            assertEquals("reply", withTimeout(5.seconds) { response.await() })
            coVerify(exactly = 1) { openAIAgentService.sendMessage("hello", emptyList()) }
        } finally {
            delegatingAgentService.close()?.join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证合并设置快照释放模型切换代次的设计。
     *
     * 验证最新快照会释放其覆盖的全部待处理代次。
     */
    @Test
    fun `latest conflated settings snapshot releases all covered switch generations`() = runBlocking {
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
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val componentCreated = CompletableDeferred<Unit>()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val firstGeneration = barrier.beginSwitch()
        val firstSettings = initialSettings.copy(
            ai = initialSettings.ai!!.copy(globalContext = "first context"),
        )
        settingsUpdateFlow.value = SettingsUpdate(firstSettings, 1, firstGeneration)

        val latestGeneration = barrier.beginSwitch()
        val latestSettings = firstSettings.copy(
            ai = firstSettings.ai!!.copy(globalContext = "latest context"),
        )
        settingsUpdateFlow.value = SettingsUpdate(latestSettings, 2, latestGeneration)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } answers {
            componentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.close() } returns Job().apply { complete() }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { componentCreated.await() }
            withTimeout(5.seconds) {
                while (barrier.isSwitching) yield()
            }

            assertFalse(barrier.isSwitching)
            verify(exactly = 1) { agentComponentFactory.create() }
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
