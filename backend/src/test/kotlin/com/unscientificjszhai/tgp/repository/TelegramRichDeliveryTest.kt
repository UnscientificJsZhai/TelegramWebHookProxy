package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.models.TelegramReplyPart
import com.unscientificjszhai.tgp.models.TelegramRichFormat
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TelegramRichDeliveryTest {
    private val directory = createTempDirectory("rich-delivery-test").toFile()
    private val file = directory.resolve("updates.json")
    private val source = "a".repeat(5000) + "后续"
    private val plan = listOf(
        TelegramReplyPart("```text\n${source.take(5000)}\n```", 0, 5000, TelegramRichFormat.MARKDOWN),
        TelegramReplyPart("**后续**", 5000, source.length, TelegramRichFormat.MARKDOWN),
    )

    @AfterTest
    fun cleanup() {
        directory.deleteRecursively()
    }

    @Test
    fun `final journal and partial plain fallback resume their saved plan`() {
        var repository = UpdatesRepository(file)
        repository.claimAgentTurn("100", 11, "chat", ReplyParameters(1))
        val final = assertNotNull(repository.finalizeAgentTurn("100", 11, source, plan))
        repository = UpdatesRepository(file)
        assertEquals(final, repository.getData("100").agentTurnJournal.single())
        repository.completeAgentUpdate(
            "100",
            11,
            PendingTelegramReply(11, "chat", source, deliveryPlan = final.deliveryPlan)
        )
        val rich = assertNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        val plain = rich.copy(deliveryStage = TelegramReplyDeliveryStage.PLAIN_FALLBACK, deliveryAttempts = 0)
        assertTrue(repository.replacePendingTelegramReply("100", rich, plain))
        val first = assertNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        assertEquals("a".repeat(4096), first.originalDeliveryText())
        assertTrue(repository.advancePendingTelegramReplyDelivery("100", first))

        repository = UpdatesRepository(file)
        val restored = repository.getPendingTelegramReplies("100").single()
        assertEquals(plan, restored.deliveryPlan)
        assertEquals(0, restored.nextPartIndex)
        assertEquals(4096, restored.plainFallbackStart)
        assertEquals("a".repeat(904), restored.originalDeliveryText())
        assertFalse(repository.advancePendingTelegramReplyDelivery("100", first), "迟到成功不能推进新快照")
        assertTrue(repository.advancePendingTelegramReplyDelivery("100", restored))
        val next = UpdatesRepository(file).getPendingTelegramReplies("100").single()
        assertEquals(1, next.nextPartIndex)
        assertEquals(0, next.plainFallbackStart)
        assertTrue(next.isRichDelivery())
        assertEquals("**后续**", next.originalDeliveryText())
        assertTrue(repository.advancePendingTelegramReplyDelivery("100", next))
        assertTrue(UpdatesRepository(file).getPendingTelegramReplies("100").isEmpty())
    }

    @Test
    fun `failed plan persistence leaves journal and cursor unchanged`() {
        var failWrite = false
        val repository = UpdatesRepository(file) { if (failWrite) throw IOException("injected failure") }
        repository.claimAgentTurn("100", 11, "chat", null)
        failWrite = true
        assertFailsWith<IOException> { repository.finalizeAgentTurn("100", 11, source, plan) }
        assertEquals(AgentTurnJournalStatus.IN_PROGRESS, repository.getData("100").agentTurnJournal.single().status)
        failWrite = false
        repository.finalizeAgentTurn("100", 11, source, plan)
        repository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", source, deliveryPlan = plan))
        val pending = repository.getPendingTelegramReplies("100").single()
        failWrite = true
        assertFailsWith<IOException> { repository.advancePendingTelegramReplyDelivery("100", pending) }
        assertEquals(pending, repository.getPendingTelegramReplies("100").single())
        assertEquals(pending, UpdatesRepository(file).getPendingTelegramReplies("100").single())
    }

    @Test
    fun `plan gaps and split surrogate pairs cannot be persisted`() {
        val repository = UpdatesRepository(file)
        val invalidPlans = listOf(plan.drop(1), plan.map { it.copy(sourceEnd = it.sourceEnd - 1) })
        invalidPlans.forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                repository.completeAgentUpdate(
                    "100",
                    11,
                    PendingTelegramReply(11, "chat", source, deliveryPlan = invalid)
                )
            }
        }
        assertFailsWith<IllegalArgumentException> {
            validateTelegramDeliveryPlan(
                "😀", listOf(
                    TelegramReplyPart("a", 0, 1, TelegramRichFormat.MARKDOWN),
                    TelegramReplyPart("b", 1, 2, TelegramRichFormat.MARKDOWN),
                )
            )
        }
        assertTrue(repository.getPendingTelegramReplies("100").isEmpty())
    }
}
