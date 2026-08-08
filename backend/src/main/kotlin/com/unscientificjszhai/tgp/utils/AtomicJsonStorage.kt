package com.unscientificjszhai.tgp.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
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

    /**
     * 确保目标目录存在，且该目录在其父目录中的目录项已经同步。
     *
     * 调用完成前不得创建主文件临时项。实现必须在目录刚创建以及先前创建结果的耐久性未知时，都能通过同步
     * 父目录重新确认目录项；[path] 的父目录必须已经存在。
     *
     * @param path 要准备的目标目录。
     * @throws IOException 目录或其父目录不可访问，或父目录同步失败时抛出。
     * @throws UnsupportedOperationException 文件系统不支持父目录同步时抛出。
     */
    fun prepareDirectoryDurably(path: Path)

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

    override fun prepareDirectoryDurably(path: Path) {
        val parent = path.parent
        if (parent == null) {
            requireDirectory(path)
            return
        }
        requireDirectory(parent)
        Files.createDirectories(path)
        forceDirectory(parent)
    }

    private fun requireDirectory(path: Path) {
        if (!Files.readAttributes(path, BasicFileAttributes::class.java).isDirectory) {
            throw NotDirectoryException(path.toString())
        }
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

/** 经过调用者完整语义验证并确认主目录项耐久后的读取结果。 */
internal sealed interface AtomicJsonRead<out T> {
    data object Missing : AtomicJsonRead<Nothing>

    data class Valid<T>(val value: T) : AtomicJsonRead<T>

    /** 主文件不能被语义验证，且现场未被修改。 */
    data class Corrupt(val cause: Exception) : AtomicJsonRead<Nothing>

    /** 权限等 I/O 异常；这不是 JSON 损坏。 */
    data class IoFailure(val cause: IOException) : AtomicJsonRead<Nothing>
}

/** 原子替换后的目录项耐久性结果。 */
internal sealed interface AtomicJsonCommitResult {
    /** 文件内容与目录项均已强制同步到持久化介质。 */
    data object Durable : AtomicJsonCommitResult

    /**
     * 新文件已经通过原子替换对当前进程可见，但目录项是否能在掉电后保留尚不确定。
     *
     * 调用方不得把该结果当作耐久提交成功；在依赖本次写入放行不可重放副作用前，必须隔离该操作或重新提交并
     * 获得 [Durable]。
     */
    data class ReplacedDurabilityUnknown(val cause: Exception) : AtomicJsonCommitResult
}

/** 目录项同步失败，文件替换可见但提交耐久性未知。 */
internal class JsonStorageDurabilityUnknownException(
    cause: Exception,
) : IOException("JSON 文件替换已可见，但目录项同步失败，提交耐久性未知。", cause)

/**
 * 要求原子 JSON 提交已经耐久。
 *
 * @return 当前 [AtomicJsonCommitResult.Durable] 结果。
 * @throws JsonStorageDurabilityUnknownException 文件替换可见但目录项同步失败时抛出。
 */
internal fun AtomicJsonCommitResult.requireDurable(): AtomicJsonCommitResult.Durable = when (this) {
    AtomicJsonCommitResult.Durable -> AtomicJsonCommitResult.Durable
    is AtomicJsonCommitResult.ReplacedDurabilityUnknown -> throw JsonStorageDurabilityUnknownException(cause)
}

/**
 * 为单个 JSON 配置文件提供同目录临时文件、强制落盘和原子替换。
 *
 * 此类只保证同一进程调用者围绕 [commit] 组织事务时的文件提交顺序；它不提供跨进程同步，
 * 也不声称在跨文件系统移动时可用。所有临时文件均创建在目标文件所在目录。
 * 目标目录可在首次提交时创建，但其父目录必须已经存在；父目录项会在任何主文件替换前同步。有效主文件
 * 读取也会重新同步父目录和目标目录，使前一进程留下的未知提交在发布前收敛。底层文件系统不能原子覆盖
 * 已有目标时，提交会安全失败而不会降级为普通移动。
 */
internal class AtomicJsonStorage(
    target: Path,
    private val maxBytes: Int,
    private val fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
    private val logger: Logger = LoggerFactory.getLogger(AtomicJsonStorage::class.java),
) {
    private val target = target.toAbsolutePath().normalize()

    init {
        require(maxBytes > 0) { "JSON storage maximum size must be positive." }
    }

    private val directory: Path = target.parent
        ?: throw IllegalArgumentException("JSON storage target must have a parent directory: $target")
    private var directoryPreparedDurably = false

    /**
     * 读取主文件并执行完整语义验证。
     *
     * 文件不存在时返回 [AtomicJsonRead.Missing]；文件过大或语义无法验证时返回
     * [AtomicJsonRead.Corrupt]，读取 I/O 或主目录项耐久性确认失败时返回 [AtomicJsonRead.IoFailure]。
     * 有效主文件只会在同步目标目录及其父目录后返回，避免前一进程留下“替换可见但耐久性未知”的文件被
     * 新进程直接用于放行副作用。此方法不会读取、创建或恢复任何备份文件。
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
     * 提交只创建主文件的临时文件。主文件替换前的任意失败都会清理本次临时文件并抛出异常，不会降级为普通
     * 移动。替换后目录同步成功时返回 [AtomicJsonCommitResult.Durable]；目录同步不受支持或失败时返回
     * [AtomicJsonCommitResult.ReplacedDurabilityUnknown]，使调用方不能误放行依赖耐久提交的副作用。
     *
     * @param bytes 已编码的完整 JSON 字节，大小不得超过本存储上限。
     * @return 文件替换及目录同步得到的耐久性结果。
     * @throws IllegalArgumentException [bytes] 超过存储上限时抛出。
     * @throws IOException 主文件读取、临时文件写入或原子替换在替换完成前失败时抛出。
     */
    fun commit(bytes: ByteArray): AtomicJsonCommitResult {
        require(bytes.size <= maxBytes) { "JSON 文件超过 $maxBytes 字节上限。" }
        when (val read = readRaw(target)) {
            AtomicJsonRawRead.Missing, is AtomicJsonRawRead.Present -> Unit
            is AtomicJsonRawRead.TooLarge -> throw JsonStorageSizeLimitExceededException(read.limitBytes)
            is AtomicJsonRawRead.IoFailure -> throw read.cause
        }
        prepareDirectoryDurably()

        var primaryTemporary: Path? = null
        try {
            primaryTemporary = createTemporary(".${target.name}.new-", ".tmp")
            fileOperations.writeAndForce(primaryTemporary, bytes)

            fileOperations.atomicReplace(primaryTemporary, target)
            primaryTemporary = null
            return forceDirectoryAfterReplacement()
        } finally {
            primaryTemporary?.let(::deleteTemporaryQuietly)
        }
    }

    private fun <T> decodePrimary(primaryBytes: ByteArray, decode: (ByteArray) -> T): AtomicJsonRead<T> {
        val value = try {
            decode(primaryBytes)
        } catch (failure: Exception) {
            return AtomicJsonRead.Corrupt(failure)
        }
        return try {
            confirmPrimaryDirectoryDurable()
            AtomicJsonRead.Valid(value)
        } catch (failure: Exception) {
            AtomicJsonRead.IoFailure(
                IOException("无法确认 JSON 主文件目录项的耐久性。", failure),
            )
        }
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

    /** 在创建提交临时项前确认目标目录本身不会因父目录未同步而在掉电后丢失。 */
    private fun prepareDirectoryDurably() {
        if (directoryPreparedDurably) return
        fileOperations.prepareDirectoryDurably(directory)
        directoryPreparedDurably = true
    }

    /** 重新同步已读取主文件的目录项，使上一进程的未知提交在发布前收敛为耐久状态。 */
    private fun confirmPrimaryDirectoryDurable() {
        prepareDirectoryDurably()
        fileOperations.forceDirectory(directory)
    }

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

    private fun forceDirectoryAfterReplacement(): AtomicJsonCommitResult = try {
        fileOperations.forceDirectory(directory)
        AtomicJsonCommitResult.Durable
    } catch (error: Exception) {
        logger.warn(
            "JSON storage directory sync after primary replacement failed for {}; durability=unknown; category={}",
            target,
            SafeLogging.failureCategory(error).wireName,
        )
        AtomicJsonCommitResult.ReplacedDurabilityUnknown(error)
    }
}
