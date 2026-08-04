package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.ChatInfo
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Telegram 聊天查询与删除的 bot 隔离测试设计。
 */
class TelegramServiceChatsTest {
    private val tempDirectory = createTempDirectory("telegram-chats-test").toFile()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证聊天 API 在请求开始时按当前 bot 查询、删除，空 token 不访问之前机器人的状态。
     */
    @Test
    fun `saved chats follow current bot and ignore empty token`() {
        val settings = SettingsRepository.forTesting(tempDirectory.resolve("settings.json"), ModelSwitchBarrier())
        val updates = UpdatesRepository(tempDirectory.resolve("updates.json"))
        updates.mergeChats("100", listOf(ChatInfo("a", "A", "private")))
        updates.mergeChats("200", listOf(ChatInfo("b", "B", "private")))
        val service = TelegramService(scope, settings, updates)

        settings.saveSettings(AppSettings(telegramToken = "100:first"))
        assertEquals(listOf(ChatInfo("a", "A", "private")), service.getSavedChats())
        service.deleteChat("a")
        assertTrue(service.getSavedChats().isEmpty())

        settings.saveSettings(AppSettings(telegramToken = "200:second"))
        assertEquals(listOf(ChatInfo("b", "B", "private")), service.getSavedChats())

        settings.saveSettings(AppSettings(telegramToken = ""))
        assertTrue(service.getSavedChats().isEmpty())
        service.deleteChat("b")

        settings.saveSettings(AppSettings(telegramToken = "200:   "))
        assertTrue(service.getSavedChats().isEmpty())
        service.deleteChat("b")

        settings.saveSettings(AppSettings(telegramToken = "200:rotated"))
        assertEquals(listOf(ChatInfo("b", "B", "private")), service.getSavedChats())
    }
}
