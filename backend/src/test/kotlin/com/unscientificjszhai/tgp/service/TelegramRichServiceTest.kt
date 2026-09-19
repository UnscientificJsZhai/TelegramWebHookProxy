package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.repository.UpdatesRepository
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class TelegramRichServiceTest {
    @Test
    fun `rich service sends one selected field and preserves upstream response`() = runBlocking {
        val directory = createTempDirectory("rich-service-test").toFile()
        val job = SupervisorJob()
        val requests = mutableListOf<JsonObject>()
        val settings = SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
        val client = HttpClient(MockEngine { request ->
            assertEquals("/bot100:test/sendRichMessage", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            requests += Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            respond("upstream-body", HttpStatusCode.BadRequest)
        }) {
            install(ContentNegotiation) { json() }
        }
        val service = TelegramService(
            CoroutineScope(job),
            settings,
            UpdatesRepository(directory.resolve("updates.json"))
        ) { client }
        try {
            val contents = listOf(
                InputRichMessage(markdown = "# 标题"),
                InputRichMessage(html = "<h1>标题</h1>"),
                InputRichMessage(blocks = Json.parseToJsonElement("""[{"type":"paragraph","text":"标题","future_field":{"file_id":"test"}}]""").jsonArray),
            )
            for (content in contents) {
                assertEquals(
                    TelegramApiResponse(HttpStatusCode.BadRequest, "upstream-body"),
                    service.sendRichMessageForToken("100:test", "123", content, ReplyParameters(1))
                )
            }
            requests.zip(listOf("markdown", "html", "blocks")).forEach { (body, key) ->
                assertEquals(setOf("chat_id", "rich_message", "reply_parameters"), body.keys)
                assertEquals(setOf(key), body.getValue("rich_message").jsonObject.keys)
                assertEquals("123", body.getValue("chat_id").jsonPrimitive.content)
            }
            assertEquals(contents.last().blocks, requests.last().getValue("rich_message").jsonObject["blocks"])
        } finally {
            service.close()
            job.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test
    fun `rich response with thousands of result nodes is accepted but temporary errors are not permanent`() {
        val body = buildJsonObject {
            put("ok", true)
            putJsonArray("result") { repeat(5000) { add(JsonPrimitive(it)) } }
        }.toString()
        assertTrue(TelegramApiResponse(HttpStatusCode.OK, body).isTelegramAccepted())
        for (code in listOf(408, 429, 500, 503)) {
            assertFalse(
                TelegramApiResponse(
                    HttpStatusCode.fromValue(code),
                    """{"ok":false,"error_code":400}"""
                ).isPermanentTelegramRejection()
            )
            assertFalse(
                TelegramApiResponse(
                    HttpStatusCode.OK,
                    """{"ok":false,"error_code":$code}"""
                ).isPermanentTelegramRejection()
            )
        }
        assertTrue(
            TelegramApiResponse(
                HttpStatusCode.OK,
                """{"ok":false,"error_code":400}"""
            ).isPermanentTelegramRejection()
        )
        assertFalse(TelegramApiResponse(HttpStatusCode.OK, "invalid").isTelegramAccepted())
    }
}
