package com.unscientificjszhai.tgp.models

import kotlin.test.*
import com.unscientificjszhai.tgp.utils.ConfigJson

class AccessControlTest {
    @Test
    fun `compiled rules do not leak into serialization or survive a settings copy`() {
        val original = AccessControlSettings(true, listOf("192.0.2.0/24"))
        assertTrue(original.allows("192.0.2.1"))
        val encoded = ConfigJson.encodeToString(AccessControlSettings.serializer(), original)
        assertEquals(
            ConfigJson.parseToJsonElement("""{"enabled":true,"rules":["192.0.2.0/24"]}"""),
            ConfigJson.parseToJsonElement(encoded)
        )
        val copied = original.copy(rules = listOf("2001:db8::/32"))
        val decoded = ConfigJson.decodeFromString(AccessControlSettings.serializer(), encoded)
        assertFalse(copied.allows("192.0.2.1"))
        assertTrue(copied.allows("2001:db8::1"))
        assertTrue(decoded.allows("192.0.2.1"))
        assertFalse(decoded.allows("2001:db8::1"))
    }

    @Test
    fun `matches IPv4 IPv6 and mapped addresses with subnet boundaries`() {
        val settings = AccessControlSettings(true, listOf("192.168.1.10/24", "2001:db8::/32", "::1"))
        listOf("192.168.1.0", "192.168.1.255", "::ffff:192.168.1.20", "2001:db8:ffff::1", "::1").forEach {
            assertTrue(settings.allows(it), it)
        }
        listOf("192.168.2.0", "2001:db9::", "::2", "localhost").forEach { assertFalse(settings.allows(it), it) }
        assertTrue(AccessControlSettings(true, listOf("::ffff:c000:0201/120")).allows("192.0.2.255"))
        assertEquals("192.0.2.0/24", IpNetwork.parse("::ffff:192.0.2.1/120").cidr)
        assertTrue(AccessControlSettings(true, listOf("0.0.0.0/0", "::/0")).allows("2001::1"))
    }

    @Test
    fun `invalid addresses and prefixes are rejected without name resolution`() {
        listOf(
            "", "localhost", "1.2.3", "01.2.3.4", "256.0.0.1", " 127.0.0.1", "127.0.0.1/33", "::/129",
            "::ffff:1.2.3.4/95", ":::/1", "fe80::1%eth0", "1.2.3.4/-1", "1.2.3.4/", "1.2.3.4/1/1"
        ).forEach {
            assertFailsWith<IllegalArgumentException>(it) { IpNetwork.parse(it) }
        }
        assertFalse(AccessControlSettings(true, emptyList()).allows("127.0.0.1"))
        assertTrue(AccessControlSettings(false, emptyList()).allows("unknown"))
        assertFailsWith<IllegalArgumentException> {
            validateAccessControl(
                AccessControlSettings(
                    false,
                    listOf("invalid")
                )
            )
        }
        assertFailsWith<IllegalArgumentException> {
            validateAccessControl(
                AccessControlSettings(
                    true,
                    List(129) { "::1" })
            )
        }
    }
}
