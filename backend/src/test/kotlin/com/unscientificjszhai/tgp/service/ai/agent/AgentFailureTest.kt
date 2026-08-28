package com.unscientificjszhai.tgp.service.ai.agent

import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.MediaData
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds

class AgentFailureTest {
    @Test
    fun `maps retryable and configuration HTTP statuses without retaining a body`() {
        val retryable = listOf(408, 409, 425, 500, 503).map { status ->
            AgentFailure.classify(AgentUpstreamHttpException(status))
        }
        assertTrue(retryable.all { it.disposition == RecoveryDisposition.RETRY })

        val authentication = AgentFailure.classify(AgentUpstreamHttpException(401))
        assertEquals(AgentFailureKind.AUTHENTICATION, authentication.kind)
        assertEquals(RecoveryDisposition.WAIT_FOR_CONFIGURATION, authentication.disposition)

        val configuration = AgentFailure.classify(AgentUpstreamHttpException(422))
        assertEquals(AgentFailureKind.CONFIGURATION, configuration.kind)
        assertEquals(RecoveryDisposition.WAIT_FOR_CONFIGURATION, configuration.disposition)

        val exception = AgentUpstreamHttpException.fromResponse(
            statusCode = 503,
            headers = mapOf("X-Ignored" to listOf("response-body-secret")),
        )
        assertFalse(exception.toString().contains("response-body-secret"))
    }

    @Test
    fun `parses delta and HTTP date retry after values`() {
        assertEquals(17.seconds, AgentUpstreamHttpException.parseRetryAfter("17", nowEpochSeconds = 100))
        val retryAt = Instant.ofEpochSecond(140).atZone(ZoneOffset.UTC)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(retryAt)
        assertEquals(40.seconds, AgentUpstreamHttpException.parseRetryAfter(header, nowEpochSeconds = 100))
        assertNull(AgentUpstreamHttpException.parseRetryAfter("not-a-delay", nowEpochSeconds = 100))
    }

    @Test
    fun `provider initialization is explicit single execution and preserves safe failure classification`() =
        runBlocking {
            var invocations = 0
            val provider = TestProvider {
                invocations++
                throw IOException("credential-and-url-must-not-escape")
            }

            val first = assertIs<AgentInitializationResult.Failed>(provider.initializeForPublication())
            val second = assertIs<AgentInitializationResult.Failed>(provider.initializeForPublication())

            assertEquals(1, invocations)
            assertEquals(AgentFailureKind.NETWORK, first.failure.kind)
            assertEquals(first, second)
            assertFalse(first.toString().contains("credential-and-url-must-not-escape"))
        }

    @Test
    fun `provider owned timeout is a retryable failure rather than target cancellation`() = runBlocking {
        val provider = TestProvider {
            withTimeout(1.milliseconds) { awaitCancellation() }
        }

        val failed = assertIs<AgentInitializationResult.Failed>(provider.initializeForPublication())

        assertEquals(AgentFailureKind.TIMEOUT, failed.failure.kind)
        assertEquals(RecoveryDisposition.RETRY, failed.failure.disposition)
    }

    private class TestProvider(
        private val initialize: suspend () -> Unit,
    ) : ProviderAgentService() {
        override val currentModel: String = "test"
        override val availableModels: List<String> = listOf("test")
        override fun isAiFeatureEnabled(aiSettings: AISettings): Boolean = true
        override fun switchModel(modelName: String): Job? = null
        override suspend fun updateModel(): ModelSnapshot = ModelSnapshot(currentModel, availableModels)
        override fun resetSession(): Job? = null
        override suspend fun sendMessage(text: String?, mediaData: List<MediaData>): String = ""
        override suspend fun performPublicationInitialization() = initialize()
    }
}
