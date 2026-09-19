import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.render.TextReportRenderer

evaluationDependsOn(":webui")

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.4.20"
    kotlin("kapt")
    id("com.gradleup.shadow") version "9.6.1"
    id("com.github.jk1.dependency-license-report") version "3.1.4"
    application
}

version = "1.2.0"

val ktorVersion = "3.5.2"
val daggerVersion = "2.60.1"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor
    implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-host-common-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-body-limit-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
    implementation("io.ktor:ktor-server-config-yaml:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.6.3")

    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")

    // Ktor client logging
    implementation("io.ktor:ktor-client-logging:$ktorVersion")

    // Gemini SDK
    implementation("com.google.genai:google-genai:1.70.0") {
        exclude(group = "com.fasterxml.jackson.core", module = "jackson-core")
        exclude(group = "commons-codec", module = "commons-codec")
    }
    implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
    implementation("commons-codec:commons-codec:1.22.1")

    // OpenAI SDK
    implementation("com.openai:openai-java:4.63.1")

    // AI provider transport.  Requests must retain their native OkHttp Call so coroutine cancellation can abort it.
    implementation("com.squareup.okhttp3:okhttp:5.5.0")

    // MCP SDK
    implementation("io.modelcontextprotocol:kotlin-sdk:0.15.0")

    implementation("com.google.dagger:dagger:$daggerVersion")
    kapt("com.google.dagger:dagger-compiler:$daggerVersion")
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")

    // 通过原文位置识别 Markdown 结构，所有扩展使用同一固定版本。
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-footnotes:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")

    // Testing
    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}

application {
    mainClass.set("com.unscientificjszhai.tgp.ApplicationKt")
}

configure<LicenseReportExtension> {
    renderers = arrayOf(TextReportRenderer("backend-licenses.txt"))
}

val createLicenses by tasks.registering {
    group = "build"
    description = "Merges backend and frontend licenses"

    val backendLicenseFile = layout.buildDirectory.file("reports/dependency-license/backend-licenses.txt")
    val frontendLicenseFile = project(":webui").layout.buildDirectory.file("license/frontend-licenses.txt")
    val outputFile = layout.buildDirectory.file("reports/dependency-license/licenses.txt")

    dependsOn("generateLicenseReport")
    dependsOn(":webui:generateLicenses")

    inputs.files(backendLicenseFile, frontendLicenseFile)
    outputs.file(outputFile)

    doLast {
        val backendContent = if (backendLicenseFile.get().asFile.exists()) {
            backendLicenseFile.get().asFile.readText()
        } else {
            ""
        }

        val frontendContent = if (frontendLicenseFile.get().asFile.exists()) {
            frontendLicenseFile.get().asFile.readText()
        } else {
            ""
        }

        outputFile.get().asFile.writeText(backendContent + "\n\n" + frontendContent)
    }
}

val isPackagingTaskRequested = gradle.startParameter.taskNames.any {
    val name = it.lowercase()
    name.contains("build") || name.contains("assemble") ||
            name.contains("jar") || name.contains("shadowjar") ||
            name.contains("dist") || name.substringAfterLast(':') in setOf("check", "verifypackagedserialization")
}

val processFrontendResources by tasks.registering(Copy::class) {
    group = "build"
    description = "Assembles frontend resources and licenses for packaging"

    if (isPackagingTaskRequested) {
        dependsOn(createLicenses)
        dependsOn(project(":webui").tasks.named("npmBuild"))

        from(project(":webui").layout.projectDirectory.dir("dist")) {
            into("static")
        }
        from(layout.buildDirectory.file("reports/dependency-license/licenses.txt")) {
            into("licenses")
        }
    }

    into(layout.buildDirectory.dir("frontend-resources"))
}

tasks.named<Jar>("jar") {
    dependsOn(processFrontendResources)
    from(processFrontendResources.map { it.destinationDir })
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("TelegramWebHookProxy")
    archiveVersion.set(version.toString())
    archiveClassifier.set("all")

    // Shadow 9 的 Kotlin 模块元数据转换器需要收到每个同名输入。
    filesMatching("META-INF/*.kotlin_module") {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    failOnDuplicateEntries.set(true)

    dependsOn(processFrontendResources)
    from(processFrontendResources.map { it.destinationDir })
}

val verifyPackagedSerialization by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Checks generic response serialization using the actual Shadow Jar"
    val packagedJar = tasks.named<ShadowJar>("shadowJar")
    dependsOn(tasks.named("testClasses"), packagedJar)
    classpath(sourceSets["test"].output.classesDirs, packagedJar.flatMap { it.archiveFile })
    mainClass.set("com.unscientificjszhai.tgp.PackagedSerializationProbeKt")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.named("check") {
    dependsOn(verifyPackagedSerialization)
}
