import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

evaluationDependsOn(":webui")

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

version = "1.0.0"

val ktorVersion = "3.3.1"

repositories {
    mavenCentral()
}

dependencies {
    // Ktor
    implementation("io.ktor:ktor-server-core-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-netty-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-host-common-jvm:${ktorVersion}")
    implementation("io.ktor:ktor-server-config-yaml:${ktorVersion}")
    implementation("ch.qos.logback:logback-classic:1.5.19")

    implementation("io.ktor:ktor-client-core:${ktorVersion}")
    implementation("io.ktor:ktor-client-okhttp:${ktorVersion}")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")

    // Ktor client logging
    implementation("io.ktor:ktor-client-logging:$ktorVersion")
}

application {
    mainClass.set("com.unscientificjszhai.tgp.ApplicationKt")
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("TelegramWebHookProxy")
    archiveVersion.set(version.toString())
    archiveClassifier.set("all")
}

tasks.named("jar") {
    dependsOn(project(":webui").tasks.named("npmBuild"))
}

tasks.named<Copy>("processResources") {
    from(project(":webui").layout.projectDirectory.dir("dist")) {
        into("static")
    }
}
