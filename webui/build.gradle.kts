plugins {
    id("com.github.node-gradle.node")
}

node {
    version.set("22.12.0")
    npmVersion.set("10.2.4")
    download.set(true)
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("npmBuild") {
    dependsOn(tasks.named("npmInstall"))
    args.set(listOf("run", "build"))
}

tasks.register<com.github.gradle.node.npm.task.NpmTask>("clean") {
    args.set(listOf("run", "clean"))
}