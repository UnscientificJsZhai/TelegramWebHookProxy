package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.Skill
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/**
 * 注册技能的分页查询、新增和删除 HTTP API 路由。
 *
 * 该方法会向接收者追加路由，并在处理请求时读写技能仓库。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param appComponent 提供技能仓库的应用级组件。
 */
fun Application.skillAPIModule(appComponent: AppComponent) {
    val skillRepository = appComponent.skillRepository

    routing {
        route("/api/skills") {
            get {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 10
                call.respond(skillRepository.getAllSkills(page, size))
            }
            post {
                val skill = call.receive<Skill>()
                skillRepository.saveSkill(skill)
                call.respond(HttpStatusCode.OK)
            }
            delete("/{id}") {
                val id = call.parameters["id"]
                if (id == null) {
                    call.respond(HttpStatusCode.BadRequest, "Missing skill ID")
                    return@delete
                }
                skillRepository.deleteSkill(id)
                call.respond(HttpStatusCode.OK)
            }
        }
    }
}
