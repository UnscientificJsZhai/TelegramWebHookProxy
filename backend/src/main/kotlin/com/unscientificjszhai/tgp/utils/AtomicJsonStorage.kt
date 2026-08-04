package com.unscientificjszhai.tgp.utils

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
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

    class Present(val bytes: ByteArray) : AtomicJsonRawRead

    data class IoFailure(val cause: IOException) : AtomicJsonRawRead
}

/** 经过调用者完整语义验证后的读取结果。 */
internal sealed interface AtomicJsonRead<out T> {
    data object Missing : AtomicJsonRead<Nothing>

    data class Valid<T>(val value: T) : AtomicJsonRead<T>

    /** 主文件或备份文件不能被语义验证，且现场未被修改。 */
    data class Corrupt(val cause: Exception) : AtomicJsonRead<Nothing>

    /** 权限等 I/O 异常；这不是 JSON 损坏，因此不会尝试备份恢复。 */
    data class IoFailure(val cause: IOException) : AtomicJsonRead<Nothing>

    /**
     * 主文件已经损坏或缺失，但读取或验证备份被 I/O 异常阻塞。
     *
     * 调用者可以稍后重新尝试恢复，但在成功前不得写入默认内存状态。
     */
    data class RecoverabilityPending(val cause: IOException) : AtomicJsonRead<Nothing>

    /**
     * 备份已完成语义验证，但其原始字节无法原子恢复主文件。
     *
     * 主文件仍可能损坏，调用者必须进入不可写状态，避免后续提交把损坏主文件复制到备份。
     */
    data class RecoveryFailed(val cause: IOException) : AtomicJsonRead<Nothing>
}

/**
 * 为单个 JSON 配置文件提供同目录临时文件、强制落盘和原子替换。
 *
 * 此类只保证同一进程调用者围绕 [commit] 组织事务时的文件提交顺序；它不提供跨进程同步，
 * 也不声称在跨文件系统移动时可用。所有临时文件均创建在目标文件所在目录。
 * 底层文件系统不能原子覆盖已有目标时，提交会安全失败而不会降级为普通移动。为兼容旧版本
 * 遗留的 `.bak` 文件，读取时仍可使用其恢复已损坏的主文件；提交不会创建或更新 `.bak` 文件。
 */
internal class AtomicJsonStorage(
    private val target: Path,
    private val fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
    private val logger: Logger = LoggerFactory.getLogger(AtomicJsonStorage::class.java),
) {
    private val directory: Path = target.parent
        ?: throw IllegalArgumentException("JSON storage target must have a parent directory: $target")
    private val backup: Path = target.resolveSibling("${target.fileName}.bak")

    /**
     * 读取文件并执行完整语义验证；主文件语义损坏或缺失且备份存在时才尝试备份恢复。只有主文件
     * 与备份都不存在时才返回 [AtomicJsonRead.Missing]。
     *
     * 备份恢复先由 [decode] 验证备份原始字节，随后直接原子恢复这些原始字节到主文件，不会
     * 重新编码或覆盖唯一备份。
     */
    fun <T> readValidatedAndRecover(decode: (ByteArray) -> T): AtomicJsonRead<T> {
        return when (val primary = readRaw(target)) {
            AtomicJsonRawRead.Missing -> recoverMissingPrimary(decode)
            is AtomicJsonRawRead.IoFailure -> AtomicJsonRead.IoFailure(primary.cause)
            is AtomicJsonRawRead.Present -> decodeOrRecover(primary.bytes, decode)
        }
    }

    /**
     * 原子提交已经编码的 JSON 字节。
     *
     * 主文件替换成功后即视为逻辑提交成功。提交只创建主文件的临时文件，不会创建或更新 `.bak`
     * 文件；目录同步失败只记录告警，绝不会把已经成功的主文件替换报告为提交失败。主文件替换前的
     * 任意失败都会清理本次临时文件并抛出异常，不会降级为普通移动。
     */
    fun commit(bytes: ByteArray) {
        fileOperations.createDirectories(directory)

        var primaryTemporary: Path? = null
        try {
            primaryTemporary = createTemporary(".${target.name}.new-")
            fileOperations.writeAndForce(primaryTemporary, bytes)

            fileOperations.atomicReplace(primaryTemporary, target)
            primaryTemporary = null
            forceDirectoryBestEffort("primary replacement")
        } finally {
            primaryTemporary?.let(::deleteTemporaryQuietly)
        }
    }

    private fun <T> decodeOrRecover(primaryBytes: ByteArray, decode: (ByteArray) -> T): AtomicJsonRead<T> {
        val primaryValue = runCatching { decode(primaryBytes) }
        primaryValue.getOrNull()?.let { return AtomicJsonRead.Valid(it) }
        val primaryFailure = primaryValue.exceptionOrNull() as? Exception
            ?: IllegalStateException("Unable to validate JSON primary file")

        return recoverFromBackup(primaryFailure, decode)
    }

    private fun <T> recoverMissingPrimary(decode: (ByteArray) -> T): AtomicJsonRead<T> {
        return when (val backupRead = readRaw(backup)) {
            AtomicJsonRawRead.Missing -> AtomicJsonRead.Missing
            is AtomicJsonRawRead.IoFailure -> AtomicJsonRead.RecoverabilityPending(backupRead.cause)
            is AtomicJsonRawRead.Present -> {
                val backupValue = runCatching { decode(backupRead.bytes) }
                val decodedBackup = backupValue.getOrElse {
                    return AtomicJsonRead.Corrupt(
                        it as? Exception ?: IllegalStateException("Unable to validate JSON backup file"),
                    )
                }
                restoreValidatedBackup(backupRead.bytes, decodedBackup)
            }
        }
    }

    private fun <T> recoverFromBackup(primaryFailure: Exception, decode: (ByteArray) -> T): AtomicJsonRead<T> {
        val backupBytes = when (val backupRead = readRaw(backup)) {
            AtomicJsonRawRead.Missing -> return AtomicJsonRead.Corrupt(primaryFailure)
            is AtomicJsonRawRead.IoFailure -> return AtomicJsonRead.RecoverabilityPending(backupRead.cause)
            is AtomicJsonRawRead.Present -> backupRead.bytes
        }
        val backupValue = runCatching { decode(backupBytes) }
        val decodedBackup = backupValue.getOrElse {
            return AtomicJsonRead.Corrupt(primaryFailure)
        }

        return restoreValidatedBackup(backupBytes, decodedBackup)
    }

    private fun <T> restoreValidatedBackup(backupBytes: ByteArray, decodedBackup: T): AtomicJsonRead<T> {
        return try {
            restorePrimaryFromBackupBytes(backupBytes)
            logger.warn("Recovered damaged JSON file {} from {}", target, backup)
            AtomicJsonRead.Valid(decodedBackup)
        } catch (e: IOException) {
            AtomicJsonRead.RecoveryFailed(e)
        }
    }

    private fun restorePrimaryFromBackupBytes(bytes: ByteArray) {
        fileOperations.createDirectories(directory)
        var temporary: Path? = null
        try {
            temporary = createTemporary(".${target.name}.restore-")
            fileOperations.writeAndForce(temporary, bytes)
            fileOperations.atomicReplace(temporary, target)
            temporary = null
            forceDirectoryBestEffort("backup recovery")
        } finally {
            temporary?.let(::deleteTemporaryQuietly)
        }
    }

    private fun readRaw(path: Path): AtomicJsonRawRead = try {
        AtomicJsonRawRead.Present(fileOperations.readAllBytes(path))
    } catch (_: NoSuchFileException) {
        AtomicJsonRawRead.Missing
    } catch (e: IOException) {
        AtomicJsonRawRead.IoFailure(e)
    }

    private fun createTemporary(prefix: String): Path = fileOperations.createTempFile(directory, prefix, ".tmp")

    private fun deleteTemporaryQuietly(path: Path) {
        runCatching { fileOperations.deleteIfExists(path) }
            .onFailure { logger.warn("Failed to remove temporary JSON file {}", path, it) }
    }

    private fun forceDirectoryBestEffort(after: String) {
        runCatching { fileOperations.forceDirectory(directory) }
            .onFailure { logger.warn("JSON storage directory sync after {} failed for {}", after, target, it) }
    }
}
