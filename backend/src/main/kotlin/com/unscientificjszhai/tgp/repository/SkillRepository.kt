package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillBrief
import com.unscientificjszhai.tgp.models.SkillStatus
import com.unscientificjszhai.tgp.models.isValidSkillId
import com.unscientificjszhai.tgp.models.validateSkill
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.AtomicJsonRawRead
import com.unscientificjszhai.tgp.utils.AtomicJsonRead
import com.unscientificjszhai.tgp.utils.AtomicJsonStorage
import com.unscientificjszhai.tgp.utils.ConfigJson
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ResourceLimits
import com.unscientificjszhai.tgp.utils.SafeLogging
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
 * 持久化技能草稿及审批状态，并向 Agent 提供仅含已批准技能的读取接口。
 *
 * 模型只能通过 [createPendingDraft] 创建待审批草稿；保存、批准和撤销都在成功原子提交后才改变
 * 对 Agent 可见的集合并发布 [skillsUpdateEvent]。
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
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 已批准技能集合成功变更时发布的无载荷事件流。
     *
     * 事件不重放；创建或编辑待审批草稿不会发布事件，消费者应在收到事件后重新读取所需数据。
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
     * 分页读取管理端可见的全部技能，包括待审批草稿。
     *
     * 配置文件不存在时返回总数为 `0` 的空页；主文件无法解析时返回空页而不改写现场。主文件含历史
     * 非法技能标识时，仓储会隔离该存储。
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
     * 读取管理端可见的全部技能摘要，包括待审批草稿。
     *
     * @return 技能摘要列表，顺序与配置文件一致；没有可读取的技能时为空列表。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getSkillSummaries(): List<SkillBrief> = storageLock.withLock {
        readSkillsForRead().map { SkillBrief(it.id, it.description) }
    }

    /**
     * 读取可安全提供给模型的已批准技能摘要。
     *
     * @return 仅包含 [SkillStatus.APPROVED] 技能的摘要列表，顺序与配置文件一致；没有已批准技能时为空列表。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getApprovedSkillSummaries(): List<SkillBrief> = storageLock.withLock {
        readSkillsForRead()
            .asSequence()
            .filter { it.status == SkillStatus.APPROVED }
            .map { SkillBrief(it.id, it.description) }
            .toList()
    }

    /**
     * 按标识读取管理端可见的单个技能，包括待审批草稿。
     *
     * @param id 要查询的技能标识，必须匹配 [com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN]。
     * @return 匹配的技能；未找到、文件不可读取或主文件无法解析时为 `null`。
     * @throws IllegalArgumentException [id] 不匹配技能标识格式时抛出。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getSkillById(id: String): Skill? {
        require(isValidSkillId(id)) { "技能标识不合法。" }
        return storageLock.withLock { readSkillsForRead().find { it.id == id } }
    }

    /**
     * 按标识读取可提供给模型的已批准技能。
     *
     * @param id 要查询的技能标识，必须匹配 [com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN]。
     * @return 匹配且处于 [SkillStatus.APPROVED] 的技能；未找到、未批准或文件不可读取时为 `null`。
     * @throws IllegalArgumentException [id] 不匹配技能标识格式时抛出。
     * @throws SkillStorageIsolationException 主文件存在可解析但包含非法技能标识的历史数据时抛出。
     */
    fun getApprovedSkillById(id: String): Skill? {
        require(isValidSkillId(id)) { "技能标识不合法。" }
        return storageLock.withLock {
            readSkillsForRead().find { it.id == id && it.status == SkillStatus.APPROVED }
        }
    }

    /**
     * 以管理端内容新增或编辑技能，并始终使其回到待审批状态。
     *
     * 新建和编辑都会将技能置为 [SkillStatus.PENDING]；编辑已批准技能会撤销其进入模型上下文的资格。
     * [skill] 的 `status` 不会被信任，已有技能的版本必须与 [skill] 的 `revision` 相等。
     *
     * @param skill 管理端提交的完整技能；其标识、描述、内容和版本号必须符合资源限制。
     * @return 已持久化的待审批技能；已有技能的版本号会递增。
     * @throws IllegalArgumentException [skill] 的字段不符合格式或大小限制，或保存后技能总数超过 `64` 时抛出。
     * @throws SkillRevisionConflictException [skill] 指向已有技能但版本号已过期时抛出。
     * @throws IllegalStateException 配置文件已损坏、含历史非法技能标识或暂不可读取时抛出；不会发布变更事件。
     */
    fun saveSkill(skill: Skill): Skill = storageLock.withLock {
        saveSkillInternal(
            id = skill.id,
            description = skill.description,
            content = skill.content,
            expectedRevision = skill.revision,
            allowRequestedIdCreation = true,
        )
    }

    /**
     * 以管理端提供的字段新增或编辑待审批技能。
     *
     * [id] 为 `null` 时由服务器生成新标识；非空 [id] 只允许编辑已经存在的技能，并且必须提供匹配的
     * [expectedRevision]。编辑结果始终是 [SkillStatus.PENDING]，以要求管理员再次明确批准。
     *
     * @param id 要编辑的技能标识；`null` 表示由服务器生成新标识，非空时必须匹配技能标识格式且已经存在。
     * @param description 技能描述，不得超过 `1024` UTF-8 字节。
     * @param content 技能内容，不得超过 `64 KiB` UTF-8 字节。
     * @param expectedRevision 编辑既有技能时必须等于当前版本；创建新技能时必须省略。
     * @return 成功持久化的待审批技能。
     * @throws IllegalArgumentException 参数不符合格式、大小或创建/编辑的版本参数约束时抛出。
     * @throws SkillNotFoundException [id] 非空但对应技能已不存在时抛出。
     * @throws SkillRevisionConflictException [expectedRevision] 与当前版本不一致时抛出。
     * @throws IllegalStateException 配置文件已损坏、含历史非法技能标识或暂不可读取时抛出；不会发布变更事件。
     */
    fun saveManagedSkill(
        id: String?,
        description: String,
        content: String,
        expectedRevision: Long?,
    ): Skill = storageLock.withLock {
        saveSkillInternal(id, description, content, expectedRevision, allowRequestedIdCreation = false)
    }

    private fun saveSkillInternal(
        id: String?,
        description: String,
        content: String,
        expectedRevision: Long?,
        allowRequestedIdCreation: Boolean,
    ): Skill {
        val skills = readSkillsForMutation().items.toMutableList()
        val existingIndex = id?.let { requestedId ->
            require(isValidSkillId(requestedId)) { "技能标识不合法。" }
            skills.indexOfFirst { it.id == requestedId }
        } ?: -1
        val savedSkill = if (id == null) {
            require(expectedRevision == null) { "创建技能时不能指定版本号。" }
            Skill(description = description, content = content, status = SkillStatus.PENDING)
        } else if (existingIndex < 0) {
            if (!allowRequestedIdCreation) {
                throw SkillNotFoundException(id)
            }
            require(expectedRevision == 0L) { "创建指定标识的技能时版本号必须为 0。" }
            Skill(id = id, description = description, content = content, status = SkillStatus.PENDING)
        } else {
            val existing = skills[existingIndex]
            if (expectedRevision != existing.revision) {
                throw SkillRevisionConflictException(id)
            }
            Skill(
                id = id,
                description = description,
                content = content,
                status = SkillStatus.PENDING,
                revision = existing.revision + 1,
            )
        }
        validateSkill(savedSkill)
        val approvedSetChanged = existingIndex >= 0 && skills[existingIndex].status == SkillStatus.APPROVED
        if (existingIndex >= 0) {
            skills[existingIndex] = savedSkill
        } else {
            skills += savedSkill
        }
        require(skills.size <= 64) { "技能数量不能超过 64 个。" }
        storage.commit(ConfigJson.encodeToString(skills).toByteArray(StandardCharsets.UTF_8))
        if (approvedSetChanged) {
            _skillsUpdateEvent.tryEmit(Unit)
        }
        return savedSkill
    }

    /**
     * 创建仅供管理员审核的模型技能草稿。
     *
     * 标识由服务器生成，模型无法通过此方法指定或覆盖既有技能；新草稿不会进入模型上下文，也不会发布
     * [skillsUpdateEvent]。
     *
     * @param description 模型提出的技能描述，不得超过 `1024` UTF-8 字节。
     * @param content 模型提出的技能内容，不得超过 `64 KiB` UTF-8 字节。
     * @return 已持久化、状态为 [SkillStatus.PENDING] 的新草稿。
     * @throws IllegalArgumentException 描述、内容或总技能数量超过限制时抛出。
     * @throws IllegalStateException 配置文件已损坏、含历史非法技能标识或暂不可读取时抛出。
     */
    fun createPendingDraft(description: String, content: String): Skill =
        saveManagedSkill(id = null, description = description, content = content, expectedRevision = null)

    /**
     * 在版本匹配时批准待审批技能，使其可以进入模型上下文。
     *
     * @param id 要批准的技能标识，必须匹配技能标识格式。
     * @param expectedRevision 管理端查看到的当前版本号，必须等于持久化版本。
     * @return 已批准且版本号递增的技能。
     * @throws IllegalArgumentException [id] 或 [expectedRevision] 不合法时抛出。
     * @throws SkillNotFoundException 技能不存在时抛出。
     * @throws SkillRevisionConflictException 版本已过期时抛出。
     * @throws SkillStateConflictException 技能并非待审批状态时抛出。
     */
    fun approveSkill(id: String, expectedRevision: Long): Skill =
        transitionSkillStatus(id, expectedRevision, SkillStatus.PENDING, SkillStatus.APPROVED)

    /**
     * 在版本匹配时撤销已批准技能，使其立即退出模型上下文。
     *
     * @param id 要撤销的技能标识，必须匹配技能标识格式。
     * @param expectedRevision 管理端查看到的当前版本号，必须等于持久化版本。
     * @return 已撤销并回到 [SkillStatus.PENDING] 的技能。
     * @throws IllegalArgumentException [id] 或 [expectedRevision] 不合法时抛出。
     * @throws SkillNotFoundException 技能不存在时抛出。
     * @throws SkillRevisionConflictException 版本已过期时抛出。
     * @throws SkillStateConflictException 技能并非已批准状态时抛出。
     */
    fun revokeSkill(id: String, expectedRevision: Long): Skill =
        transitionSkillStatus(id, expectedRevision, SkillStatus.APPROVED, SkillStatus.PENDING)

    /**
     * 删除指定标识的技能。
     *
     * 配置文件不存在或为空时不执行任何操作；删除已批准技能成功后会发布一次 [skillsUpdateEvent]。
     *
     * @param id 要删除的技能标识，必须匹配 [com.unscientificjszhai.tgp.models.SKILL_ID_PATTERN]。
     * @throws IllegalArgumentException [id] 不匹配技能标识格式时抛出。
     * @throws IllegalStateException 配置文件已损坏、含历史非法技能标识或暂不可读取时抛出；不会发布变更事件。
     */
    fun deleteSkill(id: String) {
        require(isValidSkillId(id)) { "技能标识不合法。" }
        storageLock.withLock {
            val snapshot = readSkillsForMutation()
            if (!snapshot.exists) {
                return
            }
            val removed = snapshot.items.find { it.id == id }
            storage.commit(
                ConfigJson.encodeToString(snapshot.items.filterNot { it.id == id }).toByteArray(StandardCharsets.UTF_8)
            )
            if (removed?.status == SkillStatus.APPROVED) {
                _skillsUpdateEvent.tryEmit(Unit)
            }
        }
    }

    private fun transitionSkillStatus(
        id: String,
        expectedRevision: Long,
        from: SkillStatus,
        to: SkillStatus,
    ): Skill {
        require(isValidSkillId(id)) { "技能标识不合法。" }
        require(expectedRevision >= 0) { "技能版本号不能为负数。" }
        return storageLock.withLock {
            val skills = readSkillsForMutation().items.toMutableList()
            val index = skills.indexOfFirst { it.id == id }
            if (index < 0) {
                throw SkillNotFoundException(id)
            }
            val existing = skills[index]
            if (existing.revision != expectedRevision) {
                throw SkillRevisionConflictException(id)
            }
            if (existing.status != from) {
                throw SkillStateConflictException(id, existing.status)
            }
            val transitioned = existing.copy(status = to, revision = existing.revision + 1)
            skills[index] = transitioned
            storage.commit(ConfigJson.encodeToString(skills).toByteArray(StandardCharsets.UTF_8))
            _skillsUpdateEvent.tryEmit(Unit)
            transitioned
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
                logger.error(
                    "Skills file is semantically invalid; preserving it; category={}",
                    SafeLogging.failureCategory(read.cause).wireName,
                )
                emptyList()
            }

            is AtomicJsonRead.IoFailure -> {
                requiresStorageValidationBeforeWrite = true
                logger.error(
                    "Unable to read skills data; delaying writes until it can be revalidated; category={}",
                    SafeLogging.failureCategory(read.cause).wireName,
                )
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

/** 表示技能主文件包含可解析但不再允许的技能标识，因而已被隔离。 */
internal class SkillStorageIsolationException : IllegalStateException(
    "技能存储包含非法历史标识，已隔离且拒绝读写。",
)

/** 表示请求的技能不存在。 */
internal class SkillNotFoundException(id: String) : IllegalStateException("技能 $id 不存在。")

/** 表示管理端使用了过期的技能版本。 */
internal class SkillRevisionConflictException(id: String) : IllegalStateException("技能 $id 已被更新。")

/** 表示技能不处于所请求的状态转换起点。 */
internal class SkillStateConflictException(id: String, status: SkillStatus) : IllegalStateException(
    "技能 $id 当前状态为 $status。",
)
