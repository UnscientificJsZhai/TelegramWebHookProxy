import com.github.gradle.node.npm.task.NpmTask
import com.github.gradle.node.npm.task.NpxTask

plugins {
    id("com.github.node-gradle.node")
}

node {
    version.set("22.12.0")
    npmVersion.set("10.2.4")
    download.set(true)
}

tasks.register<NpmTask>("npmBuild") {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
}

tasks.register<NpmTask>("clean") {
    args.set(listOf("run", "clean"))
}

val generateLicenses by tasks.registering(NpxTask::class) {
    group = "documentation"
    description = "Generates OSS license file for frontend dependencies"

    val licenseDir = layout.buildDirectory.dir("license")

    command.set("license-checker")

    args.set(listOf(
        "--production",
        "--customPath", layout.projectDirectory.file("license-checker.json").asFile.absolutePath,
        "--csv",
        "--out", licenseDir.get().file("frontend-licenses.txt").asFile.absolutePath
    ))

    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir(licenseDir)

    dependsOn(tasks.npmInstall)
}