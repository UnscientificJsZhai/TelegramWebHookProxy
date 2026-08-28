package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.di.AppModule
import com.unscientificjszhai.tgp.di.DaggerAppComponent
import com.unscientificjszhai.tgp.modules.*
import com.unscientificjszhai.tgp.service.BotCommandReconciler
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.ScheduledTaskWorker
import com.unscientificjszhai.tgp.service.ai.agent.AgentService
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private val applicationStopOrchestratorKey = AttributeKey<AtomicBoolean>("application-stop-orchestrator")
private val applicationLogger = LoggerFactory.getLogger("ApplicationLifecycle")
private val defaultApplicationShutdownTimeout = 20.seconds

private sealed interface ApplicationShutdownStep<out T> {
    /**
     * 在停止预算内完成的步骤。
     *
     * @property value 停止步骤返回的值。
     */
    data class Completed<T>(val value: T) : ApplicationShutdownStep<T>
    data object Failed : ApplicationShutdownStep<Nothing>
    data object NotAwaited : ApplicationShutdownStep<Nothing>
}

private fun requestApplicationWorkerStop(component: String, requestStop: () -> Unit) {
    try {
        requestStop()
    } catch (e: Throwable) {
        applicationLogger.warn(
            "Application shutdown step failed; stage=request-stop, component={}. Continuing.",
            component,
            e,
        )
    }
}

private fun remainingShutdownBudget(startedAt: TimeMark, timeout: Duration): Duration =
    (timeout - startedAt.elapsedNow()).coerceAtLeast(ZERO)

/** 在独立的 IO waiter 中执行一个停止步骤，避免监听器线程被不可取消或阻塞的清理操作占用。 */
private suspend fun <T> awaitApplicationShutdownStep(
    startedAt: TimeMark,
    timeout: Duration,
    stage: String,
    component: String,
    invokeWithoutBudget: Boolean = false,
    action: suspend () -> T,
): ApplicationShutdownStep<T> {
    val remaining = remainingShutdownBudget(startedAt, timeout)
    if (remaining <= ZERO && !invokeWithoutBudget) {
        applicationLogger.warn(
            "Application shutdown step was not awaited because its overall deadline expired; stage={}, component={}. Continuing.",
            stage,
            component,
        )
        return ApplicationShutdownStep.NotAwaited
    }

    val waiterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val waiter = waiterScope.async(start = CoroutineStart.ATOMIC) { action() }
    if (remaining <= ZERO) {
        applicationLogger.warn(
            "Application shutdown step was not awaited because its overall deadline expired; stage={}, component={}. Continuing.",
            stage,
            component,
        )
        // ATOMIC ensures a synchronous close has one best-effort chance to start even though it is no longer awaited.
        waiter.cancel()
        waiterScope.cancel()
        return ApplicationShutdownStep.NotAwaited
    }

    return try {
        ApplicationShutdownStep.Completed(withTimeout(remaining) { waiter.await() })
    } catch (_: TimeoutCancellationException) {
        applicationLogger.warn(
            "Application shutdown step timed out; stage={}, component={}. Cancelling its waiter and continuing.",
            stage,
            component,
        )
        waiter.cancel()
        ApplicationShutdownStep.NotAwaited
    } catch (e: Throwable) {
        applicationLogger.warn(
            "Application shutdown step failed; stage={}, component={}. Continuing.",
            stage,
            component,
            e,
        )
        ApplicationShutdownStep.Failed
    } finally {
        // Never join here: a blocking or non-cooperative dependency must not consume the remainder of the shutdown budget.
        waiterScope.cancel()
    }
}

/**
 * 启动监听 `0.0.0.0:10178` 的仅 HTTP/1 Netty 服务器。
 *
 * 入站连接数、请求并发数、HTTP 编码大小及读取阶段均受限，以避免慢速连接长期耗用服务资源。
 * 一段时间内没有任何入站字节的连接会被关闭，包括已完成请求后保持 keep-alive 的连接。
 * 此方法会阻塞当前线程，直至服务器停止。
 */
fun main() {
    embeddedServer(
        factory = Netty,
        rootConfig = serverConfig {
            module { module() }
        },
        configure = {
            connector {
                host = "0.0.0.0"
                port = 10178
            }
            configureHttpIngressProtection()
        },
    )
        .start(wait = true)
}

/**
 * 配置应用的序列化、依赖注入、业务路由和静态资源路由。
 *
 * 此方法会从同一 [AppComponent] 取得轮询器、定时任务 worker、Telegram 与 AI 代理，并注册唯一的
 * [ApplicationStopPreparing] 停止编排器。停止时先同步关闭三个 worker 的准入；在总停止预算内再等待其子协程
 * 结束，并串行关闭 Telegram 和 AI 代理。因而关闭顺序不依赖模块监听器的订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 */
fun Application.module() {
    val appComponent: AppComponent = DaggerAppComponent.factory().create(AppModule(this))
    val messagePoller = appComponent.messagePoller
    val scheduledTaskWorker = appComponent.scheduledTaskWorker
    val botCommandReconciler = appComponent.botCommandReconciler
    val telegramService = appComponent.telegramService
    val agentService = appComponent.agentService

    botCommandReconciler.start()
    registerApplicationStopCleanup(
        messagePoller,
        scheduledTaskWorker,
        botCommandReconciler,
        telegramService,
        agentService,
    )

    install(ContentNegotiation) {
        json(
            Json {
                prettyPrint = true
                isLenient = true
                explicitNulls = false
                ignoreUnknownKeys = true
            },
        )
    }
    installApiErrorPages()
    installProtocolUpgradeRejection()

    apiModule(appComponent.settingsChangeCoordinator, telegramService)
    skillAPIModule(appComponent.skillRepository)
    messagePollerModule(messagePoller)
    taskSchedulerModule(scheduledTaskWorker)

    routing {
        get("/license") {
            val resource = this@module.javaClass.classLoader.getResourceAsStream("licenses/licenses.txt")
            if (resource != null) {
                call.respondText(resource.bufferedReader().readText())
            } else {
                call.respondText("License file not found.", status = HttpStatusCode.NotFound)
            }
        }

        singlePageApplication {
            staticResources("/", "static") {
                default("index.html")
            }
        }
    }
}

/**
 * 注册应用停止时唯一的资源关闭编排器。
 *
 * [ApplicationStopPreparing] 到达时，编排器先同步调用 [MessagePoller.requestStop]、
 * [ScheduledTaskWorker.requestStop] 和 [BotCommandReconciler.requestStop] 关闭全部 worker 准入，再在从停止
 * 请求前开始计算的总预算内依次等待 worker、关闭 Telegram、关闭 Agent，并等待 Agent 终态。等待和关闭均在与
 * 监听器解耦的 IO waiter 中隔离；正常情况下 Telegram 关闭返回后才启动 Agent 关闭。Telegram 关闭超时后会记录
 * 固定的顺序降级日志并独立启动 Agent 关闭，避免一个同步阻塞的客户端阻止后续资源清理。预算耗尽后仍会尽力触发
 * Telegram 与 Agent 的关闭，但不再等待；此降级路径不保证两者实际调用或完成的先后顺序。三个同步 `requestStop`
 * 调用本身不受此预算限制。重复停止事件和重复注册均只执行一次，且不会依赖模块订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param messagePoller 应先关闭准入并等待停止的 Telegram 轮询器。
 * @param scheduledTaskWorker 应先关闭准入并等待停止的定时任务 worker。
 * @param botCommandReconciler 应先关闭准入并等待停止的 Telegram 命令协调器。
 * @param telegramService 应用停止时应关闭的 Telegram 服务。
 * @param agentService 应用停止时应关闭并等待清理完成的 AI 代理服务。
 * @param shutdownTimeout 从开始关闭 worker 准入前计算的总停止预算；必须非负，测试可注入较短时长。
 */
internal fun Application.registerApplicationStopCleanup(
    messagePoller: MessagePoller,
    scheduledTaskWorker: ScheduledTaskWorker,
    botCommandReconciler: BotCommandReconciler,
    telegramService: TelegramService,
    agentService: AgentService,
    shutdownTimeout: Duration = defaultApplicationShutdownTimeout,
) {
    require(shutdownTimeout >= ZERO) { "shutdownTimeout must not be negative." }
    synchronized(this) {
        if (attributes.getOrNull(applicationStopOrchestratorKey) != null) {
            return
        }
        val stopping = AtomicBoolean(false)
        attributes.put(applicationStopOrchestratorKey, stopping)
        monitor.subscribe(ApplicationStopPreparing) {
            if (!stopping.compareAndSet(false, true)) {
                return@subscribe
            }
            val startedAt = TimeSource.Monotonic.markNow()
            requestApplicationWorkerStop("message-poller") { messagePoller.requestStop() }
            requestApplicationWorkerStop("task-scheduler") { scheduledTaskWorker.requestStop() }
            requestApplicationWorkerStop("bot-command-reconciler") { botCommandReconciler.requestStop() }
            runBlocking {
                awaitApplicationShutdownStep(
                    startedAt,
                    shutdownTimeout,
                    stage = "await-worker",
                    component = "message-poller",
                ) { messagePoller.awaitStopped() }
                awaitApplicationShutdownStep(
                    startedAt,
                    shutdownTimeout,
                    stage = "await-worker",
                    component = "task-scheduler",
                ) { scheduledTaskWorker.awaitStopped() }
                awaitApplicationShutdownStep(
                    startedAt,
                    shutdownTimeout,
                    stage = "await-worker",
                    component = "bot-command-reconciler",
                ) { botCommandReconciler.awaitStopped() }
                val telegramClose = awaitApplicationShutdownStep(
                    startedAt,
                    shutdownTimeout,
                    stage = "close",
                    component = "telegram",
                    invokeWithoutBudget = true,
                ) { telegramService.close() }
                if (telegramClose is ApplicationShutdownStep.NotAwaited) {
                    applicationLogger.warn(
                        "Application shutdown close order degraded; stage=close, component=agent, predecessor=telegram. Starting independently.",
                    )
                }
                val agentClose = awaitApplicationShutdownStep(
                    startedAt,
                    shutdownTimeout,
                    stage = "close",
                    component = "agent",
                    invokeWithoutBudget = true,
                ) { agentService.close() }
                if (agentClose is ApplicationShutdownStep.Completed && agentClose.value != null) {
                    awaitApplicationShutdownStep(
                        startedAt,
                        shutdownTimeout,
                        stage = "join-close-job",
                        component = "agent",
                    ) { agentClose.value.join() }
                }
            }
        }
    }
}
