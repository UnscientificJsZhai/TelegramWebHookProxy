package com.unscientificjszhai.tgp.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.name

/**
 * JSON 文件原子读写所需的文件系统操作。
 *
 * 该接口仅用于在测试中注入确定性的文件系统故障；生产代码使用 [DefaultAtomicJsonFileOperations]。
 */
internal interface AtomicJsonFileOperations {
    fun createTempFile(directory: Path, prefix: String, suffix: String): Path

    fun readAllBytes(path: Path): ByteArray

    /**
     * 最多读取 [maxBytes] 个字节的文件内容。
     *
     * 默认实现仅供确定性测试替身复用；生产实现必须在读取期间实施上限，而不能先完整载入文件。
     *
     * @param path 要读取的文件路径。
     * @param maxBytes 允许读取的最大正数字节数。
     * @return 长度不超过 [maxBytes] 的完整文件内容。
     * @throws JsonStorageSizeLimitExceededException 文件超过上限时抛出。
     */
    fun readAtMost(path: Path, maxBytes: Int): ByteArray {
        val bytes = readAllBytes(path)
        if (bytes.size > maxBytes) throw JsonStorageSizeLimitExceededException(maxBytes)
        return bytes
    }

    fun writeAndForce(path: Path, bytes: ByteArray)

    /**
     * 原子覆盖 [target] 为 [source]，且 [target] 可能已经存在。
     *
     * 底层提供方不支持原子覆盖既有目标时必须抛出异常；调用方不得以普通移动作为回退。
     */
    fun atomicReplace(source: Path, target: Path)

    fun deleteIfExists(path: Path)

    fun createDirectories(path: Path)

    fun forceDirectory(path: Path)
}

/** 生产环境使用的 [AtomicJsonFileOperations] 实现。 */
internal object DefaultAtomicJsonFileOperations : AtomicJsonFileOperations {
    override fun createTempFile(directory: Path, prefix: String, suffix: String): Path =
        Files.createTempFile(directory, prefix, suffix)

    override fun readAllBytes(path: Path): ByteArray = Files.readAllBytes(path)

    override fun readAtMost(path: Path, maxBytes: Int): ByteArray {
        require(maxBytes > 0) { "Maximum JSON file size must be positive." }
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            if (channel.size() > maxBytes) throw JsonStorageSizeLimitExceededException(maxBytes)
            val output = ByteArrayOutputStream(minOf(channel.size().toInt(), maxBytes))
            val buffer = ByteBuffer.allocate(minOf(8192, maxBytes + 1))
            var total = 0
            while (true) {
                buffer.clear()
                val read = channel.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > maxBytes) throw JsonStorageSizeLimitExceededException(maxBytes)
                output.write(buffer.array(), 0, read)
            }
            return output.toByteArray()
        }
    }

    override fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                channel.write(buffer)
            }
            channel.force(true)
        }
    }

    override fun atomicReplace(source: Path, target: Path) {
        try {
            Files.move(
                source,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: AtomicMoveNotSupportedException) {
            // A non-atomic fallback can expose a partially replaced primary file.  Callers must
            // receive the failure while their in-memory transaction is still unchanged.
            throw e
        }
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }

    override fun createDirectories(path: Path) {
        Files.createDirectories(path)
    }

    override fun forceDirectory(path: Path) {
        FileChannel.open(path, StandardOpenOption.READ).use { channel -> channel.force(true) }
    }
}

/** 单次原始文件读取的结果。 */
internal sealed interface AtomicJsonRawRead {
    data object Missing : AtomicJsonRawRead

    class Present(val bytes: ByteArray) : AtomicJsonRawRead {
        override fun equals(other: Any?): Boolean = other is Present && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /** 文件超过所属存储的上限，不能安全解码。 */
    data class TooLarge(val limitBytes: Int) : AtomicJsonRawRead

    data class IoFailure(val cause: IOException) : AtomicJsonRawRead
}

/** 经过调用者完整语义验证后的读取结果。 */
internal sealed interface AtomicJsonRead<out T> {
    data object Missing : AtomicJsonRead<Nothing>

    data class Valid<T>(val value: T) : AtomicJsonRead<T>

    /** 主文件不能被语义验证，且现场未被修改。 */
    data class Corrupt(val cause: Exception) : AtomicJsonRead<Nothing>

    /** 权限等 I/O 异常；这不是 JSON 损坏。 */
    data class IoFailure(val cause: IOException) : AtomicJsonRead<Nothing>
}

/**
 * 为单个 JSON 配置文件提供同目录临时文件、强制落盘和原子替换。
 *
 * 此类只保证同一进程调用者围绕 [commit] 组织事务时的文件提交顺序；它不提供跨进程同步，
 * 也不声称在跨文件系统移动时可用。所有临时文件均创建在目标文件所在目录。
 * 底层文件系统不能原子覆盖已有目标时，提交会安全失败而不会降级为普通移动。
 */
internal class AtomicJsonStorage(
    private val target: Path,
    private val maxBytes: Int,
    private val fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
    private val logger: Logger = LoggerFactory.getLogger(AtomicJsonStorage::class.java),
) {
    init {
        require(maxBytes > 0) { "JSON storage maximum size must be positive." }
    }

    private val directory: Path = target.parent
        ?: throw IllegalArgumentException("JSON storage target must have a parent directory: $target")

    /** 读取主文件原始字节，不会尝试解析。 */
    fun readPrimary(): AtomicJsonRawRead = readRaw(target)

    /**
     * 读取主文件并执行完整语义验证。
     *
     * 文件不存在时返回 [AtomicJsonRead.Missing]；文件过大或语义无法验证时返回
     * [AtomicJsonRead.Corrupt]，读取 I/O 失败时返回 [AtomicJsonRead.IoFailure]。此方法不会
     * 读取、创建或恢复任何其他文件。
     */
    fun <T> readValidated(decode: (ByteArray) -> T): AtomicJsonRead<T> {
        return when (val primary = readRaw(target)) {
            AtomicJsonRawRead.Missing -> AtomicJsonRead.Missing
            is AtomicJsonRawRead.TooLarge -> AtomicJsonRead.Corrupt(
                JsonStorageSizeLimitExceededException(primary.limitBytes),
            )

            is AtomicJsonRawRead.IoFailure -> AtomicJsonRead.IoFailure(primary.cause)
            is AtomicJsonRawRead.Present -> decodePrimary(primary.bytes, decode)
        }
    }

    /**
     * 原子提交已经编码的 JSON 字节。
     *
     * 主文件替换成功后即视为逻辑提交成功。提交只创建主文件的临时文件；目录同步失败只记录告警，
     * 绝不会把已经成功的主文件替换报告为提交失败。主文件替换前的任意失败都会清理本次临时文件
     * 并抛出异常，不会降级为普通移动。
     */
    fun commit(bytes: ByteArray) {
        require(bytes.size <= maxBytes) { "JSON 文件超过 $maxBytes 字节上限。" }
        when (val read = readRaw(target)) {
            AtomicJsonRawRead.Missing, is AtomicJsonRawRead.Present -> Unit
            is AtomicJsonRawRead.TooLarge -> throw JsonStorageSizeLimitExceededException(read.limitBytes)
            is AtomicJsonRawRead.IoFailure -> throw read.cause
        }
        fileOperations.createDirectories(directory)

        var primaryTemporary: Path? = null
        try {
            primaryTemporary = createTemporary(".${target.name}.new-", ".tmp")
            fileOperations.writeAndForce(primaryTemporary, bytes)

            fileOperations.atomicReplace(primaryTemporary, target)
            primaryTemporary = null
            forceDirectoryBestEffort("primary replacement")
        } finally {
            primaryTemporary?.let(::deleteTemporaryQuietly)
        }
    }

    private fun <T> decodePrimary(primaryBytes: ByteArray, decode: (ByteArray) -> T): AtomicJsonRead<T> {
        val primaryValue = runCatching { decode(primaryBytes) }
        primaryValue.getOrNull()?.let { return AtomicJsonRead.Valid(it) }
        val primaryFailure = primaryValue.exceptionOrNull() as? Exception
            ?: IllegalStateException("Unable to validate JSON primary file")
        return AtomicJsonRead.Corrupt(primaryFailure)
    }

    private fun readRaw(path: Path): AtomicJsonRawRead = try {
        AtomicJsonRawRead.Present(fileOperations.readAtMost(path, maxBytes))
    } catch (_: NoSuchFileException) {
        AtomicJsonRawRead.Missing
    } catch (e: JsonStorageSizeLimitExceededException) {
        AtomicJsonRawRead.TooLarge(e.limitBytes)
    } catch (e: IOException) {
        AtomicJsonRawRead.IoFailure(e)
    }

    private fun createTemporary(prefix: String, suffix: String): Path =
        fileOperations.createTempFile(directory, prefix, suffix)

    private fun deleteTemporaryQuietly(path: Path) {
        runCatching { fileOperations.deleteIfExists(path) }
            .onFailure { error ->
                logger.warn(
                    "Failed to remove temporary JSON file {}; category={}",
                    path,
                    SafeLogging.failureCategory(error).wireName,
                )
            }
    }

    private fun forceDirectoryBestEffort(after: String) {
        runCatching { fileOperations.forceDirectory(directory) }
            .onFailure { error ->
                logger.warn(
                    "JSON storage directory sync after {} failed for {}; category={}",
                    after,
                    target,
                    SafeLogging.failureCategory(error).wireName,
                )
            }
    }
}
