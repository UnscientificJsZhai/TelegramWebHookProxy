package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.models.ReplyParameters
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.JsonStorageDurabilityUnknownException
import com.unscientificjszhai.tgp.utils.ResourceLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable
private data class ChatDiscoveryBudgetForTest(
    val bots: Map<String, ChatDiscoveryBotForTest>,
    val chatRecency: Map<String, Map<String, Long>>,
    val chatRecencyClock: Long,
)

@Serializable
private data class ChatDiscoveryBotForTest(
    val chats: List<ChatInfo>,
)

private fun chatDiscoveryFootprintForTest(snapshot: BotUpdatesData): Int {
    val chatBearingBots = snapshot.bots
        .asSequence()
        .filter { (_, data) -> data.chats.isNotEmpty() }
        .associateTo(LinkedHashMap()) { (botId, data) -> botId to ChatDiscoveryBotForTest(data.chats) }
    val chatIdsByBot = chatBearingBots.mapValues { (_, bot) -> bot.chats.asSequence().map { it.id }.toSet() }
    val recency = chatBearingBots.keys.associateTo(LinkedHashMap()) { botId ->
        botId to snapshot.chatRecency[botId].orEmpty().filterKeys { it in chatIdsByBot.getValue(botId) }
    }.filterValues { it.isNotEmpty() }
    return ConfigJson.encodeToString(
        ChatDiscoveryBudgetForTest(
            bots = chatBearingBots,
            chatRecency = recency,
            chatRecencyClock = snapshot.chatRecencyClock,
        ),
    ).toByteArray(StandardCharsets.UTF_8).size
}

/** 复现缺少 `UpdatesData.chats` 对象层时的旧预算测量，仅用于覆盖其低估边界。 */
@Serializable
private data class UnderCountingChatDiscoveryBudgetForTest(
    val bots: Map<String, List<ChatInfo>>,
    val chatRecency: Map<String, Map<String, Long>>,
    val chatRecencyClock: Long,
)

private fun underCountingChatDiscoveryFootprintForTest(snapshot: BotUpdatesData): Int {
    val chatBearingBots = snapshot.bots
        .asSequence()
        .filter { (_, data) -> data.chats.isNotEmpty() }
        .associateTo(LinkedHashMap()) { (botId, data) -> botId to data.chats }
    val chatIdsByBot = chatBearingBots.mapValues { (_, chats) -> chats.asSequence().map { it.id }.toSet() }
    val recency = chatBearingBots.keys.associateTo(LinkedHashMap()) { botId ->
        botId to snapshot.chatRecency[botId].orEmpty().filterKeys { it in chatIdsByBot.getValue(botId) }
    }.filterValues { it.isNotEmpty() }
    return ConfigJson.encodeToString(
        UnderCountingChatDiscoveryBudgetForTest(
            bots = chatBearingBots,
            chatRecency = recency,
            chatRecencyClock = snapshot.chatRecencyClock,
        ),
    ).toByteArray(StandardCharsets.UTF_8).size
}

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
        assertEquals(oldChats, UpdatesRepository(listFile).getChats("100"))

        val objectFile = tempDirectory.resolve("legacy-object.json")
        objectFile.writeText(ConfigJson.encodeToString(UpdatesData(oldChats, 33)))
        val objectRepository = UpdatesRepository(objectFile)

        assertEquals(33, objectRepository.getData("200").lastUpdateId)
        assertEquals(oldChats, objectRepository.getChats("200"))
        assertTrue(objectRepository.getChats("100").isEmpty())
        assertEquals(33, UpdatesRepository(objectFile).getData("200").lastUpdateId)
    }

    /** v0 根只接受明确的历史或当前字段集合；未知字段和混合格式不能再靠猜测迁移。 */
    @Test
    fun `updates v0 root migration distinguishes legacy current unknown and mixed roots`() {
        val emptyLegacyFile = tempDirectory.resolve("empty-legacy-object.json")
        emptyLegacyFile.writeText("{}")
        val emptyLegacyRepository = UpdatesRepository(emptyLegacyFile)

        assertEquals(UpdatesData(), emptyLegacyRepository.getData(" "))
        assertEquals("{}", emptyLegacyFile.readText())
        assertEquals(UpdatesData(), emptyLegacyRepository.getData("300"))
        val migrated = ConfigJson.decodeFromString<BotUpdatesData>(emptyLegacyFile.readText())
        assertTrue("300" in migrated.bots)
        assertEquals(UpdatesData(), UpdatesRepository(emptyLegacyFile).getData("300"))

        val metadataFile = tempDirectory.resolve("current-root-metadata.json")
        val metadataContent = """{"chatRecency":{},"chatRecencyClock":7}"""
        metadataFile.writeText(metadataContent)
        val metadataRepository = UpdatesRepository(metadataFile)

        assertEquals(UpdatesData(), metadataRepository.getData("400"))
        assertEquals(metadataContent, metadataFile.readText())

        listOf(
            "unknown" to """{"futureLegacyField":true}""",
            "mixed" to """{"bots":{},"lastUpdateId":7}""",
        ).forEach { (name, source) ->
            val file = tempDirectory.resolve("$name-v0-root.json")
            file.writeText(source)
            assertFailsWith<IllegalStateException> { UpdatesRepository(file) }
            assertEquals(source, file.readText())
        }
    }

    /** v1 的 data 直接按当前 BotUpdatesData 读取，绝不套用只属于 v0 的单 bot 根迁移。 */
    @Test
    fun `updates v1 data does not run legacy root migration`() {
        val file = tempDirectory.resolve("versioned-legacy-looking-updates.json")
        val source = """{"schemaVersion":1,"data":{"lastUpdateId":99}}"""
        file.writeText(source)

        val repository = UpdatesRepository(file)

        assertEquals(UpdatesData(), repository.getData(" "))
        assertEquals(source, file.readText())
    }

    /** Updates 入口会保留非法 UTF-8 与未知 v1 版本的字节，不能加载后再规范化覆盖。 */
    @Test
    fun `updates load preserves malformed UTF8 and future version bytes`() {
        val cases = listOf(
            "malformed-utf8" to ("{\"bots\":{\"100\":{\"chats\":[{\"id\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\"}]}}}".encodeToByteArray()),
            "future-version" to """{"schemaVersion":2,"data":{"bots":{}}}""".encodeToByteArray(),
        )

        cases.forEach { (name, original) ->
            val file = tempDirectory.resolve("$name-updates.json")
            Files.write(file.toPath(), original)

            assertFailsWith<IllegalStateException> { UpdatesRepository(file) }
            assertEquals(original.toList(), Files.readAllBytes(file.toPath()).toList())
        }
    }

    /** 验证 schema 中带默认值的损坏字段会按默认值恢复并允许后续规范化提交。 */
    @Test
    fun `invalid optional fields use schema defaults`() {
        val file = tempDirectory.resolve("optional-field-defaults.json")
        file.writeText(
            """
            {
              "bots": {
                "100": {
                  "lastUpdateId": "broken",
                  "pendingTelegramReplies": [
                    {
                      "updateId": 0,
                      "chatId": "chat",
                      "text": "reply",
                      "deliveryStage": "UNKNOWN",
                      "deliveryAttempts": "broken"
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val repository = UpdatesRepository(file)
        val repaired = repository.getData("100")
        val reply = repaired.pendingTelegramReplies.single()
        assertEquals(0, repaired.lastUpdateId)
        assertEquals(TelegramReplyDeliveryStage.ORIGINAL, reply.deliveryStage)
        assertEquals(0, reply.deliveryAttempts)

        repository.saveLastUpdateId("100", 1)
        assertFalse(file.readText().contains("broken"))
        assertFalse(file.readText().contains("UNKNOWN"))
    }

    /** 验证根结构或嵌套必填字段损坏会在仓储构造时中止应用初始化。 */
    @Test
    fun `structural and required field corruption abort construction`() {
        val corruptDocuments = listOf(
            "true",
            """
            {
              "bots": {
                "100": {
                  "chats": [{"id": "chat", "title": "Chat"}]
                }
              }
            }
            """.trimIndent(),
        )

        corruptDocuments.forEachIndexed { index, document ->
            val file = tempDirectory.resolve("schema-corrupt-$index.json")
            file.writeText(document)
            assertFailsWith<IllegalStateException> { UpdatesRepository(file) }
            assertEquals(document, file.readText())
        }
    }

    /** 验证主文件读取 I/O 失败会在仓储构造时中止应用初始化。 */
    @Test
    fun `read failure aborts construction`() {
        val file = tempDirectory.resolve("unreadable-updates.json")
        file.writeText(ConfigJson.encodeToString(BotUpdatesData()))
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                throw IOException("injected updates read failure")
            }
        }

        val failure = assertFailsWith<IllegalStateException> { UpdatesRepository(file, operations) }
        assertTrue(failure.cause is IOException)
    }

    /** 验证超过 updates 文件字节上限的输入会在构造时拒绝。 */
    @Test
    fun `oversized persisted updates abort construction`() {
        val file = tempDirectory.resolve("oversized-persisted-updates.json")
        file.writeBytes(ByteArray(ResourceLimits.UPDATES_BYTES + 1) { ' '.code.toByte() })

        assertFailsWith<IllegalStateException> { UpdatesRepository(file) }
        assertEquals((ResourceLimits.UPDATES_BYTES + 1).toLong(), file.length())
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
        assertEquals(MAX_DISCOVERED_CHATS_PER_BOT, state.chats.size)
        assertEquals(100, state.lastUpdateId)
        assertFalse(state.chats.any { it.id == "1" })
    }

    /** 验证发现缓存以独立 LRU 元数据限流，而刷新不会改变 API 返回的稳定展示顺序。 */
    @Test
    fun `chat discovery keeps stable order while applying per bot LRU`() {
        val repository = UpdatesRepository(tempDirectory.resolve("chat-lru.json"))
        (1..MAX_DISCOVERED_CHATS_PER_BOT).forEach { index ->
            repository.mergeChats("100", listOf(ChatInfo(index.toString(), "chat-$index", "private")))
        }

        repository.mergeChats("100", listOf(ChatInfo("1", "chat-1 refreshed", "private")))
        repository.mergeChats("100", listOf(ChatInfo("65", "chat-65", "private")))

        val saved = repository.getData("100")
        assertEquals(MAX_DISCOVERED_CHATS_PER_BOT, saved.chats.size)
        assertFalse(saved.chats.any { it.id == "2" })
        assertEquals("1", saved.chats.first().id)
        assertEquals("65", saved.chats.last().id)
        assertEquals("chat-1 refreshed", saved.chats.first().title)
        val persisted = ConfigJson.decodeFromString<BotUpdatesData>(tempDirectory.resolve("chat-lru.json").readText())
        assertEquals(saved.chats.map { it.id }.toSet(), persisted.chatRecency.getValue("100").keys)
        assertEquals(saved.chats.size, repository.getChats("100").size)
    }

    /** 验证通用更新 API 返回的是实际提交后的裁剪快照，而非调用方未经约束的候选值。 */
    @Test
    fun `update data returns committed chat trimmed snapshot`() {
        val repository = UpdatesRepository(tempDirectory.resolve("update-data-trimmed-snapshot.json"))

        val committed = repository.updateData("100") { current ->
            current.copy(
                chats = (1..(MAX_DISCOVERED_CHATS_PER_BOT + 1)).map { index ->
                    ChatInfo(index.toString(), "chat-$index", "private")
                },
            )
        }

        assertEquals(MAX_DISCOVERED_CHATS_PER_BOT, committed.chats.size)
        assertFalse(committed.chats.any { it.id == "1" })
        assertEquals(committed, repository.getData("100"))
    }

    /** 验证发现缓存的规范化及裁剪都只在原子写入成功后才发布到内存。 */
    @Test
    fun `failed chat discovery save does not publish trimmed state in memory`() {
        val file = tempDirectory.resolve("failed-chat-discovery-save.json")
        val repository = UpdatesRepository(file) { throw IOException("injected discovery save failure") }

        assertFailsWith<IOException> {
            repository.mergeChats("100", listOf(ChatInfo("chat", "Chat", "private")))
        }

        assertEquals(UpdatesData(), repository.getData("100"))
        assertFalse(file.exists())
    }

    /** 验证全局 LRU、UTF-8 预算和单项拒绝不会让发现数据挤掉更新处理业务状态。 */
    @Test
    fun `chat discovery bounds preserve business state`() {
        val repository = UpdatesRepository(tempDirectory.resolve("chat-global-budget.json"))
        repository.completeAgentUpdate("100", 7, PendingTelegramReply(7, "chat", "reply"))
        assertEquals(AgentTurnClaim.CLAIMED, repository.claimAgentTurn("100", 8, "chat", null))
        (1..5).forEach { bot ->
            (1..MAX_DISCOVERED_CHATS_PER_BOT).forEach { chat ->
                repository.mergeChats(bot.toString(), listOf(ChatInfo("$bot-$chat", "chat-$bot-$chat", "group")))
            }
        }
        repository.mergeChats(
            "100",
            listOf(ChatInfo("too-large", "中".repeat(MAX_DISCOVERED_CHAT_UTF8_BYTES), "group"))
        )

        val allChats = (1..5).sumOf { repository.getChats(it.toString()).size }
        val business = repository.getData("100")
        assertEquals(MAX_DISCOVERED_CHATS, allChats)
        assertFalse(repository.getChats("100").any { it.id == "too-large" })
        assertEquals(7, business.lastUpdateId)
        assertEquals(listOf(PendingTelegramReply(7, "chat", "reply")), business.pendingTelegramReplies)
        assertEquals(AgentTurnJournalStatus.IN_PROGRESS, business.agentTurnJournal.single().status)
    }

    /** 验证 512 KiB 发现预算计入 bot 结构与 LRU 元数据，而不只计算 ChatInfo 文本。 */
    @Test
    fun `chat discovery utf8 budget includes recency and container structure`() {
        val file = tempDirectory.resolve("chat-discovery-utf8-budget.json")
        val repository = UpdatesRepository(file)
        val candidates = (1..MAX_DISCOVERED_CHATS_PER_BOT).map { index ->
            ChatInfo(index.toString(), "x".repeat(12 * 1024), "group")
        }

        repository.mergeChats("100", candidates)

        val persisted = ConfigJson.decodeFromString<BotUpdatesData>(file.readText())
        val actualBytes = chatDiscoveryFootprintForTest(persisted)
        assertTrue(actualBytes <= MAX_DISCOVERED_CHATS_UTF8_BYTES)
        assertTrue(persisted.bots.getValue("100").chats.size < MAX_DISCOVERED_CHATS_PER_BOT)
    }

    /** 验证预算计入 `bots -> UpdatesData -> chats` 对象层，避免旧的列表代理在边界处少算空间。 */
    @Test
    fun `chat discovery budget counts the persisted bot chat container`() {
        fun snapshotForTitleLength(titleLength: Int): BotUpdatesData {
            val bots = LinkedHashMap<String, UpdatesData>()
            val recency = LinkedHashMap<String, Map<String, Long>>()
            repeat(MAX_DISCOVERED_CHATS) { index ->
                val botId = "bot-$index"
                val chatId = "chat-$index"
                bots[botId] = UpdatesData(chats = listOf(ChatInfo(chatId, "x".repeat(titleLength), "group")))
                recency[botId] = mapOf(chatId to (index + 1L))
            }
            return BotUpdatesData(
                bots = bots,
                chatRecency = recency,
                chatRecencyClock = MAX_DISCOVERED_CHATS.toLong(),
            )
        }

        var lower = 0
        var upper = 4_096
        while (lower < upper) {
            val middle = (lower + upper + 1) / 2
            if (underCountingChatDiscoveryFootprintForTest(snapshotForTitleLength(middle)) <= MAX_DISCOVERED_CHATS_UTF8_BYTES) {
                lower = middle
            } else {
                upper = middle - 1
            }
        }
        val source = snapshotForTitleLength(lower)
        assertTrue(underCountingChatDiscoveryFootprintForTest(source) <= MAX_DISCOVERED_CHATS_UTF8_BYTES)
        assertTrue(chatDiscoveryFootprintForTest(source) > MAX_DISCOVERED_CHATS_UTF8_BYTES)

        val file = tempDirectory.resolve("chat-discovery-container-boundary.json")
        file.writeText(ConfigJson.encodeToString(source))
        val committed = UpdatesRepository(file).saveLastUpdateId("bot-0", 1)
        val normalized = ConfigJson.decodeFromString<BotUpdatesData>(file.readText())

        assertEquals(1, committed.lastUpdateId)
        assertTrue(normalized.bots.values.sumOf { it.chats.size } < MAX_DISCOVERED_CHATS)
        assertTrue(chatDiscoveryFootprintForTest(normalized) <= MAX_DISCOVERED_CHATS_UTF8_BYTES)
        assertEquals(1, UpdatesRepository(file).getData("bot-0").lastUpdateId)
    }

    /** 验证没有发现聊天的业务 bot 不会占用发现缓存预算或驱逐其他 bot 的聊天。 */
    @Test
    fun `business only bots do not consume chat discovery budget`() {
        val file = tempDirectory.resolve("business-only-bots.json")
        val initialBots = LinkedHashMap<String, UpdatesData>().apply {
            repeat(5_000) { index ->
                put("business-$index", UpdatesData(lastUpdateId = index.toLong()))
            }
            put("chat-bot", UpdatesData(chats = listOf(ChatInfo("chat", "Chat", "group")), lastUpdateId = 7))
        }
        val initial = BotUpdatesData(bots = initialBots)
        // This is deliberately larger than the discovery budget when encoded as the old, incorrect whole-bot view.
        assertTrue(
            ConfigJson.encodeToString(initial)
                .toByteArray(StandardCharsets.UTF_8).size > MAX_DISCOVERED_CHATS_UTF8_BYTES
        )
        file.writeText(ConfigJson.encodeToString(initial))

        val repository = UpdatesRepository(file)
        val committed = repository.saveLastUpdateId("chat-bot", 8)

        assertEquals(8, committed.lastUpdateId)
        assertEquals(listOf(ChatInfo("chat", "Chat", "group")), committed.chats)
        assertEquals(4_999, repository.getData("business-4999").lastUpdateId)
        val reloaded = UpdatesRepository(file)
        assertEquals(4_999, reloaded.getData("business-4999").lastUpdateId)
        assertEquals(8, reloaded.getData("chat-bot").lastUpdateId)
    }

    /** 验证发现预算无法容纳最后一条聊天时会删光并收敛，随后仍可提交业务字段。 */
    @Test
    fun `chat discovery budget converges after deleting its final chat`() {
        val file = tempDirectory.resolve("delete-final-discovered-chat.json")
        val oversizedBotId = "b".repeat(MAX_DISCOVERED_CHATS_UTF8_BYTES + 1_024)
        file.writeText(
            ConfigJson.encodeToString(
                BotUpdatesData(
                    bots = mapOf(
                        oversizedBotId to UpdatesData(
                            chats = listOf(ChatInfo("chat", "Chat", "group")),
                            lastUpdateId = 7,
                        ),
                    ),
                ),
            ),
        )

        val committed = UpdatesRepository(file).saveLastUpdateId(oversizedBotId, 8)

        assertEquals(8, committed.lastUpdateId)
        assertTrue(committed.chats.isEmpty())
        assertEquals(committed, UpdatesRepository(file).getData(oversizedBotId))
    }

    /** 验证接近文件上限的 outbox 业务数据优先保留，发现缓存会被动态逐出而不会锁住后续写入。 */
    @Test
    fun `chat normalization sacrifices discovery before near limit business data`() {
        val file = tempDirectory.resolve("near-limit-business.json")
        val repository = UpdatesRepository(file)
        val durableText = "b".repeat(ResourceLimits.UPDATES_BYTES - (32 * 1024))
        repository.completeAgentUpdate("100", 1, PendingTelegramReply(1, "chat", durableText))

        repository.mergeChats("100", listOf(ChatInfo("large-discovery", "c".repeat(48 * 1024), "group")))

        assertEquals(durableText, repository.getPendingTelegramReplies("100").single().text)
        assertTrue(repository.getChats("100").isEmpty())
        assertTrue(file.length() <= ResourceLimits.UPDATES_BYTES)
    }

    /** 验证缺少新元数据的 bot 格式会在第一次写入时整体修复，且保留全部业务字段。 */
    @Test
    fun `first write repairs metadata for every old bot without losing business data`() {
        val file = tempDirectory.resolve("old-bots-without-chat-metadata.json")
        val first = UpdatesData(
            chats = listOf(ChatInfo("a", "A", "private")),
            lastUpdateId = 11,
            pendingTelegramReplies = listOf(PendingTelegramReply(11, "a", "reply")),
            agentTurnJournal = listOf(AgentTurnJournalEntry(12, "a", status = AgentTurnJournalStatus.IN_PROGRESS)),
        )
        val second = UpdatesData(chats = listOf(ChatInfo("b", "B", "group")), lastUpdateId = 22)
        file.writeText(
            """
            {
              "bots": {
                "100": ${ConfigJson.encodeToString(first)},
                "200": ${ConfigJson.encodeToString(second)}
              }
            }
            """.trimIndent(),
        )
        val repository = UpdatesRepository(file)

        repository.saveLastUpdateId("100", 13)

        val repaired = ConfigJson.decodeFromString<BotUpdatesData>(file.readText())
        assertEquals(13, repaired.bots.getValue("100").lastUpdateId)
        assertEquals(first.pendingTelegramReplies, repaired.bots.getValue("100").pendingTelegramReplies)
        assertEquals(first.agentTurnJournal, repaired.bots.getValue("100").agentTurnJournal)
        assertEquals(setOf("100", "200"), repaired.chatRecency.keys)
        assertEquals(setOf("a"), repaired.chatRecency.getValue("100").keys)
        assertEquals(setOf("b"), repaired.chatRecency.getValue("200").keys)
        assertTrue(repaired.chatRecencyClock > 0)
    }

    /** 验证时钟饱和重编号仍可给同批不同发现项分配确定且递增的序号。 */
    @Test
    fun `saturated chat recency clock is stably renumbered before batch touches`() {
        val file = tempDirectory.resolve("saturated-chat-clock.json")
        file.writeText(
            ConfigJson.encodeToString(
                BotUpdatesData(
                    bots = mapOf(
                        "100" to UpdatesData(chats = listOf(ChatInfo("old", "old", "private"))),
                        "200" to UpdatesData(chats = listOf(ChatInfo("terminal", "terminal", "group"))),
                    ),
                    chatRecency = mapOf("100" to mapOf("old" to 9L), "200" to mapOf("terminal" to Long.MAX_VALUE)),
                    chatRecencyClock = Long.MAX_VALUE,
                ),
            ),
        )
        val repository = UpdatesRepository(file)

        repository.mergeChats(
            "100",
            listOf(ChatInfo("first", "first", "private"), ChatInfo("second", "second", "private"))
        )

        val persisted = ConfigJson.decodeFromString<BotUpdatesData>(file.readText())
        val recency = persisted.chatRecency.getValue("100")
        assertEquals(1L, recency.getValue("old"))
        assertEquals(2L, persisted.chatRecency.getValue("200").getValue("terminal"))
        assertTrue(recency.getValue("first") > recency.getValue("old"))
        assertTrue(recency.getValue("second") > recency.getValue("first"))
        assertEquals(recency.getValue("second"), persisted.chatRecencyClock)
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

    /** 验证旧 JSON 默认空 outbox，且未确认更新的异常 outbox 会按损坏状态 fail-closed。 */
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
        assertFailsWith<IllegalStateException> { UpdatesRepository(aheadFile).getPendingTelegramReplies("100") }
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
        assertEquals(0, legacyReply.nextChunkStart)

        val prepared = requireNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        assertEquals(1, prepared.deliveryAttempts)
        val fallback = prepared.copy(
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

    /** 验证失联的进行中回合只会在一次提交中静默删除并推进偏移量，绝不创建 outbox。 */
    @Test
    fun `in progress journal can be silently confirmed without reply or outbox`() {
        val file = tempDirectory.resolve("silent-in-progress-confirmation.json")
        val repository = UpdatesRepository(file)
        assertEquals(
            AgentTurnClaim.CLAIMED,
            repository.claimAgentTurn("100", 11, "chat", ReplyParameters(1)),
        )

        assertTrue(repository.confirmInProgressAgentTurnWithoutReply("100", 11))
        val data = UpdatesRepository(file).getData("100")
        assertEquals(11, data.lastUpdateId)
        assertTrue(data.agentTurnJournal.isEmpty())
        assertTrue(data.pendingTelegramReplies.isEmpty())
        assertFalse(repository.confirmInProgressAgentTurnWithoutReply("100", 11))

        assertEquals(AgentTurnClaim.CLAIMED, repository.claimAgentTurn("100", 12, "chat", ReplyParameters(2)))
        assertNotNull(repository.finalizeAgentTurn("100", 12, "reply"))
        assertFalse(repository.confirmInProgressAgentTurnWithoutReply("100", 12))
        assertEquals(11, repository.getData("100").lastUpdateId)
        assertEquals(AgentTurnJournalStatus.FINAL, repository.getData("100").agentTurnJournal.single().status)
    }

    /** 验证损坏的 Agent 回合账本会中止仓储构造，避免在不可信状态下重放 Agent 或工具。 */
    @Test
    fun `corrupt agent turn journal aborts construction before claim`() {
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
        assertFailsWith<IllegalStateException> {
            UpdatesRepository(file)
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

    /** 验证目录同步失败时未知耐久的 Agent claim 不会放行调用方进入 Agent。 */
    @Test
    fun `agent turn claim requires durable directory sync`() {
        val file = tempDirectory.resolve("unknown-agent-turn-claim.json")
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun forceDirectory(path: Path) {
                throw IOException("injected directory sync failure")
            }
        }
        val repository = UpdatesRepository(file, operations)

        assertFailsWith<JsonStorageDurabilityUnknownException> {
            repository.claimAgentTurn("100", 11, "chat", ReplyParameters(1))
        }

        assertTrue(repository.getData("100").agentTurnJournal.isEmpty())
        assertTrue(file.readText().contains("IN_PROGRESS"))
    }

    /** 验证损坏主更新状态不会读取遗留 `.bak` 文件，并在构造时安全失败。 */
    @Test
    fun `damaged primary ignores legacy bak`() {
        val file = tempDirectory.resolve("updates-recovery.json")
        val backupContent =
            ConfigJson.encodeToString(BotUpdatesData(bots = mapOf("100" to UpdatesData(lastUpdateId = 42))))
        file.writeText("[ invalid")
        file.resolveSibling("updates-recovery.json.bak").writeText(backupContent)

        assertFailsWith<IllegalStateException> { UpdatesRepository(file, rejectBakOperations()) }
        assertEquals("[ invalid", file.readText())
        assertEquals(backupContent, file.resolveSibling("updates-recovery.json.bak").readText())
    }

    /** 验证损坏主文件会中止仓储构造，且不会访问遗留 `.bak` 文件。 */
    @Test
    fun `damaged updates primary aborts construction without touching legacy bak`() {
        val file = tempDirectory.resolve("updates-recovery-failure.json")
        val backup = file.resolveSibling("updates-recovery-failure.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup =
            ConfigJson.encodeToString(BotUpdatesData(bots = mapOf("100" to UpdatesData(lastUpdateId = 7))))
        file.writeText(damagedPrimary)
        backup.writeText(validBackup)
        assertFailsWith<IllegalStateException> { UpdatesRepository(file, rejectBakOperations()) }
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

    /** 验证主更新状态损坏时构造被拒绝，且主文件与遗留备份均保持原样。 */
    @Test
    fun `damaged updates primary aborts construction without overwriting either file`() {
        val file = tempDirectory.resolve("double-damaged-updates.json")
        val backup = file.resolveSibling("double-damaged-updates.json.bak")
        val damagedPrimary = "{ invalid"
        val damagedBackup = "[ invalid"
        file.writeText(damagedPrimary)
        backup.writeText(damagedBackup)

        assertFailsWith<IllegalStateException> { UpdatesRepository(file) }
        assertEquals(damagedPrimary, file.readText())
        assertEquals(damagedBackup, backup.readText())
    }

    /** 验证旧 JSON 缺少重试字段时保持兼容，检查点重试计数跨重载递增且饱和。 */
    @Test
    fun `retry checkpoint is backward compatible persistent and saturating`() {
        val legacyFile = tempDirectory.resolve("retry-checkpoint-legacy.json")
        legacyFile.writeText(
            """
            {
              "bots": {
                "100": {
                  "chats": [],
                  "lastUpdateId": 10,
                  "pendingTelegramReplies": [],
                  "agentTurnJournal": []
                }
              }
            }
            """.trimIndent(),
        )
        val legacy = UpdatesRepository(legacyFile)
        assertNull(legacy.getData("100").retryCheckpoint)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            legacy.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 2)),
            UpdatesRepository(legacyFile).recordRetryCheckpoint(
                "100",
                11,
                expectedTargetUpdateId = 11,
                nowMillis = 200
            ),
        )

        val saturatedFile = tempDirectory.resolve("retry-checkpoint-saturated.json")
        saturatedFile.writeText(
            ConfigJson.encodeToString(
                BotUpdatesData(
                    bots = mapOf(
                        "100" to UpdatesData(
                            lastUpdateId = 10,
                            retryCheckpoint = RetryCheckpoint(11, 100, Long.MAX_VALUE),
                        ),
                    ),
                ),
            ),
        )
        val saturated = UpdatesRepository(saturatedFile)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, Long.MAX_VALUE)),
            saturated.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = 11, nowMillis = 200),
        )
        assertEquals(Long.MAX_VALUE, saturated.getData("100").retryCheckpoint?.retryCount)
    }

    /** 验证检查点的条件写入不会覆盖新目标，且普通、FINAL 和 IN_PROGRESS 完成都同次清除精确目标。 */
    @Test
    fun `retry checkpoint CAS atomically reconciles every completion path`() {
        val repository = UpdatesRepository(tempDirectory.resolve("retry-checkpoint-cas.json"))
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            repository.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        assertEquals(
            RetryCheckpointRecordResult.Stale,
            repository.recordRetryCheckpoint("100", 12, expectedTargetUpdateId = null, nowMillis = 200),
        )
        assertEquals(11, repository.getData("100").retryCheckpoint?.targetUpdateId)
        assertEquals(
            RetryCheckpointCommitResult.Stale,
            repository.confirmProcessedUpdate("100", 12, expectedRetryTarget = null),
        )
        assertEquals(
            RetryCheckpointCommitResult.Committed,
            repository.confirmProcessedUpdate("100", 11, expectedRetryTarget = 11),
        )
        assertEquals(11, repository.getData("100").lastUpdateId)
        assertNull(repository.getData("100").retryCheckpoint)

        assertEquals(AgentTurnClaim.CLAIMED, repository.claimAgentTurn("100", 12, "chat", ReplyParameters(1)))
        assertNotNull(repository.finalizeAgentTurn("100", 12, "reply"))
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(12, 300, 1)),
            repository.recordRetryCheckpoint("100", 12, expectedTargetUpdateId = null, nowMillis = 300),
        )
        assertEquals(
            RetryCheckpointCommitResult.Committed,
            repository.completeAgentUpdateAtRetryCheckpoint(
                "100",
                12,
                PendingTelegramReply(12, "chat", "reply", ReplyParameters(1)),
                expectedRetryTarget = 12,
            ),
        )
        assertEquals(12, repository.getData("100").lastUpdateId)
        assertNull(repository.getData("100").retryCheckpoint)
        assertEquals(12, repository.getPendingTelegramReplies("100").single().updateId)

        assertEquals(AgentTurnClaim.CLAIMED, repository.claimAgentTurn("100", 13, "chat", ReplyParameters(2)))
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(13, 400, 1)),
            repository.recordRetryCheckpoint("100", 13, expectedTargetUpdateId = null, nowMillis = 400),
        )
        assertTrue(repository.confirmInProgressAgentTurnWithoutReply("100", 13, expectedRetryTarget = 13))
        assertEquals(13, repository.getData("100").lastUpdateId)
        assertNull(repository.getData("100").retryCheckpoint)
        assertFalse(repository.getData("100").agentTurnJournal.any { it.updateId == 13L })
    }

    /** 验证 gap 只可严格跳过持久化目标，且只推进目标而非观测到的更高标识。 */
    @Test
    fun `retry checkpoint gap skip is strict exact and atomic`() {
        val repository = UpdatesRepository(tempDirectory.resolve("retry-checkpoint-gap.json"))
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            repository.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        assertFailsWith<IllegalArgumentException> {
            repository.skipRetryCheckpointGap("100", expectedTargetUpdateId = 11, observedFirstUpdateId = 11)
        }
        assertEquals(
            RetryCheckpointGapResult.Skipped(RetryCheckpoint(11, 100, 1)),
            repository.skipRetryCheckpointGap("100", expectedTargetUpdateId = 11, observedFirstUpdateId = 15),
        )
        assertEquals(11, repository.getData("100").lastUpdateId)
        assertNull(repository.getData("100").retryCheckpoint)

        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(12, 200, 1)),
            repository.recordRetryCheckpoint("100", 12, expectedTargetUpdateId = null, nowMillis = 200),
        )
        assertEquals(
            RetryCheckpointGapResult.Stale,
            repository.skipRetryCheckpointGap("100", expectedTargetUpdateId = 11, observedFirstUpdateId = 16),
        )
        assertEquals(12, repository.getData("100").retryCheckpoint?.targetUpdateId)
        assertEquals(11, repository.getData("100").lastUpdateId)
    }

    /** 验证旧版确认 API 及通用读改写均不能把偏移量推进到重试检查点或写出非法状态。 */
    @Test
    fun `retry checkpoint blocks legacy offset commits and generic invalid transform`() {
        val file = tempDirectory.resolve("retry-checkpoint-legacy-offset-guard.json")
        var writeCount = 0
        val repository = UpdatesRepository(file) { writeCount++ }
        repository.saveLastUpdateId("100", 10)
        assertEquals(
            RetryCheckpointRecordResult.Recorded(RetryCheckpoint(11, 100, 1)),
            repository.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        val before = repository.getData("100")
        val persistedBefore = file.readText()
        val writesBefore = writeCount

        assertEquals(before, repository.saveLastUpdateId("100", 12))
        assertEquals(before, repository.completeAgentUpdate("100", 12, PendingTelegramReply(12, "chat", "reply")))
        assertFailsWith<IllegalArgumentException> {
            repository.updateData("100") { current -> current.copy(lastUpdateId = 11) }
        }

        assertEquals(writesBefore, writeCount)
        assertEquals(persistedBefore, file.readText())
        assertEquals(before, repository.getData("100"))
        assertEquals(10, repository.getData("100").lastUpdateId)
        assertEquals(RetryCheckpoint(11, 100, 1), repository.getData("100").retryCheckpoint)
        assertTrue(repository.getPendingTelegramReplies("100").isEmpty())
    }

    /** 验证已确认或损坏的同标识检查点被严格拒绝，不能制造永远重拉已确认 offset 的状态。 */
    @Test
    fun `retry checkpoint target must be strictly ahead of saved offset`() {
        val repository = UpdatesRepository(tempDirectory.resolve("retry-checkpoint-ahead-only.json"))
        repository.saveLastUpdateId("100", 11)
        assertEquals(
            RetryCheckpointRecordResult.Stale,
            repository.recordRetryCheckpoint("100", 11, expectedTargetUpdateId = null, nowMillis = 100),
        )
        assertNull(repository.getData("100").retryCheckpoint)

        val corruptFile = tempDirectory.resolve("retry-checkpoint-equals-offset.json")
        corruptFile.writeText(
            ConfigJson.encodeToString(
                BotUpdatesData(
                    bots = mapOf(
                        "100" to UpdatesData(
                            lastUpdateId = 11,
                            retryCheckpoint = RetryCheckpoint(11, 100, 1),
                        ),
                    ),
                ),
            ),
        )
        assertFailsWith<IllegalStateException> { UpdatesRepository(corruptFile) }
    }

    /** 验证长回复成功后只以 UTF-16 cursor 推进一个片段，末片段成功才删除 outbox。 */
    @Test
    fun `outbox advances one Telegram text chunk at a time`() {
        val repository = UpdatesRepository(tempDirectory.resolve("outbox-chunks.json"))
        val source = "a".repeat(4096) + "b"
        repository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", source, ReplyParameters(1)))

        val first = requireNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        assertEquals(0, first.nextChunkStart)
        assertEquals(1, first.deliveryAttempts)
        assertTrue(repository.advancePendingTelegramReplyDelivery("100", first))

        val second = requireNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        assertEquals(4096, second.nextChunkStart)
        assertEquals(1, second.deliveryAttempts)
        assertTrue(repository.advancePendingTelegramReplyDelivery("100", second))
        assertTrue(repository.getPendingTelegramReplies("100").isEmpty())
    }

    /** 验证跨重启保存的 Unicode cursor 从完整 surrogate 与组合字符边界继续。 */
    @Test
    fun `outbox restart resumes a Unicode chunk at its persisted boundary`() {
        val file = tempDirectory.resolve("outbox-unicode-restart.json")
        val source = "a".repeat(4095) + "😀e\u0301" + "b".repeat(4096)
        val firstRepository = UpdatesRepository(file)
        firstRepository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", source))
        val first = requireNotNull(firstRepository.preparePendingTelegramReplyDelivery("100", 11))
        assertTrue(firstRepository.advancePendingTelegramReplyDelivery("100", first))

        val resumed = requireNotNull(UpdatesRepository(file).preparePendingTelegramReplyDelivery("100", 11))
        assertEquals(4095, resumed.nextChunkStart)
        assertEquals("😀", resumed.text.substring(resumed.nextChunkStart, resumed.nextChunkStart + 2))
        assertTrue(resumed.nextChunkStart == 0 || !Character.isLowSurrogate(resumed.text[resumed.nextChunkStart]))
    }

    /** 验证回退耗尽只跳过当前片段，且源文本和后续片段仍会保留。 */
    @Test
    fun `exhausted fallback advances to later source chunk without overwriting source`() {
        val repository = UpdatesRepository(tempDirectory.resolve("outbox-fallback-chunk.json"))
        val source = "a".repeat(4096) + "b"
        val original = PendingTelegramReply(11, "chat", source, ReplyParameters(1))
        repository.completeAgentUpdate("100", 11, original)
        val fallback = original.copy(
            replyParameters = null,
            deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
            deliveryAttempts = MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS,
        )
        assertTrue(repository.replacePendingTelegramReply("100", original, fallback))

        assertTrue(repository.discardExhaustedPendingTelegramReplyFallback("100", fallback))
        val pending = repository.getPendingTelegramReplies("100").single()
        assertEquals(source, pending.text)
        assertEquals(4096, pending.nextChunkStart)
        assertEquals(TelegramReplyDeliveryStage.ORIGINAL, pending.deliveryStage)
        assertEquals(0, pending.deliveryAttempts)
    }

    /** 验证长原文的中间片段回退耗尽后继续后续原文片段，而不是删除整条回复。 */
    @Test
    fun `exhausted fallback in a middle chunk continues the following source chunk`() {
        val repository = UpdatesRepository(tempDirectory.resolve("outbox-middle-fallback-chunk.json"))
        val source = "a".repeat(4096) + "b".repeat(4096) + "c"
        repository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", source))
        val first = requireNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        assertTrue(repository.advancePendingTelegramReplyDelivery("100", first))
        val middle = repository.getPendingTelegramReplies("100").single()
        val exhaustedFallback = middle.copy(
            deliveryStage = TelegramReplyDeliveryStage.FALLBACK,
            deliveryAttempts = MAX_FALLBACK_TELEGRAM_REPLY_DELIVERY_ATTEMPTS,
        )
        assertTrue(repository.replacePendingTelegramReply("100", middle, exhaustedFallback))

        assertTrue(repository.discardExhaustedPendingTelegramReplyFallback("100", exhaustedFallback))
        val following = repository.getPendingTelegramReplies("100").single()
        assertEquals(8192, following.nextChunkStart)
        assertEquals(TelegramReplyDeliveryStage.ORIGINAL, following.deliveryStage)
        assertEquals("c", following.text.substring(following.nextChunkStart))
    }

    /** 验证持久化 cursor 不允许落在 surrogate pair 中间。 */
    @Test
    fun `outbox rejects an invalid Unicode chunk cursor`() {
        val repository = UpdatesRepository(tempDirectory.resolve("outbox-invalid-cursor.json"))
        assertFailsWith<IllegalArgumentException> {
            repository.completeAgentUpdate(
                "100",
                11,
                PendingTelegramReply(11, "chat", "😀x", nextChunkStart = 1),
            )
        }
    }

    /** 验证损坏 JSON 中处于 surrogate pair 内的 cursor 会中止构造，且不会覆盖现场。 */
    @Test
    fun `corrupt persisted outbox cursor aborts construction without overwrite`() {
        val file = tempDirectory.resolve("corrupt-outbox-cursor.json")
        val corrupt = BotUpdatesData(
            bots = mapOf(
                "100" to UpdatesData(
                    lastUpdateId = 11,
                    pendingTelegramReplies = listOf(PendingTelegramReply(11, "chat", "😀x", nextChunkStart = 1)),
                ),
            ),
        )
        // 直接编码用于模拟历史或手工篡改文件；构造值本身的约束由仓储加载路径负责拒绝。
        file.writeText(ConfigJson.encodeToString(corrupt))
        val originalContent = file.readText()

        assertFailsWith<IllegalStateException> { UpdatesRepository(file) }
        assertEquals(originalContent, file.readText())
    }

    /** 验证重复 updateId 的持久化 outbox 会作为损坏状态拒绝加载。 */
    @Test
    fun `corrupt persisted outbox duplicate update ids fail closed`() {
        val file = tempDirectory.resolve("corrupt-outbox-duplicates.json")
        file.writeText(
            ConfigJson.encodeToString(
                BotUpdatesData(
                    bots = mapOf(
                        "100" to UpdatesData(
                            lastUpdateId = 11,
                            pendingTelegramReplies = listOf(
                                PendingTelegramReply(11, "chat", "first"),
                                PendingTelegramReply(11, "chat", "second"),
                            ),
                        ),
                    ),
                ),
            ),
        )

        assertFailsWith<IllegalStateException> { UpdatesRepository(file).getData("100") }
    }

    /** 验证迟到成功使用的旧快照不能推进已经重新登记过的当前片段。 */
    @Test
    fun `outbox chunk advance is compare and swap fenced`() {
        val repository = UpdatesRepository(tempDirectory.resolve("outbox-advance-cas.json"))
        repository.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", "a".repeat(4096) + "b"))
        val stale = requireNotNull(repository.preparePendingTelegramReplyDelivery("100", 11))
        val current = stale.copy(deliveryAttempts = 2)
        assertTrue(repository.replacePendingTelegramReply("100", stale, current))

        assertFalse(repository.advancePendingTelegramReplyDelivery("100", stale))
        assertEquals(current, repository.getPendingTelegramReplies("100").single())
    }

    /** 验证片段推进文件提交失败时 cursor 不会在内存或磁盘中前移。 */
    @Test
    fun `outbox chunk advance write failure retains current chunk`() {
        val file = tempDirectory.resolve("outbox-advance-write-failure.json")
        val initial = UpdatesRepository(file)
        initial.completeAgentUpdate("100", 11, PendingTelegramReply(11, "chat", "a".repeat(4096) + "b"))
        val prepared = requireNotNull(initial.preparePendingTelegramReplyDelivery("100", 11))
        val failing = UpdatesRepository(file) { throw IOException("injected chunk advance failure") }

        assertFailsWith<IOException> { failing.advancePendingTelegramReplyDelivery("100", prepared) }
        assertEquals(prepared, failing.getPendingTelegramReplies("100").single())
        assertEquals(prepared, UpdatesRepository(file).getPendingTelegramReplies("100").single())
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
