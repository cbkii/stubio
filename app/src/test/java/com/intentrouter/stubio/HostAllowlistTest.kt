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
