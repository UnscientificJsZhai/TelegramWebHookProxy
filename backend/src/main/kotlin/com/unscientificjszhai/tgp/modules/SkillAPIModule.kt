package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.isValidSkillId
import com.unscientificjszhai.tgp.repository.SkillStorageIsolationException
import com.unscientificjszhai.tgp.utils.ResourceLimits
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.plugins.bodylimit.*
import kotlinx.coroutines.CancellationException

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
                try {
                    val page = call.strictDecimalQueryParameter("page", 1, 1..Int.MAX_VALUE) ?: return@get
                    val size = call.strictDecimalQueryParameter("size", 10, 1..50) ?: return@get
                    call.respond(skillRepository.getAllSkills(page, size))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SkillStorageIsolationException) {
                    call.respondSkillStorageUnavailable()
                } catch (_: IllegalArgumentException) {
                    call.respondSkillInputError("分页参数不合法")
                }
            }
            route("") {
                install(RequestBodyLimit) { bodyLimit { ResourceLimits.SKILL_REQUEST_BYTES } }
                post {
                    try {
                        val skill = call.receive<Skill>()
                        skillRepository.saveSkill(skill)
                        call.respond(HttpStatusCode.OK)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: SkillStorageIsolationException) {
                        call.respondSkillStorageUnavailable()
                    } catch (_: IllegalArgumentException) {
                        call.respond(HttpStatusCode.BadRequest, "技能字段或数量超过限制")
                    }
                }
            }
            delete("/{id}") {
                val id = call.parameters["id"]
                if (call.request.queryParameters.names().isNotEmpty() || id == null || !isValidSkillId(id)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid skill ID")
                    return@delete
                }
                try {
                    skillRepository.deleteSkill(id)
                    call.respond(HttpStatusCode.OK)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SkillStorageIsolationException) {
                    call.respondSkillStorageUnavailable()
                }
            }
        }
    }
}

private suspend fun ApplicationCall.strictDecimalQueryParameter(
    name: String,
    defaultValue: Int,
    acceptedRange: IntRange,
): Int? {
    val values = request.queryParameters.getAll(name) ?: return defaultValue
    val value = values.singleOrNull()
    val parsed = value?.takeIf { it.matches(DECIMAL_INTEGER) }?.toLongOrNull()
    if (parsed == null || parsed !in acceptedRange.first.toLong()..acceptedRange.last.toLong()) {
        respondSkillInputError("分页参数不合法")
        return null
    }
    return parsed.toInt()
}

private val DECIMAL_INTEGER = Regex("[0-9]+")

/** 在不泄露隔离存储细节的前提下响应技能存储不可用。 */
private suspend fun ApplicationCall.respondSkillStorageUnavailable() {
    respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "技能存储不可用。"))
}

private suspend fun ApplicationCall.respondSkillInputError(message: String) {
    respond(HttpStatusCode.BadRequest, mapOf("error" to message))
}
