package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 按机器人隔离的更新状态仓储测试设计。
 */
class UpdatesRepositoryTest {
    private val tempDirectory = createTempDirectory("updates-repository-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证两个 bot 的聊天、删除和偏移量互不影响，并支持同一 bot 的 token 轮换。
     */
    @Test
    fun `bot states and chat deletion are isolated`() {
        val repository = UpdatesRepository(tempDirectory.resolve("updates.json"))
        repository.mergeChats("100", listOf(ChatInfo("a", "A", "private")))
        repository.saveLastUpdateId("100", 11)
        repository.mergeChats("200", listOf(ChatInfo("b", "B", "private")))
        repository.saveLastUpdateId("200", 22)

        repository.deleteChat("100", "a")

        assertTrue(repository.getChats("100").isEmpty())
        assertEquals(11, repository.getData("100").lastUpdateId)
        assertEquals(listOf(ChatInfo("b", "B", "private")), repository.getChats("200"))
        assertEquals(22, repository.getData("200").lastUpdateId)
        assertEquals("100", "100:rotated-token".botIdFromTelegramToken())
        assertEquals(null, "100:   ".botIdFromTelegramToken())
        assertEquals(null, "100".botIdFromTelegramToken())
    }

    /**
     * 验证旧数组与旧对象格式只会在第一个有效 bot 访问时迁移。
     */
    @Test
    fun `legacy list and object migrate only to first valid bot`() {
        val listFile = tempDirectory.resolve("legacy-list.json")
        val oldChats = listOf(ChatInfo("a", "Legacy", "private"))
        listFile.writeText(ConfigJson.encodeToString(oldChats))
        val listRepository = UpdatesRepository(listFile)

        assertTrue(listRepository.getChats(" ").isEmpty())
        assertEquals(ConfigJson.encodeToString(oldChats), listFile.readText())
        assertEquals(oldChats, listRepository.getChats("100"))
        assertTrue(listRepository.getChats("200").isEmpty())

        val objectFile = tempDirectory.resolve("legacy-object.json")
        objectFile.writeText(ConfigJson.encodeToString(UpdatesData(oldChats, 33)))
        val objectRepository = UpdatesRepository(objectFile)

        assertEquals(33, objectRepository.getData("200").lastUpdateId)
        assertEquals(oldChats, objectRepository.getChats("200"))
        assertTrue(objectRepository.getChats("100").isEmpty())
    }

    /**
     * 验证旧状态首次迁移写入失败后，重试变更仍保留原有聊天和偏移量。
     */
    @Test
    fun `failed legacy migration keeps data for a later retry`() {
        val file = tempDirectory.resolve("failed-legacy-migration.json")
        val legacy = UpdatesData(listOf(ChatInfo("a", "Legacy", "private")), 7)
        file.writeText(ConfigJson.encodeToString(legacy))
        val repository = UpdatesRepository(file)

        assertTrue(file.delete())
        assertTrue(file.mkdirs())
        assertFailsWith<Exception> {
            repository.getData("100")
        }
        assertTrue(file.deleteRecursively())

        val retried = repository.saveLastUpdateId("100", 9)
        assertEquals(legacy.chats, retried.chats)
        assertEquals(9, retried.lastUpdateId)
        assertEquals(retried, UpdatesRepository(file).getData("100"))
    }

    /**
     * 验证新格式不会因忽略未知键而被误解为旧格式，并可在重载后保留所有 bot 状态。
     */
    @Test
    fun `new format reload keeps all bot states`() {
        val file = tempDirectory.resolve("new-format.json")
        val expected = BotUpdatesData(
            bots = mapOf(
                "100" to UpdatesData(listOf(ChatInfo("a", "A", "private")), 11),
                "200" to UpdatesData(listOf(ChatInfo("b", "B", "group")), 22),
            ),
        )
        file.writeText(ConfigJson.encodeToString(expected))

        val repository = UpdatesRepository(file)
        assertEquals(expected.bots["100"], repository.getData("100"))
        assertEquals(expected.bots["200"], repository.getData("200"))
        assertFalse(ConfigJson.parseToJsonElement(file.readText()) !is JsonObject)
    }

    /**
     * 验证并发聊天发现、聊天删除和偏移量保存不会丢失彼此字段。
     */
    @Test
    fun `concurrent mutations keep chats and offset`() = runBlocking {
        val repository = UpdatesRepository(tempDirectory.resolve("concurrent.json"))
        val addedChats = (1..100).map { ChatInfo(it.toString(), "chat-$it", "private") }

        addedChats.map { chat ->
            async(Dispatchers.Default) {
                repository.mergeChats("100", listOf(chat))
            }
        }.awaitAll()
        (1L..100L).map { offset ->
            async(Dispatchers.Default) {
                repository.saveLastUpdateId("100", offset)
            }
        }.awaitAll()
        repository.deleteChat("100", "1")

        val state = repository.getData("100")
        assertEquals(99, state.chats.size)
        assertEquals(100, state.lastUpdateId)
        assertFalse(state.chats.any { it.id == "1" })
    }

    /** 验证 Agent 完成记录、outbox 与偏移量原子写入，并按 bot 和更新标识隔离。 */
    @Test
    fun `agent completion atomically keeps ordered isolated outbox`() {
        val file = tempDirectory.resolve("outbox.json")
        val repository = UpdatesRepository(file)
        val later = PendingTelegramReply(12, "chat-a", "later", ReplyParameters(12))
        val first = PendingTelegramReply(11, "chat-a", "first", ReplyParameters(11))

        repository.completeAgentUpdate("100", 12, later)
        repository.completeAgentUpdate("100", 11, first)
        repository.completeAgentUpdate("200", 21, PendingTelegramReply(21, "chat-b", "other"))
        repository.completeAgentUpdate("100", 13)

        assertEquals(13, repository.getData("100").lastUpdateId)
        assertEquals(listOf(first, later), repository.getPendingTelegramReplies("100"))
        assertEquals(listOf(PendingTelegramReply(21, "chat-b", "other")), repository.getPendingTelegramReplies("200"))

        repository.deletePendingTelegramReply("100", 11)
        assertEquals(listOf(later), UpdatesRepository(file).getPendingTelegramReplies("100"))
        assertEquals(
            listOf(PendingTelegramReply(21, "chat-b", "other")),
            UpdatesRepository(file).getPendingTelegramReplies("200")
        )
    }

    /** 验证旧 JSON 默认空 outbox，且不允许投递尚未持久化确认的异常遗留记录。 */
    @Test
    fun `outbox defaults for old schema and only exposes confirmed updates`() {
        val oldFile = tempDirectory.resolve("old-outbox.json")
        oldFile.writeText(ConfigJson.encodeToString(UpdatesData(lastUpdateId = 7)))
        assertTrue(UpdatesRepository(oldFile).getPendingTelegramReplies("100").isEmpty())

        val aheadFile = tempDirectory.resolve("ahead-outbox.json")
        val ahead = PendingTelegramReply(8, "chat", "must wait")
        aheadFile.writeText(
            ConfigJson.encodeToString(
                UpdatesData(
                    lastUpdateId = 7,
                    pendingTelegramReplies = listOf(ahead)
                )
            )
        )
        assertTrue(UpdatesRepository(aheadFile).getPendingTelegramReplies("100").isEmpty())
    }

    /** 验证旧 outbox JSON 的回复默认使用原文阶段和零次投递，并在重启后保留新的投递状态。 */
    @Test
    fun `legacy outbox reply defaults delivery state and persists it across restart`() {
        val file = tempDirectory.resolve("legacy-outbox-delivery-state.json")
        file.writeText(
            """
            {
              "bots": {
                "100": {
                  "chats": [],
                  "lastUpdateId": 11,
                  "pendingTelegramReplies": [
                    {
                      "updateId": 11,
                      "chatId": "chat",
                      "text": "original",
                      "replyParameters": { "message_id": 1 }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val repository = UpdatesRepository(file)

        val legacyReply = repository.getPendingTelegramReplies("100").single()
        assertEquals(TelegramReplyDeliveryStage.ORIGINAL, legacyReply.deliveryStage)
        assertEquals(0, legacyReply.deliveryAttempts)
        assertEquals(0, legacyReply.permanentRejectionCount)

        val prepared = requireNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        assertEquals(1, prepared.deliveryAttempts)
        val fallback = prepared.copy(
            text = "抱歉，上一条回复未能发送。",
            replyParameters = null,
            deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
            deliveryAttempts = 0,
            permanentRejectionCount = 0,
        )
        assertTrue(repository.replacePendingTelegramReply("100", prepared, fallback))

        assertEquals(fallback, UpdatesRepository(file).getPendingTelegramReplies("100").single())
    }

    /** 验证 outbox 原子提交失败时不会污染内存状态或错误推进偏移量。 */
    @Test
    fun `failed outbox completion leaves in memory state unchanged`() {
        val file = tempDirectory.resolve("failed-outbox.json")
        val repository = UpdatesRepository(file) { throw IOException("injected write failure") }

        assertFailsWith<IOException> {
            repository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", "reply"))
        }

        assertEquals(0, repository.getData("100").lastUpdateId)
        assertTrue(repository.getPendingTelegramReplies("100").isEmpty())
    }

    /** 验证 Agent 回合账本跨重载保留状态，并只在偏移量确认后删除 FINAL 残留。 */
    @Test
    fun `agent turn journal persists final state and cleans it after confirmed offset`() {
        val file = tempDirectory.resolve("agent-turn-journal.json")
        val repository = UpdatesRepository(file)

        assertEquals(
            AgentTurnClaim.CLAIMED,
            repository.claimAgentTurn("100", 11, "chat", ReplyParameters(1)),
        )
        assertEquals(
            AgentTurnClaim.InProgress(
                AgentTurnJournalEntry(11, "chat", ReplyParameters(1), AgentTurnJournalStatus.IN_PROGRESS),
            ),
            UpdatesRepository(file).claimAgentTurn("100", 11, "chat", ReplyParameters(1)),
        )

        val final = assertNotNull(repository.finalizeAgentTurn("100", 11, "reply"))
        assertEquals(AgentTurnJournalStatus.FINAL, final.status)
        assertEquals("reply", final.reply)
        assertEquals(final, UpdatesRepository(file).getData("100").agentTurnJournal.single())

        repository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", "reply", ReplyParameters(1)))
        assertEquals(1, repository.cleanupConfirmedAgentTurns("100"))
        assertTrue(UpdatesRepository(file).getData("100").agentTurnJournal.isEmpty())
    }

    /** 验证损坏的 Agent 回合账本无法 claim，避免在不可信状态下重放 Agent 或工具。 */
    @Test
    fun `corrupt agent turn journal fails closed before claim`() {
        val file = tempDirectory.resolve("corrupt-agent-turn-journal.json")
        file.writeText(
            """
            {
              "bots": {
                "100": {
                  "chats": [],
                  "lastUpdateId": 0,
                  "pendingTelegramReplies": [],
                  "agentTurnJournal": [
                    {
                      "updateId": 11,
                      "chatId": "chat",
                      "status": "IN_PROGRESS",
                      "reply": "must-not-exist"
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )
        val repository = UpdatesRepository(file)

        assertFailsWith<IllegalStateException> {
            repository.claimAgentTurn("100", 12, "chat", ReplyParameters(2))
        }
        assertTrue(file.readText().contains("must-not-exist"))
    }

    /** 验证 claim 原子写失败时不遗留进行中账本，也不会错误允许调用方进入 Agent。 */
    @Test
    fun `agent turn claim write failure leaves no journal state`() {
        val file = tempDirectory.resolve("failed-agent-turn-claim.json")
        val repository = UpdatesRepository(file) { throw IOException("injected journal write failure") }

        assertFailsWith<IOException> {
            repository.claimAgentTurn("100", 11, "chat", ReplyParameters(1))
        }
        assertTrue(repository.getData("100").agentTurnJournal.isEmpty())
    }

    /**
     * 验证损坏主更新状态不会读取遗留 `.bak` 文件，且后续访问安全失败。
     */
    @Test
    fun `damaged primary ignores legacy bak`() {
        val file = tempDirectory.resolve("updates-recovery.json")
        val backupContent =
            ConfigJson.encodeToString(BotUpdatesData(bots = mapOf("100" to UpdatesData(lastUpdateId = 42))))
        file.writeText("[ invalid")
        file.resolveSibling("updates-recovery.json.bak").writeText(backupContent)

        val repository = UpdatesRepository(file, rejectBakOperations())

        assertFailsWith<IllegalStateException> { repository.getData("100") }
        assertEquals("[ invalid", file.readText())
        assertEquals(backupContent, file.resolveSibling("updates-recovery.json.bak").readText())
    }

    /**
     * 验证损坏主文件会拒绝后续 mutation，且不会访问遗留 `.bak` 文件。
     */
    @Test
    fun `damaged updates primary disables later mutations without touching legacy bak`() {
        val file = tempDirectory.resolve("updates-recovery-failure.json")
        val backup = file.resolveSibling("updates-recovery-failure.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup =
            ConfigJson.encodeToString(BotUpdatesData(bots = mapOf("100" to UpdatesData(lastUpdateId = 7))))
        file.writeText(damagedPrimary)
        backup.writeText(validBackup)
        val repository = UpdatesRepository(file, rejectBakOperations())

        assertFailsWith<IllegalStateException> { repository.saveLastUpdateId("100", 8) }
        assertEquals(damagedPrimary, file.readText())
        assertEquals(validBackup, backup.readText())
    }

    /**
     * 验证主更新状态缺失时返回空状态，且不会访问遗留 `.bak` 文件。
     */
    @Test
    fun `missing updates primary ignores legacy bak`() {
        val file = tempDirectory.resolve("missing-updates.json")
        val backup = file.resolveSibling("missing-updates.json.bak")
        val backupContent =
            ConfigJson.encodeToString(BotUpdatesData(bots = mapOf("100" to UpdatesData(lastUpdateId = 42))))
        backup.writeText(backupContent)

        val repository = UpdatesRepository(file, rejectBakOperations())

        assertEquals(UpdatesData(), repository.getData("100"))
        assertFalse(file.exists())
        assertEquals(backupContent, backup.readText())
    }

    /**
     * 验证主更新状态与备份均损坏时，mutation 被拒绝且两个文件保持原样。
     */
    @Test
    fun `double damaged updates files reject mutations without overwriting either file`() {
        val file = tempDirectory.resolve("double-damaged-updates.json")
        val backup = file.resolveSibling("double-damaged-updates.json.bak")
        val damagedPrimary = "{ invalid"
        val damagedBackup = "[ invalid"
        file.writeText(damagedPrimary)
        backup.writeText(damagedBackup)

        val repository = UpdatesRepository(file)

        assertFailsWith<IllegalStateException> { repository.saveLastUpdateId("100", 1) }
        assertEquals(damagedPrimary, file.readText())
        assertEquals(damagedBackup, backup.readText())
    }

    private fun rejectBakOperations(): AtomicJsonFileOperations =
        object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be read" }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }

            override fun writeAndForce(path: Path, bytes: ByteArray) {
                check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be written" }
                DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
            }
        }
}
