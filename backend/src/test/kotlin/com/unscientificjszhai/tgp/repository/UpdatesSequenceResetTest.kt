package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.*

internal class UpdatesSequenceResetTest {
    private val directory = createTempDirectory("updates-sequence-reset-test").toFile()
    private val file = directory.resolve("updates.json")

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun `sequence reset persists its first target while retaining chats and other bots`() {
        val repository = UpdatesRepository(file)
        val chat = ChatInfo(id = "123", title = "Test", type = "private")
        repository.updateData("100") { it.copy(lastUpdateId = 900_000_000, chats = listOf(chat)) }
        repository.saveLastUpdateId("200", 42)
        repository.recordRetryCheckpoint("100", 900_000_001, null, 1)
        val previousCheckpoint = repository.getData("100").retryCheckpoint

        assertTrue(repository.resetUpdateSequence("100", 900_000_000, previousCheckpoint, 1, 10))

        val reloaded = UpdatesRepository(file)
        val reset = reloaded.getData("100")
        assertEquals(0L, reset.lastUpdateId)
        assertEquals(RetryCheckpoint(1, 10, 1), reset.retryCheckpoint)
        assertEquals(listOf(chat), reset.chats)
        assertEquals(42L, reloaded.getData("200").lastUpdateId)
        assertEquals(RetryCheckpointCommitResult.Committed, reloaded.confirmProcessedUpdate("100", 1, 1))
        assertEquals(1L, UpdatesRepository(file).getData("100").lastUpdateId)
    }

    @Test
    fun `sequence reset waits for the old reply outbox without discarding it`() {
        val repository = UpdatesRepository(file)
        val reply = PendingTelegramReply(900_000_000, "123", "old reply")
        repository.updateData("100") { it.copy(lastUpdateId = 900_000_000, pendingTelegramReplies = listOf(reply)) }
        val before = repository.getData("100")

        assertFalse(repository.resetUpdateSequence("100", 900_000_000, null, 400_000_000, 10))
        assertEquals(before, UpdatesRepository(file).getData("100"))
        repository.deletePendingTelegramReply("100", reply.updateId)
        assertTrue(repository.resetUpdateSequence("100", 900_000_000, null, 400_000_000, 10))
    }

    @Test
    fun `sequence reset rejects stale snapshots and unconfirmed old journal entries`() {
        val repository = UpdatesRepository(file)
        repository.saveLastUpdateId("100", 900_000_000)
        repository.recordRetryCheckpoint("100", 900_000_001, null, 1)
        val checkpoint = repository.getData("100").retryCheckpoint
        repository.recordRetryCheckpoint("100", 900_000_001, 900_000_001, 2)
        assertFalse(repository.resetUpdateSequence("100", 900_000_000, checkpoint, 400_000_000, 10))
        val currentCheckpoint = repository.getData("100").retryCheckpoint
        repository.claimAgentTurn("100", 900_000_001, "123", null)
        val before = repository.getData("100")
        assertFalse(repository.resetUpdateSequence("100", 900_000_000, currentCheckpoint, 400_000_000, 10))
        assertEquals(before, UpdatesRepository(file).getData("100"))
    }

    @Test
    fun `failed sequence reset keeps the old cursor durable and can be retried`() {
        UpdatesRepository(file).saveLastUpdateId("100", 900_000_000)
        var rejectReset = true
        val repository = UpdatesRepository(file) { state ->
            if (rejectReset && state.bots["100"]?.lastUpdateId == 399_999_999L) {
                throw IOException("injected reset failure")
            }
        }
        assertFailsWith<IOException> {
            repository.resetUpdateSequence("100", 900_000_000, null, 400_000_000, 10)
        }
        assertEquals(900_000_000L, repository.getData("100").lastUpdateId)
        assertEquals(repository.getData("100"), UpdatesRepository(file).getData("100"))
        rejectReset = false
        assertTrue(repository.resetUpdateSequence("100", 900_000_000, null, 400_000_000, 10))
    }
}
