import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jk1.license.render.TextReportRenderer

evaluationDependsOn(":webui")

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.2.20"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    id("com.github.jk1.dependency-license-report") version "2.9"
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

configure<com.github.jk1.license.LicenseReportExtension> {
    renderers = arrayOf(TextReportRenderer("backend-licenses.txt"))
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("TelegramWebHookProxy")
    archiveVersion.set(version.toString())
    archiveClassifier.set("all")
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

tasks.named<Copy>("processResources") {
    dependsOn(createLicenses)
    dependsOn(project(":webui").tasks.named("npmBuild"))
    from(project(":webui").layout.projectDirectory.dir("dist")) {
        into("static")
    }
    from(layout.buildDirectory.file("reports/dependency-license/licenses.txt")) {
        into("licenses")
    }
}
