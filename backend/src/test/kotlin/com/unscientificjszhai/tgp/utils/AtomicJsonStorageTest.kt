package com.unscientificjszhai.tgp.utils

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 原子 JSON 主文件读写的故障注入测试。
 */
class AtomicJsonStorageTest {
    private val tempDirectory = createTempDirectory("atomic-json-storage-test")

    @AfterTest
    fun cleanUp() {
        tempDirectory.toFile().deleteRecursively()
    }

    /**
     * 验证主文件替换前的每个故障点都保留主文件，且清理同目录临时文件。
     */
    @Test
    fun `pre primary replace failures keep the old primary and remove temporary files`() {
        val stages = listOf(
            FailureStage.PRIMARY_TEMP_WRITE,
            FailureStage.PRIMARY_REPLACE,
        )

        stages.forEach { stage ->
            val directory = Files.createDirectory(tempDirectory.resolve(stage.name.lowercase()))
            val target = directory.resolve("state.json")
            Files.writeString(target, "old-primary")
            val storage = AtomicJsonStorage(target, 1024 * 1024, FailingFileOperations(stage))

            assertFailsWith<IOException> { storage.commit("new-primary".encodeToByteArray()) }

            assertEquals("old-primary", Files.readString(target), "stage=$stage")
            Files.list(directory).use { files ->
                assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }, "stage=$stage")
            }
        }
    }

    /**
     * 验证提交只替换主文件，不会创建或更新 `.bak` 文件。
     */
    @Test
    fun `commit does not create or update backup files`() {
        val firstTarget = tempDirectory.resolve("first-commit.json")
        val firstBackup = tempDirectory.resolve("first-commit.json.bak")

        AtomicJsonStorage(
            firstTarget,
            1024 * 1024,
            RejectBakFileOperations()
        ).commit("initial-content".encodeToByteArray())

        assertEquals("initial-content", Files.readString(firstTarget))
        assertTrue(Files.notExists(firstBackup))

        val target = tempDirectory.resolve("without-backup.json")
        val backup = tempDirectory.resolve("without-backup.json.bak")
        Files.writeString(target, "old-primary")

        AtomicJsonStorage(target, 1024 * 1024, RejectBakFileOperations()).commit("new-primary".encodeToByteArray())

        assertEquals("new-primary", Files.readString(target))
        assertTrue(Files.notExists(backup))

        Files.writeString(backup, "legacy-backup")
        AtomicJsonStorage(target, 1024 * 1024, RejectBakFileOperations()).commit("newer-primary".encodeToByteArray())

        assertEquals("newer-primary", Files.readString(target))
        assertEquals("legacy-backup", Files.readString(backup))
    }

    /**
     * 验证不支持原子主替换时不会退化为普通移动。
     */
    @Test
    fun `atomic move unsupported for an existing primary target keeps primary without fallback`() {
        val target = tempDirectory.resolve("unsupported.json")
        Files.writeString(target, "old-primary")
        val operations = FailingFileOperations(FailureStage.ATOMIC_MOVE_UNSUPPORTED)
        val storage = AtomicJsonStorage(target, 1024 * 1024, operations)

        assertFailsWith<AtomicMoveNotSupportedException> { storage.commit("new-primary".encodeToByteArray()) }

        assertEquals("old-primary", Files.readString(target))
        assertEquals(1, operations.replaceCount, "must not attempt a non-atomic fallback")
        Files.list(tempDirectory).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    /**
     * 验证主文件语义损坏时直接返回损坏状态，遗留 `.bak` 文件不会被读取或改写。
     */
    @Test
    fun `corrupt primary ignores legacy bak file`() {
        val target = tempDirectory.resolve("corrupt.json")
        val backup = tempDirectory.resolve("corrupt.json.bak")
        Files.writeString(target, "not-valid")
        Files.writeString(backup, "valid-but-ignored")
        val storage = AtomicJsonStorage(target, 1024 * 1024, RejectBakFileOperations())

        val result = storage.readValidated { throw IllegalArgumentException("invalid primary") }

        assertTrue(result is AtomicJsonRead.Corrupt)
        assertEquals("not-valid", Files.readString(target))
        assertEquals("valid-but-ignored", Files.readString(backup))
    }

    /**
     * 验证主文件缺失时返回首次启动状态，遗留 `.bak` 文件不会被读取或改写。
     */
    @Test
    fun `missing primary ignores legacy bak file`() {
        val target = tempDirectory.resolve("missing-primary.json")
        val backup = tempDirectory.resolve("missing-primary.json.bak")
        Files.writeString(backup, "valid-but-ignored")

        val result = AtomicJsonStorage(target, 1024 * 1024, RejectBakFileOperations()).readValidated { "decoded" }

        assertEquals(AtomicJsonRead.Missing, result)
        assertTrue(Files.notExists(target))
        assertEquals("valid-but-ignored", Files.readString(backup))
    }

    /**
     * 验证超出上限的主文件不会完整读取或解析，也不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `oversized primary is corrupt and ignores legacy bak file`() {
        val target = tempDirectory.resolve("oversized-primary.json")
        val backup = tempDirectory.resolve("oversized-primary.json.bak")
        Files.write(target, ByteArray(17) { 'x'.code.toByte() })
        Files.writeString(backup, "valid-but-ignored")
        val storage = AtomicJsonStorage(target, 16, RejectBakFileOperations())

        val result = storage.readValidated { "decoded" }

        assertTrue(result is AtomicJsonRead.Corrupt)
        assertEquals("valid-but-ignored", Files.readString(backup))
    }

    /**
     * 验证超过持久化上限的提交在创建临时文件前失败，既不替换主文件也不更新备份。
     */
    @Test
    fun `oversized commit preserves existing files`() {
        val target = tempDirectory.resolve("oversized-commit.json")
        val backup = tempDirectory.resolve("oversized-commit.json.bak")
        Files.writeString(target, "old-primary")
        Files.writeString(backup, "old-backup")
        val storage = AtomicJsonStorage(target, 8)

        assertFailsWith<IllegalArgumentException> { storage.commit("too-large".encodeToByteArray()) }

        assertEquals("old-primary", Files.readString(target))
        assertEquals("old-backup", Files.readString(backup))
    }

    /**
     * 验证主文件读取 I/O 失败时返回独立结果且不会读取遗留 `.bak` 文件。
     */
    @Test
    fun `primary io failure does not read legacy bak file`() {
        val primaryPath = tempDirectory.resolve("io-failure.json")
        val backup = tempDirectory.resolve("io-failure.json.bak")
        Files.writeString(primaryPath, "primary")
        Files.writeString(backup, "ignored")
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
                if (path == primaryPath) {
                    throw IOException("injected primary read failure")
                }
                if (path.fileName.toString().endsWith(".bak")) {
                    throw AssertionError("legacy bak file must not be read")
                }
                return DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes)
            }
        }

        val result = AtomicJsonStorage(primaryPath, 1024 * 1024, operations).readValidated { "decoded" }

        assertTrue(result is AtomicJsonRead.IoFailure)
        assertEquals("ignored", Files.readString(backup))
    }

    /**
     * 验证主文件替换后的目录同步失败不会回报已经完成的逻辑提交失败。
     */
    @Test
    fun `commit ignores post primary replacement directory sync failure`() {
        val target = tempDirectory.resolve("commit-order.json")
        Files.writeString(target, "old-primary")
        val operations = RecordingFileOperations(failDirectoryForce = true)

        AtomicJsonStorage(target, 1024 * 1024, operations).commit("new-primary".encodeToByteArray())

        val primaryReplacement = operations.events.indexOf("replace:commit-order.json")
        val directoryForce = operations.events.indexOf("force-directory")
        assertTrue(primaryReplacement < directoryForce)
        assertEquals(1, operations.events.count { it == "force-directory" })
        assertEquals("new-primary", Files.readString(target))
    }

    private enum class FailureStage {
        PRIMARY_TEMP_WRITE,
        PRIMARY_REPLACE,
        ATOMIC_MOVE_UNSUPPORTED,
    }

    private class FailingFileOperations(
        private val stage: FailureStage,
    ) : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        private var writeCount = 0
        var replaceCount = 0
            private set

        override fun writeAndForce(path: Path, bytes: ByteArray) {
            writeCount++
            if (stage == FailureStage.PRIMARY_TEMP_WRITE && writeCount == 1) {
                throw IOException("injected write failure")
            }
            DefaultAtomicJsonFileOperations.writeAndForce(path, bytes)
        }

        override fun atomicReplace(source: Path, target: Path) {
            replaceCount++
            if (stage == FailureStage.PRIMARY_REPLACE && replaceCount == 1) {
                throw IOException("injected primary replace failure")
            }
            if (stage == FailureStage.ATOMIC_MOVE_UNSUPPORTED && replaceCount == 1) {
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "injected")
            }
            DefaultAtomicJsonFileOperations.atomicReplace(source, target)
        }
    }

    private class RecordingFileOperations(
        private val failDirectoryForce: Boolean,
    ) : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        val events = mutableListOf<String>()
        override fun atomicReplace(source: Path, target: Path) {
            events += "replace:${target.fileName}"
            DefaultAtomicJsonFileOperations.atomicReplace(source, target)
        }

        override fun forceDirectory(path: Path) {
            events += "force-directory"
            if (failDirectoryForce) {
                throw IOException("injected post-commit directory sync failure")
            }
        }
    }

    /** 任何尝试访问遗留 `.bak` 文件的存储操作都会让测试立即失败。 */
    private class RejectBakFileOperations : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
        override fun readAtMost(path: Path, maxBytes: Int): ByteArray =
            rejectBak(path) { DefaultAtomicJsonFileOperations.readAtMost(path, maxBytes) }

        override fun writeAndForce(path: Path, bytes: ByteArray) {
            rejectBak(path) { DefaultAtomicJsonFileOperations.writeAndForce(path, bytes) }
        }

        override fun atomicReplace(source: Path, target: Path) {
            rejectBak(source) { rejectBak(target) { DefaultAtomicJsonFileOperations.atomicReplace(source, target) } }
        }

        override fun deleteIfExists(path: Path) {
            rejectBak(path) { DefaultAtomicJsonFileOperations.deleteIfExists(path) }
        }

        private fun <T> rejectBak(path: Path, action: () -> T): T {
            check(!path.fileName.toString().endsWith(".bak")) { "legacy bak file must not be accessed: $path" }
            return action()
        }
    }
}
