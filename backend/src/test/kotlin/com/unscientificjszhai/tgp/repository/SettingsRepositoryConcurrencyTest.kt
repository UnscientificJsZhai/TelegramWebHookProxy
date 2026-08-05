package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 设置仓储并发变换与内容修订值的测试设计。
 */
class SettingsRepositoryConcurrencyTest {

    /**
     * 验证并发局部变换都基于进入锁时的最新快照。
     */
    @Test
    fun `concurrent partial updates preserve both changes`() {
        val directory = createTempDirectory("settings-concurrent-test").toFile()
        try {
            val repository = SettingsRepository.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            val start = CountDownLatch(1)
            val threads = listOf(
                thread(start = true) {
                    start.await()
                    repository.updateSettings { current -> current.copy(chatId = "new-chat") }
                },
                thread(start = true) {
                    start.await()
                    repository.updateSettings { current ->
                        current.copy(ai = (current.ai ?: AISettings()).copy(globalContext = "new-context"))
                    }
                },
            )

            start.countDown()
            threads.forEach(Thread::join)

            assertEquals("new-chat", repository.settingsFlow.value.chatId)
            assertEquals("new-context", repository.settingsFlow.value.ai?.globalContext)
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * 验证过期 CAS 与相同值变换都不会产生持久化或发布副作用。
     */
    @Test
    fun `stale revision generation and no-op do not write or publish`() {
        val directory = createTempDirectory("settings-revision-test").toFile()
        try {
            val operations = CountingFileOperations()
            val barrier = ModelSwitchBarrier()
            val repository = SettingsRepository.forTesting(
                directory.resolve("settings.json"),
                barrier,
                operations,
            )
            val staleSnapshot = repository.currentSettingsSnapshot()
            val staleRevision = staleSnapshot.revision
            repository.updateSettings { it.copy(chatId = "current-chat") }
            val committed = repository.currentSettingsSnapshot()
            val writesAfterCommit = operations.writeCount
            val versionAfterCommit = repository.settingsUpdateFlow.value.version
            val generationAfterCommit = barrier.latestPendingGeneration()

            assertFailsWith<SettingsRevisionMismatchException> {
                repository.updateSettings(staleRevision) { it.copy(telegramToken = "should-not-save") }
            }
            assertFailsWith<SettingsGenerationMismatchException> {
                repository.updateSettings(expectedGeneration = staleSnapshot.generation) {
                    it.copy(telegramToken = "should-not-save")
                }
            }
            val noOp = repository.updateSettings { it.copy() }

            assertNotEquals(staleRevision, committed.revision)
            assertEquals(staleSnapshot.generation + 1, committed.generation)
            assertEquals(committed, noOp.previous)
            assertEquals(committed, noOp.current)
            assertEquals(writesAfterCommit, operations.writeCount)
            assertEquals(versionAfterCommit, repository.settingsUpdateFlow.value.version)
            assertEquals(generationAfterCommit, barrier.latestPendingGeneration())
            assertEquals("current-chat", repository.settingsFlow.value.chatId)
        } finally {
            directory.deleteRecursively()
        }
    }

    /** 验证读取设置快照时，不会将并发切换中的 token 和默认聊天标识跨代次混合。 */
    @Test
    fun `concurrent settings snapshots always preserve token and chat pairing`() {
        val directory = createTempDirectory("settings-snapshot-pairing-test").toFile()
        try {
            val repository = SettingsRepository.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            val settingsA = AppSettings(telegramToken = "100:token-a", chatId = "chat-a")
            val settingsB = AppSettings(telegramToken = "200:token-b", chatId = "chat-b")
            repository.updateSettings { settingsA }
            val startIteration = CyclicBarrier(2)
            val finishIteration = CyclicBarrier(2)
            val observedPairs = ConcurrentLinkedQueue<Pair<String, String>>()
            val iterations = 100

            val writer = thread(start = true) {
                repeat(iterations) { index ->
                    startIteration.await()
                    repository.updateSettings { if (index % 2 == 0) settingsB else settingsA }
                    finishIteration.await()
                }
            }
            val reader = thread(start = true) {
                repeat(iterations) {
                    startIteration.await()
                    val settings = repository.currentSettingsSnapshot().settings
                    observedPairs.add(settings.telegramToken to settings.chatId)
                    finishIteration.await()
                }
            }

            writer.join()
            reader.join()

            assertEquals(iterations, observedPairs.size)
            observedPairs.forEach { observed ->
                assertTrue(
                    observed == (settingsA.telegramToken to settingsA.chatId) ||
                            observed == (settingsB.telegramToken to settingsB.chatId),
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class CountingFileOperations : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        var writeCount = 0
            private set

        override fun writeAndForce(path: Path, bytes: ByteArray) {
            writeCount++
            DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
        }
    }
}
