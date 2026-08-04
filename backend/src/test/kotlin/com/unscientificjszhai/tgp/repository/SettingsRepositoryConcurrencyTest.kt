package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

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
    fun `stale revision and no-op do not write or publish`() {
        val directory = createTempDirectory("settings-revision-test").toFile()
        try {
            val operations = CountingFileOperations()
            val barrier = ModelSwitchBarrier()
            val repository = SettingsRepository.forTesting(
                directory.resolve("settings.json"),
                barrier,
                operations,
            )
            val staleRevision = repository.currentSettingsSnapshot().revision
            repository.updateSettings { it.copy(chatId = "current-chat") }
            val committed = repository.currentSettingsSnapshot()
            val writesAfterCommit = operations.writeCount
            val versionAfterCommit = repository.settingsUpdateFlow.value.version
            val generationAfterCommit = barrier.latestPendingGeneration()

            assertFailsWith<SettingsRevisionMismatchException> {
                repository.updateSettings(staleRevision) { it.copy(telegramToken = "should-not-save") }
            }
            val noOp = repository.updateSettings { it.copy() }

            assertNotEquals(staleRevision, committed.revision)
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

    private class CountingFileOperations : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        var writeCount = 0
            private set

        override fun writeAndForce(path: Path, bytes: ByteArray) {
            writeCount++
            DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
        }
    }
}
