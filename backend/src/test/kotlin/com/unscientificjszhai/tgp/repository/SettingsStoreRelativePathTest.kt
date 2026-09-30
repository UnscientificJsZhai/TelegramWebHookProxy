package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AppSettings
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsStoreRelativePathTest {
    @Test
    fun `default relative path saves settings in a fresh working directory`() {
        val workingDirectory = createTempDirectory("settings-store-relative-path")
        try {
            val classpath = System.getProperty("java.class.path")
            val java = Path.of(System.getProperty("java.home"), "bin", "java")
            val process =
                ProcessBuilder(java.toString(), "-cp", classpath, SettingsStoreFreshDirectoryProbe::class.java.name)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .start()

            try {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS), "settings store probe timed out")
                assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
                assertTrue(Files.isRegularFile(workingDirectory.resolve("config/settings.json")))
            } finally {
                process.destroyForcibly()
            }
        } finally {
            workingDirectory.toFile().deleteRecursively()
        }
    }
}

internal object SettingsStoreFreshDirectoryProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = SettingsStore()
        check(store.load().settings == AppSettings())
        store.commit(AppSettings())
        check(store.load().settings == AppSettings())
    }
}
