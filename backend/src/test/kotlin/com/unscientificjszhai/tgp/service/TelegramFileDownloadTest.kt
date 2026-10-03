package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TelegramFileDownloadTest {
    @Test
    fun `download distinguishes permanent rejection from temporary errors and enforces size limit`() =
        runBlocking<Unit> {
            val directory = createTempDirectory("telegram-file-download-test").toFile()
            val job = SupervisorJob()
            var status = HttpStatusCode.OK
            var declaredSize: Long? = null
            val client = HttpClient(MockEngine {
                respond("audio", status, headersOf(HttpHeaders.ContentLength, (declaredSize ?: 5).toString()))
            })
            val settings =
                SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            val service = TelegramService(
                CoroutineScope(job),
                settings,
                UpdatesRepository(directory.resolve("updates.json"))
            ) { client }
            try {
                assertContentEquals("audio".toByteArray(), service.downloadFileForToken("100:test", "voice.ogg"))
                for (code in listOf(400, 403, 404)) {
                    status = HttpStatusCode.fromValue(code)
                    assertFailsWith<TelegramFileDownloadRejectedException> {
                        service.downloadFileForToken("100:test", "voice.ogg")
                    }
                }
                for (code in listOf(408, 429, 500, 503)) {
                    status = HttpStatusCode.fromValue(code)
                    val error = assertFailsWith<IllegalStateException> {
                        service.downloadFileForToken("100:test", "voice.ogg")
                    }
                    assertFalse(error is TelegramFileDownloadRejectedException)
                }
                status = HttpStatusCode.OK
                declaredSize = 21 * 1024 * 1024L
                assertFailsWith<TelegramPayloadTooLargeException> {
                    service.downloadFileForToken("100:test", "voice.ogg")
                }
            } finally {
                service.close()
                job.cancelAndJoin()
                directory.deleteRecursively()
            }
        }
}
