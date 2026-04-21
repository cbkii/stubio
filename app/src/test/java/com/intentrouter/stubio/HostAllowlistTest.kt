package com.intentrouter.stubio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAllowlistTest {

    @Test
    fun parseAdditionalAllowedHosts_trimsNormalizesAndDeduplicates() {
        val parsed = MainActivity.parseAdditionalAllowedHosts(" media.example.com, 192.168.1.80,MEDIA.EXAMPLE.COM, [::1], , ")

        assertEquals(setOf("media.example.com", "192.168.1.80", "::1"), parsed)
    }

    @Test
    fun normalizeHost_lowercasesAndStripsBrackets() {
        assertEquals("fe80::1%eth0", MainActivity.normalizeHost("[Fe80::1%Eth0]"))
        assertEquals("[host", MainActivity.normalizeHost("[host"))
        assertEquals("host]", MainActivity.normalizeHost("host]"))
    }

    @Test
    fun parseAdditionalAllowedHosts_keepsAllNonEmptyEntries() {
        val parsed = MainActivity.parseAdditionalAllowedHosts("ok.example.com, myserver, 999.1.1.1, 10.0.0.1")

        assertEquals(setOf("ok.example.com", "myserver", "999.1.1.1", "10.0.0.1"), parsed)
    }

    @Test
    fun parseAdditionalAllowedHosts_returnsEmptyForNullOrBlank() {
        assertEquals(emptySet<String>(), MainActivity.parseAdditionalAllowedHosts(null))
        assertEquals(emptySet<String>(), MainActivity.parseAdditionalAllowedHosts(""))
        assertEquals(emptySet<String>(), MainActivity.parseAdditionalAllowedHosts("  "))
    }

    @Test
    fun isAllowedHost_acceptsConfiguredAdditionalHost() {
        val additional = MainActivity.parseAdditionalAllowedHosts("media.example.com,10.0.0.20")

        assertTrue(MainActivity.isAllowedHost("media.example.com", "127.0.0.1", additional))
        assertTrue(MainActivity.isAllowedHost("MEDIA.EXAMPLE.COM", "127.0.0.1", additional))
        assertTrue(MainActivity.isAllowedHost("10.0.0.20", "127.0.0.1", additional))
    }

    @Test
    fun isAllowedHost_rejectsHostOutsideBuiltinsStoredServerAndAdditionalAllowlist() {
        val additional = MainActivity.parseAdditionalAllowedHosts("media.example.com,10.0.0.20")

        assertFalse(MainActivity.isAllowedHost("evil.example.com", "192.168.1.2", additional))
    }

    @Test
    fun isAllowedHost_acceptsBuiltinHosts() {
        val empty = emptySet<String>()
        assertTrue(MainActivity.isAllowedHost("127.0.0.1", "127.0.0.1", empty))
        assertTrue(MainActivity.isAllowedHost("localhost", "127.0.0.1", empty))
        assertTrue(MainActivity.isAllowedHost("192.168.1.100", "127.0.0.1", empty))
        assertTrue(MainActivity.isAllowedHost("10.0.0.1", "127.0.0.1", empty))
        assertTrue(MainActivity.isAllowedHost("app.stremio.com", "127.0.0.1", empty))
        assertTrue(MainActivity.isAllowedHost("app.strem.io", "127.0.0.1", empty))
    }

    @Test
    fun isAllowedUri_acceptsLocalContentAndFileSchemesForRouting() {
        val empty = emptySet<String>()

        assertTrue(
            MainActivity.isAllowedUriSchemeAndHost(
                "content",
                null,
                "127.0.0.1",
                empty
            )
        )
        assertTrue(
            MainActivity.isAllowedUriSchemeAndHost(
                "file",
                null,
                "127.0.0.1",
                empty
            )
        )
    }

    @Test
    fun isAllowedUri_appliesHostAllowlistToHttpSchemesOnly() {
        val additional = MainActivity.parseAdditionalAllowedHosts("media.example.com")

        assertTrue(
            MainActivity.isAllowedUriSchemeAndHost(
                "https",
                "media.example.com",
                "127.0.0.1",
                additional
            )
        )
        assertFalse(
            MainActivity.isAllowedUriSchemeAndHost(
                "https",
                "evil.example.com",
                "127.0.0.1",
                additional
            )
        )
    }

    @Test
    fun isAllowedUri_acceptsIntentSchemeForLegacyDeepLinks() {
        assertTrue(
            MainActivity.isAllowedUriSchemeAndHost(
                "intent",
                "example",
                "127.0.0.1",
                emptySet()
            )
        )
    }

    @Test
    fun isAllowedUri_acceptsAndroidAppSchemeForDeepLinkWrappers() {
        assertTrue(
            MainActivity.isAllowedUriSchemeAndHost(
                "android-app",
                "com.stremio.one",
                "127.0.0.1",
                emptySet()
            )
        )
    }

    @Test
    fun isAllowedUri_appliesHostAllowlistToRtspSchemes() {
        val additional = MainActivity.parseAdditionalAllowedHosts("media.example.com")

        assertTrue(
            MainActivity.isAllowedUriSchemeAndHost(
                "rtsp",
                "media.example.com",
                "127.0.0.1",
                additional
            )
        )
        assertFalse(
            MainActivity.isAllowedUriSchemeAndHost(
                "rtsps",
                "evil.example.com",
                "127.0.0.1",
                additional
            )
        )
    }
}
