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
    val queryName = uri.getQueryParameter("name")
        ?: uri.getQueryParameter("title")
    val queryFilename = uri.getQueryParameter("filename")
        ?: uri.getQueryParameter("file")
        ?: uri.getQueryParameter("download")

    val path = uri.path
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
        query = uri.query,
        fragment = uri.fragment,
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

    return candidates.any { value ->
        when (matchMode) {
            MatchMode.SUBSTRING -> value.contains(pattern, ignoreCase = caseInsensitive)
            MatchMode.REGEX -> {
                val opts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
                runCatching { Regex(pattern, opts).containsMatchIn(value) }.getOrDefault(false)
            }
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

    val isRegexLiteral = patternRaw.startsWith("/") && (patternRaw.endsWith("/") || patternRaw.endsWith("/i"))

    val pattern: String
    val caseInsensitive: Boolean
    val matchMode: MatchMode

    if (isRegexLiteral) {
        matchMode = MatchMode.REGEX
        caseInsensitive = patternRaw.endsWith("/i")
        val endIndex = maxOf(1, if (caseInsensitive) patternRaw.length - 2 else patternRaw.length - 1)
        pattern = patternRaw.substring(1, endIndex)
    } else {
        matchMode = MatchMode.SUBSTRING
        caseInsensitive = true
        pattern = patternRaw
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
