package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.Skill
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SkillRepository @Inject constructor() {
    private val logger = LoggerFactory.getLogger(SkillRepository::class.java)
    private val configFile = File("config/skills.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val _skillsFlow = MutableStateFlow(loadSkills())
    val skillsFlow: StateFlow<List<Skill>> = _skillsFlow.asStateFlow()

    init {
        if (!configFile.parentFile.exists()) {
            configFile.parentFile.mkdirs()
        }
    }

    private fun loadSkills(): List<Skill> {
        return if (configFile.exists()) {
            try {
                json.decodeFromString(configFile.readText())
            } catch (e: Exception) {
                logger.error("Error loading skills.json", e)
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    fun getAllSkills(): List<Skill> = _skillsFlow.value

    fun getSkillById(id: String): Skill? = _skillsFlow.value.find { it.id == id }

    fun saveSkill(skill: Skill) {
        val currentSkills = _skillsFlow.value.toMutableList()
        val index = currentSkills.indexOfFirst { it.id == skill.id }
        if (index != -1) {
            currentSkills[index] = skill
        } else {
            currentSkills.add(skill)
        }
        updateSkills(currentSkills)
    }

    fun deleteSkill(id: String) {
        val currentSkills = _skillsFlow.value.filter { it.id != id }
        updateSkills(currentSkills)
    }

    private fun updateSkills(skills: List<Skill>) {
        try {
            configFile.writeText(json.encodeToString(skills))
            _skillsFlow.value = skills
        } catch (e: Exception) {
            logger.error("Error saving skills.json", e)
        }
    }
}
