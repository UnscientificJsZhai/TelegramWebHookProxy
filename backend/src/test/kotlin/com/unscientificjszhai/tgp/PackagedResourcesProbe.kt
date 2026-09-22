package com.unscientificjszhai.tgp

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** 在隔离的工作目录中启动正式 Jar 的应用模块，验证类路径资源及实际 HTTP 托管。 */
object PackagedResourcesProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val index = packagedResource("static/index.html")
        val licenses = packagedResource("licenses/licenses.txt")
        check(licenses.isNotEmpty()) { "Packaged licenses are empty" }
        val assets = Regex("(?:src|href)=\"/(assets/[^\"]+)\"")
            .findAll(index.toString(Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
        check(assets.isNotEmpty()) { "Packaged index does not reference any frontend assets" }

        val server = embeddedServer(Netty, host = "127.0.0.1", port = 0) { module() }
        try {
            server.start(wait = false)
            val port = runBlocking { server.engine.resolvedConnectors().single().port }
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build().use { client ->
                fun assertResponse(path: String, expected: ByteArray) {
                    val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build()
                    val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
                    check(response.statusCode() == 200) { "$path returned HTTP ${response.statusCode()}" }
                    check(response.body().contentEquals(expected)) { "$path did not serve the packaged resource" }
                }

                assertResponse("/", index)
                assertResponse("/settings", index)
                assets.forEach { asset -> assertResponse("/$asset", packagedResource("static/$asset")) }
                assertResponse("/license", licenses)
            }
            println("Packaged frontend and license hosting: PASS")
        } finally {
            server.stop(1_000, 5_000)
        }
    }

    private fun packagedResource(path: String): ByteArray {
        val resource = checkNotNull(javaClass.classLoader.getResource(path)) { "Missing packaged resource: $path" }
        check(resource.protocol == "jar") { "$path must be loaded from the packaged Jar" }
        return resource.readBytes()
    }
}
