package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.di.AppModule
import com.unscientificjszhai.tgp.di.DaggerAppComponent
import com.unscientificjszhai.tgp.modules.apiModule
import com.unscientificjszhai.tgp.modules.messagePollerModule
import com.unscientificjszhai.tgp.modules.skillAPIModule
import com.unscientificjszhai.tgp.modules.taskSchedulerModule
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json

/**
 * 启动监听 `0.0.0.0:10178` 的 Netty HTTP 服务器。
 *
 * 此方法会阻塞当前线程，直至服务器停止。
 */
fun main() {
    embeddedServer(Netty, port = 10178, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

/**
 * 配置应用的序列化、依赖注入、业务路由和静态资源路由。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 */
fun Application.module() {
    val appComponent: AppComponent = DaggerAppComponent.factory().create(AppModule(this))

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
