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

/**
 * 设置仓储与模型切换屏障协作的测试设计。
 */
class SettingsRepositoryBarrierTest {
    private val tempDirectory = createTempDirectory("settings-barrier-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证会开启模型切换屏障的设置范围。
     *
     * 验证仅影响代理生命周期的设置变更会创建屏障代次。
     */
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

    /**
     * 验证无关保存对待处理屏障代次的传递设计。
     *
     * 验证最新设置快照会携带仍待处理的最高代次。
     */
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

    /**
     * 验证合并设置快照释放屏障代次的设计。
     *
     * 验证最新生命周期快照会释放被合并的较早代次。
     */
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

    /**
     * 验证设置写入失败时的屏障回滚设计。
     *
     * 验证失败写入创建的屏障代次会被取消。
     */
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
