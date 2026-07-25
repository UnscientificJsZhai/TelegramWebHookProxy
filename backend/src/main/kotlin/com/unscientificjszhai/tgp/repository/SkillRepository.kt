package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillBrief
import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeToSequence
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
/**
 * 持久化技能定义，并提供分页读取与变更通知。
 *
 * 技能存储在 JSON 配置文件中；保存和删除会以替换文件的方式提交更新，并在成功后发布事件。
 */
class SkillRepository private constructor(
    private val configFile: File,
) {
    /**
     * 创建使用默认技能配置文件的仓储。
     *
     * @constructor 创建使用 `config/skills.json` 的仓储；该目录不存在时会创建。
     */
    @Inject
    constructor() : this(File("config/skills.json"))

    companion object {
        internal fun forTesting(configFile: File): SkillRepository = SkillRepository(configFile)
    }

    private val logger = LoggerFactory.getLogger(SkillRepository::class.java)

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
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    /**
     * 分页读取所有技能。
     *
     * 配置文件不存在、为空或无法解析时返回总数为 `0` 的空页。
     *
     * @param page 从 `1` 开始的页码；调用方应传入正整数。
     * @param size 每页最大技能数；调用方应传入正整数。
     * @return 包含总技能数和当前页技能的结果；技能顺序与配置文件一致。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun getAllSkills(page: Int = 1, size: Int = 10): PageResult<Skill> {
        if (!configFile.exists() || configFile.length() == 0L) {
            return PageResult(0, emptyList())
        }
        return try {
            configFile.inputStream().use { inputStream ->
                var total = 0
                val items = mutableListOf<Skill>()
                val startIndex = (page - 1) * size
                val endIndex = startIndex + size

                ConfigJson.decodeToSequence<Skill>(inputStream).forEachIndexed { index, skill ->
                    total++
                    if (index in startIndex..<endIndex) {
                        items.add(skill)
                    }
                }
                PageResult(total, items)
            }
        } catch (e: Exception) {
            logger.error("Error loading skills.json for getAllSkills", e)
            PageResult(0, emptyList())
        }
    }

    /**
     * 读取所有技能的摘要信息。
     *
     * 配置文件不存在、为空或无法解析时返回空列表。
     *
     * @return 技能摘要列表，顺序与配置文件一致；没有可读取的技能时为空列表。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun getSkillSummaries(): List<SkillBrief> {
        if (!configFile.exists() || configFile.length() == 0L) {
            return emptyList()
        }
        return try {
            configFile.inputStream().use { inputStream ->
                ConfigJson.decodeToSequence<SkillBrief>(inputStream).toList()
            }
        } catch (e: Exception) {
            logger.error("Error loading skills.json for getSkillSummaries", e)
            emptyList()
        }
    }

    /**
     * 按标识读取单个技能。
     *
     * @param id 要查询的技能标识，不能为空；按完全相等的字符串匹配。
     * @return 匹配的技能；未找到或配置文件无法读取时为 `null`。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun getSkillById(id: String): Skill? {
        return try {
            configFile.inputStream().use { inputStream ->
                ConfigJson.decodeToSequence<Skill>(inputStream).find { it.id == id }
            }
        } catch (e: Exception) {
            logger.error("Error loading skills.json while finding skill id $id", e)
            return null
        }
    }

    /**
     * 新增或覆盖保存技能。
     *
     * 标识相同的已有技能会被 [skill] 完全替换；成功后会发布一次 [skillsUpdateEvent]。
     *
     * @param skill 要持久化的完整技能，不能为空。
     * @throws Exception 配置文件无法读取、编码、写入或替换时抛出。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun saveSkill(skill: Skill) {
        if (!configFile.exists() || configFile.length() == 0L) {
            configFile.parentFile.mkdirs()
            configFile.writeText("[\n${ConfigJson.encodeToString(skill)}\n]")
            _skillsUpdateEvent.tryEmit(Unit)
            return
        }
        val tmpFile = File.createTempFile("tmp_skill", ".json")
        try {
            configFile.inputStream().use { inputStream ->
                tmpFile.outputStream().bufferedWriter().use { writer ->
                    val elements = ConfigJson.decodeToSequence<Skill>(inputStream)

                    writer.write("[\n")
                    var isFirst = true
                    var alreadyUpdated = false

                    elements.forEach { element ->
                        if (!isFirst) writer.write(",\n")

                        if (element.id == skill.id) {
                            writer.write(ConfigJson.encodeToString(skill))
                            alreadyUpdated = true
                        } else {
                            writer.write(ConfigJson.encodeToString(element))
                        }
                        isFirst = false
                    }

                    if (!alreadyUpdated) {
                        if (!isFirst) writer.write(",\n")
                        writer.write(ConfigJson.encodeToString(skill))
                    }

                    writer.write("\n]")
                }
            }

            Files.move(
                tmpFile.toPath(),
                configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            _skillsUpdateEvent.tryEmit(Unit)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }

    /**
     * 删除指定标识的技能。
     *
     * 配置文件不存在或为空时不执行任何操作；成功完成文件更新后会发布一次 [skillsUpdateEvent]，
     * 即使未找到匹配技能也是如此。
     *
     * @param id 要删除的技能标识，不能为空；按完全相等的字符串匹配。
     * @throws Exception 配置文件无法读取、编码、写入或替换时抛出。
     */
    @OptIn(ExperimentalSerializationApi::class)
    fun deleteSkill(id: String) {
        if (!configFile.exists() || configFile.length() == 0L) {
            return
        }
        val tmpFile = File.createTempFile("tmp_skill", ".json")
        try {
            configFile.inputStream().use { inputStream ->
                tmpFile.outputStream().bufferedWriter().use { writer ->
                    val elements = ConfigJson.decodeToSequence<Skill>(inputStream)
                    writer.write("[\n")
                    var isFirst = true
                    elements.forEach { element ->
                        if (element.id != id) {
                            if (!isFirst) writer.write(",\n")
                            writer.write(ConfigJson.encodeToString(element))
                            isFirst = false
                        }
                    }
                    writer.write("\n]")
                }
            }

            Files.move(
                tmpFile.toPath(),
                configFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
            _skillsUpdateEvent.tryEmit(Unit)
        } catch (e: Exception) {
            tmpFile.delete()
            throw e
        }
    }
}
