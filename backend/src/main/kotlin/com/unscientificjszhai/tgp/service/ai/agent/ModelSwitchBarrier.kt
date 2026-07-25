package com.unscientificjszhai.tgp.service.ai.agent

import kotlinx.coroutines.CompletableDeferred
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用于保护可能替换或重置 AI 代理的变更的进程级屏障。
 *
 * 每次相关设置写入都会在持久化前开启一个新代次。发送方始终等待最新的未完成
 * 代次。有意保留较早的代次，直到其设置流处理完成：若较新的写入失败，等待中的
 * 发送方会继续等待此前有效的代次，而不会被过早放行。
 */
@Singleton
class ModelSwitchBarrier @Inject constructor() {
    private val lock = Any()
    private var nextGeneration = 0L
    private val pendingGenerations = LinkedHashMap<Long, CompletableDeferred<Unit>>()
    private var activeRequests = 0
    private var activeRequestsDrained = CompletableDeferred<Unit>().also { it.complete(Unit) }

    /**
     * 开启新的设置切换代次。
     *
     * @return 新代次的递增版本号；在该代次完成或取消前，请求会等待屏障放行。
     */
    fun beginSwitch(): Long = synchronized(lock) {
        val generation = ++nextGeneration
        pendingGenerations[generation] = CompletableDeferred()
        generation
    }

    /**
     * 获取最新的未完成切换代次。
     *
     * @return 最新未完成代次的版本号；不存在待处理切换时返回 `null`。
     */
    fun latestPendingGeneration(): Long? = synchronized(lock) {
        pendingGenerations.keys.lastOrNull()
    }

    /**
     * 指示当前是否存在会阻塞 AI 请求的未完成切换。
     *
     * 为 `true` 时，后续 [runWhenReady] 调用会等待至少一个切换代次完成；为 `false` 时不会
     * 因切换而等待。
     */
    val isSwitching: Boolean
        get() = synchronized(lock) { pendingGenerations.isNotEmpty() }

    /**
     * 等待期间成为最新代次的每个代次都完成后才返回。这能防止过期切换完成时，
     * 在较新的设置变更仍在应用期间放行请求。
     *
     * 此挂起函数不会阻塞线程；调用方取消时会停止等待，且不会改变屏障状态。
     */
    suspend fun awaitReady() {
        while (true) {
            val completion = synchronized(lock) {
                pendingGenerations.entries.lastOrNull()?.value
            } ?: return
            completion.await()
        }
    }

    /**
     * 仅当 [block] 以原子方式通过屏障后才执行。切换会在重置或关闭底层代理前
     * 等待所有已放行的代码块完成，因此在切换前进入的请求可以执行完毕。
     *
     * @param T [block] 的返回类型。
     * @param block 通过屏障后执行的挂起代码块；应尽快完成，避免延迟模型切换。
     * @return [block] 的返回值。
     */
    suspend fun <T> runWhenReady(block: suspend () -> T): T {
        while (true) {
            awaitReady()
            val admitted = synchronized(lock) {
                if (pendingGenerations.isEmpty()) {
                    if (activeRequests++ == 0) {
                        activeRequestsDrained = CompletableDeferred()
                    }
                    true
                } else {
                    false
                }
            }
            if (admitted) break
        }

        try {
            return block()
        } finally {
            val drained = synchronized(lock) {
                check(activeRequests > 0) { "Model switch request accounting underflow." }
                if (--activeRequests == 0) activeRequestsDrained else null
            }
            drained?.complete(Unit)
        }
    }

    /**
     * 等待当前切换前已放行的所有请求完成。
     *
     * 此方法不会阻止后续新请求进入；调用方通常应在开始替换或重置底层代理前调用。此挂起
     * 函数不会阻塞线程；调用方取消时会停止等待，且不会改变屏障状态。
     */
    suspend fun awaitInFlightRequests() {
        val completion = synchronized(lock) {
            activeRequestsDrained.takeIf { activeRequests > 0 }
        }
        completion?.await()
    }

    /**
     * 将已完成生命周期处理的设置快照所覆盖的所有代次标记为完成，无论处理成功
     * 还是失败。携带 [generation] 的 StateFlow 快照会取代此前所有已持久化的
     * 生命周期变更，包括生命周期收集器尚未来得及观察就被 StateFlow 合并的变更。
     *
     * @param generation 已完成设置快照携带的代次；为 `null` 时不执行任何操作。
     */
    fun completeThrough(generation: Long?) {
        if (generation == null) return

        val completions = synchronized(lock) {
            val completedGenerations = mutableListOf<CompletableDeferred<Unit>>()
            val iterator = pendingGenerations.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key > generation) break
                iterator.remove()
                completedGenerations += entry.value
            }
            completedGenerations
        }
        completions.forEach { it.complete(Unit) }
    }

    /**
     * 将指定设置流处理代次标记为完成。
     *
     * @param generation 要完成的代次；为 `null` 或不再待处理时不执行任何操作。
     */
    fun complete(generation: Long?) = finish(generation)

    /**
     * 取消设置写入未能持久化的代次。任何较早的有效代次仍保持未完成状态，
     * 因而继续保护调用。
     *
     * @param generation 要取消的代次；为 `null` 或不再待处理时不执行任何操作。
     */
    fun cancel(generation: Long?) = finish(generation)

    private fun finish(generation: Long?) {
        if (generation == null) return
        val completion = synchronized(lock) { pendingGenerations.remove(generation) }
        completion?.complete(Unit)
    }
}
