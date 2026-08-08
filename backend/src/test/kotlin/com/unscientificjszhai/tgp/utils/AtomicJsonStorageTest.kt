package com.unscientificjszhai.tgp.utils

import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.*

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

    @Test
    fun `validated read confirms visible primary durability before publishing it`() {
        val target = tempDirectory.resolve("visible-but-unconfirmed.json")
        Files.writeString(target, "content")
        var directorySyncAvailable = false
        var targetDirectoryForces = 0
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun forceDirectory(path: Path) {
                targetDirectoryForces++
                if (!directorySyncAvailable) throw IOException("injected directory sync failure")
                DefaultAtomicJsonFileOperations.forceDirectory(path)
            }
        }
        val storage = AtomicJsonStorage(target, 1024 * 1024, operations)

        val uncertain = storage.readValidated { bytes -> bytes.decodeToString() }

        assertIs<AtomicJsonRead.IoFailure>(uncertain)
        assertEquals(1, targetDirectoryForces)

        directorySyncAvailable = true
        val confirmed = storage.readValidated { bytes -> bytes.decodeToString() }

        assertEquals(AtomicJsonRead.Valid("content"), confirmed)
        assertEquals(2, targetDirectoryForces)
    }

    /** 验证首次提交由原子层准备目标目录，并在任何主文件替换前完成父目录耐久确认。 */
    @Test
    fun `new target directory is durably prepared before primary replacement`() {
        val targetDirectory = tempDirectory.resolve("new-config")
        val target = targetDirectory.resolve("state.json")
        val events = mutableListOf<String>()
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun prepareDirectoryDurably(path: Path) {
                events += "prepare-directory"
                DefaultAtomicJsonFileOperations.prepareDirectoryDurably(path)
            }

            override fun atomicReplace(source: Path, target: Path) {
                events += "replace-primary"
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }

            override fun forceDirectory(path: Path) {
                events += "force-target-directory"
                DefaultAtomicJsonFileOperations.forceDirectory(path)
            }
        }

        val result = AtomicJsonStorage(target, 1024 * 1024, operations).commit("content".encodeToByteArray())

        assertEquals(AtomicJsonCommitResult.Durable, result)
        assertEquals(
            listOf("prepare-directory", "replace-primary", "force-target-directory"),
            events,
        )
        assertEquals("content", Files.readString(target))
    }

    /**
     * 验证父目录同步失败发生在替换前；即使目录已经可见，重建存储后仍会重新确认目录耐久性再提交。
     */
    @Test
    fun `directory preparation failure prevents replacement and is retried by a new storage instance`() {
        val targetDirectory = tempDirectory.resolve("uncertain-config")
        val target = targetDirectory.resolve("state.json")
        var preparationAvailable = false
        var preparationCalls = 0
        var replacementCalls = 0
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun prepareDirectoryDurably(path: Path) {
                preparationCalls++
                if (!preparationAvailable) {
                    Files.createDirectories(path)
                    throw IOException("injected parent directory sync failure")
                }
                DefaultAtomicJsonFileOperations.prepareDirectoryDurably(path)
            }

            override fun atomicReplace(source: Path, target: Path) {
                replacementCalls++
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }

        assertFailsWith<IOException> {
            AtomicJsonStorage(target, 1024 * 1024, operations).commit("first".encodeToByteArray())
        }
        assertEquals(1, preparationCalls)
        assertEquals(0, replacementCalls)
        assertTrue(Files.isDirectory(targetDirectory))
        assertTrue(Files.notExists(target))

        preparationAvailable = true
        val result = AtomicJsonStorage(target, 1024 * 1024, operations).commit("second".encodeToByteArray())

        assertEquals(AtomicJsonCommitResult.Durable, result)
        assertEquals(2, preparationCalls)
        assertEquals(1, replacementCalls)
        assertEquals("second", Files.readString(target))
    }

    /**
     * 验证主文件替换后的目录同步失败会返回可见但耐久性未知的独立状态。
     */
    @Test
    fun `commit reports unknown durability after post replacement directory sync failure`() {
        val target = tempDirectory.resolve("commit-order.json")
        Files.writeString(target, "old-primary")
        val operations = RecordingFileOperations(failDirectoryForce = true)

        val result = AtomicJsonStorage(target, 1024 * 1024, operations).commit("new-primary".encodeToByteArray())

        val primaryReplacement = operations.events.indexOf("replace:commit-order.json")
        val directoryForce = operations.events.indexOf("force-directory")
        assertTrue(primaryReplacement < directoryForce)
        assertEquals(1, operations.events.count { it == "force-directory" })
        assertEquals("new-primary", Files.readString(target))
        assertTrue(result is AtomicJsonCommitResult.ReplacedDurabilityUnknown)
        assertFailsWith<JsonStorageDurabilityUnknownException> { result.requireDurable() }
    }

    /** 验证目录同步成功时提交返回明确的耐久状态。 */
    @Test
    fun `commit reports durable after directory sync succeeds`() {
        val target = tempDirectory.resolve("durable.json")

        val result = AtomicJsonStorage(target, 1024 * 1024).commit("content".encodeToByteArray())

        assertEquals(AtomicJsonCommitResult.Durable, result)
        assertEquals(AtomicJsonCommitResult.Durable, result.requireDurable())
    }

    /** 验证文件系统不支持目录同步时同样返回耐久性未知，而不是误报成功。 */
    @Test
    fun `commit reports unknown durability when directory sync is unsupported`() {
        val target = tempDirectory.resolve("unsupported-directory-sync.json")
        val operations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun forceDirectory(path: Path) {
                throw UnsupportedOperationException("injected unsupported directory sync")
            }
        }

        val result = AtomicJsonStorage(target, 1024 * 1024, operations).commit("content".encodeToByteArray())

        val unknown = assertIs<AtomicJsonCommitResult.ReplacedDurabilityUnknown>(result)
        assertIs<UnsupportedOperationException>(unknown.cause)
        assertEquals("content", Files.readString(target))
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
