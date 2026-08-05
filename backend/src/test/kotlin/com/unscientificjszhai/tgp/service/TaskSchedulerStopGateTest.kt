package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.LoopMode
import com.unscientificjszhai.tgp.repository.SettingsRepository
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import javax.inject.Provider
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 定时任务服务终态准入门测试设计。 */
class TaskSchedulerStopGateTest {
    private val temporaryDirectory = createTempDirectory("task-scheduler-stop-gate").toFile()

    @AfterTest
    fun cleanUp() {
        temporaryDirectory.deleteRecursively()
    }

    /** 验证关闭后无法重启、新建任务或以内部测试切入点扫描执行。 */
    @Test
    fun `stopped scheduler rejects restart creation and scan admission`() = runBlocking {
        val telegram = mockk<TelegramService>()
        val agent = mockk<AgentService>()
        val settings = SettingsRepository.forTesting(
            File(temporaryDirectory, "settings.json"),
            ModelSwitchBarrier(),
        )
        val scheduler = TaskSchedulerService(
            CoroutineScope(EmptyCoroutineContext),
            telegram,
            Provider<AgentService> { agent },
            settings,
            File(temporaryDirectory, "schedule.json"),
        )
        val existingTask = scheduler.createTask("must not run", 0L, LoopMode.ONCE, "chat")

        scheduler.requestStop()
        scheduler.requestStop()
        scheduler.start()
        assertFailsWith<IllegalStateException> {
            scheduler.createTask("must not be created", 0L, LoopMode.ONCE, "chat")
        }
        scheduler.scanAndExecute()
        scheduler.awaitStopped()
        scheduler.closeAndJoin()

        assertEquals(listOf(existingTask), scheduler.listTasks().map { it.id })
        coVerify(exactly = 0) { agent.sendMessage(any<String>()) }
    }
}
