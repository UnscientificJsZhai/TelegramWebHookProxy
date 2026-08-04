package com.unscientificjszhai.tgp.modules

import com.unscientificjszhai.tgp.di.AppComponent
import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import com.unscientificjszhai.tgp.models.SkillStatus
import com.unscientificjszhai.tgp.models.isValidSkillId
import com.unscientificjszhai.tgp.repository.SkillNotFoundException
import com.unscientificjszhai.tgp.repository.SkillRepository
import com.unscientificjszhai.tgp.repository.SkillRevisionConflictException
import com.unscientificjszhai.tgp.repository.SkillStateConflictException
import com.unscientificjszhai.tgp.repository.SkillStorageIsolationException
import com.unscientificjszhai.tgp.utils.ResourceLimits
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.bodylimit.RequestBodyLimit
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

/**
 * 注册管理端的技能查询、草稿编辑、批准、撤销和删除 HTTP API 路由。
 *
 * 模型工具不调用这些路由，只能创建待审批草稿。该方法会向接收者追加路由，并在处理请求时读写技能仓库。
 *
 * @receiver 已创建且尚未停止的 Ktor 应用实例。
 * @param appComponent 提供技能仓库的应用级组件。
 */
fun Application.skillAPIModule(appComponent: AppComponent) {
    val skillRepository = appComponent.skillRepository

    routing {
        route("/api/skills") {
            install(RequestBodyLimit) { bodyLimit { ResourceLimits.SKILL_REQUEST_BYTES } }
            get {
                try {
                    val page = call.strictDecimalQueryParameter("page", 1, 1..Int.MAX_VALUE) ?: return@get
                    val size = call.strictDecimalQueryParameter("size", 10, 1..50) ?: return@get
                    call.respond(skillRepository.getAllSkills(page, size).toApiResponse())
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SkillStorageIsolationException) {
                    call.respondSkillStorageUnavailable()
                } catch (_: IllegalArgumentException) {
                    call.respondSkillInputError("分页参数不合法")
                }
            }
            post {
                try {
                    val request = call.receive<ManagedSkillRequest>()
                    call.respond(
                        skillRepository.saveManagedSkill(
                            id = request.id,
                            description = request.description,
                            content = request.content,
                            expectedRevision = request.revision,
                        ).toApiResponse(),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: SkillStorageIsolationException) {
                    call.respondSkillStorageUnavailable()
                } catch (_: SkillNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "技能不存在。"))
                } catch (_: SkillRevisionConflictException) {
                    call.respondSkillConflict("技能已被修改，请刷新后重试。")
                } catch (_: IllegalArgumentException) {
                    call.respondSkillInputError("技能字段、版本或数量超过限制")
                }
            }

            route("/{id}") {
                post("/approve") {
                    call.handleSkillTransition(skillRepository, approve = true)
                }
                post("/revoke") {
                    call.handleSkillTransition(skillRepository, approve = false)
                }
                delete {
                    val id = call.validSkillPathId() ?: return@delete
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
}

private suspend fun ApplicationCall.handleSkillTransition(skillRepository: SkillRepository, approve: Boolean) {
    val id = validSkillPathId() ?: return
    try {
        val request = receive<SkillTransitionRequest>()
        val skill = if (approve) {
            skillRepository.approveSkill(id, request.revision)
        } else {
            skillRepository.revokeSkill(id, request.revision)
        }
        respond(skill.toApiResponse())
    } catch (e: CancellationException) {
        throw e
    } catch (_: SkillStorageIsolationException) {
        respondSkillStorageUnavailable()
    } catch (_: SkillNotFoundException) {
        respond(HttpStatusCode.NotFound, mapOf("error" to "技能不存在。"))
    } catch (_: SkillRevisionConflictException) {
        respondSkillConflict("技能已被修改，请刷新后重试。")
    } catch (_: SkillStateConflictException) {
        respondSkillConflict("技能当前状态不允许此操作，请刷新后重试。")
    } catch (_: IllegalArgumentException) {
        respondSkillInputError("技能标识或版本不合法")
    }
}

private suspend fun ApplicationCall.validSkillPathId(): String? {
    val id = parameters["id"]
    if (request.queryParameters.names().isNotEmpty() || id == null || !isValidSkillId(id)) {
        respondSkillInputError("技能标识不合法")
        return null
    }
    return id
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

@Serializable
private data class ManagedSkillRequest(
    val id: String? = null,
    val description: String,
    val content: String,
    val revision: Long? = null,
)

@Serializable
private data class SkillTransitionRequest(
    val revision: Long,
)

/** 在不泄露隔离存储细节的前提下响应技能存储不可用。 */
private suspend fun ApplicationCall.respondSkillStorageUnavailable() {
    respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "技能存储不可用。"))
}

private suspend fun ApplicationCall.respondSkillConflict(message: String) {
    respond(HttpStatusCode.Conflict, mapOf("error" to message))
}

/** 面向 HTTP 管理端的完整技能表示；审批字段不使用默认值，确保响应始终包含它们。 */
@Serializable
private data class SkillApiResponse(
    val id: String,
    val description: String,
    val content: String,
    val status: SkillStatus,
    val revision: Long,
)

private fun Skill.toApiResponse(): SkillApiResponse = SkillApiResponse(
    id = id,
    description = description,
    content = content,
    status = status,
    revision = revision,
)

private fun PageResult<Skill>.toApiResponse(): PageResult<SkillApiResponse> =
    PageResult(total = total, items = items.map(Skill::toApiResponse))

private suspend fun ApplicationCall.respondSkillInputError(message: String) {
    respond(HttpStatusCode.BadRequest, mapOf("error" to message))
}
