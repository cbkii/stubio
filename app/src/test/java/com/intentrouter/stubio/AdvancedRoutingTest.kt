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
        val rule = parseRuleLine("org.videolan.vlc:/https\\://example\\.com/:30")
        assertNotNull(rule)
        assertEquals("org.videolan.vlc", rule?.packageName)
        assertEquals("https://example\\.com", rule?.pattern)
        assertEquals(30, rule?.order)
    }

    @Test
    fun `rules sort by ascending order`() {
        val rulesText = """
            pkg1:pattern:20
            pkg2:pattern:10
            pkg3:pattern:30
        """.trimIndent()
        val rules = parseAdvancedRules(rulesText).sortedBy { it.order }
        assertEquals(3, rules.size)
        assertEquals("pkg2", rules[0].packageName)
        assertEquals("pkg1", rules[1].packageName)
        assertEquals("pkg3", rules[2].packageName)
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
        val rule = parseRuleLine("pkg:/\\.mkv(\\?|$)/i:10")!!

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
