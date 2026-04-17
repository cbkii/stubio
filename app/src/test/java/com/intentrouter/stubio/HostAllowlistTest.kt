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
    fun normalizeHost_keepsIPv6ZoneSuffixAndUnpairedBrackets() {
        assertEquals("fe80::1%Eth0", MainActivity.normalizeHost("[Fe80::1%Eth0]"))
        assertEquals("[host", MainActivity.normalizeHost("[host"))
        assertEquals("host]", MainActivity.normalizeHost("host]"))
    }

    @Test
    fun parseAdditionalAllowedHosts_discardsInvalidHostTokens() {
        val parsed = MainActivity.parseAdditionalAllowedHosts("ok.example.com, bad host, x, 999.1.1.1, 256.0.0.1, 010.0.0.1, 10.0.0.1")

        assertEquals(setOf("ok.example.com", "10.0.0.1"), parsed)
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
}
