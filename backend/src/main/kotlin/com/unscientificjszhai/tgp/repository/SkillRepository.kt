package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillBrief
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock

@Singleton
/**
 * 持久化技能定义，并提供分页读取与变更通知。
 *
 * 技能存储在 JSON 配置文件中；保存和删除会以替换文件的方式提交更新，并在成功后发布事件。
 */
class SkillRepository private constructor(
    configFile: File,
    fileOperations: AtomicJsonFileOperations,
) {
    /**
     * 创建使用默认技能配置文件的仓储。
     *
     * @constructor 创建使用 `config/skills.json` 的仓储；该目录不存在时会创建。
     */
    @Inject
    constructor() : this(File("config/skills.json"), DefaultAtomicJsonFileOperations)

    companion object {
        internal fun forTesting(
            configFile: File,
            fileOperations: AtomicJsonFileOperations = DefaultAtomicJsonFileOperations,
        ): SkillRepository = SkillRepository(configFile, fileOperations)
    }

    private val logger = LoggerFactory.getLogger(SkillRepository::class.java)
    private val storage = AtomicJsonStorage(configFile.toPath(), fileOperations)
    private val storageLock = ReentrantLock()
    private var requiresStorageValidationBeforeWrite = false

    private val _skillsUpdateEvent = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 技能数据成功变更时发布的无载荷事件流。
     *
     * 事件不重放；消费者应在收到事件后重新读取所需数据。
     */
    val skillsUpdateEvent: SharedFlow<Unit> = _skillsUpdateEvent.asSharedFlow()

    init {
        configFile.parentFile?.let { parent ->
            if (!parent.exists()) {
                parent.mkdirs()
            }
        }
    }

    /**
     * 分页读取所有技能。
     *
     * 配置文件不存在时返回总数为 `0` 的空页；主文件语义损坏但 `.bak` 完整时会先恢复备份。
     * 主文件和备份均无法解析时返回空页而不改写现场。
     *
     * @param page 从 `1` 开始的页码；调用方应传入正整数。
     * @param size 每页最大技能数；调用方应传入正整数。
     * @return 包含总技能数和当前页技能的结果；技能顺序与配置文件一致。
     */
    fun getAllSkills(page: Int = 1, size: Int = 10): PageResult<Skill> {
        return storageLock.withLock {
            val skills = readSkillsForRead()
            val startIndex = (page - 1) * size
            val endIndex = startIndex + size
            PageResult(skills.size, skills.filterIndexed { index, _ -> index in startIndex..<endIndex })
        }
    }

    /**
     * 读取所有技能的摘要信息。
     *
     * 配置文件不存在时返回空列表；主文件语义损坏但 `.bak` 完整时会先恢复备份。主文件和备份
     * 均无法解析时返回空列表而不改写现场。
     *
     * @return 技能摘要列表，顺序与配置文件一致；没有可读取的技能时为空列表。
     */
    fun getSkillSummaries(): List<SkillBrief> {
        return storageLock.withLock {
            readSkillsForRead().map { SkillBrief(it.id, it.description) }
        }
    }

    /**
     * 按标识读取单个技能。
     *
     * @param id 要查询的技能标识，不能为空；按完全相等的字符串匹配。
     * @return 匹配的技能；未找到、文件不可读取或主文件和备份均无法解析时为 `null`。
     */
    fun getSkillById(id: String): Skill? {
        return storageLock.withLock {
            readSkillsForRead().find { it.id == id }
        }
    }

    /**
     * 新增或覆盖保存技能。
     *
     * 标识相同的已有技能会被 [skill] 完全替换；成功后会发布一次 [skillsUpdateEvent]。
     *
     * @param skill 要持久化的完整技能，不能为空。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；不会发布变更事件。
     * @throws Exception 配置文件无法编码或原子提交时抛出；不会发布变更事件。
     */
    fun saveSkill(skill: Skill) {
        storageLock.withLock {
            val skills = readSkillsForMutation().items.toMutableList()
            val existingIndex = skills.indexOfFirst { it.id == skill.id }
            if (existingIndex >= 0) {
                skills[existingIndex] = skill
            } else {
                skills += skill
            }
            storage.commit(ConfigJson.encodeToString(skills).toByteArray(StandardCharsets.UTF_8))
            _skillsUpdateEvent.tryEmit(Unit)
        }
    }

    /**
     * 删除指定标识的技能。
     *
     * 配置文件不存在或为空时不执行任何操作；成功完成文件更新后会发布一次 [skillsUpdateEvent]，
     * 即使未找到匹配技能也是如此。
     *
     * @param id 要删除的技能标识，不能为空；按完全相等的字符串匹配。
     * @throws IllegalStateException 配置文件、备份不可安全恢复或暂不可读取时抛出；不会发布变更事件。
     * @throws Exception 配置文件无法编码或原子提交时抛出；不会发布变更事件。
     */
    fun deleteSkill(id: String) {
        storageLock.withLock {
            val snapshot = readSkillsForMutation()
            if (!snapshot.exists) {
                return
            }
            storage.commit(
                ConfigJson.encodeToString(snapshot.items.filterNot { it.id == id }).toByteArray(StandardCharsets.UTF_8),
            )
            _skillsUpdateEvent.tryEmit(Unit)
        }
    }

    private fun readSkillsForRead(): List<Skill> = when (val read = storage.readValidatedAndRecover(::decodeSkills)) {
        AtomicJsonRead.Missing -> {
            requiresStorageValidationBeforeWrite = false
            emptyList()
        }

        is AtomicJsonRead.Valid -> {
            requiresStorageValidationBeforeWrite = false
            read.value
        }

        is AtomicJsonRead.Corrupt -> {
            requiresStorageValidationBeforeWrite = true
            logger.error("Skills file and its backup are semantically invalid; preserving both files", read.cause)
            emptyList()
        }

        is AtomicJsonRead.IoFailure -> {
            requiresStorageValidationBeforeWrite = true
            logger.error("Unable to read skills data; delaying writes until it can be revalidated", read.cause)
            emptyList()
        }

        is AtomicJsonRead.RecoveryFailed -> {
            requiresStorageValidationBeforeWrite = true
            logger.error(
                "Validated skills backup could not be restored; preserving files and disabling writes",
                read.cause
            )
            emptyList()
        }

        is AtomicJsonRead.RecoverabilityPending -> {
            requiresStorageValidationBeforeWrite = true
            logger.error("Skills recovery is blocked by I/O; delaying writes until revalidation", read.cause)
            emptyList()
        }
    }

    private fun readSkillsForMutation(): SkillSnapshot {
        return when (val read = storage.readValidatedAndRecover(::decodeSkills)) {
            AtomicJsonRead.Missing -> {
                requiresStorageValidationBeforeWrite = false
                SkillSnapshot(exists = false, items = emptyList())
            }

            is AtomicJsonRead.Valid -> {
                requiresStorageValidationBeforeWrite = false
                SkillSnapshot(exists = true, items = read.value)
            }

            is AtomicJsonRead.Corrupt -> {
                requiresStorageValidationBeforeWrite = true
                throw IllegalStateException("技能文件及备份均已损坏，拒绝覆盖现场。", read.cause)
            }

            is AtomicJsonRead.IoFailure -> {
                requiresStorageValidationBeforeWrite = true
                throw IllegalStateException("技能文件尚不可读取，拒绝覆盖现场。", read.cause)
            }

            is AtomicJsonRead.RecoveryFailed -> {
                requiresStorageValidationBeforeWrite = true
                throw IllegalStateException("有效技能备份无法恢复主文件，拒绝覆盖现场。", read.cause)
            }

            is AtomicJsonRead.RecoverabilityPending -> {
                requiresStorageValidationBeforeWrite = true
                throw IllegalStateException("技能备份尚不可读取或验证，拒绝覆盖现场。", read.cause)
            }
        }
    }

    private fun decodeSkills(bytes: ByteArray): List<Skill> {
        val content = bytes.toString(StandardCharsets.UTF_8)
        if (content.isBlank()) {
            throw IllegalArgumentException("Skills data must not be blank")
        }
        return ConfigJson.decodeFromString(content)
    }

    private data class SkillSnapshot(
        val exists: Boolean,
        val items: List<Skill>,
    )
}
