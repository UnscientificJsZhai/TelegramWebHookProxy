package com.unscientificjszhai.tgp.utils

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** [SchemaValidatedJsonStorage] 的 schema 修复、迁移和提交状态测试。 */
class SchemaValidatedJsonStorageTest {
    private val tempDirectory = createTempDirectory("schema-validated-json-storage-test")

    @AfterTest
    fun cleanUp() {
        tempDirectory.toFile().deleteRecursively()
    }

    /** 嵌套 optional 字段损坏时仅删除该字段，并以不含原值的路径日志使用构造默认值。 */
    @Test
    fun `nested invalid optional field uses default and logs only its path`() {
        val file = tempDirectory.resolve("nested.json")
        Files.writeString(
            file,
            """
            {
              "required": "present",
              "nested": {"name": "child", "enabled": "SECRET_VALUE"},
              "futureField": {"isIgnored": true}
            }
            """.trimIndent(),
        )
        val capture = logCapture("nested")
        try {
            val result = fixtureStorage(file, logger = capture.logger).read()

            val value = assertIs<AtomicJsonRead.Valid<Fixture>>(result).value
            assertEquals(Fixture("present", Nested("child", enabled = true)), value)
            val messages = capture.appender.list.map(ILoggingEvent::getFormattedMessage)
            assertTrue(messages.any { it.contains("path=$.nested.enabled") })
            assertTrue(messages.none { it.contains("SECRET_VALUE") })
        } finally {
            capture.close()
        }
    }

    /** required 根字段或嵌套 required 字段损坏时不允许退回祖先 optional 字段默认值。 */
    @Test
    fun `invalid required fields are corrupt`() {
        val cases = listOf(
            """{"required": 7}""",
            """{"required":"ok","nested":{"name":7}}""",
            """{"nested":{"name":"ok"}}""",
        )

        cases.forEachIndexed { index, source ->
            val file = tempDirectory.resolve("required-$index.json")
            Files.writeString(file, source)

            assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(file).read(), "case=$index")
        }
    }

    /** 非法 optional 枚举值会使用默认值，合法枚举值保持不变。 */
    @Test
    fun `invalid optional enum uses default`() {
        val invalidFile = tempDirectory.resolve("invalid-enum.json")
        Files.writeString(invalidFile, """{"required":"ok","mode":"REMOVED_VALUE"}""")
        val invalid = assertIs<AtomicJsonRead.Valid<Fixture>>(fixtureStorage(invalidFile).read()).value
        assertEquals(Mode.SAFE, invalid.mode)

        val validFile = tempDirectory.resolve("valid-enum.json")
        Files.writeString(validFile, """{"required":"ok","mode":"FAST"}""")
        val valid = assertIs<AtomicJsonRead.Valid<Fixture>>(fixtureStorage(validFile).read()).value
        assertEquals(Mode.FAST, valid.mode)
    }

    /** optional 集合本身虽有默认值，但其中的损坏元素和 map value 必须使整个文件损坏。 */
    @Test
    fun `invalid collection elements and map values are corrupt`() {
        val cases = listOf(
            """{"required":"ok","items":[1,"bad"]}""",
            """{"required":"ok","counts":{"sensitive-map-key":"bad"}}""",
        )

        cases.forEachIndexed { index, source ->
            val file = tempDirectory.resolve("collection-$index.json")
            Files.writeString(file, source)

            val result = assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(file).read(), "case=$index")
            val expectedPath = if (index == 0) "$.items[1]" else "$.counts[*].value"
            assertTrue(result.cause.message.orEmpty().contains(expectedPath))
            assertTrue(!result.cause.message.orEmpty().contains("sensitive-map-key"))
        }
    }

    /** 旧字段迁移按名称记录、保持幂等，并在 schema 解码前生成当前字段。 */
    @Test
    fun `named migration upgrades an old field before decoding`() {
        val file = tempDirectory.resolve("migration.json")
        Files.writeString(file, """{"required":"ok","oldMode":"FAST"}""")
        val migration = JsonElementMigration("rename-old-mode") { element ->
            if (element !is JsonObject || "oldMode" !in element || "mode" in element) {
                element
            } else {
                buildJsonObject {
                    element.forEach { (name, value) ->
                        if (name != "oldMode") put(name, value)
                    }
                    put("mode", element.getValue("oldMode"))
                }
            }
        }
        val capture = logCapture("migration")
        try {
            val value = assertIs<AtomicJsonRead.Valid<Fixture>>(
                fixtureStorage(file, migrations = listOf(migration), logger = capture.logger).read(),
            ).value

            assertEquals(Mode.FAST, value.mode)
            assertTrue(capture.appender.list.any { it.formattedMessage.contains("rename-old-mode") })
        } finally {
            capture.close()
        }
    }

    /** 非幂等迁移会被视为输入无法安全升级，不会产生有效配置。 */
    @Test
    fun `non idempotent migration is corrupt`() {
        val file = tempDirectory.resolve("non-idempotent.json")
        Files.writeString(file, """{"required":"ok"}""")
        var revision = 0
        val migration = JsonElementMigration("bad-migration") { element ->
            buildJsonObject {
                (element as JsonObject).forEach(::put)
                put("revision", revision++)
            }
        }

        assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(file, migrations = listOf(migration)).read())
    }

    /** 无效 UTF-8 和未闭合 JSON 在任何 schema 解码前均返回损坏状态。 */
    @Test
    fun `malformed UTF-8 and broken JSON structure are corrupt`() {
        val malformedUtf8 = tempDirectory.resolve("malformed-utf8.json")
        Files.write(
            malformedUtf8,
            "{\"required\":\"".encodeToByteArray() + byteArrayOf(0xc3.toByte()) + "\"}".encodeToByteArray(),
        )
        assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(malformedUtf8).read())

        val brokenJson = tempDirectory.resolve("broken-structure.json")
        Files.writeString(brokenJson, "{\"required\":\"ok\"")
        assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(brokenJson).read())
    }

    /** 写入原样返回底层目录同步的 Durable 或可见但耐久性未知状态。 */
    @Test
    fun `commit returns atomic storage durability status`() {
        val durableFile = tempDirectory.resolve("durable.json")
        val durableOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun forceDirectory(path: Path) = Unit
        }
        assertEquals(
            AtomicJsonCommitResult.Durable,
            fixtureStorage(durableFile, durableOperations).commit(Fixture("durable")),
        )

        val uncertainFile = tempDirectory.resolve("uncertain.json")
        val uncertainOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun forceDirectory(path: Path) {
                throw IOException("injected directory sync failure")
            }
        }
        assertIs<AtomicJsonCommitResult.ReplacedDurabilityUnknown>(
            fixtureStorage(uncertainFile, uncertainOperations).commit(Fixture("uncertain")),
        )
        assertTrue(Files.readString(uncertainFile).contains("uncertain"))
    }

    /** 写入业务校验失败时不会调用底层原子替换。 */
    @Test
    fun `commit validates before replacing the primary`() {
        val file = tempDirectory.resolve("validator.json")
        Files.writeString(file, "old-primary")
        val storage = SchemaValidatedJsonStorage(
            AtomicJsonStorage(file, TEST_MAX_BYTES),
            Fixture.serializer(),
            validator = { require(it.required != "rejected") },
        )

        assertFailsWith<IllegalArgumentException> { storage.commit(Fixture("rejected")) }
        assertEquals("old-primary", Files.readString(file))
    }

    /** 未开启未知字段忽略时拒绝构造，避免旧程序无法读取未来版本新增字段。 */
    @Test
    fun `storage requires forward compatible JSON configuration`() {
        assertFailsWith<IllegalArgumentException> {
            SchemaValidatedJsonStorage(
                AtomicJsonStorage(tempDirectory.resolve("strict.json"), TEST_MAX_BYTES),
                Fixture.serializer(),
                json = Json,
            )
        }
    }

    /** 验证存储级结构预算同时作用于读取和写入，未配置存储仍使用通用默认上限。 */
    @Test
    fun `storage specific structure budget applies to reads and writes`() {
        val file = tempDirectory.resolve("storage-budget.json")
        val value = Fixture(
            required = "wide",
            items = (0..JsonStructureLimits.MAX_NODES).toList(),
        )
        Files.writeString(file, ConfigJson.encodeToString(value))

        assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(file).read())

        val configured = fixtureStorage(
            file,
            structureBudget = JsonStructureLimits.Budget(maxNodes = JsonStructureLimits.MAX_NODES * 2),
        )
        assertEquals(value, assertIs<AtomicJsonRead.Valid<Fixture>>(configured.read()).value)

        val committed = value.copy(required = "committed")
        assertEquals(AtomicJsonCommitResult.Durable, configured.commit(committed))
        assertEquals(committed, assertIs<AtomicJsonRead.Valid<Fixture>>(configured.read()).value)
    }

    private fun fixtureStorage(
        file: Path,
        operations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        migrations: List<JsonElementMigration> = emptyList(),
        structureBudget: JsonStructureLimits.Budget = JsonStructureLimits.DEFAULT_BUDGET,
        logger: org.slf4j.Logger = LoggerFactory.getLogger(SchemaValidatedJsonStorage::class.java),
    ): SchemaValidatedJsonStorage<Fixture> = SchemaValidatedJsonStorage(
        AtomicJsonStorage(file, TEST_MAX_BYTES, operations),
        Fixture.serializer(),
        migrations = migrations,
        structureBudget = structureBudget,
        logger = logger,
    )

    private fun logCapture(suffix: String): LogCapture {
        val logger = LoggerFactory.getLogger("SchemaValidatedJsonStorageTest.$suffix.${System.nanoTime()}") as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        return LogCapture(logger, appender)
    }

    private data class LogCapture(
        val logger: Logger,
        val appender: ListAppender<ILoggingEvent>,
    ) {
        fun close() {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    @Serializable
    private data class Fixture(
        val required: String,
        val nested: Nested = Nested("default-child"),
        val mode: Mode = Mode.SAFE,
        val items: List<Int> = emptyList(),
        val counts: Map<String, Int> = emptyMap(),
    )

    @Serializable
    private data class Nested(
        val name: String,
        val enabled: Boolean = true,
    )

    @Serializable
    private enum class Mode {
        SAFE,
        FAST,
    }

    private companion object {
        const val TEST_MAX_BYTES = 1024 * 1024
    }
}
