package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SettingsUpdate
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
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
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * 代理委派服务对设置流和模型切换屏障的协作测试设计。
 */
class DelegatingAgentServiceTest {
    /** 返回默认历史 URL 标记为 `false` 的设置仓储替身，供不涉及磁盘恢复的委派测试复用。 */
    private fun mockedSettingsRepository(): SettingsRepository = mockk(relaxed = true)

    /**
     * 验证有效初始配置会在候选 Agent 完成就绪后才释放启动屏障。
     */
    @Test
    fun `initial readiness barrier waits for the first candidate agent`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
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
        val agentCreated = CompletableDeferred<Unit>()
        val readiness = Job()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } answers {
            agentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.initializationJob() } returns readiness
        every { openAIAgentService.close() } returns Job().apply { complete() }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { agentCreated.await() }
            assertTrue(barrier.isSwitching)

            readiness.complete()
            withTimeout(5.seconds) {
                while (barrier.isSwitching) yield()
            }
        } finally {
            readiness.complete()
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证缺少初始 API 密钥会禁用代理并释放启动屏障。
     */
    @Test
    fun `missing initial API key releases readiness without creating an agent`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.OPENAI, agentEnabled = true),
        )
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns MutableStateFlow(
            SettingsUpdate(
                initialSettings,
                0,
                null
            )
        )
        every { skillRepository.skillsUpdateEvent } returns MutableSharedFlow()

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) {
                while (barrier.isSwitching) yield()
            }
            verify(exactly = 0) { agentComponentFactory.create() }
            assertFalse(delegatingAgentService.isAiFeatureEnabled(initialSettings.ai!!))
        } finally {
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证历史非法 OpenAI 基础地址会阻止激活的 OpenAI 候选发布，并在处理初始快照后释放启动屏障。
     */
    @Test
    fun `historical invalid OpenAI base URL disables active OpenAI without creating a component`() = runBlocking {
        val tempDirectory = Files.createTempDirectory("delegating-invalid-openai-url").toFile()
        val configFile = File(tempDirectory, "settings.json")
        configFile.writeText(
            """{"ai":{"provider":"OPENAI","openAiApiKey":"key","openAiBaseUrl":"https://gateway.example.com/v1/%6dodels","agentEnabled":true}}""",
        )
        val barrier = ModelSwitchBarrier()
        val settingsRepository = SettingsRepository.forTesting(configFile, barrier)
        val skillRepository = SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) {
                while (barrier.isSwitching) yield()
            }
            val aiSettings = checkNotNull(settingsRepository.settingsFlow.value.ai)
            assertTrue(settingsRepository.hasHistoricalInvalidOpenAiBaseUrl)
            verify(exactly = 0) { agentComponentFactory.create() }
            assertFalse(delegatingAgentService.isAiFeatureEnabled(aiSettings))
        } finally {
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
            tempDirectory.deleteRecursively()
        }
    }

    /**
     * 验证休眠的历史 OpenAI 地址不会阻止激活的 Gemini 候选创建并发布。
     */
    @Test
    fun `dormant historical invalid OpenAI base URL does not block Gemini`() = runBlocking {
        val tempDirectory = Files.createTempDirectory("delegating-dormant-openai-url").toFile()
        val configFile = File(tempDirectory, "settings.json")
        configFile.writeText(
            """{"ai":{"provider":"GEMINI","geminiApiKey":"key","openAiBaseUrl":"https://gateway.example.com/v1/chat/%63ompletions","agentEnabled":true}}""",
        )
        val barrier = ModelSwitchBarrier()
        val settingsRepository = SettingsRepository.forTesting(configFile, barrier)
        val skillRepository = SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val agentComponent = mockk<AgentComponent>()
        val geminiAgentService = mockk<GeminiAgentService>()
        val componentCreated = CompletableDeferred<Unit>()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { agentComponentFactory.create() } answers {
            componentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.geminiAgentService } returns geminiAgentService
        every { geminiAgentService.initializationJob() } returns null
        every { geminiAgentService.close() } returns Job().apply { complete() }
        every { geminiAgentService.isAiFeatureEnabled(any()) } returns true
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
            val aiSettings = checkNotNull(settingsRepository.settingsFlow.value.ai)
            assertTrue(settingsRepository.hasHistoricalInvalidOpenAiBaseUrl)
            verify(exactly = 1) { agentComponentFactory.create() }
            assertTrue(delegatingAgentService.isAiFeatureEnabled(aiSettings))
        } finally {
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
            tempDirectory.deleteRecursively()
        }
    }

    /**
     * 验证关闭不等待卡住的初始候选 Agent 清理就释放启动屏障。
     */
    @Test
    fun `close releases the barrier before an unready candidate finishes closing`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
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
        val agentCreated = CompletableDeferred<Unit>()
        val candidateCloseCalled = CompletableDeferred<Unit>()
        val readiness = Job()
        val candidateClose = Job()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns MutableStateFlow(
            SettingsUpdate(
                initialSettings,
                0,
                null
            )
        )
        every { skillRepository.skillsUpdateEvent } returns MutableSharedFlow()
        every { agentComponentFactory.create() } answers {
            agentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.initializationJob() } returns readiness
        every { openAIAgentService.close() } answers {
            candidateCloseCalled.complete(Unit)
            candidateClose
        }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { agentCreated.await() }
            assertTrue(barrier.isSwitching)

            val closeWaitJob = delegatingAgentService.close()
            assertFalse(barrier.isSwitching)
            withTimeout(5.seconds) { candidateCloseCalled.await() }
            assertFalse(closeWaitJob.isCompleted)

            candidateClose.complete()
            withTimeout(5.seconds) { closeWaitJob.join() }
        } finally {
            readiness.complete()
            candidateClose.complete()
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证父作用域取消不会等待卡住的初始候选 Agent 清理才释放启动屏障。
     */
    @Test
    fun `parent scope cancellation releases the barrier before an unready candidate finishes closing`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
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
        val agentCreated = CompletableDeferred<Unit>()
        val candidateCloseCalled = CompletableDeferred<Unit>()
        val readiness = Job()
        val candidateClose = Job()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns MutableStateFlow(
            SettingsUpdate(
                initialSettings,
                0,
                null
            )
        )
        every { skillRepository.skillsUpdateEvent } returns MutableSharedFlow()
        every { agentComponentFactory.create() } answers {
            agentCreated.complete(Unit)
            agentComponent
        }
        every { agentComponent.openAIAgentService } returns openAIAgentService
        every { openAIAgentService.initializationJob() } returns readiness
        every { openAIAgentService.close() } answers {
            candidateCloseCalled.complete(Unit)
            candidateClose
        }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { agentCreated.await() }
            assertTrue(barrier.isSwitching)

            serviceScope.cancel()
            withTimeout(5.seconds) { candidateCloseCalled.await() }
            assertFalse(barrier.isSwitching)

            candidateClose.complete()
            withTimeout(5.seconds) { requireNotNull(serviceScope.coroutineContext[Job]).join() }
        } finally {
            readiness.complete()
            candidateClose.complete()
            delegatingAgentService.close().join()
            serviceScope.cancel()
            requireNotNull(serviceScope.coroutineContext[Job]).join()
        }
    }

    /**
     * 验证候选初始化超过短总时限后，即使候选关闭也挂起，设置切换屏障仍会释放并保留旧服务。
     */
    @Test
    fun `candidate deadline releases switch barrier while readiness and close are both hanging`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val candidateComponent = mockk<AgentComponent>()
        val oldService = mockk<OpenAIAgentService>()
        val candidateService = mockk<OpenAIAgentService>()
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "old-key", agentEnabled = true),
        )
        val replacementSettings = initialSettings.copy(
            ai = initialSettings.ai!!.copy(openAiApiKey = "new-key"),
        )
        val settingsFlow = MutableStateFlow(initialSettings)
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val firstCreated = CompletableDeferred<Unit>()
        val candidateCreated = CompletableDeferred<Unit>()
        val candidateCloseCalled = CompletableDeferred<Unit>()
        val hangingMcpReadiness = Job()
        val hangingCandidateClose = Job()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns settingsFlow
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns MutableSharedFlow()
        every { agentComponentFactory.create() } returnsMany listOf(oldComponent, candidateComponent)
        every { oldComponent.openAIAgentService } answers {
            firstCreated.complete(Unit)
            oldService
        }
        every { candidateComponent.openAIAgentService } answers {
            candidateCreated.complete(Unit)
            candidateService
        }
        every { oldService.initializationJob() } returns null
        every { candidateService.initializationJob() } returns hangingMcpReadiness
        every { oldService.close() } returns Job().apply { complete() }
        every { candidateService.close() } answers {
            candidateCloseCalled.complete(Unit)
            hangingCandidateClose
        }
        every { oldService.currentModel } returns "old-model"

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
            AgentExecutionDeadlines(
                mcpBatch = 1.seconds,
                candidateInitialization = 100.milliseconds,
                scheduledTurn = 1.seconds,
            ),
        )

        try {
            withTimeout(5.seconds) { firstCreated.await() }
            withTimeout(5.seconds) {
                while (barrier.isSwitching) {
                    yield()
                }
            }
            assertEquals("old-model", delegatingAgentService.currentModel)

            val generation = barrier.beginSwitch()
            settingsFlow.value = replacementSettings
            settingsUpdateFlow.value = SettingsUpdate(replacementSettings, 1, generation)

            withTimeout(5.seconds) { candidateCreated.await() }
            withTimeout(5.seconds) { candidateCloseCalled.await() }
            withTimeout(5.seconds) {
                while (barrier.isSwitching) {
                    yield()
                }
            }

            assertEquals("old-model", delegatingAgentService.currentModel)
            assertTrue(
                withTimeout(1.seconds) {
                    delegatingAgentService.withReadyService { readyService -> readyService === oldService }
                },
                "released barrier must admit operations on the retained old service",
            )
        } finally {
            hangingMcpReadiness.complete()
            hangingCandidateClose.complete()
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证初始设置订阅和技能更新的处理设计。
     *
     * 验证初始订阅仅创建一次组件，技能更新会重置会话。
     */
    @Test
    fun `StateFlow 初始订阅只创建一次组件且技能更新会重置会话`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
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
            delegatingAgentService.close().join()
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
        val settingsRepository = mockedSettingsRepository()
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
            delegatingAgentService.close().join()
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
        val settingsRepository = mockedSettingsRepository()
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
        every { openAIAgentService.initializationJob() } returns null
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
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证上下文和授权聊天变更会等待已放行请求排空及会话重置，且在此期间持续阻塞新的发送。
     */
    @Test
    fun `global context and agent chat changes drain requests before reset and block new sends`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val agentComponent = mockk<AgentComponent>()
        val openAIAgentService = mockk<OpenAIAgentService>(relaxed = true)
        val initialSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "test-api-key",
                agentEnabled = true,
                agentChatId = "first-chat",
                globalContext = "first context",
            ),
        )
        val settingsFlow = MutableStateFlow(initialSettings)
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val componentCreated = CompletableDeferred<Unit>()
        val inFlightStarted = CompletableDeferred<Unit>()
        val allowInFlight = CompletableDeferred<Unit>()
        val firstResetStarted = CompletableDeferred<Unit>()
        val secondResetStarted = CompletableDeferred<Unit>()
        val firstReset = Job()
        val secondReset = Job()
        var resetCount = 0
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
        every { openAIAgentService.initializationJob() } returns null
        every { openAIAgentService.close() } returns Job().apply { complete() }
        every { openAIAgentService.resetSession() } answers {
            when (resetCount++) {
                0 -> {
                    firstResetStarted.complete(Unit)
                    firstReset
                }

                1 -> {
                    secondResetStarted.complete(Unit)
                    secondReset
                }

                else -> error("Unexpected extra reset")
            }
        }
        coEvery { openAIAgentService.sendMessage("in-flight", emptyList()) } coAnswers {
            inFlightStarted.complete(Unit)
            allowInFlight.await()
            "old reply"
        }
        coEvery { openAIAgentService.sendMessage("new", emptyList()) } returns "new reply"

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { componentCreated.await() }
            withTimeout(5.seconds) { while (barrier.isSwitching) yield() }

            val inFlight = async { delegatingAgentService.sendMessage("in-flight") }
            withTimeout(5.seconds) { inFlightStarted.await() }
            val contextSettings = initialSettings.copy(
                ai = initialSettings.ai!!.copy(globalContext = "replacement context"),
            )
            settingsFlow.value = contextSettings
            settingsUpdateFlow.value = SettingsUpdate(contextSettings, 1, barrier.beginSwitch())
            val firstNewSend = async { delegatingAgentService.sendMessage("new") }

            yield()
            assertFalse(firstResetStarted.isCompleted)
            assertFalse(firstNewSend.isCompleted)
            coVerify(exactly = 0) { openAIAgentService.sendMessage("new", emptyList()) }

            allowInFlight.complete(Unit)
            assertEquals("old reply", withTimeout(5.seconds) { inFlight.await() })
            withTimeout(5.seconds) { firstResetStarted.await() }
            assertTrue(barrier.isSwitching)
            assertFalse(firstNewSend.isCompleted)

            firstReset.complete()
            assertEquals("new reply", withTimeout(5.seconds) { firstNewSend.await() })
            withTimeout(5.seconds) { while (barrier.isSwitching) yield() }

            val chatSettings = contextSettings.copy(
                ai = contextSettings.ai!!.copy(agentChatId = "replacement-chat"),
            )
            settingsFlow.value = chatSettings
            settingsUpdateFlow.value = SettingsUpdate(chatSettings, 2, barrier.beginSwitch())
            withTimeout(5.seconds) { secondResetStarted.await() }
            val secondNewSend = async { delegatingAgentService.sendMessage("new") }

            yield()
            assertTrue(barrier.isSwitching)
            assertFalse(secondNewSend.isCompleted)
            coVerify(exactly = 1) { openAIAgentService.sendMessage("new", emptyList()) }

            secondReset.complete()
            assertEquals("new reply", withTimeout(5.seconds) { secondNewSend.await() })
            withTimeout(5.seconds) { while (barrier.isSwitching) yield() }
            verify(exactly = 2) { openAIAgentService.resetSession() }
        } finally {
            allowInFlight.complete(Unit)
            firstReset.complete()
            secondReset.complete()
            delegatingAgentService.close().join()
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
        val settingsRepository = mockedSettingsRepository()
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
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证代理重建时新组件的发布顺序。
     *
     * 替代组件必须先完成初始化；之后旧组件的关闭会转入后台追踪，不能阻塞新代理发布或模型切换屏障。
     */
    @Test
    fun `重建会在旧代理慢速关闭时发布已创建的新代理`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val newComponent = mockk<AgentComponent>()
        val oldService = mockk<OpenAIAgentService>()
        val newService = mockk<OpenAIAgentService>()
        val initialSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "old-key",
                agentEnabled = true,
            ),
        )
        val replacementSettings = initialSettings.copy(
            ai = initialSettings.ai!!.copy(openAiApiKey = "new-key"),
        )
        val settingsFlow = MutableStateFlow(initialSettings)
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val firstComponentCreated = CompletableDeferred<Unit>()
        val newConnectionStarted = CompletableDeferred<Unit>()
        val oldCloseStarted = CompletableDeferred<Unit>()
        val initialMcpConnection = Job()
        val releaseOldClose = Job()
        val barrier = ModelSwitchBarrier()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns settingsFlow
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } returnsMany listOf(oldComponent, newComponent)
        every { oldComponent.openAIAgentService } answers {
            firstComponentCreated.complete(Unit)
            oldService
        }
        every { newComponent.openAIAgentService } answers {
            newConnectionStarted.complete(Unit)
            newService
        }
        every { oldService.initializationJob() } returns null
        every { newService.initializationJob() } returns initialMcpConnection
        every { oldService.close() } answers {
            oldCloseStarted.complete(Unit)
            releaseOldClose
        }
        every { newService.close() } returns Job().apply { complete() }
        every { oldService.currentModel } returns "old-model"
        every { newService.currentModel } returns "new-model"

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { firstComponentCreated.await() }
            settingsFlow.value = replacementSettings
            settingsUpdateFlow.value = SettingsUpdate(replacementSettings, 1, barrier.beginSwitch())

            withTimeout(5.seconds) { newConnectionStarted.await() }
            assertFalse(oldCloseStarted.isCompleted)
            initialMcpConnection.complete()
            withTimeout(5.seconds) { oldCloseStarted.await() }
            withTimeout(5.seconds) {
                while (delegatingAgentService.currentModel != "new-model") yield()
            }
            assertEquals("new-model", delegatingAgentService.currentModel)
            assertFalse(releaseOldClose.isCompleted, "old cleanup must not delay candidate publication")
            releaseOldClose.complete()
            verify(exactly = 1) { oldService.close() }
        } finally {
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证 Gemini 替代实例完成首轮初始化后会立即发布，不等待旧代理的慢速关闭。
     */
    @Test
    fun `Gemini replacement publishes after readiness without waiting for old close`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val newComponent = mockk<AgentComponent>()
        val oldService = mockk<OpenAIAgentService>()
        val newService = mockk<GeminiAgentService>()
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "old-key", agentEnabled = true),
        )
        val replacementSettings = initialSettings.copy(
            ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key", agentEnabled = true),
        )
        val settingsFlow = MutableStateFlow(initialSettings)
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val firstComponentCreated = CompletableDeferred<Unit>()
        val replacementCreated = CompletableDeferred<Unit>()
        val oldCloseStarted = CompletableDeferred<Unit>()
        val geminiReadiness = Job()
        val releaseOldClose = Job()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val barrier = ModelSwitchBarrier()

        every { settingsRepository.settingsFlow } returns settingsFlow
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } returnsMany listOf(oldComponent, newComponent)
        every { oldComponent.openAIAgentService } answers {
            firstComponentCreated.complete(Unit)
            oldService
        }
        every { newComponent.geminiAgentService } answers {
            replacementCreated.complete(Unit)
            newService
        }
        every { oldService.initializationJob() } returns null
        every { newService.initializationJob() } returns geminiReadiness
        every { oldService.close() } answers {
            oldCloseStarted.complete(Unit)
            releaseOldClose
        }
        every { newService.close() } returns Job().apply { complete() }
        every { oldService.currentModel } returns "old-model"
        every { newService.currentModel } returns "gemini-model"

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            barrier,
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { firstComponentCreated.await() }
            settingsFlow.value = replacementSettings
            settingsUpdateFlow.value = SettingsUpdate(replacementSettings, 1, null)

            withTimeout(5.seconds) { replacementCreated.await() }
            assertFalse(oldCloseStarted.isCompleted)
            assertEquals("old-model", delegatingAgentService.currentModel)

            geminiReadiness.complete()
            withTimeout(5.seconds) { oldCloseStarted.await() }
            withTimeout(5.seconds) {
                while (delegatingAgentService.currentModel != "gemini-model") yield()
            }
            assertFalse(releaseOldClose.isCompleted, "old cleanup must not delay the replacement publication")
            releaseOldClose.complete()
        } finally {
            geminiReadiness.complete()
            releaseOldClose.complete()
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
        Unit
    }

    /**
     * 验证 Gemini 初始化任务取消时关闭替代实例并保留已发布的旧代理。
     *
     * 单个 MCP 服务器的连接错误由 MCP 客户端降级处理，不属于本测试的初始化任务取消情形。
     */
    @Test
    fun `cancelled Gemini initialization keeps the old agent`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val oldComponent = mockk<AgentComponent>()
        val newComponent = mockk<AgentComponent>()
        val oldService = mockk<OpenAIAgentService>()
        val newService = mockk<GeminiAgentService>()
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "old-key", agentEnabled = true),
        )
        val replacementSettings = initialSettings.copy(
            ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key", agentEnabled = true),
        )
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(initialSettings, 0, null))
        val firstComponentCreated = CompletableDeferred<Unit>()
        val replacementCreated = CompletableDeferred<Unit>()
        val failedReadiness = Job().apply { cancel() }
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(initialSettings)
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns MutableSharedFlow()
        every { agentComponentFactory.create() } returnsMany listOf(oldComponent, newComponent)
        every { oldComponent.openAIAgentService } answers {
            firstComponentCreated.complete(Unit)
            oldService
        }
        every { newComponent.geminiAgentService } answers {
            replacementCreated.complete(Unit)
            newService
        }
        every { newService.initializationJob() } returns failedReadiness
        every { oldService.initializationJob() } returns null
        every { oldService.close() } returns Job().apply { complete() }
        every { newService.close() } returns Job().apply { complete() }
        every { oldService.currentModel } returns "old-model"

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            ModelSwitchBarrier(),
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { firstComponentCreated.await() }
            withTimeout(5.seconds) {
                while (runCatching { delegatingAgentService.currentModel }.getOrNull() != "old-model") yield()
            }
            settingsUpdateFlow.value = SettingsUpdate(replacementSettings, 1, null)
            withTimeout(5.seconds) { replacementCreated.await() }
            withTimeout(5.seconds) {
                while (delegatingAgentService.currentModel != "old-model") yield()
            }

            verify(exactly = 0) { oldService.close() }
            verify(exactly = 1) { newService.close() }
        } finally {
            delegatingAgentService.close().join()
            serviceScope.cancel()
            serviceScope.coroutineContext[Job]?.join()
        }
    }

    /**
     * 验证委派服务关闭在调用方取消等待任务后仍会关闭当前代理。
     *
     * 重试关闭会提供可等待的任务，直到已开始的代理清理结束。
     */
    @Test
    fun `Delegating close survives cancellation of its returned wait job`() = runBlocking {
        val settingsRepository = mockedSettingsRepository()
        val skillRepository = mockk<SkillRepository>()
        val agentComponentFactory = mockk<AgentComponent.Factory>()
        val agentComponent = mockk<AgentComponent>()
        val agentService = mockk<OpenAIAgentService>()
        val settings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "test-api-key",
                agentEnabled = true,
            ),
        )
        val settingsUpdateFlow = MutableStateFlow(SettingsUpdate(settings, 0, null))
        val skillsUpdateEvent = MutableSharedFlow<Unit>()
        val agentCreated = CompletableDeferred<Unit>()
        val agentCloseCalled = CompletableDeferred<Unit>()
        val agentCloseJob = Job()
        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        every { settingsRepository.settingsFlow } returns MutableStateFlow(settings)
        every { settingsRepository.settingsUpdateFlow } returns settingsUpdateFlow
        every { skillRepository.skillsUpdateEvent } returns skillsUpdateEvent
        every { agentComponentFactory.create() } returns agentComponent
        every { agentComponent.openAIAgentService } answers {
            agentCreated.complete(Unit)
            agentService
        }
        every { agentService.initializationJob() } returns null
        every { agentService.close() } answers {
            agentCloseCalled.complete(Unit)
            agentCloseJob
        }

        val delegatingAgentService = DelegatingAgentService(
            agentComponentFactory,
            settingsRepository,
            skillRepository,
            ModelSwitchBarrier(),
            serviceScope,
        )

        try {
            withTimeout(5.seconds) { agentCreated.await() }
            val cancelledWaitJob = delegatingAgentService.close()
            cancelledWaitJob.cancel()
            val retryWaitJob = delegatingAgentService.close()

            withTimeout(5.seconds) { agentCloseCalled.await() }
            assertFalse(retryWaitJob.isCompleted)

            agentCloseJob.complete()
            withTimeout(5.seconds) { retryWaitJob.join() }
            verify(exactly = 1) { agentService.close() }
        } finally {
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
