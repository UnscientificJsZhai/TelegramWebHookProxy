package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.MediaData
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.CancellableOkHttpTransport
import com.unscientificjszhai.tgp.service.ai.agent.AudioTranscriptionFailedException
import com.unscientificjszhai.tgp.service.ai.agent.AudioTranscriptionTooLargeException
import com.unscientificjszhai.tgp.service.ai.agent.GeminiAgentService
import com.unscientificjszhai.tgp.service.ai.agent.MAX_AGENT_INLINE_MEDIA_BYTES
import com.unscientificjszhai.tgp.service.ai.agent.MAX_AUDIO_TRANSCRIPTION_BYTES
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Gemini 与 OpenAI 原生 HTTP 协议路径的集成测试。 */
class RawAgentTransportTest {
    private val temporaryDirectory = Files.createTempDirectory("raw-agent-transport-test").toFile()
    private val server = MockWebServer()
    private val scope = CoroutineScope(EmptyCoroutineContext)
    private val settingsRepository = SettingsRepository.forTesting(
        File(temporaryDirectory, "settings.json"),
        ModelSwitchBarrier(),
    )
    private val skillRepository = com.unscientificjszhai.tgp.repository.SkillRepository.forTesting(
        File(temporaryDirectory, "skills.json"),
    )

    init {
        server.start()
    }

    @AfterTest
    fun cleanUp() {
        server.close()
        temporaryDirectory.deleteRecursively()
    }

    /** 验证 Gemini 原生路径以正确的 v1beta wire 格式发送工具与内容。 */
    @Test
    fun `Gemini raw transport sends v1beta contents and tool schema`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(
                ai = AISettings(
                    provider = AIProvider.GEMINI,
                    geminiApiKey = "gemini-key",
                    globalContext = "global context",
                ),
            ),
        )
        val service =
            GeminiAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawGeminiTransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(
            MockResponse.Builder().body(
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"call-1","name":"read_skill","args":{"id":"missing"}}}]}}]}""",
            ).build(),
        )
        server.enqueue(
            MockResponse.Builder().body(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"Gemini raw reply"}]}}]}""",
            ).build(),
        )

        assertEquals("Gemini raw reply", service.sendMessage("hello"))
        val request = assertNotNull(server.takeRequest())
        assertTrue(request.target.startsWith("/v1beta/models/gemini-3.5-flash-lite:generateContent?key=gemini-key"))
        val payload = request.body!!.utf8()
        assertTrue(payload.contains("\"contents\""))
        assertTrue(payload.contains("\"systemInstruction\""))
        assertTrue(payload.contains("\"tools\""))
        assertTrue(payload.contains("\"functionDeclarations\""))
        assertTrue(payload.contains("\"type\":\"OBJECT\""))
        assertTrue(!payload.contains("\"httpOptions\""))
        server.takeRequest().also { toolResponseRequest ->
            assertNotNull(toolResponseRequest)
            val toolPayload = toolResponseRequest.body!!.utf8()
            assertTrue(toolPayload.contains("\"functionResponse\""))
            assertTrue(toolPayload.contains("\"id\":\"call-1\""))
        }
        service.close().join()
    }

    /** 验证 REST 历史达到 64 条短内容后会整体滑动最早完整回合并继续完成。 */
    @Test
    fun `Gemini raw history slides after 64 short entries`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key")),
        )
        val service =
            GeminiAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawGeminiTransport(service)
        assertNotNull(service.resetSession()).join()
        repeat(34) {
            server.enqueue(MockResponse.Builder().body(geminiTextResponse("完成")).build())
        }

        val requests = buildList {
            repeat(34) { index ->
                assertEquals("完成", service.sendMessage("短消息$index"))
                add(assertNotNull(server.takeRequest()))
            }
        }

        assertEquals(63, geminiContents(requests[32].body!!.utf8()).size)
        assertEquals(63, geminiContents(requests[33].body!!.utf8()).size)
        service.close().join()
    }

    /** 验证 REST 裁剪带函数调用的旧回合时，不会把 functionResponse 当作下一个回合的开头。 */
    @Test
    fun `Gemini raw trimming removes function calls and responses together`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key")),
        )
        val service =
            GeminiAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawGeminiTransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(
            MockResponse.Builder().body(
                """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"id":"old-call","name":"missing","args":{}}}]}}]}""",
            ).build(),
        )
        repeat(32) {
            server.enqueue(MockResponse.Builder().body(geminiTextResponse("完成")).build())
        }

        assertEquals("完成", service.sendMessage("工具回合"))
        repeat(30) { index -> assertEquals("完成", service.sendMessage("普通回合$index")) }
        assertEquals("完成", service.sendMessage("触发裁剪"))

        val requests = buildList {
            repeat(33) { add(assertNotNull(server.takeRequest())) }
        }
        val trimmedPayload = requests.last().body!!.utf8()
        assertFalse(trimmedPayload.contains("functionCall"))
        assertFalse(trimmedPayload.contains("functionResponse"))
        service.close().join()
    }

    /** 验证 REST 字节预留超限时只删除最早完整回合，而不清空全部历史。 */
    @Test
    fun `Gemini raw byte reservation removes only the oldest complete turn`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key")),
        )
        val service =
            GeminiAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawGeminiTransport(service)
        assertNotNull(service.resetSession()).join()
        repeat(3) {
            server.enqueue(MockResponse.Builder().body(geminiTextResponse("完成")).build())
        }
        val firstImage = MediaData(ByteArray(MAX_AGENT_INLINE_MEDIA_BYTES) { 1 }, "image/png")
        val secondImage = MediaData(ByteArray(MAX_AGENT_INLINE_MEDIA_BYTES) { 2 }, "image/png")

        assertEquals("完成", service.sendMessage(null, listOf(firstImage)))
        assertNotNull(server.takeRequest())
        assertEquals("完成", service.sendMessage(null, listOf(secondImage)))
        assertNotNull(server.takeRequest())
        assertEquals("完成", service.sendMessage("小消息"))
        val trimmedRequest = assertNotNull(server.takeRequest())

        val contents = geminiContents(trimmedRequest.body!!.utf8())
        assertEquals(3, contents.size)
        assertTrue(contents.first().toString().contains("AgICAg"))
        service.close().join()
    }

    /** 验证 OpenAI 原生路径保留自定义基础路径、Bearer、SDK 工具 JSON 和模型 DTO 解析。 */
    @Test
    fun `OpenAI raw transport preserves base path chat tools and model list`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key")),
        )
        val service =
            OpenAIAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawOpenAITransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(
            MockResponse.Builder().body(
                """{"id":"chat-1","object":"chat.completion","created":0,"model":"gpt-5.6-luna","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"OpenAI raw reply"}}]}""",
            ).build(),
        )

        assertEquals("OpenAI raw reply", service.sendMessage("hello"))
        server.takeRequest().also { request ->
            assertNotNull(request)
            assertEquals("/gateway/v1/chat/completions", request.target)
            assertEquals("Bearer openai-key", request.headers["Authorization"])
            val payload = request.body!!.utf8()
            assertTrue(payload.contains("\"messages\""))
            assertTrue(payload.contains("\"tools\""))
            assertTrue(payload.contains("create_scheduled_task"))
        }

        server.enqueue(
            MockResponse.Builder().body(
                """{"object":"list","data":[{"id":"gpt-5.6-luna","object":"model","created":0,"owned_by":"test"}]}""",
            ).build(),
        )
        assertEquals(listOf("gpt-5.6-luna"), service.updateModel()?.availableModels)
        server.takeRequest().also { request ->
            assertNotNull(request)
            assertEquals("/gateway/v1/models", request.target)
            assertEquals("Bearer openai-key", request.headers["Authorization"])
        }
        service.close().join()
    }

    /**
     * 验证 Telegram OGG 语音以 multipart 转写为文本后才进入 OpenAI 聊天请求。
     */
    @Test
    fun `OpenAI raw transport transcribes normalized Telegram OGG before chatting`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key")),
        )
        val service =
            OpenAIAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawOpenAITransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(MockResponse.Builder().body("""{"text":"转写后的内容"}""").build())
        server.enqueue(
            MockResponse.Builder().body(
                """{"id":"chat-ogg","object":"chat.completion","created":0,"model":"gpt-5.6-luna","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"语音回复"}}]}""",
            ).build(),
        )

        assertEquals(
            "语音回复",
            service.sendMessage(
                "请处理语音",
                listOf(MediaData("ogg-payload".encodeToByteArray(), "audio/ogg; codecs=opus"))
            ),
        )
        val transcriptionRequest = assertNotNull(server.takeRequest())
        assertEquals("/gateway/v1/audio/transcriptions", transcriptionRequest.target)
        assertEquals("Bearer openai-key", transcriptionRequest.headers["Authorization"])
        val multipart = transcriptionRequest.body!!.utf8()
        assertTrue(multipart.contains("name=\"model\""))
        assertTrue(multipart.contains("gpt-4o-mini-transcribe"))
        assertTrue(multipart.contains("name=\"file\"; filename=\"telegram-voice.ogg\""))
        assertTrue(multipart.contains("Content-Type: audio/ogg"))
        assertTrue(multipart.contains("ogg-payload"))

        val chatRequest = assertNotNull(server.takeRequest())
        assertEquals("/gateway/v1/chat/completions", chatRequest.target)
        val chatPayload = chatRequest.body!!.utf8()
        assertTrue(chatPayload.contains("请处理语音"))
        assertTrue(chatPayload.contains("转写后的内容"))
        service.close().join()
    }

    /**
     * 验证 OGG 转写的空文本和本地大小拒绝都不会提交聊天历史或创建聊天请求。
     */
    @Test
    fun `failed or oversized OGG transcription leaves OpenAI history untouched`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key")),
        )
        val service =
            OpenAIAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawOpenAITransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(MockResponse.Builder().body("""{"text":"   "}""").build())

        assertFailsWith<AudioTranscriptionFailedException> {
            service.sendMessage(null, listOf(MediaData(byteArrayOf(1), "audio/ogg")))
        }
        assertEquals("/gateway/v1/audio/transcriptions", assertNotNull(server.takeRequest()).target)
        assertNull(server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertTrue(service.createChatCompletionParams(emptyList()).messages().isEmpty())

        assertFailsWith<AudioTranscriptionTooLargeException> {
            service.sendMessage(null, listOf(MediaData(ByteArray(MAX_AUDIO_TRANSCRIPTION_BYTES + 1), "audio/ogg")))
        }
        assertNull(server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertTrue(service.createChatCompletionParams(emptyList()).messages().isEmpty())
        service.close().join()
    }

    /**
     * 验证只有 WAV 和 MP3 会作为支持音频模型的直接输入，且不触发 OGG 转写端点。
     */
    @Test
    fun `OpenAI raw transport keeps WAV and MP3 as direct input audio`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key")),
        )
        val service =
            OpenAIAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawOpenAITransport(service)
        setPrivateField(service, "currentModel", "gpt-4o-audio-preview")
        assertNotNull(service.resetSession()).join()
        listOf(
            "audio/wav" to "wav",
            "audio/mpeg" to "mp3",
        ).forEach { (mimeType, format) ->
            server.enqueue(
                MockResponse.Builder().body(
                    """{"id":"chat-$format","object":"chat.completion","created":0,"model":"gpt-4o-audio-preview","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"$format reply"}}]}""",
                ).build(),
            )
            assertEquals("$format reply", service.sendMessage(null, listOf(MediaData(byteArrayOf(1, 2), mimeType))))
            val request = assertNotNull(server.takeRequest())
            assertEquals("/gateway/v1/chat/completions", request.target)
            assertTrue(request.body!!.utf8().contains("\"format\":\"$format\""))
        }
        service.close().join()
    }

    /** 验证取消原生 Gemini 调用会释放会话锁且不会提交暂存历史。 */
    @Test
    fun `cancelled Gemini raw request releases mutex and rolls back history`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key")),
        )
        val service =
            GeminiAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawGeminiTransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(MockResponse.Builder().headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build())

        val requestJob = async(Dispatchers.Default) { service.sendMessage("cancel me") }
        assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
        requestJob.cancelAndJoin()

        assertTrue(requestJob.isCancelled)
        val rawSession = assertNotNull(privateField(service, "rawSession"))
        @Suppress("UNCHECKED_CAST")
        assertTrue((privateField(rawSession, "history") as List<*>).isEmpty())
        withTimeout(5.seconds) {
            assertNotNull(service.resetSession()).join()
        }
        service.close().join()
    }

    /** 验证取消原生 OpenAI 调用不会提交暂存消息，并立即让后续会话操作取得锁。 */
    @Test
    fun `cancelled OpenAI raw request releases mutex and rolls back history`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key")),
        )
        val service =
            OpenAIAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawOpenAITransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(MockResponse.Builder().headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build())

        val requestJob = async(Dispatchers.Default) { service.sendMessage("cancel me") }
        assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS))
        requestJob.cancelAndJoin()

        assertTrue(requestJob.isCancelled)
        assertTrue(service.createChatCompletionParams(emptyList()).messages().isEmpty())
        withTimeout(5.seconds) {
            assertNotNull(service.resetSession()).join()
        }
        service.close().join()
    }

    /** 验证取消 OGG 转写请求会原样传播取消并保持 OpenAI 历史为空。 */
    @Test
    fun `cancelled OpenAI OGG transcription releases mutex and rolls back history`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.OPENAI, openAiApiKey = "openai-key")),
        )
        val service =
            OpenAIAgentService(scope, settingsRepository, skillRepository, MCPClientService(scope)) { mockk() }
        installRawOpenAITransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(MockResponse.Builder().headersDelay(5, java.util.concurrent.TimeUnit.SECONDS).build())

        val requestJob = async(Dispatchers.Default) {
            service.sendMessage(null, listOf(MediaData(byteArrayOf(1), "audio/ogg")))
        }
        assertEquals(
            "/gateway/v1/audio/transcriptions",
            assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)).target
        )
        requestJob.cancelAndJoin()

        assertTrue(requestJob.isCancelled)
        assertTrue(service.createChatCompletionParams(emptyList()).messages().isEmpty())
        withTimeout(5.seconds) {
            assertNotNull(service.resetSession()).join()
        }
        service.close().join()
    }

    /** 验证 Gemini 原生 wire adapter 不会丢弃 MCP Schema 的组合和约束字段。 */
    @Test
    fun `Gemini raw transport preserves anyOf and schema constraints`() = runBlocking {
        settingsRepository.saveSettings(
            AppSettings(ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "gemini-key")),
        )
        val mcpClientService = mockk<MCPClientService>()
        coEvery { mcpClientService.connect(any()) } returns Unit
        every { mcpClientService.getAllTools() } returns listOf("schema" to constrainedMcpTool())
        every { mcpClientService.close() } returns kotlinx.coroutines.Job().apply { complete() }
        val service = GeminiAgentService(scope, settingsRepository, skillRepository, mcpClientService) { mockk() }
        installRawGeminiTransport(service)
        assertNotNull(service.resetSession()).join()
        server.enqueue(
            MockResponse.Builder().body(
                """{"candidates":[{"content":{"role":"model","parts":[{"text":"schema preserved"}]}}]}""",
            ).build(),
        )

        assertEquals("schema preserved", service.sendMessage("check schema"))
        val payload = assertNotNull(server.takeRequest()).body!!.utf8()
        assertFalse(payload.contains("schema_constrained"))
        assertTrue(Regex("mcp_[A-Za-z0-9_-]{43}").containsMatchIn(payload))
        assertTrue(payload.contains("\"anyOf\""))
        assertTrue(payload.contains("\"maxLength\":12"))
        assertTrue(payload.contains("\"minimum\":1.0"))
        assertTrue(payload.contains("\"pattern\":\"^[a-z]+$\""))
        assertTrue(payload.contains("\"default\":{\"enabled\":true}"))
        service.close().join()
    }

    private fun installRawGeminiTransport(service: GeminiAgentService) {
        setPrivateField(service, "rawTransport", CancellableOkHttpTransport(OkHttpClient()))
        setPrivateField(service, "rawBaseUrl", server.url("/v1beta").toString().trimEnd('/'))
        setPrivateField(service, "rawApiKey", "gemini-key")
    }

    private fun installRawOpenAITransport(service: OpenAIAgentService) {
        setPrivateField(service, "rawTransport", CancellableOkHttpTransport(OkHttpClient()))
        setPrivateField(service, "rawBaseUrl", server.url("/gateway/v1").toString().trimEnd('/'))
        setPrivateField(service, "rawApiKey", "openai-key")
    }

    private fun geminiTextResponse(text: String): String =
        """{"candidates":[{"content":{"role":"model","parts":[{"text":"$text"}]}}]}"""

    private fun geminiContents(payload: String) = Json.parseToJsonElement(payload)
        .jsonObject["contents"]
        ?.jsonArray
        ?: error("Gemini 请求未包含 contents。")

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.set(target, value)
    }

    private fun privateField(target: Any, fieldName: String): Any? =
        target.javaClass.getDeclaredField(fieldName).apply { isAccessible = true }.get(target)

    private fun constrainedMcpTool(): Tool = Tool(
        name = "constrained",
        inputSchema = ToolSchema(
            properties = kotlinx.serialization.json.buildJsonObject {
                put("mode", kotlinx.serialization.json.buildJsonObject {
                    put("anyOf", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("string"))
                        })
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("type", kotlinx.serialization.json.JsonPrimitive("integer"))
                        })
                    })
                    put("maxLength", kotlinx.serialization.json.JsonPrimitive(12))
                    put("minimum", kotlinx.serialization.json.JsonPrimitive(1))
                    put("pattern", kotlinx.serialization.json.JsonPrimitive("^[a-z]+$"))
                    put("default", kotlinx.serialization.json.buildJsonObject {
                        put("enabled", kotlinx.serialization.json.JsonPrimitive(true))
                    })
                })
            },
        ),
    )
}
