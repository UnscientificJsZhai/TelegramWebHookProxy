package com.unscientificjszhai.tgp.service.ai

import com.unscientificjszhai.tgp.models.MCPServerConfig
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds

class MCPClientLifecycleTest {
    @Test
    fun `shutdown after old connection detaches waits for its client cleanup`() = withConnectedService { fixture ->
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        var shutdown: Job? = null
        coEvery { fixture.previousClient.close() } coAnswers {
            cleanupStarted.complete(Unit)
            releaseCleanup.await()
        }
        val generationChecks = AtomicInteger()
        every { fixture.service["isCurrentConnectionRequest"](any<Long>()) } answers {
            // 第二次检查紧接在旧快照摘除后，精确控制关闭与连接替换的交错，不依赖线程调度时序。
            if (generationChecks.incrementAndGet() == 2) shutdown = fixture.service.close()
            callOriginal()
        }

        try {
            fixture.service.connect(listOf(MCPServerConfig("replacement", "http://replacement.example/mcp")))
            withTimeout(5.seconds) { cleanupStarted.await() }
            assertFalse(assertNotNull(shutdown).isCompleted)
            releaseCleanup.complete(Unit)
            withTimeout(5.seconds) { assertNotNull(shutdown).join() }
            assertEquals(1, fixture.clientsCreated.get())
            coVerify(exactly = 1) { fixture.previousClient.close() }
            coVerify(exactly = 0) { fixture.replacementClient.connect(any()) }
        } finally {
            releaseCleanup.complete(Unit)
        }
    }

    @Test
    fun `newer connection request still closes client detached by superseded request`() =
        withConnectedService { fixture ->
            val cleanupStarted = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            var replacement: Job? = null
            coEvery { fixture.previousClient.close() } coAnswers {
                cleanupStarted.complete(Unit)
                releaseCleanup.await()
            }
            val generationChecks = AtomicInteger()
            every { fixture.service["isCurrentConnectionRequest"](any<Long>()) } answers {
                if (generationChecks.incrementAndGet() == 2) {
                    // 立即推进新请求的代次，再等待当前请求持有的连接锁。
                    replacement = fixture.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        fixture.service.connect(listOf(MCPServerConfig("latest", "http://latest.example/mcp")))
                    }
                }
                callOriginal()
            }

            try {
                fixture.service.connect(listOf(MCPServerConfig("superseded", "http://superseded.example/mcp")))
                withTimeout(5.seconds) { assertNotNull(replacement).join() }
                withTimeout(5.seconds) { cleanupStarted.await() }
                assertEquals(2, fixture.clientsCreated.get())
                releaseCleanup.complete(Unit)
                withTimeout(5.seconds) { fixture.service.close().join() }
                coVerify(exactly = 1) { fixture.previousClient.close() }
                coVerify(exactly = 1) { fixture.replacementClient.connect(any()) }
                coVerify(exactly = 1) { fixture.replacementClient.close() }
            } finally {
                releaseCleanup.complete(Unit)
            }
        }

    private fun withConnectedService(block: suspend (Fixture) -> Unit) = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
        val previousClient = mockClient()
        val replacementClient = mockClient()
        val clientsCreated = AtomicInteger()
        val service = spyk(
            MCPClientService(scope, clientFactory = {
                when (clientsCreated.incrementAndGet()) {
                    1 -> previousClient
                    2 -> replacementClient
                    else -> error("Unexpected additional MCP client")
                }
            }),
            recordPrivateCalls = true,
        )
        try {
            service.connect(listOf(MCPServerConfig("previous", "http://previous.example/mcp")))
            block(Fixture(scope, service, previousClient, replacementClient, clientsCreated))
        } finally {
            withTimeout(5.seconds) { service.close().join() }
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }

    private fun mockClient(): Client = mockk<Client>().also { client ->
        coEvery { client.connect(any()) } just Runs
        coEvery { client.listTools(any()) } returns ListToolsResult(emptyList())
        coEvery { client.close() } just Runs
    }

    private data class Fixture(
        val scope: CoroutineScope,
        val service: MCPClientService,
        val previousClient: Client,
        val replacementClient: Client,
        val clientsCreated: AtomicInteger,
    )
}
