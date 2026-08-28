package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.di.AgentComponent
import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.SettingsChangeCoordinator
import com.unscientificjszhai.tgp.service.SettingsUpdate
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.AgentExecutionDeadlines
import com.unscientificjszhai.tgp.utils.SafeLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.LoggerFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 根据最新设置发布一个完整就绪的 AI Agent，并在初始化故障后自动创建全新候选恢复。
 *
 * 设置/技能观察器只提交不可变目标，不等待网络。恢复控制器保证同一时刻最多初始化一个候选；每个失败
 * 实例都会关闭，过期候选即使迟到成功也无法通过本类的生命周期锁发布。新配置未就绪时保留旧实例仅供
 * 退出和关闭，所有新准入仍保持 fail-closed。
 */
@Singleton
class DelegatingAgentService @Inject internal constructor(
    private val agentComponentFactory: AgentComponent.Factory,
    private val settingsChangeCoordinator: SettingsChangeCoordinator,
    skillRepository: SkillRepository,
    private val modelSwitchBarrier: ModelSwitchBarrier,
    parentScope: CoroutineScope,
    @Suppress("UNUSED_PARAMETER")
    deadlines: AgentExecutionDeadlines = AgentExecutionDeadlines(),
) : AgentService() {
    private val logger = LoggerFactory.getLogger(DelegatingAgentService::class.java)
    private val lifecycleLock = Any()
    private val cleanupLock = Any()

    /** 失败候选、退休服务和关闭时的当前服务共同使用的全局后台清理容量。 */
    private val agentCleanupPermits = Semaphore(2)
    private val closingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val recoveryParentScope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]),
    )
    private val backgroundCleanupJobs = mutableSetOf<Job>()

    private val initialReadinessGeneration = modelSwitchBarrier.beginSwitch()
    private var initialReadinessPending = true

    @Volatile
    private var closed = false
    private var settingsJob: Job? = null
    private var closeCompletion: CompletableDeferred<Unit>? = null
    private var closeJob: Job? = null

    /**
     * 一个设置版本期望使用的完整 Agent 配置。
     *
     * @property settingsVersion 生成配置的单调设置版本。
     * @property provider 目标 AI 提供商。
     * @property apiKey 目标提供商凭据。
     * @property baseUrl 目标提供商基础地址。
     * @property proxySettings 目标网络代理设置。
     */
    private data class AgentConfiguration(
        val settingsVersion: Long,
        val provider: AIProvider,
        val apiKey: String,
        val baseUrl: String,
        val proxySettings: ProxySettings?,
    ) {
        val networkIdentity: NetworkIdentity
            get() = NetworkIdentity(provider, apiKey, baseUrl, proxySettings)
    }

    /**
     * 决定 Agent 组件能否复用的网络身份。
     *
     * @property provider 目标 AI 提供商。
     * @property apiKey 目标提供商凭据。
     * @property baseUrl 目标提供商基础地址。
     * @property proxySettings 目标网络代理设置。
     */
    private data class NetworkIdentity(
        val provider: AIProvider,
        val apiKey: String,
        val baseUrl: String,
        val proxySettings: ProxySettings?,
    )

    /**
     * 带生命周期 epoch 的 Agent 收敛目标。
     *
     * @property epoch 创建目标时的服务生命周期代次。
     * @property settingsUpdate 触发目标的设置事件。
     * @property configuration 需要构建或复用的 Agent 配置。
     */
    private data class AgentTarget(
        val epoch: Long,
        val settingsUpdate: SettingsUpdate,
        val configuration: AgentConfiguration,
    )

    /**
     * 尚未提交为当前 Agent 的候选组件与服务。
     *
     * @property component 持有候选服务依赖的 Dagger 子组件。
     * @property service 候选 Agent 服务。
     */
    private data class AgentCandidate(
        val component: AgentComponent,
        val service: AgentService,
    )

    /**
     * 首次关闭调用原子摘取的待清理资源。
     *
     * @property completion 所有关闭调用共享的完成信号。
     * @property settingsJob 设置订阅任务。
     * @property terminalTransitionJob 当前 Agent 终态转换任务。
     * @property currentService 关闭开始时的当前 Agent 服务。
     */
    private data class ClosingResources(
        val completion: CompletableDeferred<Unit>,
        val settingsJob: Job?,
        val terminalTransitionJob: Job?,
        val currentService: AgentService?,
    )

    private var lifecycleEpoch = 0L
    private var desiredSettingsVersion = -1L
    private var desiredConfiguration: AgentConfiguration? = null
    private var readyConfiguration: AgentConfiguration? = null
    private var lastRecoveryNetworkIdentity: NetworkIdentity? = null
    private var terminalTransitionJob: Job? = null
    private var currentAgentComponent: AgentComponent? = null

    @Volatile
    private var _currentService: AgentService? = null

    private val recoveryController = AgentRecoveryController(
        parentScope = recoveryParentScope,
        logger = logger,
        createCandidate = ::createCandidate,
        initializeCandidate = { candidate -> candidate.service.initializeForPublication() },
        publishCandidate = ::publishCandidate,
        closeCandidate = { candidate -> candidate.service.close() },
        cleanupPermits = agentCleanupPermits,
        prepareFirstAttempt = {
            // readyConfiguration is invalidated before the target is submitted, so new requests fail closed while
            // this drains every request that was admitted by the prior lifecycle.
            modelSwitchBarrier.awaitInFlightRequests()
        },
    )

    override val availability: StateFlow<AgentAvailabilitySnapshot>
        get() = recoveryController.availability

    init {
        settingsJob = combine(
            settingsChangeCoordinator.settingsUpdateFlow,
            skillRepository.skillsUpdateEvent.onStart { emit(Unit) },
        ) { settingsUpdate, _ -> settingsUpdate }
            .onEach(::submitLifecycleTarget)
            .launchIn(parentScope)
            .also { job -> job.invokeOnCompletion { completeInitialReadiness() } }
    }

    /** 把一次设置或技能事件线性化为恢复目标；本方法不执行网络或长时间等待。 */
    private fun submitLifecycleTarget(settingsUpdate: SettingsUpdate) {
        val configuration = settingsUpdate.toAgentConfigurationOrNull()
        val (epoch, supersededTerminalTransition) = synchronized(lifecycleLock) {
            if (closed || settingsUpdate.version < desiredSettingsVersion) return
            val previousTerminalTransition = terminalTransitionJob
            terminalTransitionJob = null
            desiredSettingsVersion = settingsUpdate.version
            desiredConfiguration = configuration
            readyConfiguration = null
            ++lifecycleEpoch to previousTerminalTransition
        }
        supersededTerminalTransition?.cancel(
            CancellationException("Agent terminal lifecycle target was superseded."),
        )
        val firstAttemptFinished = {
            modelSwitchBarrier.completeSettingsThrough(settingsUpdate.switchGeneration)
            completeInitialReadiness()
        }

        val aiSettings = settingsUpdate.settings.ai
        if (configuration == null || aiSettings == null) {
            recoveryController.disable(settingsUpdate.version)
            scheduleTerminalTransition(epoch, settingsUpdate.version, firstAttemptFinished)
            return
        }
        if (
            configuration.provider == AIProvider.OPENAI &&
            settingsChangeCoordinator.hasHistoricalInvalidOpenAiBaseUrl
        ) {
            recoveryController.block(
                provider = configuration.provider,
                settingsVersion = configuration.settingsVersion,
                failure = AgentFailure(
                    kind = AgentFailureKind.CONFIGURATION,
                    disposition = RecoveryDisposition.WAIT_FOR_CONFIGURATION,
                ),
            )
            scheduleTerminalTransition(epoch, settingsUpdate.version, firstAttemptFinished)
            return
        }

        val resetFailures = synchronized(lifecycleLock) {
            (lastRecoveryNetworkIdentity != configuration.networkIdentity).also {
                lastRecoveryNetworkIdentity = configuration.networkIdentity
            }
        }
        recoveryController.replaceTarget(
            target = AgentTarget(epoch, settingsUpdate, configuration),
            provider = configuration.provider,
            settingsVersion = configuration.settingsVersion,
            resetFailures = resetFailures,
            onFirstAttemptFinished = firstAttemptFinished,
        )
    }

    /**
     * 禁用或确定配置阻塞时先停止新准入，再等待此前已准入请求退出，最后摘除并关闭旧发布服务。
     *
     * 新目标会取消本任务；[detachPublishedAgent] 的 epoch/version 校验保证迟到排空永远不能摘除后来发布的
     * 服务。设置屏障在成功、抢占、关闭或等待取消时都由任务完成回调释放。
     */
    private fun scheduleTerminalTransition(
        epoch: Long,
        settingsVersion: Long,
        onFinished: () -> Unit,
    ) {
        val transitionJob = recoveryParentScope.launch(start = CoroutineStart.LAZY) {
            modelSwitchBarrier.awaitInFlightRequests()
            detachPublishedAgent(epoch, settingsVersion)?.let { service ->
                scheduleBackgroundCleanup(service)
            }
        }
        val accepted = synchronized(lifecycleLock) {
            if (closed || lifecycleEpoch != epoch || desiredSettingsVersion != settingsVersion) {
                false
            } else {
                terminalTransitionJob = transitionJob
                true
            }
        }
        transitionJob.invokeOnCompletion {
            onFinished()
            synchronized(lifecycleLock) {
                if (terminalTransitionJob === transitionJob) terminalTransitionJob = null
            }
        }
        if (accepted) {
            transitionJob.start()
        } else {
            transitionJob.cancel(CancellationException("Agent terminal lifecycle target is stale."))
        }
    }

    /** 创建拥有独立 AgentScope、HTTP 传输和 MCP 客户端的候选。 */
    private suspend fun createCandidate(target: AgentTarget): AgentCandidate {
        // The recovery controller already owns one permit from the global cleanup capacity before invoking this
        // callback. Two unfinished closes therefore pause component creation without a second, nested acquisition.
        check(isTargetCurrent(target)) { "Agent recovery target is stale." }
        val component = agentComponentFactory.create()
        val service = when (target.configuration.provider) {
            AIProvider.OPENAI -> component.openAIAgentService
            AIProvider.GEMINI -> component.geminiAgentService
        }
        return AgentCandidate(component, service)
    }

    /** 在生命周期锁内核对 epoch、设置版本和完整配置并原子发布候选。 */
    private fun publishCandidate(target: AgentTarget, candidate: AgentCandidate): Boolean {
        var previous: AgentService? = null
        val published = synchronized(lifecycleLock) {
            if (!isTargetCurrentLocked(target) || !isSettingsVersionCurrent(target.configuration.settingsVersion)) {
                false
            } else {
                previous = _currentService
                currentAgentComponent = candidate.component
                _currentService = candidate.service
                readyConfiguration = target.configuration
                true
            }
        }
        if (!published) return false
        previous?.takeIf { it !== candidate.service }?.let(::scheduleBackgroundCleanup)
        logger.debug(
            "Published recovered Agent candidate; provider={} settingsVersion={}",
            target.configuration.provider,
            target.configuration.settingsVersion,
        )
        return true
    }

    /** 仅在目标仍是最新生命周期时摘除已发布服务。 */
    private fun detachPublishedAgent(epoch: Long, settingsVersion: Long): AgentService? = synchronized(lifecycleLock) {
        if (closed || lifecycleEpoch != epoch || desiredSettingsVersion != settingsVersion) return@synchronized null
        _currentService.also { clearCurrentAgentLocked() }
    }

    private fun isTargetCurrent(target: AgentTarget): Boolean = synchronized(lifecycleLock) {
        isTargetCurrentLocked(target) && isSettingsVersionCurrent(target.configuration.settingsVersion)
    }

    private fun isTargetCurrentLocked(target: AgentTarget): Boolean =
        !closed &&
                lifecycleEpoch == target.epoch &&
                desiredSettingsVersion == target.configuration.settingsVersion &&
                desiredConfiguration == target.configuration

    private fun isSettingsVersionCurrent(settingsVersion: Long): Boolean =
        settingsChangeCoordinator.settingsUpdateFlow.value.version == settingsVersion

    private fun AISettings.requiredApiKey(): String = when (provider) {
        AIProvider.OPENAI -> openAiApiKey
        AIProvider.GEMINI -> geminiApiKey
    }

    private fun SettingsUpdate.toAgentConfigurationOrNull(): AgentConfiguration? {
        val aiSettings = settings.ai ?: return null
        val apiKey = aiSettings.requiredApiKey()
        if (!aiSettings.agentEnabled || apiKey.isBlank()) return null
        return AgentConfiguration(
            settingsVersion = version,
            provider = aiSettings.provider,
            apiKey = apiKey,
            baseUrl = aiSettings.openAiBaseUrl,
            proxySettings = settings.proxy,
        )
    }

    private fun clearCurrentAgentLocked() {
        _currentService = null
        currentAgentComponent = null
        readyConfiguration = null
    }

    private fun completeInitialReadiness() {
        val shouldComplete = synchronized(lifecycleLock) {
            initialReadinessPending.also { initialReadinessPending = false }
        }
        if (shouldComplete) modelSwitchBarrier.complete(initialReadinessGeneration)
    }

    /** 跟踪已退休发布服务的关闭；与失败候选共享全局两项清理容量。 */
    private fun scheduleBackgroundCleanup(service: AgentService) {
        val cleanupJob = closingScope.launch(start = CoroutineStart.LAZY) {
            agentCleanupPermits.acquire()
            try {
                service.close()?.join()
            } catch (e: Exception) {
                logger.error(
                    "Failed to close background Agent resources; category={}",
                    SafeLogging.failureCategory(e).wireName,
                )
            } finally {
                agentCleanupPermits.release()
            }
        }
        synchronized(cleanupLock) { backgroundCleanupJobs.add(cleanupJob) }
        cleanupJob.invokeOnCompletion {
            synchronized(cleanupLock) { backgroundCleanupJobs.remove(cleanupJob) }
        }
        cleanupJob.start()
    }

    private suspend fun drainBackgroundCleanup() {
        while (true) {
            val jobs = synchronized(cleanupLock) {
                backgroundCleanupJobs.removeAll { it.isCompleted }
                backgroundCleanupJobs.toList()
            }
            if (jobs.isEmpty()) return
            jobs.joinAll()
        }
    }

    override val currentModel: String
        get() = readyServiceForCurrentSettingsOrNull()?.currentModel
            ?: throw AgentConfigurationNotReadyException()

    override val availableModels: List<String>
        get() = readyServiceForCurrentSettingsOrNull()?.availableModels
            ?: throw AgentConfigurationNotReadyException()

    override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean {
        val settingsUpdate = settingsChangeCoordinator.settingsUpdateFlow.value
        val configuration = settingsUpdate.toAgentConfigurationOrNull() ?: return false
        if (settingsUpdate.settings.ai != aiSettings) return false
        val service = synchronized(lifecycleLock) {
            _currentService?.takeIf {
                !closed &&
                        desiredSettingsVersion == configuration.settingsVersion &&
                        desiredConfiguration == configuration &&
                        readyConfiguration == configuration
            }
        } ?: return false
        return service.isAiFeatureEnabled(aiSettings) && isReadyForCurrentSettings(configuration, service)
    }

    override fun switchModel(modelName: String): Job? =
        readyServiceForCurrentSettingsOrNull()?.switchModel(modelName)

    override suspend fun updateModel(): ModelSnapshot? =
        withReadyService { readyService -> readyService.updateModel() }

    override fun resetSession(): Job? {
        if (closed) return null
        val service = readyServiceForCurrentSettingsOrNull()
        // Every unpublished recovery candidate is a fresh AgentComponent and cannot carry history from the prior
        // Telegram token. When no service is admitted for the current settings there is therefore nothing usable to
        // clear; treating that case as a successful no-op lets token rotation install its new polling session while
        // recovery continues. Normal command/automatic resets still reach the exact ready service selected above.
        return service?.resetSession() ?: CompletableDeferred(Unit).also { it.complete(Unit) }
    }

    /**
     * 返回精确应用设置变更协调器当前状态的发布服务。
     *
     * @return 当前设置对应且已发布就绪的服务；技能重建或任意设置切换期间返回 `null`。
     */
    private fun readyServiceForCurrentSettingsOrNull(): AgentService? {
        val settingsUpdate = settingsChangeCoordinator.settingsUpdateFlow.value
        val configuration = settingsUpdate.toAgentConfigurationOrNull() ?: return null
        return synchronized(lifecycleLock) {
            _currentService?.takeIf { service ->
                isReadyForCapturedSettingsLocked(configuration, service) &&
                        isSettingsVersionCurrent(configuration.settingsVersion)
            }
        }
    }

    override suspend fun <T> withReadyService(block: suspend (AgentService) -> T): T =
        modelSwitchBarrier.runWhenReady {
            val settingsUpdate = settingsChangeCoordinator.settingsUpdateFlow.value
            val configuration = settingsUpdate.toAgentConfigurationOrNull()
                ?: throw AgentConfigurationNotReadyException()
            val service = synchronized(lifecycleLock) {
                _currentService?.takeIf { isReadyForCapturedSettingsLocked(configuration, it) }
            } ?: throw AgentConfigurationNotReadyException()
            withContext(AgentToolExecutionContext.from(settingsUpdate)) { block(service) }
        }

    private fun isReadyForCurrentSettings(configuration: AgentConfiguration, service: AgentService): Boolean =
        settingsChangeCoordinator.settingsUpdateFlow.value.let { current ->
            current.version == configuration.settingsVersion && synchronized(lifecycleLock) {
                isReadyForCapturedSettingsLocked(configuration, service) &&
                        isSettingsVersionCurrent(configuration.settingsVersion)
            }
        }

    private fun isReadyForCapturedSettingsLocked(
        configuration: AgentConfiguration,
        service: AgentService,
    ): Boolean =
        !closed &&
                desiredSettingsVersion == configuration.settingsVersion &&
                desiredConfiguration == configuration &&
                readyConfiguration == configuration &&
                _currentService === service

    override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String =
        withReadyService { readyService -> readyService.sendMessage(text, mediaData) }

    /** 取消恢复计时器和候选，关闭当前、失败及退休组件，且关闭后禁止任何发布。 */
    override fun close(): Job {
        val resources = synchronized(lifecycleLock) {
            closeCompletion?.let { return closeWaiter(it) }
            val completion = CompletableDeferred<Unit>()
            closed = true
            completeInitialReadiness()
            val settings = settingsJob
            settingsJob = null
            settings?.cancel()
            val terminalTransition = terminalTransitionJob
            terminalTransitionJob = null
            terminalTransition?.cancel(CancellationException("Delegating Agent service closed."))
            val current = _currentService
            clearCurrentAgentLocked()
            closeCompletion = completion
            ClosingResources(
                completion = completion,
                settingsJob = settings,
                terminalTransitionJob = terminalTransition,
                currentService = current,
            )
        }
        // Never acquire the recovery-controller lock while holding lifecycleLock: a concurrently linearized target
        // may complete its first-attempt callback from the controller lock and needs lifecycleLock to release barriers.
        val recoveryJob = recoveryController.close()
        closingScope.launch {
            withContext(NonCancellable) {
                try {
                    awaitCloseStep("settings subscription") { resources.settingsJob?.join() }
                    awaitCloseStep("terminal lifecycle transition") { resources.terminalTransitionJob?.join() }
                    awaitCloseStep("recovery controller") { recoveryJob.join() }
                    resources.currentService?.let(::scheduleBackgroundCleanup)
                    awaitCloseStep("current and retired Agents") { drainBackgroundCleanup() }
                } finally {
                    recoveryParentScope.cancel()
                    resources.completion.complete(Unit)
                }
            }
        }
        return closeWaiter(resources.completion)
    }

    private suspend fun awaitCloseStep(label: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            logger.error(
                "Failed to close delegating Agent {}; category={}",
                label,
                SafeLogging.failureCategory(e).wireName,
            )
        }
    }

    private fun closeWaiter(completion: CompletableDeferred<Unit>): Job = synchronized(lifecycleLock) {
        if (closeJob == null || closeJob?.isCancelled == true) {
            closeJob = closingScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) { completion.await() }
            }
        }
        closeJob!!
    }
}
