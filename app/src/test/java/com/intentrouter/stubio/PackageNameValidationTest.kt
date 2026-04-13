package com.intentrouter.stubio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the package-name validation regex used in [SetupActivity].
 *
 * The regex must accept typical Android reverse-DNS package names and reject
 * values that would cause [android.content.pm.PackageManager] lookups to fail
 * silently or route to the wrong app.
 */
class PackageNameValidationTest {

    // Mirror of SetupActivity.PACKAGE_PATTERN — keep in sync if it changes.
    private val pattern = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")

    @Test
    fun wellFormedPackageNamesAreAccepted() {
        val valid = listOf(
            "org.videolan.vlc",
            "com.google.android.youtube",
            "com.teamsmart.videomanager.tv",
            "com.mxtech.videoplayer.ad",
            "com.stremio.one",
            "tv.twitch.android.app",
            "com.plexapp.android",
        )
        for (name in valid) {
            assertTrue("Expected '$name' to be valid", name.matches(pattern))
        }
    }

    @Test
    fun malformedPackageNamesAreRejected() {
        val invalid = listOf(
            "",            // empty
            "vlc",         // single segment, no dot
            "org",         // single segment
            ".org.vlc",    // leading dot
            "123.pkg",     // first char is a digit
            "org..vlc",    // consecutive dots
            "org.vlc.",    // trailing dot
        )
        for (name in invalid) {
            assertFalse("Expected '$name' to be invalid", name.matches(pattern))
        }
    }
}
