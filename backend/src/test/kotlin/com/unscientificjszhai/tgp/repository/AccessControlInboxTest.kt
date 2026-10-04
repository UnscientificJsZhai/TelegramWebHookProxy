package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AccessControlInboxTest {
    @Test
    fun `received cursor survives partial completion and clears after acknowledged backlog drains`() {
        val directory = createTempDirectory().toFile()
        try {
            val file = directory.resolve("updates.json")
            val repository = UpdatesRepository(file)
            repository.saveLastUpdateId("100", 100)
            repository.receiveUpdates("100", listOf(Update(110), Update(120)))
            repository.consumeAccessUnlock("100", 120)
            repository.saveLastUpdateId("100", 110)
            assertEquals(120, repository.getData("100").receivedThroughId)
            assertEquals(listOf(120L), repository.getData("100").receivedUpdates.map { it.updateId })
            assertEquals(120, UpdatesRepository(file).getData("100").receivedThroughId)

            repository.saveLastUpdateId("100", 200)
            assertNull(repository.getData("100").receivedThroughId)
            assertTrue(repository.getData("100").receivedUpdates.isEmpty())
            assertTrue(repository.getData("100").consumedAccessUnlockIds.isEmpty())
            assertNull(UpdatesRepository(file).getData("100").receivedThroughId)

            val replayed = repository.receiveUpdates("100", listOf(Update(120)))
            assertEquals(200, replayed.lastUpdateId)
            assertNull(replayed.receivedThroughId)
            val next = repository.receiveUpdates("100", listOf(Update(201)))
            assertEquals(201, next.receivedThroughId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `legacy stale received cursor is cleared without changing committed progress`() {
        val directory = createTempDirectory().toFile()
        try {
            val file = directory.resolve("updates.json")
            file.writeText("""{"bots":{"100":{"lastUpdateId":200,"receivedThroughId":120}}}""")
            val data = UpdatesRepository(file).receiveUpdates("100", emptyList())
            assertEquals(200, data.lastUpdateId)
            assertNull(data.receivedThroughId)
            assertNull(UpdatesRepository(file).getData("100").receivedThroughId)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `present corrupt inbox fields and nested payload cannot silently drop acknowledged updates`() {
        val directory = createTempDirectory().toFile()
        try {
            val file = directory.resolve("updates.json")
            listOf(
                """"receivedUpdates":false,"receivedThroughId":101""",
                """"receivedUpdates":[{"update_id":101,"message":false}],"receivedThroughId":101""",
                """"receivedUpdates":[{"update_id":101}],"receivedThroughId":"bad"""",
                """"consumedAccessUnlockIds":false""",
            ).forEach { fields ->
                val original = """{"bots":{"100":{"lastUpdateId":100,$fields}}}"""
                file.writeText(original)
                assertFailsWith<IllegalStateException>(fields) { UpdatesRepository(file) }
                assertEquals(original, file.readText())
            }
            file.writeText("""{"bots":{"100":{"lastUpdateId":100}}}""")
            assertTrue(UpdatesRepository(file).getData("100").receivedUpdates.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `inbox byte bound preserves headroom and cursor after rejected intake`() {
        val directory = createTempDirectory().toFile()
        try {
            val repository = UpdatesRepository(directory.resolve("updates.json"))
            repository.saveLastUpdateId("100", 100)
            val pending = (101L..107L).map { id -> Update(id, Message(id, Chat(42, "private"), "a".repeat(65000))) }
            repository.receiveUpdates("100", pending)
            assertFailsWith<IllegalArgumentException> {
                repository.receiveUpdates(
                    "100",
                    listOf(Update(108, Message(108, Chat(42, "private"), "a".repeat(100000))))
                )
            }
            assertEquals(107, repository.getData("100").receivedThroughId)
            repository.saveLastUpdateId("100", 101)
            assertEquals(101, repository.getData("100").lastUpdateId)
            assertEquals(6, repository.getData("100").receivedUpdates.size)
        } finally {
            directory.deleteRecursively()
        }
    }
}
