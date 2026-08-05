package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.di.AppModule
import com.unscientificjszhai.tgp.di.DaggerAppComponent
import com.unscientificjszhai.tgp.modules.apiModule
import com.unscientificjszhai.tgp.modules.installApiErrorPages
import com.unscientificjszhai.tgp.modules.messagePollerModule
import com.unscientificjszhai.tgp.modules.skillAPIModule
import com.unscientificjszhai.tgp.modules.taskSchedulerModule
import com.unscientificjszhai.tgp.service.BotCommandReconciler
import com.unscientificjszhai.tgp.service.MessagePoller
import com.unscientificjszhai.tgp.service.TelegramService
import com.unscientificjszhai.tgp.service.ai.TaskSchedulerService
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import io.ktor.util.AttributeKey
import java.util.concurrent.atomic.AtomicBoolean

private val applicationStopOrchestratorKey = AttributeKey<AtomicBoolean>("application-stop-orchestrator")

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
 * 此方法会从同一 [AppComponent] 取得轮询器、调度器、Telegram 与 AI 代理，并注册唯一的
 * [ApplicationStopPreparing] 停止编排器。停止时先关闭三个 worker 的准入并等待其所有子协程结束，随后才
 * 关闭 Telegram 和等待 AI 代理终态，因而关闭顺序不依赖模块监听器的订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 */
fun Application.module() {
    val appComponent: AppComponent = DaggerAppComponent.factory().create(AppModule(this))
    val messagePoller = appComponent.messagePoller
    val taskSchedulerService = appComponent.taskSchedulerService
    val botCommandReconciler = appComponent.botCommandReconciler
    val telegramService = appComponent.telegramService
    val agentService = appComponent.agentService

    botCommandReconciler.start()
    registerApplicationStopCleanup(
        messagePoller,
        taskSchedulerService,
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

    apiModule(appComponent)
    skillAPIModule(appComponent)
    messagePollerModule(appComponent)
    taskSchedulerModule(appComponent)

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
 * [TaskSchedulerService.requestStop] 和 [BotCommandReconciler.requestStop]，再通过 `runBlocking` 与
 * [NonCancellable] 等待三个 worker 拥有的全部协程终态；仅在这之后关闭 [TelegramService] 并等待
 * [AgentService.close] 返回的任务。重复停止事件和重复注册均只执行一次，且不会依赖模块订阅顺序。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param messagePoller 应先关闭准入并等待停止的 Telegram 轮询器。
 * @param taskSchedulerService 应先关闭准入并等待停止的定时任务调度器。
 * @param botCommandReconciler 应先关闭准入并等待停止的 Telegram 命令协调器。
 * @param telegramService 应用停止时应关闭的 Telegram 服务。
 * @param agentService 应用停止时应关闭并等待清理完成的 AI 代理服务。
 */
internal fun Application.registerApplicationStopCleanup(
    messagePoller: MessagePoller,
    taskSchedulerService: TaskSchedulerService,
    botCommandReconciler: BotCommandReconciler,
    telegramService: TelegramService,
    agentService: AgentService,
) {
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
            messagePoller.requestStop()
            taskSchedulerService.requestStop()
            botCommandReconciler.requestStop()
            runBlocking {
                withContext(NonCancellable) {
                    messagePoller.awaitStopped()
                    taskSchedulerService.awaitStopped()
                    botCommandReconciler.awaitStopped()
                    telegramService.close()
                    agentService.close()?.join()
                }
            }
        }
    }
}
