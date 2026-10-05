package com.unscientificjszhai.tgp.service

import com.unscientificjszhai.tgp.models.*
import com.unscientificjszhai.tgp.service.ai.agent.ModelSwitchBarrier
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.*

class AccessControlServiceTest {
    @Test
    fun `network suggestions use actual prefixes and never expand saved rules`() {
        val directory = createTempDirectory().toFile()
        try {
            val coordinator =
                SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            var interfaces = listOf(
                InterfaceSubnet("eth0", "192.168.2.15", 24),
                InterfaceSubnet("eth1", "2001:db8:1::2", 64),
                InterfaceSubnet("unknown", "10.1.2.3", -1)
            )
            val service =
                AccessControlService(coordinator, networkEnvironment = { NetworkEnvironment(interfaces, true) })
            val info = service.info("192.168.2.20", listOf("0.0.0.0:10178", ":::10178"))
            assertEquals(listOf("192.168.2.0/24"), info.suggestions.single { it.label == "网卡 eth0" }.rules)
            assertTrue(info.suggestions.single { it.label == "网卡 eth0" }.allowsPeer)
            assertFalse(info.suggestions.single { it.label == "网卡 eth1" }.allowsPeer)
            assertEquals(listOf("192.168.2.20/32"), info.suggestions.single { it.label == "仅当前连接来源" }.rules)
            assertTrue(info.environmentNotes.any { it.contains("宿主机") })
            assertTrue(info.environmentNotes.any { it.contains("unknown") })
            val fixed = AccessControlSettings(true, info.suggestions.single { it.label == "网卡 eth0" }.rules)
            coordinator.updateSettings { it.copy(accessControl = fixed) }
            interfaces = listOf(InterfaceSubnet("eth0", "172.30.4.1", 16))
            assertEquals(
                listOf("172.30.0.0/16"),
                service.info("172.30.1.1", emptyList()).suggestions.single { it.label == "网卡 eth0" }.rules
            )
            assertEquals(fixed, coordinator.currentSettingsSnapshot().settings.accessControl)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `file override survives restart and applies latest settings after removal`() {
        val directory = createTempDirectory().toFile()
        try {
            val file = directory.resolve("settings.json")
            val coordinator = SettingsChangeCoordinator.forTesting(file, ModelSwitchBarrier())
            val service = AccessControlService(coordinator)
            val restricted = AccessControlSettings(true, listOf("192.0.2.1"))
            assertTrue(service.check(restricted, "127.0.0.1").requiresConfirmation)
            assertFailsWith<AccessLossConfirmationRequired> {
                service.requireConfirmation(
                    restricted,
                    "127.0.0.1",
                    false
                )
            }
            service.override.create()
            assertTrue(service.check(restricted, "127.0.0.1").allowedAfterSubmit)
            assertFalse(service.check(restricted, "127.0.0.1").allowedByProposedSettings)
            coordinator.updateSettings { it.copy(accessControl = restricted) }
            val restarted = AccessControlService(SettingsChangeCoordinator.forTesting(file, ModelSwitchBarrier()))
            assertTrue(restarted.override.isPresent())
            restarted.override.delete()
            assertFalse(restarted.check(restricted, "127.0.0.1").allowedAfterSubmit)
            assertTrue(file.readText().contains("192.0.2.1"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `invalid semantic settings fail closed protect original and require explicit repair`() {
        for (versioned in listOf(false, true)) {
            val directory = createTempDirectory().toFile()
            try {
                val file = directory.resolve("settings.json")
                val payload = """{"telegramToken":"preserved","accessControl":{"enabled":false,"rules":["bad/33"]}}"""
                val original = if (versioned) """{"schemaVersion":1,"data":$payload}""" else payload
                file.writeText(original)
                val coordinator = SettingsChangeCoordinator.forTesting(file, ModelSwitchBarrier())
                assertEquals(listOf("accessControl"), coordinator.currentSettingsWithRecovery().second)
                assertFalse(coordinator.currentSettingsSnapshot().settings.accessControl.allows("127.0.0.1"))
                assertFailsWith<HistoricalInvalidAccessControlException> { coordinator.updateSettings { it.copy(chatId = "42") } }
                assertEquals(original, file.readText())
                coordinator.updateSettings(replacesHistoricalInvalidAccessControl = true) { it }
                assertTrue(coordinator.currentSettingsWithRecovery().second.isEmpty())
                assertEquals("preserved", coordinator.currentSettingsSnapshot().settings.telegramToken)
            } finally {
                directory.deleteRecursively()
            }
        }
    }

    @Test
    fun `missing fields stay unrestricted while corrupt structure cannot silently disable restriction`() {
        val directory = createTempDirectory().toFile()
        try {
            val file = directory.resolve("settings.json")
            file.writeText("""{"chatId":"42"}""")
            assertFalse(
                SettingsChangeCoordinator.forTesting(file, ModelSwitchBarrier())
                    .currentSettingsSnapshot().settings.accessControl.enabled
            )
            listOf(
                "null",
                "false",
                "{}",
                """{"enabled":"bad","rules":[]}""",
                """{"enabled":true,"rules":null}"""
            ).forEach { broken ->
                val original = """{"accessControl":$broken}"""
                file.writeText(original)
                assertFailsWith<IllegalStateException> {
                    SettingsChangeCoordinator.forTesting(
                        file,
                        ModelSwitchBarrier()
                    )
                }
                assertEquals(original, file.readText())
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unlock consumes commands silently and only trusts latest configured private sender`() {
        val directory = createTempDirectory().toFile()
        try {
            val coordinator =
                SettingsChangeCoordinator.forTesting(directory.resolve("settings.json"), ModelSwitchBarrier())
            val service = AccessControlService(coordinator)
            fun message(chat: Long = 42, sender: Long? = 42, type: String = "private") = Message(
                messageId = 1, chat = Chat(chat, type), text = "/access_unlock",
                from = sender?.let { User(it, false, "user") },
            )
            assertTrue(service.handleUnlock(message()))
            assertFalse(service.override.isPresent())
            coordinator.updateSettings { it.copy(ai = AISettings(agentChatId = "42", agentEnabled = false)) }
            listOf(message(43), message(sender = 43), message(sender = null), message(type = "group")).forEach {
                assertTrue(service.handleUnlock(it)); assertFalse(service.override.isPresent())
            }
            assertTrue(service.handleUnlock(message()))
            assertTrue(service.override.isPresent())
            service.override.delete()
            coordinator.updateSettings { it.copy(ai = AISettings(agentChatId = "43")) }
            service.handleUnlock(message())
            assertFalse(service.override.isPresent())
            val failing =
                AccessControlService(coordinator, object : AccessControlOverride(directory.toPath().resolve("unused")) {
                    override fun create() {
                        throw IOException("failure")
                    }
                })
            assertTrue(failing.handleUnlock(message(43, 43)))
            assertFalse(service.override.isPresent())
        } finally {
            directory.deleteRecursively()
        }
    }
}
