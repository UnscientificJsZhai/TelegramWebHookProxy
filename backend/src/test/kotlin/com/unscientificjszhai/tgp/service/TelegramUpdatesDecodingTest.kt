package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.utils.JsonStructureLimitExceededException
import com.unscientificjszhai.tgp.utils.JsonStructureLimits
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

internal class TelegramUpdatesDecodingTest : MessagePollerFacadeTestSupport() {
    /** 满批合法富消息必须能被略过，随后普通消息仍能推进持久化 offset。 */
    @Test
    fun `rich update batch does not block following ordinary updates`() = runBlocking {
        val fixture = fixture()
        fixture.saveRawSettings(AppSettings(telegramToken = "100:test"))
        fixture.updates.saveLastUpdateId("100", 10)
        val blocks = List(500) { """{"type":"paragraph","text":{"type":"bold","text":"字"}}""" }
            .joinToString(",")
        val richUpdates = (11..20).joinToString(",") { id ->
            """{"update_id":$id,"message":{"message_id":$id,"date":1,"chat":{"id":123,"type":"private"},"rich_message":{"blocks":[$blocks]}}}"""
        }
        val offsets = CopyOnWriteArrayList<Long>()
        val confirmed = CompletableDeferred<Unit>()
        val telegram = TelegramService(parentScope, fixture.settings, fixture.updates) {
            HttpClient(MockEngine { request ->
                assertEquals("10", request.url.parameters["limit"])
                val offset = request.url.parameters["offset"]!!.toLong()
                offsets += offset
                val body = when (offset) {
                    11L -> """{"ok":true,"result":[$richUpdates]}"""
                    21L -> """{"ok":true,"result":[{"update_id":21,"message":{"message_id":21,"chat":{"id":123,"type":"private"},"text":"后续消息"}}]}"""
                    22L -> {
                        confirmed.complete(Unit)
                        awaitCancellation()
                    }

                    else -> error("意外的轮询 offset: $offset")
                }
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        }
        val poller =
            MessagePoller(parentScope, telegram, fixture.agent, fixture.settings, fixture.updates, fixture.barrier)
        try {
            poller.start()
            withTimeout(5.seconds) { confirmed.await() }
            assertEquals(listOf(11L, 21L, 22L), offsets.toList())
            assertEquals(21L, fixture.updates.getData("100").lastUpdateId)
            assertNull(fixture.updates.getData("100").retryCheckpoint)
        } finally {
            poller.closeAndJoin()
            telegram.close()
        }
    }

    /** 放宽节点预算不改变响应字节和递归深度边界。 */
    @Test
    fun `update decoding still rejects excessive depth and response bytes`() = runBlocking<Unit> {
        val fixture = fixture()
        val bodies = listOf(
            """{"ok":true,"result":[],"unknown":${"[".repeat(JsonStructureLimits.MAX_DEPTH + 1)}0${
                "]".repeat(
                    JsonStructureLimits.MAX_DEPTH + 1
                )
            }}""",
            """{"ok":true,"result":[],"unknown":"${"x".repeat(1024 * 1024)}"}""",
        ).iterator()
        val telegram = TelegramService(parentScope, fixture.settings, fixture.updates) {
            HttpClient(MockEngine { respond(bodies.next(), HttpStatusCode.OK) })
        }
        try {
            assertFailsWith<JsonStructureLimitExceededException> { telegram.getUpdatesForToken("100:test") }
            assertFailsWith<TelegramPayloadTooLargeException> { telegram.getUpdatesForToken("100:test") }
        } finally {
            telegram.close()
        }
    }
}
