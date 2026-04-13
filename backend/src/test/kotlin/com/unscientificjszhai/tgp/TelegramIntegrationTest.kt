package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.TelegramService
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TelegramIntegrationTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var updatesRepository: UpdatesRepository
    private lateinit var telegramService: TelegramService
    
    private val settingsFile = File("config/settings.json")
    private val updatesFile = File("config/updates.json")

    @BeforeTest
    fun setup() {
        // Ensure clean state
        settingsFile.delete()
        updatesFile.delete()
        
        settingsRepository = SettingsRepository()
        updatesRepository = UpdatesRepository()
        
        // Inject test config into repository
        if (TestConfig.isTelegramConfigured()) {
            val settings = AppSettings(
                telegramToken = TestConfig.telegramToken!!,
                chatId = TestConfig.chatId!!
            )
            settingsRepository.saveSettings(settings)
        }
        
        telegramService = TelegramService(settingsRepository, updatesRepository)
    }

    @AfterTest
    fun teardown() {
        settingsFile.delete()
        updatesFile.delete()
    }

    @Test
    fun `test sending real telegram message`() = runTest {
        assumeTrue(TestConfig.isTelegramConfigured(), "Telegram configuration missing, skipping integration test.")
        
        val response = telegramService.sendMessage(
            chatId = TestConfig.chatId!!,
            text = "Integration test message from TelegramWebHookProxy"
        )
        
        assertEquals(HttpStatusCode.OK, response.status, "Failed to send Telegram message: ${response.bodyAsText()}")
    }
}
