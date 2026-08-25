package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 具有显式、单次发布初始化阶段的提供商 Agent 基类。
 *
 * 构造器只能建立本地状态；由委派层创建候选后调用 [initializeForPublication] 才能执行网络发现、MCP
 * 连接和首轮会话配置。一次实例无论成功、失败或取消都不会再次执行初始化。
 */
abstract class ProviderAgentService : AgentService() {
    private val publicationInitializationMutex = Mutex()
    private var publicationInitialization: PublicationInitialization = PublicationInitialization.New

    final override suspend fun initializeForPublication(): AgentInitializationResult =
        publicationInitializationMutex.withLock {
            when (val state = publicationInitialization) {
                PublicationInitialization.New -> initializeOnce()
                is PublicationInitialization.Completed -> state.result
                PublicationInitialization.Cancelled -> throw CancellationException(
                    "Agent candidate initialization was cancelled.",
                )
            }
        }

    private suspend fun initializeOnce(): AgentInitializationResult {
        publicationInitialization = PublicationInitialization.Cancelled
        return try {
            performPublicationInitialization()
            AgentInitializationResult.Ready.also {
                publicationInitialization = PublicationInitialization.Completed(it)
            }
        } catch (e: TimeoutCancellationException) {
            // A provider-owned nested timeout is a real failure. Cancellation of an enclosing candidate attempt
            // must continue outward so a superseded target is never recorded as failed.
            if (!currentCoroutineContext().isActive) throw e
            AgentInitializationResult.Failed(AgentFailure.classify(e)).also {
                publicationInitialization = PublicationInitialization.Completed(it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentInitializationResult.Failed(AgentFailure.classify(e)).also {
                publicationInitialization = PublicationInitialization.Completed(it)
            }
        }
    }

    /** 执行首轮会话配置与模型发现；成功返回表示候选可以发布。 */
    protected abstract suspend fun performPublicationInitialization()

    /**
     * 等待提供商已有的异步会话任务，并保留其真实失败原因。
     *
     * [Job.join] 不传播子任务异常；发布初始化不能据此把网络错误压缩成布尔值。候选调用方取消时会同时
     * 取消该任务，而任务自身无原因取消则转换为安全的无效初始化失败。
     */
    protected suspend fun awaitPublicationJob(job: Job?) {
        val actualJob = job ?: throw AgentInvalidResponseException()
        suspendCancellableCoroutine { continuation ->
            actualJob.invokeOnCompletion { cause ->
                if (!continuation.isActive) return@invokeOnCompletion
                when (cause) {
                    null -> continuation.resume(Unit)
                    is CancellationException -> continuation.resumeWithException(AgentInvalidResponseException(cause))
                    else -> continuation.resumeWithException(cause)
                }
            }
            continuation.invokeOnCancellation { cancellation ->
                actualJob.cancel(cancellation as? CancellationException)
            }
        }
    }

    private sealed interface PublicationInitialization {
        data object New : PublicationInitialization
        data object Cancelled : PublicationInitialization
        data class Completed(val result: AgentInitializationResult) : PublicationInitialization
    }
}
