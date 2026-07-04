package com.intentrouter.stubio

import android.content.Intent
import android.net.Uri

data class AdvancedRoutingRule(
    val enabled: Boolean = true,
    val label: String = "",
    val packageName: String,
    val pattern: String,
    val order: Int,
    val matchMode: MatchMode = MatchMode.REGEX,
    val target: RouteTarget = RouteTarget.ANY,
    val scopes: Set<MatchScope> = setOf(MatchScope.RAW_URL, MatchScope.DECODED_URL, MatchScope.PATH, MatchScope.QUERY),
    val caseInsensitive: Boolean = true,
    val mimeOverride: String? = null
)

enum class MatchMode {
    SUBSTRING,
    REGEX
}

enum class RouteTarget {
    ANY,
    STREAM,
    TRAILER
}

enum class MatchScope {
    RAW_URL,
    DECODED_URL,
    SCHEME,
    HOST,
    PATH,
    QUERY,
    FRAGMENT,
    EXTENSION,
    QUERY_NAME,
    QUERY_FILENAME,
    INTENT_EXTRAS
}

data class RoutingContext(
    val rawUrl: String,
    val decodedUrl: String,
    val scheme: String?,
    val host: String?,
    val path: String?,
    val query: String?,
    val fragment: String?,
    val extension: String?,
    val queryName: String?,
    val queryFilename: String?,
    val intentExtrasText: String,
    val isTrailer: Boolean
)

fun buildRoutingContext(uri: Uri, originalIntent: Intent, isTrailer: Boolean): RoutingContext {
    val rawUrl = uri.toString()
    val decodedUrl = runCatching { Uri.decode(rawUrl) }.getOrElse { rawUrl }
    val queryName = if (uri.isHierarchical) {
        uri.getQueryParameter("name") ?: uri.getQueryParameter("title")
    } else null

    val queryFilename = if (uri.isHierarchical) {
        uri.getQueryParameter("filename")
            ?: uri.getQueryParameter("file")
            ?: uri.getQueryParameter("download")
    } else null

    val path = if (uri.isHierarchical) uri.path else null
    val query = if (uri.isHierarchical) uri.query else null
    val fragment = if (uri.isHierarchical) uri.fragment else null

    val extension = path
        ?.substringAfterLast('/', "")
        ?.substringBefore('?')
        ?.substringAfterLast('.', "")
        ?.takeIf { it.isNotBlank() && it.length <= 8 }
        ?.lowercase()

    val extrasText = originalIntent.extras
        ?.keySet()
        ?.joinToString("\n") { key ->
            "$key=${originalIntent.extras?.get(key)?.toString().orEmpty()}"
        }
        .orEmpty()

    return RoutingContext(
        rawUrl = rawUrl,
        decodedUrl = decodedUrl,
        scheme = uri.scheme,
        host = uri.host,
        path = path,
        query = query,
        fragment = fragment,
        extension = extension,
        queryName = queryName,
        queryFilename = queryFilename,
        intentExtrasText = extrasText,
        isTrailer = isTrailer
    )
}

fun AdvancedRoutingRule.matches(context: RoutingContext): Boolean {
    val candidates = scopes.mapNotNull { scope ->
        when (scope) {
            MatchScope.RAW_URL -> context.rawUrl
            MatchScope.DECODED_URL -> context.decodedUrl
            MatchScope.SCHEME -> context.scheme
            MatchScope.HOST -> context.host
            MatchScope.PATH -> context.path
            MatchScope.QUERY -> context.query
            MatchScope.FRAGMENT -> context.fragment
            MatchScope.EXTENSION -> context.extension
            MatchScope.QUERY_NAME -> context.queryName
            MatchScope.QUERY_FILENAME -> context.queryFilename
            MatchScope.INTENT_EXTRAS -> context.intentExtrasText
        }
    }

    val compiledRegex = if (matchMode == MatchMode.REGEX) {
        val opts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
        runCatching { Regex(pattern, opts) }.getOrNull()
    } else null

    return candidates.any { value ->
        when (matchMode) {
            MatchMode.SUBSTRING -> value.contains(pattern, ignoreCase = caseInsensitive)
            MatchMode.REGEX -> compiledRegex?.containsMatchIn(value) ?: false
        }
    }
}

fun parseAdvancedRules(rulesText: String): List<AdvancedRoutingRule> {
    return rulesText.lines()
        .map { it.trim() }
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { parseRuleLine(it) }
}

fun parseRuleLine(line: String): AdvancedRoutingRule? {
    val first = findUnescapedColon(line, start = 0) ?: return null
    val last = findLastUnescapedColon(line) ?: return null
    if (last <= first) return null

    val pkg = line.substring(0, first).trim()
    val patternRaw = line.substring(first + 1, last).trim()
    val orderStr = line.substring(last + 1).trim()
    val order = orderStr.toIntOrNull() ?: return null

    if (pkg.isBlank() || patternRaw.isBlank()) return null

    val pattern: String
    val caseInsensitive: Boolean
    val matchMode: MatchMode

    when {
        patternRaw.startsWith("regexi:") -> {
            matchMode = MatchMode.REGEX
            caseInsensitive = true
            pattern = patternRaw.substring(7)
        }
        patternRaw.startsWith("regex:") -> {
            matchMode = MatchMode.REGEX
            caseInsensitive = false
            pattern = patternRaw.substring(6)
        }
        patternRaw.startsWith("contains:") -> {
            matchMode = MatchMode.SUBSTRING
            caseInsensitive = true
            pattern = patternRaw.substring(9)
        }
        patternRaw.startsWith("/") && (patternRaw.endsWith("/") || patternRaw.endsWith("/i")) -> {
            matchMode = MatchMode.REGEX
            caseInsensitive = patternRaw.endsWith("/i")
            val endIndex = maxOf(1, if (caseInsensitive) patternRaw.length - 2 else patternRaw.length - 1)
            pattern = patternRaw.substring(1, endIndex)
        }
        else -> {
            matchMode = MatchMode.SUBSTRING
            caseInsensitive = true
            pattern = patternRaw
        }
    }

    val unescapedPattern = pattern.replace("\\:", ":")

    return AdvancedRoutingRule(
        packageName = pkg,
        pattern = unescapedPattern,
        order = order,
        matchMode = matchMode,
        caseInsensitive = caseInsensitive
    )
}

fun findUnescapedColon(line: String, start: Int): Int? {
    var i = start
    while (i < line.length) {
        if (line[i] == ':') {
            if (i == 0 || line[i - 1] != '\\') return i
        }
        i++
    }
    return null
}

fun findLastUnescapedColon(line: String): Int? {
    var i = line.length - 1
    while (i >= 0) {
        if (line[i] == ':') {
            if (i == 0 || line[i - 1] != '\\') return i
        }
        i--
    }
    return null
}


data class AdvancedRoutingTemplate(
    val title: String,
    val description: String,
    val patternText: String,
    val defaultOrder: Int = 100
)

val ADVANCED_ROUTING_TEMPLATES = listOf(
    AdvancedRoutingTemplate("HLS / m3u8 streams", "Matches HLS playlist links, often used by streaming/proxy servers.", "regexi:\\.m3u8(\\?|$)|/hls/"),
    AdvancedRoutingTemplate("MPEG-TS segments", "Matches transport-stream links or segment paths.", "regexi:\\.ts(\\?|$)|/segment"),
    AdvancedRoutingTemplate("Direct video files", "Matches common visible video file extensions in the URL.", "regexi:\\.(mkv|mp4|avi|webm|m4v)(\\?|$)"),
    AdvancedRoutingTemplate("Matroska only", "Matches direct MKV file links.", "regexi:\\.mkv(\\?|$)"),
    AdvancedRoutingTemplate("MP4 only", "Matches direct MP4 file links.", "regexi:\\.mp4(\\?|$)"),
    AdvancedRoutingTemplate("YouTube / trailer links", "Matches trailer text and common YouTube-style links.", "regexi:\\btrailer\\b|youtube\\.com|youtu\\.be|/yt/"),
    AdvancedRoutingTemplate("Dolby Vision / HDR text", "Matches quality terms when the addon/proxy includes them in the URL/query.", "regexi:\\b(DV|Dolby[ ._-]?Vision|HDR10\\+?|HLG)\\b"),
    AdvancedRoutingTemplate("HEVC / H.265 text", "Matches codec terms if they are present in the incoming URL/query.", "regexi:\\b(HEVC|H\\.?265|x265)\\b"),
    AdvancedRoutingTemplate("AV1 text", "Matches AV1 codec text if present.", "regexi:\\bAV1\\b"),
    AdvancedRoutingTemplate("4K / 2160p text", "Matches 4K quality labels when encoded into the incoming text.", "regexi:\\b(4K|2160p)\\b"),
    AdvancedRoutingTemplate("1080p text", "Matches 1080p quality labels when encoded into the incoming text.", "regexi:\\b1080p\\b"),
    AdvancedRoutingTemplate("Local Stremio server", "Matches local Stremio server URLs.", "contains:127.0.0.1"),
    AdvancedRoutingTemplate("LAN stream host", "Matches private LAN IP stream hosts.", "regexi:https?://(192\\.168\\.|10\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)"),
    AdvancedRoutingTemplate("Literal path segment", "Shows how to match a literal slash-wrapped path without treating it as regex.", "contains:/api/"),
    AdvancedRoutingTemplate("Query name/title present", "Matches URLs where addon/proxy included a name or title query parameter.", "regexi:[?&](name|title)="),
    AdvancedRoutingTemplate("Filename query present", "Matches URLs where addon/proxy included filename-like query data.", "regexi:[?&](filename|file|download)=")
)
