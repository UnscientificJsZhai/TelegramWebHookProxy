package com.unscientificjszhai.tgp

import com.unscientificjszhai.tgp.models.PageResult
import com.unscientificjszhai.tgp.models.Skill
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.typeOf

/** 在只包含测试类和实际 Shadow Jar 的 JVM 中验证 Ktor 使用的泛型 serializer 查找路径。 */
fun main() {
    val pageSerializer = Json.serializersModule.serializer(typeOf<PageResult<Skill>>())
    val page = Json.encodeToString(pageSerializer, PageResult<Skill>(total = 0, items = emptyList()))
    check(Json.parseToJsonElement(page) == Json.parseToJsonElement("""{"total":0,"items":[]}"""))

    val errorSerializer = Json.serializersModule.serializer(typeOf<Map<String, String>>())
    val error = Json.encodeToString(errorSerializer, mapOf("error" to "invalid request"))
    check(Json.parseToJsonElement(error) == Json.parseToJsonElement("""{"error":"invalid request"}"""))
    println("Packaged generic response serialization: PASS")
}
