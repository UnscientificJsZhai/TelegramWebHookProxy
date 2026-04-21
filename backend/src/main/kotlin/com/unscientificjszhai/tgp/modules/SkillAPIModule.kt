package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.Skill
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.skillAPIModule(appComponent: AppComponent) {
    val skillRepository = appComponent.skillRepository

    routing {
        route("/api/skills") {
            get {
                call.respond(skillRepository.getAllSkills())
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
