package com.unscientificjszhai.tgp.utils

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 原子 JSON 文件提交与旧备份恢复的故障注入测试设计。
 */
class AtomicJsonStorageTest {
    private val tempDirectory = createTempDirectory("atomic-json-storage-test")

    @AfterTest
    fun cleanUp() {
        tempDirectory.toFile().deleteRecursively()
    }

    /**
     * 验证主文件替换前的每个故障点都保留主文件和旧备份，且清理同目录临时文件。
     */
    @Test
    fun `pre primary replace failures keep the old primary and backup and remove temporary files`() {
        val stages = listOf(
            FailureStage.PRIMARY_TEMP_WRITE,
            FailureStage.PRIMARY_REPLACE,
        )

        stages.forEach { stage ->
            val directory = Files.createDirectory(tempDirectory.resolve(stage.name.lowercase()))
            val target = directory.resolve("state.json")
            val backup = directory.resolve("state.json.bak")
            val oldBackup = "old-distinct-backup".encodeToByteArray()
            Files.writeString(target, "old-primary")
            Files.write(backup, oldBackup)
            val storage = AtomicJsonStorage(target, 1024 * 1024, FailingFileOperations(stage))

            assertFailsWith<IOException> { storage.commit("new-primary".encodeToByteArray()) }

            assertEquals("old-primary", Files.readString(target), "stage=$stage")
            assertContentEquals(oldBackup, Files.readAllBytes(backup), "stage=$stage")
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

        AtomicJsonStorage(firstTarget, 1024 * 1024).commit("initial-content".encodeToByteArray())

        assertEquals("initial-content", Files.readString(firstTarget))
        assertTrue(Files.notExists(firstBackup))

        val target = tempDirectory.resolve("without-backup.json")
        val backup = tempDirectory.resolve("without-backup.json.bak")
        Files.writeString(target, "old-primary")

        AtomicJsonStorage(target, 1024 * 1024).commit("new-primary".encodeToByteArray())

        assertEquals("new-primary", Files.readString(target))
        assertTrue(Files.notExists(backup))

        Files.writeString(backup, "legacy-backup")
        AtomicJsonStorage(target, 1024 * 1024).commit("newer-primary".encodeToByteArray())

        assertEquals("newer-primary", Files.readString(target))
        assertEquals("legacy-backup", Files.readString(backup))
    }

    /**
     * 验证不支持原子主替换时不会退化为普通移动，也不会预先覆盖已有备份。
     */
    @Test
    fun `atomic move unsupported for an existing primary target keeps primary and backup without fallback`() {
        val target = tempDirectory.resolve("unsupported.json")
        val backup = tempDirectory.resolve("unsupported.json.bak")
        val oldBackup = "old-distinct-backup".encodeToByteArray()
        Files.writeString(target, "old-primary")
        Files.write(backup, oldBackup)
        val operations = FailingFileOperations(FailureStage.ATOMIC_MOVE_UNSUPPORTED)
        val storage = AtomicJsonStorage(target, 1024 * 1024, operations)

        assertFailsWith<AtomicMoveNotSupportedException> { storage.commit("new-primary".encodeToByteArray()) }

        assertEquals("old-primary", Files.readString(target))
        assertContentEquals(oldBackup, Files.readAllBytes(backup))
        assertEquals(1, operations.replaceCount, "must not attempt a non-atomic fallback")
        Files.list(tempDirectory).use { files ->
            assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") })
        }
    }

    /**
     * 验证只有主文件语义损坏时才会原样恢复经完整验证的备份，而不会旋转覆盖备份。
     */
    @Test
    fun `valid backup restores damaged primary as original bytes`() {
        val target = tempDirectory.resolve("recover.json")
        val backup = tempDirectory.resolve("recover.json.bak")
        Files.writeString(target, "not-valid")
        val backupBytes = "valid-backup-with-layout\n".encodeToByteArray()
        Files.write(backup, backupBytes)
        val storage = AtomicJsonStorage(target, 1024 * 1024)

        val result = storage.readValidatedAndRecover { bytes ->
            require(bytes.contentEquals(backupBytes)) { "not a valid semantic payload" }
            "decoded"
        }

        assertEquals(AtomicJsonRead.Valid("decoded"), result)
        assertTrue(Files.readAllBytes(target).contentEquals(backupBytes))
        assertTrue(Files.readAllBytes(backup).contentEquals(backupBytes))
    }

    /**
     * 验证主文件缺失但备份有效时会恢复主文件，而不是将配置当作首次启动的空状态。
     */
    @Test
    fun `missing primary restores valid backup as original bytes`() {
        val target = tempDirectory.resolve("missing-primary.json")
        val backup = tempDirectory.resolve("missing-primary.json.bak")
        val backupBytes = "valid-backup-with-layout\n".encodeToByteArray()
        Files.write(backup, backupBytes)

        val result = AtomicJsonStorage(target, 1024 * 1024).readValidatedAndRecover { bytes ->
            require(bytes.contentEquals(backupBytes))
            "decoded"
        }

        assertEquals(AtomicJsonRead.Valid("decoded"), result)
        assertTrue(Files.readAllBytes(target).contentEquals(backupBytes))
        assertTrue(Files.readAllBytes(backup).contentEquals(backupBytes))
    }

    /**
     * 验证超出上限的主文件不会完整读取或解析，而是可由有效且受限的备份恢复。
     */
    @Test
    fun `oversized primary is recovered from a bounded valid backup`() {
        val target = tempDirectory.resolve("oversized-primary.json")
        val backup = tempDirectory.resolve("oversized-primary.json.bak")
        val backupBytes = "valid".encodeToByteArray()
        Files.write(target, ByteArray(17) { 'x'.code.toByte() })
        Files.write(backup, backupBytes)
        val storage = AtomicJsonStorage(target, 16)

        val result = storage.readValidatedAndRecover { bytes ->
            require(bytes.contentEquals(backupBytes))
            "decoded"
        }

        assertEquals(AtomicJsonRead.Valid("decoded"), result)
        assertContentEquals(backupBytes, Files.readAllBytes(target))
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
     * 验证旧备份已验证但无法替换损坏主文件时返回独立结果并保留两个原始文件。
     */
    @Test
    fun `failed backup recovery is distinguished and preserves both files`() {
        val primaryPath = tempDirectory.resolve("recovery-failure.json")
        val backup = tempDirectory.resolve("recovery-failure.json.bak")
        val damagedPrimary = "not-valid".encodeToByteArray()
        val backupBytes = "valid-backup".encodeToByteArray()
        Files.write(primaryPath, damagedPrimary)
        Files.write(backup, backupBytes)
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == primaryPath) {
                    throw IOException("injected recovery replacement failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }

        val result = AtomicJsonStorage(primaryPath, 1024 * 1024, operations).readValidatedAndRecover { bytes ->
            require(bytes.contentEquals(backupBytes))
            "decoded"
        }

        assertTrue(result is AtomicJsonRead.RecoveryFailed)
        assertTrue(Files.readAllBytes(primaryPath).contentEquals(damagedPrimary))
        assertTrue(Files.readAllBytes(backup).contentEquals(backupBytes))
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
}
