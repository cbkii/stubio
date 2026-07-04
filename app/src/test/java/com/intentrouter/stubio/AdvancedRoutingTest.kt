package com.intentrouter.stubio

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AdvancedRoutingTest {

    @Test
    fun `parse valid shorthand rule`() {
        val rule = parseRuleLine("org.videolan.vlc:test_pattern:10")
        assertNotNull(rule)
        assertEquals("org.videolan.vlc", rule?.packageName)
        assertEquals("test_pattern", rule?.pattern)
        assertEquals(10, rule?.order)
        assertEquals(MatchMode.SUBSTRING, rule?.matchMode)
        assertTrue(rule?.caseInsensitive == true)
    }

    @Test
    fun `parse slash-regex with flag i`() {
        val rule = parseRuleLine("com.mxtech.videoplayer.ad:/\\.mkv/i:20")
        assertNotNull(rule)
        assertEquals("com.mxtech.videoplayer.ad", rule?.packageName)
        assertEquals("\\.mkv", rule?.pattern)
        assertEquals(20, rule?.order)
        assertEquals(MatchMode.REGEX, rule?.matchMode)
        assertTrue(rule?.caseInsensitive == true)
    }

    @Test
    fun `parse contains prefix`() {
        val rule = parseRuleLine("pkg:contains:/api/:30")
        assertNotNull(rule)
        assertEquals("pkg", rule?.packageName)
        assertEquals("/api/", rule?.pattern)
        assertEquals(30, rule?.order)
        assertEquals(MatchMode.SUBSTRING, rule?.matchMode)
    }

    @Test
    fun `parse regex prefix`() {
        val rule = parseRuleLine("pkg:regex:\\.mkv(\\?|$):40")
        assertNotNull(rule)
        assertEquals("pkg", rule?.packageName)
        assertEquals("\\.mkv(\\?|$)", rule?.pattern)
        assertEquals(40, rule?.order)
        assertEquals(MatchMode.REGEX, rule?.matchMode)
        assertFalse(rule?.caseInsensitive ?: true)
    }

    @Test
    fun `parse regexi prefix`() {
        val rule = parseRuleLine("pkg:regexi:\\.m3u8:50")
        assertNotNull(rule)
        assertEquals("pkg", rule?.packageName)
        assertEquals("\\.m3u8", rule?.pattern)
        assertEquals(50, rule?.order)
        assertEquals(MatchMode.REGEX, rule?.matchMode)
        assertTrue(rule?.caseInsensitive ?: false)
    }

    @Test
    fun `parse short invalid slash format avoids exception`() {
        val rule = parseRuleLine("pkg:/i:10")
        assertNotNull(rule)
        assertEquals("pkg", rule?.packageName)
        assertEquals("", rule?.pattern)
        assertEquals(10, rule?.order)
        assertEquals(MatchMode.REGEX, rule?.matchMode)
    }

    @Test
    fun `parse very short slash regex variants do not throw`() {
        val single = parseRuleLine("pkg:/i:10")
        val doubled = parseRuleLine("pkg://:10")
        assertNotNull(single)
        assertNotNull(doubled)
        assertEquals(MatchMode.REGEX, single?.matchMode)
        assertEquals(MatchMode.REGEX, doubled?.matchMode)
    }

    @Test
    fun `reject missing order`() {
        val rule = parseRuleLine("org.videolan.vlc:test_pattern")
        assertNull(rule)
    }

    @Test
    fun `reject non-numeric order`() {
        val rule = parseRuleLine("org.videolan.vlc:test_pattern:abc")
        assertNull(rule)
    }

    @Test
    fun `pattern containing escaped colon does not break parsing`() {
        val rule = parseRuleLine("org.videolan.vlc:regexi:https\\://example\\.com:30")
        assertNotNull(rule)
        assertEquals("org.videolan.vlc", rule?.packageName)
        assertEquals("https://example\\.com", rule?.pattern)
        assertEquals(30, rule?.order)
    }

    @Test
    fun `url context extracts fields`() {
        val uriStr = "https://example.com/path/video.mkv?name=testName&filename=testFile.mkv"
        val uri = Uri.parse(uriStr)
        val intent = Intent().apply { putExtra("test", "value") }

        val context = buildRoutingContext(uri, intent, false)
        assertEquals("https", context.scheme)
        assertEquals("example.com", context.host)
        assertEquals("/path/video.mkv", context.path)
        assertEquals("mkv", context.extension)
        assertEquals("testName", context.queryName)
        assertEquals("testFile.mkv", context.queryFilename)
    }

    @Test
    fun `opaque URI extraction does not crash`() {
        val uri = Uri.parse("mailto:test@example.com")
        val context = buildRoutingContext(uri, Intent(), false)
        assertEquals("mailto", context.scheme)
        assertNull(context.host)
        assertNull(context.path)
    }

    @Test
    fun `rule match substring`() {
        val rule = parseRuleLine("pkg:test:10")!!

        val uri = Uri.parse("https://example.com/test.mkv")
        val context = buildRoutingContext(uri, Intent(), false)

        assertTrue(rule.matches(context))

        val uriFail = Uri.parse("https://example.com/foo.mkv")
        val contextFail = buildRoutingContext(uriFail, Intent(), false)
        assertFalse(rule.matches(contextFail))
    }

    @Test
    fun `rule match regex`() {
        val rule = parseRuleLine("pkg:regexi:\\.mkv(\\?|$):10")!!

        val uri1 = Uri.parse("https://example.com/video.mkv")
        val context1 = buildRoutingContext(uri1, Intent(), false)
        assertTrue(rule.matches(context1))

        val uri2 = Uri.parse("https://example.com/video.MKV?test=1")
        val context2 = buildRoutingContext(uri2, Intent(), false)
        assertTrue(rule.matches(context2))

        val uri3 = Uri.parse("https://example.com/video.mp4")
        val context3 = buildRoutingContext(uri3, Intent(), false)
        assertFalse(rule.matches(context3))
    }
}
