package com.unscientificjszhai.tgp.utils

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

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

    /** 默认 v0 写入保持历史根 payload，而显式 v1 写入和读取使用完整版本封套。 */
    @Test
    fun `v0 and v1 storage formats round trip`() {
        val v0File = tempDirectory.resolve("legacy-v0.json")
        val v0Storage = fixtureStorage(v0File)
        v0Storage.commit(Fixture("legacy"))
        assertEquals(
            "legacy",
            ConfigJson.parseToJsonElement(Files.readString(v0File)).jsonObject["required"]?.jsonPrimitive?.content
        )
        assertEquals(Fixture("legacy"), assertIs<AtomicJsonRead.Valid<Fixture>>(v0Storage.read()).value)

        val v1File = tempDirectory.resolve("versioned-v1.json")
        val v1Storage = fixtureStorage(v1File, writeFormat = JsonStorageWriteFormat.VERSIONED_V1)
        v1Storage.commit(Fixture("versioned"))
        val document = ConfigJson.parseToJsonElement(Files.readString(v1File)).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("versioned", document.getValue("data").jsonObject.getValue("required").jsonPrimitive.content)
        assertEquals(Fixture("versioned"), assertIs<AtomicJsonRead.Valid<Fixture>>(v1Storage.read()).value)
    }

    /** 只允许恰有整数版本和 data 的 v1 封套，不能将畸形版本元数据交给兼容解码。 */
    @Test
    fun `versioned envelope shape and version must be strict`() {
        val invalidDocuments = listOf(
            """{"schemaVersion":"1","data":{"required":"ok"}}""",
            """{"schemaVersion":true,"data":{"required":"ok"}}""",
            """{"schemaVersion":1.0,"data":{"required":"ok"}}""",
            """{"schemaVersion":0,"data":{"required":"ok"}}""",
            """{"schemaVersion":1}""",
            """{"data":{"required":"ok"}}""",
            """{"schemaVersion":1,"data":{"required":"ok"},"extra":true}""",
        )

        invalidDocuments.forEachIndexed { index, source ->
            val file = tempDirectory.resolve("invalid-envelope-$index.json")
            Files.writeString(file, source)
            assertIs<AtomicJsonRead.Corrupt>(fixtureStorage(file).read(), "case=$index")
        }
    }

    /** 重复根键不能让 JSON 解析器以末值覆盖较高版本或较早 data，并会锁住后续提交。 */
    @Test
    fun `duplicate root keys preserve bytes without migration decode or commit`() {
        val cases = listOf(
            "future-version-bypass" to """{"schemaVersion":2,"schemaVersion":1,"data":{"required":"bypass"}}""",
            "escaped-version-bypass" to """{"schemaVersion":2,"schema\u0056ersion":1,"data":{"required":"bypass"}}""",
            "duplicate-data" to """{"schemaVersion":1,"data":{"required":"first"},"data":{"required":"last"}}""",
            "duplicate-v0-key" to """{"required":"first","required":"last"}""",
        )

        cases.forEach { (name, source) ->
            val file = tempDirectory.resolve("duplicate-root-$name.json")
            val original = source.encodeToByteArray()
            Files.write(file, original)
            var migrationCalls = 0
            var validatorCalls = 0
            val storage = fixtureStorage(
                file,
                migrations = listOf(JsonElementMigration("must-not-run-$name") {
                    migrationCalls++
                    it
                }),
                validator = { validatorCalls++ },
            )

            val failure = assertIs<AtomicJsonRead.Corrupt>(storage.read(), "case=$name").cause
            assertIs<JsonStorageDuplicateRootKeyException>(failure, "case=$name")
            assertEquals(0, migrationCalls, "case=$name")
            assertEquals(0, validatorCalls, "case=$name")
            assertEquals(original.toList(), Files.readAllBytes(file).toList(), "case=$name")
            assertFailsWith<JsonStorageDuplicateRootKeyException>("case=$name") {
                storage.commit(Fixture("replacement"))
            }
            assertEquals(original.toList(), Files.readAllBytes(file).toList(), "case=$name")
        }
    }

    /** 高版本在迁移、修复和解码前拒绝，保留原始字节并禁止此实例随后写回。 */
    @Test
    fun `future version preserves bytes without migration decode or commit`() {
        val file = tempDirectory.resolve("future-version.json")
        val original = """{"schemaVersion":2,"data":{"required":"future"}}""".encodeToByteArray()
        Files.write(file, original)
        var migrationCalls = 0
        var validatorCalls = 0
        val migration = JsonElementMigration("must-not-run") {
            migrationCalls++
            it
        }
        val storage = fixtureStorage(
            file,
            migrations = listOf(migration),
            validator = { validatorCalls++ },
        )

        val failure = assertIs<AtomicJsonRead.Corrupt>(storage.read()).cause
        assertIs<JsonStorageUnsupportedSchemaVersionException>(failure)
        assertEquals(0, migrationCalls)
        assertEquals(0, validatorCalls)
        assertEquals(original.toList(), Files.readAllBytes(file).toList())
        assertFailsWith<JsonStorageUnsupportedSchemaVersionException> { storage.commit(Fixture("replacement")) }
        assertEquals(original.toList(), Files.readAllBytes(file).toList())
    }

    /** 成功读取 v1 后，即使默认策略为 v0，后续提交也不能将文件降级为无封套根。 */
    @Test
    fun `successful v1 read makes later commits monotonic`() {
        val file = tempDirectory.resolve("monotonic-version.json")
        Files.writeString(file, """{"schemaVersion":1,"data":{"required":"before"}}""")
        val storage = fixtureStorage(file, writeFormat = JsonStorageWriteFormat.LEGACY_V0)

        assertEquals(Fixture("before"), assertIs<AtomicJsonRead.Valid<Fixture>>(storage.read()).value)
        storage.commit(Fixture("after"))

        val document = ConfigJson.parseToJsonElement(Files.readString(file)).jsonObject
        assertEquals(1, document.getValue("schemaVersion").jsonPrimitive.int)
        assertEquals("after", document.getValue("data").jsonObject.getValue("required").jsonPrimitive.content)
    }

    /** 启动时格式策略默认关闭，只接受文档化的精确枚举值。 */
    @Test
    fun `write format policy defaults and rejects invalid values`() {
        assertEquals(JsonStorageWriteFormat.LEGACY_V0, JsonStorageWriteFormatPolicy.fromStartupEnvironment(null))
        assertEquals(JsonStorageWriteFormat.LEGACY_V0, JsonStorageWriteFormatPolicy.fromStartupEnvironment("LEGACY_V0"))
        assertEquals(
            JsonStorageWriteFormat.VERSIONED_V1,
            JsonStorageWriteFormatPolicy.fromStartupEnvironment("VERSIONED_V1")
        )
        assertFailsWith<IllegalArgumentException> {
            JsonStorageWriteFormatPolicy.fromStartupEnvironment("versioned_v1")
        }
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
        validator: (Fixture) -> Unit = {},
        writeFormat: JsonStorageWriteFormat = JsonStorageWriteFormat.LEGACY_V0,
    ): SchemaValidatedJsonStorage<Fixture> = SchemaValidatedJsonStorage(
        AtomicJsonStorage(file, TEST_MAX_BYTES, operations),
        Fixture.serializer(),
        migrations = migrations,
        structureBudget = structureBudget,
        logger = logger,
        validator = validator,
        writeFormat = writeFormat,
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
