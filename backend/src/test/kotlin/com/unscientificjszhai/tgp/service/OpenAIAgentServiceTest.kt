package com.unscientificjszhai.tgp.service

import com.openai.client.OpenAIClient
import com.openai.models.ChatModel
import com.openai.models.ReasoningEffort
import com.openai.models.models.Model
import com.openai.models.models.ModelListPage
import com.openai.services.blocking.ModelService
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.service.ai.MCPClientService
import com.unscientificjszhai.tgp.service.ai.agent.OpenAIAgentService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenAIAgentServiceTest {

    private lateinit var settingsRepository: SettingsRepository
    private lateinit var skillRepository: SkillRepository
    private lateinit var service: OpenAIAgentService
    private lateinit var tempDirectory: File

    @BeforeTest
    fun setup() {
        tempDirectory = Files.createTempDirectory("openai-agent-service-test").toFile()
        val testScope = CoroutineScope(EmptyCoroutineContext)
        settingsRepository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"))
        skillRepository = SkillRepository.forTesting(File(tempDirectory, "skills.json"))
        service = OpenAIAgentService(
            testScope,
            settingsRepository,
            skillRepository,
            MCPClientService(testScope),
        ) { mockk() }
    }

    @AfterTest
    fun teardown() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun testDefaultModelIsLunaAndRetainsGpt4oOptions() {
        assertEquals("gpt-5.6-luna", service.currentModel)
        assertEquals(
            listOf(
                "gpt-5.6-luna",
                ChatModel.GPT_4O.toString(),
                ChatModel.GPT_4O_MINI.toString(),
            ),
            service.availableModels,
        )
    }

    @Test
    fun testPreferredModelFallbackOrder() {
        assertEquals("gpt-5.6-luna", service.preferredModel(listOf("gpt-5.6-luna", "gpt-4o")))
        assertEquals("gpt-4o", service.preferredModel(listOf("third-party", "gpt-4o")))
        assertEquals("third-party", service.preferredModel(listOf("third-party", "gpt-4o-mini")))
        assertEquals(null, service.preferredModel(emptyList()))
    }

    @Test
    fun testModelRefreshFallsBackToGpt4oWhenLunaUnavailable() = runBlocking {
        val client = mockk<OpenAIClient>()
        val modelService = mockk<ModelService>()
        val page = mockk<ModelListPage>()
        val thirdPartyModel = mockk<Model>()
        val gpt4oModel = mockk<Model>()
        every { client.models() } returns modelService
        every { modelService.list() } returns page
        every { page.data() } returns listOf(thirdPartyModel, gpt4oModel)
        every { thirdPartyModel.id() } returns "third-party"
        every { gpt4oModel.id() } returns ChatModel.GPT_4O.toString()
        injectClient(client)

        service.updateModel()

        withTimeout(5_000) {
            while (service.availableModels != listOf("third-party", ChatModel.GPT_4O.toString())) {
                delay(10)
            }
        }
        assertEquals(ChatModel.GPT_4O.toString(), service.currentModel)
    }

    @Test
    fun testGpt56RequestSetsReasoningEffortNone() {
        val params = service.createChatCompletionParams(emptyList())

        assertEquals(ReasoningEffort.NONE, params.reasoningEffort().get())
    }

    @Test
    fun testLegacyModelRequestDoesNotSetReasoningEffort() {
        service.switchModel(ChatModel.GPT_4O.toString())

        val params = service.createChatCompletionParams(emptyList())

        assertFalse(params.reasoningEffort().isPresent)
    }

    private fun injectClient(client: OpenAIClient) {
        OpenAIAgentService::class.java.getDeclaredField("client").apply { isAccessible = true }.set(service, client)
    }

}
