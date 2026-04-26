package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest

class GeminiAgentServiceTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRepository: com.unscientificjszhai.tgp.repository.SkillRepository
    private lateinit var service: GeminiAgentService
    private val configFile = File("config/settings.json")
    private val skillFile = File("config/skills.json")

    @BeforeTest
    fun setup() {
        if (configFile.exists()) {
            configFile.delete()
        }
        if (skillFile.exists()) {
            skillFile.delete()
        }
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository()
        skillRepository = com.unscientificjszhai.tgp.repository.SkillRepository()
        service =
            GeminiAgentService(testScope, settingsRepository, skillRepository, MCPClientService(testScope)) { mockk() }
    }

    @AfterTest
    fun teardown() {
        if (configFile.exists()) {
            configFile.delete()
        }
        if (skillFile.exists()) {
            skillFile.delete()
        }
    }
}
