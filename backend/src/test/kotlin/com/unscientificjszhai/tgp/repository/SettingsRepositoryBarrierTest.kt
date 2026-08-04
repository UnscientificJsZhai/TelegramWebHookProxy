package com.unscientificjszhai.tgp.repository

import com.unscientificjszhai.tgp.models.AIProvider
import com.unscientificjszhai.tgp.models.AISettings
import com.unscientificjszhai.tgp.models.AppSettings
import com.unscientificjszhai.tgp.models.ProxySettings
import com.unscientificjszhai.tgp.models.ProxyType
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import com.unscientificjszhai.tgp.utils.AtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.DefaultAtomicJsonFileOperations
import com.unscientificjszhai.tgp.utils.ConfigJson
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 设置仓储与模型切换屏障协作的测试设计。
 */
class SettingsRepositoryBarrierTest {
    private val tempDirectory = createTempDirectory("settings-barrier-test").toFile()

    @AfterTest
    fun cleanUp() {
        tempDirectory.deleteRecursively()
    }

    /**
     * 验证会开启模型切换屏障的设置范围。
     *
     * 验证仅影响代理生命周期的设置变更会创建屏障代次。
     */
    @Test
    fun `only agent lifecycle settings open a model switch barrier`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        var settings = AppSettings(
            ai = AISettings(
                provider = AIProvider.OPENAI,
                openAiApiKey = "openai-key",
                agentEnabled = true,
                selectedModel = "gpt-first",
            ),
        )
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        val initialGeneration = barrier.latestPendingGeneration()
        barrier.complete(initialGeneration)

        settings = settings.copy(ai = settings.ai!!.copy(globalContext = "new context"))
        repository.saveSettings(settings)
        assertFalse(barrier.isSwitching)

        settings = settings.copy(ai = settings.ai!!.copy(selectedModel = "gpt-next"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(geminiApiKey = "unused-key"))
        repository.saveSettings(settings)
        assertFalse(barrier.isSwitching)

        settings = settings.copy(ai = settings.ai!!.copy(openAiBaseUrl = "https://example.invalid/v1"))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
        barrier.complete(barrier.latestPendingGeneration())

        settings = settings.copy(ai = settings.ai!!.copy(agentEnabled = false))
        repository.saveSettings(settings)
        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证无关保存对待处理屏障代次的传递设计。
     *
     * 验证最新设置快照会携带仍待处理的最高代次。
     */
    @Test
    fun `unrelated save carries an open generation to its latest settings snapshot`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        val initialSettings = AppSettings(
            ai = AISettings(provider = AIProvider.GEMINI, geminiApiKey = "key", agentEnabled = true),
        )
        repository.saveSettings(initialSettings)
        val firstUpdate = repository.settingsUpdateFlow.value
        val generation = firstUpdate.switchGeneration

        val latestSettings = initialSettings.copy(chatId = "new-chat-id")
        repository.saveSettings(latestSettings)
        val latestUpdate = repository.settingsUpdateFlow.value

        assertEquals(latestSettings, latestUpdate.settings)
        assertEquals(generation, latestUpdate.switchGeneration)
    }

    /**
     * 验证合并设置快照释放屏障代次的设计。
     *
     * 验证最新生命周期快照会释放被合并的较早代次。
     */
    @Test
    fun `latest lifecycle snapshot releases conflated earlier generations`() {
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(File(tempDirectory, "settings.json"), barrier)
        val firstSettings = AppSettings(
            ai = AISettings(
                provider = AIProvider.GEMINI,
                geminiApiKey = "key",
                agentEnabled = true,
            ),
        )

        repository.saveSettings(firstSettings)
        val firstGeneration = repository.settingsUpdateFlow.value.switchGeneration

        val latestSettings = firstSettings.copy(
            ai = firstSettings.ai!!.copy(selectedModel = "gemini-next"),
        )
        repository.saveSettings(latestSettings)
        val latestUpdate = repository.settingsUpdateFlow.value

        assertEquals(latestSettings, latestUpdate.settings)
        assertTrue(latestUpdate.switchGeneration!! > firstGeneration!!)

        // 模拟首次更新被 StateFlow 合并后，生命周期收集器只能观察到
        // 最新快照的情况。
        barrier.completeThrough(latestUpdate.switchGeneration)

        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证设置写入失败时的屏障回滚设计。
     *
     * 验证失败写入创建的屏障代次会被取消。
     */
    @Test
    fun `a failed settings write cancels its switch generation`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "settings.json")
        val repository = SettingsRepository.forTesting(configFile, barrier)

        tempDirectory.deleteRecursively()
        tempDirectory.writeText("not a directory")

        assertFailsWith<IOException> {
            repository.saveSettings(AppSettings(ai = AISettings(agentEnabled = true)))
        }
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证每次 Telegram token 实际变化都会递增独立代次，普通设置保存不会重启轮询生命周期。
     */
    @Test
    fun `telegram token generation records rapid restoration without unrelated changes`() {
        val repository = SettingsRepository.forTesting(File(tempDirectory, "token-generation.json"), ModelSwitchBarrier())
        val initialGeneration = repository.telegramTokenUpdateFlow.value.generation

        repository.saveSettings(AppSettings(telegramToken = "100:A"))
        val firstGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.saveSettings(AppSettings(telegramToken = ""))
        val emptyGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.saveSettings(AppSettings(telegramToken = "100:A"))
        val restoredGeneration = repository.telegramTokenUpdateFlow.value.generation
        repository.saveSettings(AppSettings(telegramToken = "100:A", chatId = "unchanged-token"))

        assertEquals(initialGeneration + 1, firstGeneration)
        assertEquals(firstGeneration + 1, emptyGeneration)
        assertEquals(emptyGeneration + 1, restoredGeneration)
        assertEquals(restoredGeneration, repository.telegramTokenUpdateFlow.value.generation)
    }

    /**
     * 验证非法代理会在创建屏障、写盘和发布设置流之前被拒绝。
     */
    @Test
    fun `invalid proxy save has no side effects`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "invalid-proxy-settings.json")
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val initialSettings = AppSettings(telegramToken = "100:original", chatId = "original-chat")
        repository.saveSettings(initialSettings)
        val originalContent = configFile.readText()
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value

        assertFailsWith<IllegalArgumentException> {
            repository.saveSettings(
                initialSettings.copy(proxy = ProxySettings("proxy.example.com", 65536, ProxyType.HTTP)),
            )
        }

        assertEquals(initialSettings, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertEquals(originalContent, configFile.readText())
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证未知历史代理类型不会丢弃其他设置，也不会重写原始配置文件。
     */
    @Test
    fun `unknown historical proxy type preserves the remaining settings without rewriting the file`() {
        val configFile = File(tempDirectory, "unknown-proxy-type.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"},"ai":{"provider":"OPENAI","openAiApiKey":"key","agentEnabled":true}}
            """.trimIndent()
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("100:token", repository.settingsFlow.value.telegramToken)
        assertEquals("chat", repository.settingsFlow.value.chatId)
        assertEquals(AIProvider.OPENAI, repository.settingsFlow.value.ai?.provider)
        assertEquals("key", repository.settingsFlow.value.ai?.openAiApiKey)
        assertEquals(null, repository.settingsFlow.value.proxy)
        assertTrue(repository.hasHistoricalInvalidProxy)
        assertEquals(originalContent, configFile.readText())
    }

    /**
     * 验证历史非法代理必须由完整设置显式替换为合法代理，复制当前设置的保存没有副作用。
     */
    @Test
    fun `historical invalid proxy rejects copied settings until a valid proxy explicitly resolves it`() {
        val barrier = ModelSwitchBarrier()
        val configFile = File(tempDirectory, "historical-proxy-resolution.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":1080,"type":"UNKNOWN"},"ai":{"provider":"GEMINI","geminiApiKey":"key"}}
            """.trimIndent()
        configFile.writeText(originalContent)
        val repository = SettingsRepository.forTesting(configFile, barrier)
        val originalSettings = repository.settingsFlow.value
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value

        assertFailsWith<IllegalArgumentException> {
            repository.saveSettings(originalSettings.copy(chatId = "copied-settings-chat"))
        }

        assertEquals(originalSettings, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertTrue(repository.hasHistoricalInvalidProxy)
        assertFalse(barrier.isSwitching)
        assertEquals(originalContent, configFile.readText())

        val resolvedSettings = originalSettings.copy(
            chatId = "resolved-chat",
            proxy = ProxySettings("127.0.0.1", 1080, ProxyType.SOCKS),
        )
        repository.saveSettings(resolvedSettings)

        assertEquals(resolvedSettings, repository.settingsFlow.value)
        assertFalse(repository.hasHistoricalInvalidProxy)
        assertTrue(barrier.isSwitching)
    }

    /**
     * 验证缺少类型且端口非法的旧代理不会被迁移或改写，并保留其他设置。
     */
    @Test
    fun `invalid old proxy without a type remains untouched and preserves the remaining settings`() {
        val configFile = File(tempDirectory, "invalid-old-proxy.json")
        val originalContent =
            """
            {"telegramToken":"100:token","chatId":"chat","proxy":{"host":"proxy.example.com","port":70000},"ai":{"provider":"GEMINI","geminiApiKey":"key"}}
            """.trimIndent()
        configFile.writeText(originalContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("100:token", repository.settingsFlow.value.telegramToken)
        assertEquals("chat", repository.settingsFlow.value.chatId)
        assertEquals(AIProvider.GEMINI, repository.settingsFlow.value.ai?.provider)
        assertEquals("key", repository.settingsFlow.value.ai?.geminiApiKey)
        assertEquals(null, repository.settingsFlow.value.proxy)
        assertTrue(repository.hasHistoricalInvalidProxy)
        assertEquals(originalContent, configFile.readText())
    }

    /**
     * 验证主文件语义损坏时会以经验证备份的原始字节恢复设置。
     */
    @Test
    fun `damaged settings primary recovers from a valid backup without reencoding it`() {
        val configFile = File(tempDirectory, "recover-settings.json")
        val backupContent = "{\n  \"telegramToken\": \"100:backup\",\n  \"chatId\": \"backup-chat\"\n}\n"
        configFile.writeText("{ invalid")
        File(tempDirectory, "recover-settings.json.bak").writeText(backupContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("100:backup", repository.settingsFlow.value.telegramToken)
        assertEquals("backup-chat", repository.settingsFlow.value.chatId)
        assertEquals(backupContent, configFile.readText())
        assertEquals(backupContent, File(tempDirectory, "recover-settings.json.bak").readText())
    }

    /**
     * 验证主替换失败会取消新屏障，且不会推进设置流或 token 代次。
     */
    @Test
    fun `primary replace failure leaves settings flows and barrier unchanged`() {
        val configFile = File(tempDirectory, "primary-replace-failure.json")
        val initial = AppSettings(telegramToken = "100:old", chatId = "old-chat")
        configFile.writeText(ConfigJson.encodeToString(initial))
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == configFile.toPath()) {
                    throw IOException("injected primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
        val barrier = ModelSwitchBarrier()
        val repository = SettingsRepository.forTesting(configFile, barrier, fileOperations)
        val originalSettingsUpdate = repository.settingsUpdateFlow.value
        val originalTokenUpdate = repository.telegramTokenUpdateFlow.value
        val originalContent = configFile.readText()

        assertFailsWith<IOException> {
            repository.saveSettings(
                initial.copy(
                    telegramToken = "200:new",
                    ai = AISettings(agentEnabled = true),
                ),
            )
        }

        assertEquals(initial, repository.settingsFlow.value)
        assertEquals(originalSettingsUpdate, repository.settingsUpdateFlow.value)
        assertEquals(originalTokenUpdate, repository.telegramTokenUpdateFlow.value)
        assertEquals(originalContent, configFile.readText())
        assertFalse(barrier.isSwitching)
    }

    /**
     * 验证有效备份恢复主文件失败后，设置仓储保持安全默认值并拒绝覆盖两个原始文件。
     */
    @Test
    fun `failed settings recovery disables later saves without touching primary or backup`() {
        val configFile = File(tempDirectory, "settings-recovery-failure.json")
        val backupFile = File(tempDirectory, "settings-recovery-failure.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup = ConfigJson.encodeToString(AppSettings(telegramToken = "100:backup"))
        configFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun atomicReplace(source: Path, target: Path) {
                if (target == configFile.toPath()) {
                    throw IOException("injected recovery replacement failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertFailsWith<IllegalStateException> {
            repository.saveSettings(AppSettings(telegramToken = "200:new"))
        }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())
    }

    /**
     * 验证主设置文件缺失时会从有效备份恢复，而不是以默认设置覆盖备份。
     */
    @Test
    fun `missing settings primary restores valid backup`() {
        val configFile = File(tempDirectory, "missing-settings.json")
        val backupFile = File(tempDirectory, "missing-settings.json.bak")
        val backupContent = "{\n  \"telegramToken\": \"100:backup\",\n  \"chatId\": \"backup-chat\"\n}\n"
        backupFile.writeText(backupContent)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals("100:backup", repository.settingsFlow.value.telegramToken)
        assertEquals(backupContent, configFile.readText())
        assertEquals(backupContent, backupFile.readText())
    }

    /**
     * 验证双坏设置文件只提供安全默认值，后续保存不会覆盖任一现场文件。
     */
    @Test
    fun `double damaged settings files reject saves without overwriting either file`() {
        val configFile = File(tempDirectory, "double-damaged-settings.json")
        val backupFile = File(tempDirectory, "double-damaged-settings.json.bak")
        val damagedPrimary = "{ invalid"
        val damagedBackup = "[ invalid"
        configFile.writeText(damagedPrimary)
        backupFile.writeText(damagedBackup)

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier())

        assertEquals(AppSettings(), repository.settingsFlow.value)
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(damagedBackup, backupFile.readText())
    }

    /**
     * 验证主文件损坏且备份暂时不可读时，恢复成功前不允许写入；恢复后提交保留验证过的备份。
     */
    @Test
    fun `pending backup read is revalidated before save without copying damaged primary to backup`() {
        val configFile = File(tempDirectory, "pending-settings.json")
        val backupFile = File(tempDirectory, "pending-settings.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup = "{\n  \"telegramToken\": \"100:backup\"\n}\n"
        configFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        var blockBackupRead = true
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAllBytes(path: Path): ByteArray {
                if (blockBackupRead && path == backupFile.toPath()) {
                    throw IOException("injected backup read failure")
                }
                return DefaultAtomicJsonFileOperations.readAllBytes(path)
            }
        }

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:blocked")) }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())

        blockBackupRead = false
        assertFailsWith<IllegalStateException> {
            repository.saveSettings(AppSettings(telegramToken = "200:committed"))
        }
        assertEquals("100:backup", repository.settingsFlow.value.telegramToken)
        assertEquals(validBackup, configFile.readText())
        repository.saveSettings(AppSettings(telegramToken = "200:committed"))

        assertEquals(validBackup, backupFile.readText())
        assertEquals("200:committed", ConfigJson.decodeFromString<AppSettings>(configFile.readText()).telegramToken)
    }

    /**
     * 验证首次主文件读取 I/O 失败同样会阻断默认状态写入；恢复可读后先恢复备份再保存。
     */
    @Test
    fun `initial primary read failure is revalidated before save without overwriting backup`() {
        val configFile = File(tempDirectory, "initial-io-settings.json")
        val backupFile = File(tempDirectory, "initial-io-settings.json.bak")
        val damagedPrimary = "{ invalid"
        val validBackup = "{\n  \"telegramToken\": \"100:backup\"\n}\n"
        configFile.writeText(damagedPrimary)
        backupFile.writeText(validBackup)
        var blockPrimaryRead = true
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAllBytes(path: Path): ByteArray {
                if (blockPrimaryRead && path == configFile.toPath()) {
                    throw IOException("injected primary read failure")
                }
                return DefaultAtomicJsonFileOperations.readAllBytes(path)
            }
        }

        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:blocked")) }
        assertEquals(damagedPrimary, configFile.readText())
        assertEquals(validBackup, backupFile.readText())

        blockPrimaryRead = false
        assertFailsWith<IllegalStateException> {
            repository.saveSettings(AppSettings(telegramToken = "200:committed"))
        }
        assertEquals("100:backup", repository.settingsFlow.value.telegramToken)
        assertEquals(validBackup, configFile.readText())
        repository.saveSettings(AppSettings(telegramToken = "200:committed"))

        assertEquals(validBackup, backupFile.readText())
        assertEquals("200:committed", ConfigJson.decodeFromString<AppSettings>(configFile.readText()).telegramToken)
    }

    /**
     * 验证恢复发布快照后，候选保存失败不会使内存退回默认值，重试会基于恢复后的 token 提交。
     */
    @Test
    fun `recovery publishes settings before failed candidate save and retry`() {
        val configFile = File(tempDirectory, "recovery-retry-settings.json")
        val backupFile = File(tempDirectory, "recovery-retry-settings.json.bak")
        val recovered = AppSettings(telegramToken = "100:backup", chatId = "backup-chat")
        val validBackup = ConfigJson.encodeToString(recovered)
        configFile.writeText("{ invalid")
        backupFile.writeText(validBackup)
        var blockBackupRead = true
        var failCandidatePrimaryReplace = false
        val fileOperations = object : AtomicJsonFileOperations by DefaultAtomicJsonFileOperations {
            override fun readAllBytes(path: Path): ByteArray {
                if (blockBackupRead && path == backupFile.toPath()) {
                    throw IOException("injected backup read failure")
                }
                return DefaultAtomicJsonFileOperations.readAllBytes(path)
            }

            override fun atomicReplace(source: Path, target: Path) {
                if (failCandidatePrimaryReplace && target == configFile.toPath()) {
                    throw IOException("injected candidate primary replace failure")
                }
                DefaultAtomicJsonFileOperations.atomicReplace(source, target)
            }
        }
        val repository = SettingsRepository.forTesting(configFile, ModelSwitchBarrier(), fileOperations)
        val initialGeneration = repository.telegramTokenUpdateFlow.value.generation

        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }
        blockBackupRead = false
        assertFailsWith<IllegalStateException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }

        assertEquals(recovered, repository.settingsFlow.value)
        assertEquals(initialGeneration + 1, repository.telegramTokenUpdateFlow.value.generation)
        assertEquals(validBackup, configFile.readText())
        assertEquals(validBackup, backupFile.readText())

        failCandidatePrimaryReplace = true
        assertFailsWith<IOException> { repository.saveSettings(AppSettings(telegramToken = "200:new")) }
        assertEquals(recovered, repository.settingsFlow.value)
        assertEquals(initialGeneration + 1, repository.telegramTokenUpdateFlow.value.generation)
        assertEquals(validBackup, configFile.readText())
        assertEquals(validBackup, backupFile.readText())

        failCandidatePrimaryReplace = false
        repository.saveSettings(AppSettings(telegramToken = "200:new"))
        assertEquals("200:new", repository.settingsFlow.value.telegramToken)
        assertEquals(initialGeneration + 2, repository.telegramTokenUpdateFlow.value.generation)
        assertEquals(validBackup, backupFile.readText())
    }
}
