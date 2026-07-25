package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import java.io.File
import java.io.FileNotFoundException
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsRepositoryBarrierTest {
    private val tempDirectory = createTempDirectory("settings-barrier-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `only agent lifecycle settings open a model switch barrier`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        var settings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "openai-key",
                agentEnabled = true,
                selectedModel = "gpt-first",
            ),
        )
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        val initialGeneration = barrier.latestPendingGeneration()
        barrier.complete(initialGeneration)

        settings = settings.copy(ai = settings.ai!!.copy(globalContext = "new context"))
        repository.saveSettings(settings)
        assertFalse(barrier.isSwitching)

        settings = settings.copy(ai = settings.ai!!.copy(selectedModel = "gpt-next"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(geminiApiKey = "unused-key"))
        repository.saveSettings(settings)
        assertFalse(barrier.isSwitching)

        settings = settings.copy(ai = settings.ai!!.copy(openAiBaseUrl = "https://example.invalid/v1"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(agentEnabled = false))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
    }

    @Test
    fun `unrelated save carries an open generation to its latest settings snapshot`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "key", agentEnabled = true),
        )
        repository.saveSettings(initialSettings)
        val firstUpdate = repository.settingsUpdateFlow.value
        val generation = firstUpdate.switchGeneration

        val latestSettings = initialSettings.copy(chatId = "new-chat-id")
        repository.saveSettings(latestSettings)
        val latestUpdate = repository.settingsUpdateFlow.value

        assertEquals(latestSettings, latestUpdate.settings)
        assertEquals(generation, latestUpdate.switchGeneration)
    }

    @Test
    fun `latest lifecycle snapshot releases conflated earlier generations`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        val firstSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.GEMINI,
                geminiApiKey = "key",
                agentEnabled = true,
            ),
        )

        repository.saveSettings(firstSettings)
        val firstGeneration = repository.settingsUpdateFlow.value.switchGeneration

        val latestSettings = firstSettings.copy(
            ai = firstSettings.ai!!.copy(selectedModel = "gemini-next"),
        )
        repository.saveSettings(latestSettings)
        val latestUpdate = repository.settingsUpdateFlow.value

        assertEquals(latestSettings, latestUpdate.settings)
        assertTrue(latestUpdate.switchGeneration!! > firstGeneration!!)

        // 模拟首次更新被 StateFlow 合并后，生命周期收集器只能观察到
        // 最新快照的情况。
        barrier.completeThrough(latestUpdate.switchGeneration)

        assertFalse(barrier.isSwitching)
    }

    @Test
    fun `a failed settings write cancels its switch generation`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "settings.json")
        val repository = SettingsRepository.forTesting(configFile, barrier)

        tempDirectory.deleteRecursively()
        tempDirectory.writeText("not a directory")

        assertFailsWith<FileNotFoundException> {
            repository.saveSettings(AppSettings(ai = AISettings(agentEnabled = true)))
        }
        assertFalse(barrier.isSwitching)
    }
}
