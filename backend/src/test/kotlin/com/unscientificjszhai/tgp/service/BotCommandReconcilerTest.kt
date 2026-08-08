package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.http.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.*
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Telegram Bot 命令协调器的串行收敛与停止边界测试设计。 */
class BotCommandReconcilerTest {
    private val temporaryDirectory = createTempDirectory("bot-command-reconciler-test").toFile()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @AfterTest
    fun cleanUp() {
        scope.cancel()
        temporaryDirectory.deleteRecursively()
    }

    /** 验证在途 A 完成后仍会按顺序写入更新期间到达的 B。 */
    @Test
    fun `a blocked command update is followed by the latest target`() = runBlocking {
        val repository = newRepository("a-then-b.json")
        repository.updateSettings { settings("100:token-a", AIProvider.GEMINI) }
        val telegramService = mockk<TelegramService>()
        val aStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        coEvery { telegramService.updateBotCommands("100:token-a", AIProvider.GEMINI) } coAnswers {
            synchronized(calls) { calls += "A" }
            aStarted.complete(Unit)
            releaseA.await()
            successfulResponse()
        }
        coEvery { telegramService.updateBotCommands("200:token-b", AIProvider.OPENAI) } coAnswers {
            synchronized(calls) { calls += "B" }
            successfulResponse()
        }
        val reconciler = BotCommandReconciler(scope, repository, telegramService) { }

        try {
            reconciler.start()
            withTimeout(5.seconds) { aStarted.await() }
            repository.updateSettings { settings("200:token-b", AIProvider.OPENAI) }
            releaseA.complete(Unit)

            eventually { assertEquals(listOf("A", "B"), synchronized(calls) { calls.toList() }) }
        } finally {
            releaseA.complete(Unit)
            reconciler.closeAndJoin()
        }
    }

    /** 验证失败会以受控退避重试同一最新目标，成功后不会再重复写入无关设置更新。 */
    @Test
    fun `latest command failure retries and unrelated updates do not duplicate a converged command`() = runBlocking {
        val repository = newRepository("retry-and-deduplicate.json")
        repository.updateSettings { settings("100:token", AIProvider.GEMINI) }
        val telegramService = mockk<TelegramService>()
        val retryStarted = CompletableDeferred<Duration>()
        val allowRetry = CompletableDeferred<Unit>()
        var attempts = 0
        coEvery { telegramService.updateBotCommands("100:token", AIProvider.GEMINI) } coAnswers {
            attempts++
            if (attempts == 1) {
                throw IllegalStateException("temporary Telegram rejection")
            }
            successfulResponse()
        }
        val reconciler = BotCommandReconciler(scope, repository, telegramService) { backoff ->
            retryStarted.complete(backoff)
            allowRetry.await()
        }

        try {
            reconciler.start()
            assertEquals(1.seconds, withTimeout(5.seconds) { retryStarted.await() })
            allowRetry.complete(Unit)
            eventually { assertEquals(2, attempts) }

            repository.updateSettings { current -> current.copy(chatId = "unrelated-chat") }
            kotlinx.coroutines.delay(100.milliseconds)
            assertEquals(2, attempts)
        } finally {
            allowRetry.complete(Unit)
            reconciler.closeAndJoin()
        }
    }

    /** 验证新版本会立即替代仍在退避的失败目标，而不会重试旧 A。 */
    @Test
    fun `a newer target replaces an old failure during backoff`() = runBlocking {
        val repository = newRepository("newer-replaces-failure.json")
        repository.updateSettings { settings("100:token-a", AIProvider.GEMINI) }
        val telegramService = mockk<TelegramService>()
        val retryStarted = CompletableDeferred<Unit>()
        var aAttempts = 0
        val calls = mutableListOf<String>()
        coEvery { telegramService.updateBotCommands("100:token-a", AIProvider.GEMINI) } coAnswers {
            aAttempts++
            synchronized(calls) { calls += "A" }
            throw IllegalStateException("A rejected")
        }
        coEvery { telegramService.updateBotCommands("200:token-b", AIProvider.OPENAI) } coAnswers {
            synchronized(calls) { calls += "B" }
            successfulResponse()
        }
        val reconciler = BotCommandReconciler(scope, repository, telegramService) {
            retryStarted.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        try {
            reconciler.start()
            withTimeout(5.seconds) { retryStarted.await() }
            repository.updateSettings { settings("200:token-b", AIProvider.OPENAI) }

            eventually { assertEquals(listOf("A", "B"), synchronized(calls) { calls.toList() }) }
            assertEquals(1, aAttempts)
        } finally {
            reconciler.closeAndJoin()
        }
    }

    /** 验证启动追赶当前设置、空 token 不发请求，且无有效提供方时删除命令。 */
    @Test
    fun `startup sync skips empty token and deletes commands when no provider is effective`() = runBlocking {
        val telegramService = mockk<TelegramService>()
        coEvery { telegramService.updateBotCommands(any(), any()) } returns successfulResponse()

        val enabledRepository = newRepository("startup-enabled.json")
        enabledRepository.updateSettings { settings("100:enabled", AIProvider.OPENAI) }
        val enabled = BotCommandReconciler(scope, enabledRepository, telegramService) { }
        val emptyRepository = newRepository("startup-empty.json")
        val empty = BotCommandReconciler(scope, emptyRepository, telegramService) { }
        val disabledRepository = newRepository("startup-delete.json")
        disabledRepository.updateSettings {
            AppSettings(
                telegramToken = "200:delete",
                ai = AISettings(agentEnabled = false)
            )
        }
        val disabled = BotCommandReconciler(scope, disabledRepository, telegramService) { }

        try {
            enabled.start()
            empty.start()
            disabled.start()
            eventually {
                coVerify(exactly = 1) { telegramService.updateBotCommands("100:enabled", AIProvider.OPENAI) }
                coVerify(exactly = 1) { telegramService.updateBotCommands("200:delete", null) }
            }
            coVerify(exactly = 0) { telegramService.updateBotCommands("", any()) }
        } finally {
            enabled.closeAndJoin()
            empty.closeAndJoin()
            disabled.closeAndJoin()
        }
    }

    /** 验证停止会取消仍在退避的重试，并形成可等待的 worker 终态。 */
    @Test
    fun `stop cancels retry backoff and waits for terminal worker completion`() = runBlocking {
        val repository = newRepository("stop-cancels-backoff.json")
        repository.updateSettings { settings("100:token", AIProvider.GEMINI) }
        val telegramService = mockk<TelegramService>()
        val retryStarted = CompletableDeferred<Unit>()
        val retryCancelled = CompletableDeferred<Unit>()
        coEvery {
            telegramService.updateBotCommands(
                "100:token",
                AIProvider.GEMINI
            )
        } throws IllegalStateException("rejected")
        val reconciler = BotCommandReconciler(scope, repository, telegramService) {
            retryStarted.complete(Unit)
            try {
                CompletableDeferred<Unit>().await()
            } finally {
                retryCancelled.complete(Unit)
            }
        }

        reconciler.start()
        withTimeout(5.seconds) { retryStarted.await() }
        reconciler.requestStop()
        withTimeout(5.seconds) {
            reconciler.awaitStopped()
            retryCancelled.await()
        }
        assertTrue(retryCancelled.isCompleted)
        coVerify(exactly = 1) { telegramService.updateBotCommands("100:token", AIProvider.GEMINI) }
    }

    private fun newRepository(name: String): SettingsRepository = SettingsRepository.forTesting(
        temporaryDirectory.resolve(name),
        ModelSwitchBarrier(),
    )

    private fun settings(token: String, provider: AIProvider): AppSettings = AppSettings(
        telegramToken = token,
        ai = when (provider) {
            AIProvider.GEMINI -> AISettings(
                provider = provider,
                agentEnabled = true,
                geminiApiKey = "gemini-key",
            )

            AIProvider.OPENAI -> AISettings(
                provider = provider,
                agentEnabled = true,
                openAiApiKey = "openai-key",
            )
        },
    )

    private fun successfulResponse(): TelegramApiResponse = TelegramApiResponse(
        HttpStatusCode.OK,
        """{\"ok\":true}""",
    )

    private suspend fun eventually(assertion: () -> Unit) {
        withTimeout(5.seconds) {
            while (true) {
                try {
                    assertion()
                    return@withTimeout
                } catch (_: AssertionError) {
                    kotlinx.coroutines.delay(20.milliseconds)
                }
            }
        }
    }
}
