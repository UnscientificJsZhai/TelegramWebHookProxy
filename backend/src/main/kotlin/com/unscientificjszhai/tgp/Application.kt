package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.di.AppModule
import com.unscientificjszhai.tgp.di.DaggerAppComponent
import com.unscientificjszhai.tgp.modules.apiModule
import com.unscientificjszhai.tgp.modules.installApiErrorPages
import com.unscientificjszhai.tgp.modules.messagePollerModule
import com.unscientificjszhai.tgp.modules.skillAPIModule
import com.unscientificjszhai.tgp.modules.taskSchedulerModule
import com.unscientificjszhai.tgp.service.TelegramService
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
 * 此方法会注册一次 `ApplicationStopped` 监听器，以关闭
 * `TelegramService` 和当前 AI 代理；代理关闭会等待其
 * 异步资源清理完成。应由应用生命周期只调用一次。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 */
fun Application.module() {
    val appComponent: AppComponent = DaggerAppComponent.factory().create(AppModule(this))
    val telegramService = appComponent.telegramService
    val agentService = appComponent.agentService

    registerApplicationStopCleanup(telegramService, agentService)

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
 * 注册应用停止时的 Telegram 与 AI 代理资源清理。
 *
 * 代理服务的异步关闭任务会在停止事件返回前完成，避免 Agent 组件持有的 MCP 连接在应用停止后继续运行。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param telegramService 应用停止时应关闭的 Telegram 服务。
 * @param agentService 应用停止时应关闭并等待清理完成的 AI 代理服务。
 */
internal fun Application.registerApplicationStopCleanup(
    telegramService: TelegramService,
    agentService: AgentService,
) {
    monitor.subscribe(ApplicationStopped) {
        telegramService.close()
        runBlocking {
            withContext(NonCancellable) {
                agentService.close()?.join()
            }
        }
    }
}
