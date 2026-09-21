package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 以虚拟调度器和明确的在途信号验证远端写入顺序，避免固定休眠判断后台收敛。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BotCommandReconcilerTest {
    @Test
    fun `A B A converges after stale B succeeds`() = assertReturnsToOriginal(failAfterWrite = false)

    @Test
    fun `A B A reconfirms remote state after ambiguous B failure`() = assertReturnsToOriginal(failAfterWrite = true)

    private fun assertReturnsToOriginal(failAfterWrite: Boolean) = runTest {
        val initial = enabledSettings("100:test")
        val updates = MutableStateFlow(SettingsUpdate(initial, 0, null))
        val coordinator = mockk<SettingsChangeCoordinator> {
            every { settingsUpdateFlow } returns updates
        }
        val telegram = mockk<TelegramService>()
        val calls = mutableListOf<AIProvider?>()
        var remoteProvider: AIProvider? = null
        val deleting = CompletableDeferred<Unit>()
        val finishDelete = CompletableDeferred<Unit>()
        coEvery { telegram.updateBotCommands("100:test", any()) } coAnswers {
            val provider = secondArg<AIProvider?>()
            calls += provider
            if (provider == null) {
                deleting.complete(Unit)
                finishDelete.await()
            }
            remoteProvider = provider
            if (provider == null && failAfterWrite) throw IOException("response lost after remote write")
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true,"result":true}""")
        }
        val reconciler = BotCommandReconciler(
            this, coordinator, telegram, { awaitCancellation() }, StandardTestDispatcher(testScheduler),
        )
        try {
            reconciler.start()
            runCurrent()
            assertEquals(listOf<AIProvider?>(AIProvider.GEMINI), calls)
            updates.value = SettingsUpdate(initial.copy(ai = initial.ai!!.copy(agentEnabled = false)), 1, null)
            runCurrent()
            assertTrue(deleting.isCompleted)
            updates.value = SettingsUpdate(initial, 2, null)
            runCurrent()
            finishDelete.complete(Unit)
            runCurrent()
            assertEquals(listOf(AIProvider.GEMINI, null, AIProvider.GEMINI), calls)
            assertEquals(AIProvider.GEMINI, remoteProvider)

            // 与命令无关的设置变更不得产生重复远程写入。
            updates.value = SettingsUpdate(initial.copy(chatId = "another-chat"), 3, null)
            runCurrent()
            assertEquals(3, calls.size)
        } finally {
            reconciler.closeAndJoin()
        }
    }

    @Test
    fun `token rotation waits for in flight command then converges the new bot`() = runTest {
        val initial = enabledSettings("100:old")
        val updates = MutableStateFlow(SettingsUpdate(initial, 0, null))
        val coordinator = mockk<SettingsChangeCoordinator> {
            every { settingsUpdateFlow } returns updates
        }
        val telegram = mockk<TelegramService>()
        val finishOld = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        coEvery { telegram.updateBotCommands(any(), any()) } coAnswers {
            val token = firstArg<String>()
            calls += token
            if (token == "100:old") finishOld.await()
            TelegramApiResponse(HttpStatusCode.OK, """{"ok":true,"result":true}""")
        }
        val reconciler = BotCommandReconciler(
            this, coordinator, telegram, { awaitCancellation() }, StandardTestDispatcher(testScheduler),
        )
        try {
            reconciler.start()
            runCurrent()
            updates.value = SettingsUpdate(enabledSettings("200:new"), 1, null)
            runCurrent()
            assertEquals(listOf("100:old"), calls)
            finishOld.complete(Unit)
            runCurrent()
            assertEquals(listOf("100:old", "200:new"), calls)
        } finally {
            reconciler.closeAndJoin()
        }
    }

    private fun enabledSettings(token: String) = AppSettings(
        telegramToken = token,
        ai = AISettings(agentEnabled = true, provider = AIProvider.GEMINI),
    )
}
