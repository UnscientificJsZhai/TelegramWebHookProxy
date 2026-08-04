package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillBrief
import com.unscientificjszhai.tgp.models.isValidSkillId
import com.unscientificjszhai.tgp.models.validateSkill
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonRawRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ResourceLimits
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
    private val storage = AtomicJsonStorage(configFile.toPath(), ResourceLimits.SKILLS_BYTES, fileOperations)
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
     * 配置文件不存在时返回总数为 `0` 的空页；主文件无法解析时返回空页而不改写现场。
     * 主文件含历史非法技能标识时，仓储会隔离该存储。
     *
     * @param page 从 `1` 开始的页码，必须在 `1..Int.MAX_VALUE` 范围内。
     * @param size 每页请求的技能数量，必须在 `1..50` 范围内。
     * @return 包含总技能数和当前页技能的结果；技能顺序与配置文件一致。
     * @throws IllegalArgumentException [page] 小于 `1` 或 [size] 不在 `1..50` 范围内时抛出。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getAllSkills(page: Int = 1, size: Int = 10): PageResult<Skill> {
        require(page >= 1) { "页码必须大于等于 1。" }
        require(size in 1..50) { "每页数量必须在 1..50 范围内。" }
        return storageLock.withLock {
            val skills = readSkillsForRead()
            val startIndex = (page.toLong() - 1) * size
            val items = if (startIndex >= skills.size) {
                emptyList()
            } else {
                val fromIndex = startIndex.toInt()
                val toIndex = minOf(fromIndex + size, skills.size)
                skills.subList(fromIndex, toIndex)
            }
            PageResult(skills.size, items)
        }
    }

    /**
     * 读取所有技能的摘要信息。
     *
     * 配置文件不存在时返回空列表；主文件无法解析时返回空列表而不改写现场。主文件含历史非法
     * 技能标识时会隔离存储。
     *
     * @return 技能摘要列表，顺序与配置文件一致；没有可读取的技能时为空列表。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getSkillSummaries(): List<SkillBrief> {
        return storageLock.withLock {
            readSkillsForRead().map { SkillBrief(it.id, it.description) }
        }
    }

    /**
     * 按标识读取单个技能。
     *
     * @param id 要查询的技能标识，必须匹配 [com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN]；按完全相等的字符串匹配。
     * @return 匹配的技能；未找到、文件不可读取或主文件无法解析时为 `null`。
     * @throws IllegalArgumentException [id] 不匹配技能标识格式时抛出。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getSkillById(id: String): Skill? {
        require(isValidSkillId(id)) { "技能标识不合法。" }
        return storageLock.withLock {
            readSkillsForRead().find { it.id == id }
        }
    }

    /**
     * 新增或覆盖保存技能。
     *
     * 标识相同的已有技能会被 [skill] 完全替换；成功后会发布一次 [skillsUpdateEvent]。
     *
     * @param skill 要持久化的完整技能；其标识必须匹配 [com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN]，
     * 描述不得超过 `1024` UTF-8 字节，内容不得超过 `64 KiB`，保存后技能总数不得超过 `64`。
     * @throws IllegalArgumentException [skill] 的字段不符合格式或大小限制，或保存后技能总数超过 `64` 时抛出。
     * @throws IllegalStateException 配置文件已损坏、含历史非法技能标识或暂不可读取时抛出；不会发布变更事件。
     * @throws Exception 配置文件无法编码或原子提交时抛出；不会发布变更事件。
     */
    fun saveSkill(skill: Skill) {
        storageLock.withLock {
            validateSkill(skill)
            val skills = readSkillsForMutation().items.toMutableList()
            val existingIndex = skills.indexOfFirst { it.id == skill.id }
            if (existingIndex >= 0) {
                skills[existingIndex] = skill
            } else {
                skills += skill
            }
            require(skills.size <= 64) { "技能数量不能超过 64 个。" }
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
     * @param id 要删除的技能标识，必须匹配 [com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN]；按完全相等的字符串匹配。
     * @throws IllegalArgumentException [id] 不匹配技能标识格式时抛出。
     * @throws IllegalStateException 配置文件已损坏、含历史非法技能标识或暂不可读取时抛出；不会发布变更事件。
     * @throws Exception 配置文件无法编码或原子提交时抛出；不会发布变更事件。
     */
    fun deleteSkill(id: String) {
        require(isValidSkillId(id)) { "技能标识不合法。" }
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

    private fun readSkillsForRead(): List<Skill> {
        ensureNoHistoricalInvalidSkillIds()
        return when (val read = storage.readValidated(::decodeSkills)) {
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
                logger.error("Skills file is semantically invalid; preserving it", read.cause)
                emptyList()
            }

            is AtomicJsonRead.IoFailure -> {
                requiresStorageValidationBeforeWrite = true
                logger.error("Unable to read skills data; delaying writes until it can be revalidated", read.cause)
                emptyList()
            }

        }
    }

    private fun readSkillsForMutation(): SkillSnapshot {
        ensureNoHistoricalInvalidSkillIds()
        return when (val read = storage.readValidated(::decodeSkills)) {
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
                throw IllegalStateException("技能文件已损坏，拒绝覆盖现场。", read.cause)
            }

            is AtomicJsonRead.IoFailure -> {
                requiresStorageValidationBeforeWrite = true
                throw IllegalStateException("技能文件尚不可读取，拒绝覆盖现场。", read.cause)
            }

        }
    }

    private fun decodeSkills(bytes: ByteArray): List<Skill> {
        val content = bytes.toString(StandardCharsets.UTF_8)
        if (content.isBlank()) {
            throw IllegalArgumentException("Skills data must not be blank")
        }
        return ConfigJson.decodeFromString<List<Skill>>(content).also { skills ->
            require(skills.size <= 64) { "技能数量不能超过 64 个。" }
            skills.forEach(::validateSkill)
        }
    }

    /**
     * 拒绝包含旧式非法技能标识的可解析主文件，避免将其作为空列表继续运行或覆盖现场。
     */
    private fun ensureNoHistoricalInvalidSkillIds() {
        val containsInvalidId = containsDecodableSkillWithInvalidId(storage.readPrimary())
        if (containsInvalidId) {
            requiresStorageValidationBeforeWrite = true
            logger.error("Skills storage contains a decodable skill with an invalid ID; preserving files and isolating storage")
            throw SkillStorageIsolationException()
        }
    }

    private fun containsDecodableSkillWithInvalidId(read: AtomicJsonRawRead): Boolean {
        val bytes = (read as? AtomicJsonRawRead.Present)?.bytes ?: return false
        val skills = runCatching {
            ConfigJson.decodeFromString<List<Skill>>(bytes.toString(StandardCharsets.UTF_8))
        }.getOrNull() ?: return false
        return skills.any { !isValidSkillId(it.id) }
    }

    private data class SkillSnapshot(
        val exists: Boolean,
        val items: List<Skill>,
    )
}

/**
 * 表示技能主文件包含可解析但不再允许的技能标识，因而已被隔离。
 *
 * 隔离期间仓储不会改写或暴露主文件中的技能数据；调用方必须由管理员修复文件后重试。
 */
internal class SkillStorageIsolationException : IllegalStateException(
    "技能存储包含非法历史标识，已隔离且拒绝读写。",
)
