package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.GeminiAgentService
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull

class GeminiAgentServiceTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var service: GeminiAgentService
    private val configFile = File("config/settings.json")

    @BeforeTest
    fun setup() {
        if (configFile.exists()) {
            configFile.delete()
        }
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository()
        service = GeminiAgentService(testScope, settingsRepository, MCPClientService(testScope))
    }

    @AfterTest
    fun teardown() {
        if (configFile.exists()) {
            configFile.delete()
        }
    }

    @Test
    fun testSessionReset() = runTest {
        assertNull(service.chat)

        // Set API key and enable agent
        val settings = AppSettings(
            ai = AISettings(
                geminiApiKey = "fake_key",
                agentEnabled = true
            )
        )
        settingsRepository.saveSettings(settings)

        // Wait for the flow to trigger
        var attempts = 0
        while (service.chat == null && attempts < 20) {
            Thread.sleep(100)
            attempts++
        }

        assertNotNull(service.chat, "Chat should be initialized")
        val firstChat = service.chat

        // Reset session
        service.resetSession()
        
        // Chat should be a new instance
        assertNotNull(service.chat, "Chat should still be initialized after reset")
        assertNotSame(firstChat, service.chat, "Chat instance should be different after reset")
    }
}
