package com.unscientificjszhai.tgp.models

import com.unscientificjszhai.tgp.utils.ConfigJson
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 应用与 AI 设置序列化兼容性的测试设计。
 */
class SettingsTest {

    /**
     * 验证完整应用设置的序列化设计。
     *
     * 验证序列化结果包含 AI 配置及其关键字段。
     */
    @Test
    fun testAppSettingsSerialization() {
        val appSettings = AppSettings(
            telegramToken = "token",
            chatId = "123",
            ai = AISettings(
                geminiApiKey = "test_key",
                selectedModel = "models/gemini-test",
                agentEnabled = true,
                agentChatId = "123",
                globalContext = "context",
                mcpServers = listOf(
                    MCPServerConfig(
                        name = "server1",
                        url = "http://localhost:3000",
                        headers = mapOf("Authorization" to "Bearer token")
                    )
                )
            )
        )

        val jsonString = ConfigJson.encodeToString(appSettings)
        val jsonElement = ConfigJson.parseToJsonElement(jsonString).jsonObject

        assertTrue(jsonElement.containsKey("ai"))
        val aiElement = jsonElement["ai"]?.jsonObject
        assertNotNull(aiElement)
        assertTrue(aiElement.containsKey("provider"))
        assertTrue(aiElement.containsKey("geminiApiKey"))
        assertTrue(aiElement.containsKey("openAiApiKey"))
        assertTrue(aiElement.containsKey("openAiBaseUrl"))
        assertTrue(aiElement.containsKey("selectedModel"))
        assertTrue(aiElement.containsKey("autoCleanContextIntervalMinutes"))
        assertTrue(aiElement.containsKey("silentContextCleanup"))
        assertTrue(aiElement.containsKey("mcpServers"))
        assertTrue(aiElement.containsKey("httpToolSettings"))
    }

    /**
     * 验证 AI 设置默认值的序列化设计。
     *
     * 验证默认值字段不会在序列化时被省略。
     */
    @Test
    fun testAISettingsDefaultValuesInSerialization() {
        val aiSettings = AISettings()
        val jsonString = ConfigJson.encodeToString(aiSettings)
        val jsonElement = ConfigJson.parseToJsonElement(jsonString).jsonObject

        // 验证默认值字段仍会写入序列化结果。
        assertTrue(jsonElement.containsKey("provider"), "Provider field should be present even if default")
        assertTrue(jsonElement.containsKey("openAiApiKey"), "openAiApiKey field should be present even if default")
        assertTrue(jsonElement.containsKey("openAiBaseUrl"), "openAiBaseUrl field should be present even if default")
        assertTrue(jsonElement.containsKey("selectedModel"), "selectedModel field should be present even if default")
        assertTrue(
            jsonElement.containsKey("autoCleanContextIntervalMinutes"),
            "autoCleanContextIntervalMinutes field should be present even if default",
        )
        assertTrue(
            jsonElement.containsKey("silentContextCleanup"),
            "silentContextCleanup field should be present even if default",
        )
        assertTrue(
            jsonElement.containsKey("httpToolSettings"),
            "httpToolSettings field should be present even if default",
        )
    }

    /**
     * 验证旧版 AI 设置 JSON 的反序列化兼容设计。
     *
     * 验证缺失的新增字段会使用当前定义的默认值。
     */
    @Test
    fun testAISettingsDeserializeOldJsonUsesDefaults() {
        val jsonString = """
            {
              "provider": "GEMINI",
              "geminiApiKey": "test_key",
              "openAiApiKey": "",
              "openAiBaseUrl": "",
              "agentEnabled": true,
              "agentChatId": "123",
              "globalContext": "context",
              "mcpServers": []
            }
        """.trimIndent()

        val aiSettings = ConfigJson.decodeFromString<AISettings>(jsonString)

        assertEquals(0, aiSettings.autoCleanContextIntervalMinutes)
        assertEquals(false, aiSettings.silentContextCleanup)
        assertEquals("", aiSettings.selectedModel)
        assertEquals(false, aiSettings.httpToolSettings.enabled)
        assertTrue(aiSettings.httpToolSettings.targets.isEmpty())
    }

    /**
     * 验证 HTTP 工具仅接受精确固定目标、受限 HTTP loopback 例外及硬配置上限。
     */
    @Test
    fun `HTTP tool validation accepts only bounded fixed targets`() {
        validateHttpToolSettings(
            HttpToolSettings(
                enabled = true,
                requestTimeoutMillis = 30_000,
                maxConcurrentRequests = 4,
                targets = listOf(
                    HttpCallTarget(
                        id = "local-post",
                        scheme = "http",
                        host = "127.0.0.1",
                        port = 8080,
                        path = "/hook",
                        method = HttpToolMethod.POST,
                        allowedCidrs = listOf("127.0.0.1/32"),
                    ),
                ),
            ),
        )

        listOf(
            HttpCallTarget("wildcard", host = "*.example.com", path = "/hook"),
            HttpCallTarget("query", host = "api.example.com", path = "/hook?debug=true"),
            HttpCallTarget("encoded-dot", host = "api.example.com", path = "/hook/%2e%2e/admin"),
            HttpCallTarget("encoded-slash", host = "api.example.com", path = "/hook%2Fadmin"),
            HttpCallTarget("encoded-backslash", host = "api.example.com", path = "/hook%5Cadmin"),
            HttpCallTarget("redirect", scheme = "ftp", host = "api.example.com", path = "/hook"),
            HttpCallTarget("http-host", scheme = "http", host = "localhost", path = "/hook"),
            HttpCallTarget("http-cidr", scheme = "http", host = "127.0.0.1", path = "/hook"),
        ).forEach { target ->
            assertFailsWith<IllegalArgumentException> { validateHttpCallTarget(target) }
        }
        assertFailsWith<IllegalArgumentException> {
            validateHttpToolSettings(HttpToolSettings(requestTimeoutMillis = 30_001))
        }
        assertFailsWith<IllegalArgumentException> {
            validateHttpToolSettings(HttpToolSettings(maxConcurrentRequests = 5))
        }
    }

    /**
     * 验证代理校验只接受不含 URL 组成部分的裸主机和合法端口，并支持未加方括号的 IPv6。
     */
    @Test
    fun `proxy validation accepts bare hosts and rejects URLs or invalid ports`() {
        validateProxySettings(ProxySettings("proxy.example.com", 8080, ProxyType.HTTP))
        validateProxySettings(ProxySettings("123", 8080, ProxyType.HTTP))
        validateProxySettings(ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS))
        validateProxySettings(ProxySettings("2001:db8::1", 1080, ProxyType.SOCKS))

        listOf(
            ProxySettings("", 8080, ProxyType.HTTP),
            ProxySettings("https://proxy.example.com", 8080, ProxyType.HTTP),
            ProxySettings("proxy.example.com/path", 8080, ProxyType.HTTP),
            ProxySettings("user@proxy.example.com", 8080, ProxyType.HTTP),
            ProxySettings("proxy.example.com:8080", 8080, ProxyType.HTTP),
            ProxySettings("proxy\n.example.com", 8080, ProxyType.HTTP),
            ProxySettings("proxy.example.com", 0, ProxyType.HTTP),
            ProxySettings("proxy.example.com", 65536, ProxyType.HTTP),
        ).forEach { proxy ->
            assertFailsWith<IllegalArgumentException> { validateProxySettings(proxy) }
        }
    }

    /**
     * 验证 OpenAI 基础地址会保留网关前缀与 IPv6，同时拒绝会与固定请求路径重复的操作端点。
     */
    @Test
    fun `OpenAI base URL validation is semantic and preserves gateway prefixes`() {
        listOf(
            "",
            "https://api.example.com/v1",
            "https://api.example.com/gateway/v1/",
            "http://[2001:db8::1]:8080/tenant/v1",
        ).forEach(::validateOpenAiBaseUrl)

        assertEquals(
            "https://api.example.com/gateway/v1",
            openAiBaseUrlForRequests("https://api.example.com/gateway/v1/")
        )
        assertEquals("https://api.openai.com/v1", openAiBaseUrlForRequests(""))

        listOf(
            " https://api.example.com/v1",
            "ftp://api.example.com/v1",
            "https://user@api.example.com/v1",
            "https://api.example.com/v1?tenant=test",
            "https://api.example.com/v1#fragment",
            "https://api.example.com:0/v1",
            "https://api.example.com/v1/models",
            "https://api.example.com/v1/models/",
            "https://api.example.com/v1/%6dodels",
            "https://api.example.com/v1/chat/%63ompletions/",
            "https://api.example.com/v1/audio/%74ranscriptions",
            "https://api.example.com/v1/%2e%2e/models",
            "https://api.example.com/v1/chat%2fcompletions",
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { validateOpenAiBaseUrl(invalid) }
        }

        val deeplyEncodedSlash = generateSequence("%2f") { previous -> "%25${previous.drop(1)}" }
            .drop(12)
            .first()
        val nestedEndpoint = "https://api.example.com/v1${deeplyEncodedSlash}models"
        assertFailsWith<IllegalArgumentException> { validateOpenAiBaseUrl(nestedEndpoint) }
        assertFailsWith<IllegalArgumentException> { openAiBaseUrlForRequests(nestedEndpoint) }
    }

    /** 验证认证凭据必须成对提供，且仅允许 HTTP 代理使用。 */
    @Test
    fun `proxy validation accepts paired HTTP credentials only`() {
        validateProxySettings(ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, "user", "password"))
        validateProxySettings(ProxySettings("proxy.example.com", 8080, ProxyType.HTTP))

        listOf(
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, username = "user"),
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, password = "password"),
            ProxySettings("proxy.example.com", 8080, ProxyType.HTTP, username = " ", password = "password"),
            ProxySettings("proxy.example.com", 1080, ProxyType.SOCKS, username = "user", password = "password"),
        ).forEach { proxy ->
            assertFailsWith<IllegalArgumentException> { validateProxySettings(proxy) }
        }
    }

    /**
     * 验证 MCP 配置共用校验限制服务器身份、绝对 HTTP(S) URL 和不可注入的固定请求头。
     */
    @Test
    fun `MCP server validation accepts bounded safe configurations only`() {
        validateMcpServerConfigs(
            listOf(
                MCPServerConfig(
                    name = "main_server",
                    url = "https://mcp.example.com/v1?tenant=test",
                    headers = mapOf("Authorization" to "Bearer token", "X-Request-Id" to "request-1"),
                ),
            ),
        )

        listOf(
            listOf(MCPServerConfig("", "https://mcp.example.com")),
            listOf(MCPServerConfig("服务", "https://mcp.example.com")),
            listOf(MCPServerConfig("server", "ftp://mcp.example.com")),
            listOf(MCPServerConfig("server", "HTTPS://mcp.example.com")),
            listOf(MCPServerConfig("server", "https://user:secret@mcp.example.com")),
            listOf(MCPServerConfig("server", "https://mcp.example.com#fragment")),
            listOf(MCPServerConfig("server", "https://mcp.example.com", mapOf("Bad Header" to "value"))),
            listOf(MCPServerConfig("server", "https://mcp.example.com", mapOf("X-Test" to "line\r\nbreak"))),
            listOf(MCPServerConfig("server", "https://mcp.example.com", mapOf("Host" to "mcp.example.com"))),
            listOf(MCPServerConfig("server", "https://mcp.example.com", mapOf("X-Test" to "值"))),
            listOf(
                MCPServerConfig(
                    "server",
                    "https://mcp.example.com",
                    ('A'..'D').associate { name -> name.toString() to "a".repeat(4_093) },
                ),
            ),
            listOf(
                MCPServerConfig("server", "https://first.example.com"),
                MCPServerConfig("server", "https://second.example.com"),
            ),
        ).forEach { configs ->
            assertFailsWith<IllegalArgumentException> { validateMcpServerConfigs(configs) }
        }
    }
}
