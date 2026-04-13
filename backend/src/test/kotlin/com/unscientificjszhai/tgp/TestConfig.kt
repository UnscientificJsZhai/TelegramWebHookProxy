package com.unscientificjszhai.tgp

/**
 * Test configuration utility to read sensitive parameters from system properties.
 */
object TestConfig {
    val telegramToken: String? = System.getProperty("telegram.token")?.takeIf { it.isNotBlank() }
    val chatId: String? = System.getProperty("telegram.chat_id")?.takeIf { it.isNotBlank() }
    val geminiApiKey: String? = System.getProperty("gemini.api_key")?.takeIf { it.isNotBlank() }

    fun isTelegramConfigured() = !telegramToken.isNullOrBlank() && !chatId.isNullOrBlank()
    fun isGeminiConfigured() = !geminiApiKey.isNullOrBlank()
}
