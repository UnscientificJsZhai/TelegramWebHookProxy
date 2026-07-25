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

    /** 开启新的切换代次并返回其版本号。 */
    fun beginSwitch(): Long = synchronized(lock) {
        val generation = ++nextGeneration
        pendingGenerations[generation] = CompletableDeferred()
        generation
    }

    /** 返回最新的未完成代次；若不存在则返回 null。 */
    fun latestPendingGeneration(): Long? = synchronized(lock) {
        pendingGenerations.keys.lastOrNull()
    }

    /** 当前 AI 请求是否应当等待。 */
    val isSwitching: Boolean
        get() = synchronized(lock) { pendingGenerations.isNotEmpty() }

    /**
     * 等待期间成为最新代次的每个代次都完成后才返回。这能防止过期切换完成时，
     * 在较新的设置变更仍在应用期间放行请求。
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

    /** 等待当前切换前已放行的请求完成。 */
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

    /** 将恰好为 [generation] 的设置流处理标记为完成。 */
    fun complete(generation: Long?) = finish(generation)

    /**
     * 取消设置写入未能持久化的代次。任何较早的有效代次仍保持未完成状态，
     * 因而继续保护调用。
     */
    fun cancel(generation: Long?) = finish(generation)

    private fun finish(generation: Long?) {
        if (generation == null) return
        val completion = synchronized(lock) { pendingGenerations.remove(generation) }
        completion?.complete(Unit)
    }
}
