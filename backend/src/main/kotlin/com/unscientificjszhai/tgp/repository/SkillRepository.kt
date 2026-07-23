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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillRepository private constructor(
    private val configFile: File,
) {
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
    val skillsUpdateEvent: SharedFlow<Unit> = _skillsUpdateEvent.asSharedFlow()

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

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
