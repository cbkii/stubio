package com.intentrouter.stubio

import org.json.JSONArray
import org.json.JSONObject

data class AdvancedRoutingRuleConfig(
    val id: String,
    var enabled: Boolean = true,
    var packageName: String = "",
    var patternRaw: String = "",
    var order: Int = 100,
    var target: RouteTarget = RouteTarget.ANY,
    var matchMode: MatchMode? = null,
    var caseInsensitive: Boolean = true,
    var mimeOverride: String? = null
) {
    fun toAdvancedRoutingRule(): AdvancedRoutingRule? {
        val parsed = parseRuleLine("${packageName}:${patternRaw}:${order}")
        if (parsed != null) {
            return parsed.copy(enabled = enabled, target = target, mimeOverride = mimeOverride)
        }
        return null
    }

    fun toJSON(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("enabled", enabled)
        json.put("packageName", packageName)
        json.put("patternRaw", patternRaw)
        json.put("order", order)
        json.put("target", target.name)
        json.put("matchMode", matchMode?.name)
        json.put("caseInsensitive", caseInsensitive)
        json.put("mimeOverride", mimeOverride)
        return json
    }

    companion object {
        fun fromJSON(json: JSONObject): AdvancedRoutingRuleConfig {
            return AdvancedRoutingRuleConfig(
                id = json.getString("id"),
                enabled = json.optBoolean("enabled", true),
                packageName = json.optString("packageName", ""),
                patternRaw = json.optString("patternRaw", ""),
                order = json.optInt("order", 100),
                target = runCatching { RouteTarget.valueOf(json.optString("target", "ANY")) }.getOrDefault(RouteTarget.ANY),
                matchMode = runCatching { MatchMode.valueOf(json.optString("matchMode")) }.getOrNull(),
                caseInsensitive = json.optBoolean("caseInsensitive", true),
                mimeOverride = json.optString("mimeOverride", null).takeIf { !it.isNullOrBlank() }
            )
        }
    }
}

fun serializeAdvancedRules(rules: List<AdvancedRoutingRuleConfig>): String {
    val array = JSONArray()
    for (rule in rules) {
        array.put(rule.toJSON())
    }
    return array.toString()
}

fun deserializeAdvancedRules(jsonString: String?): List<AdvancedRoutingRuleConfig> {
    if (jsonString.isNullOrBlank()) return emptyList()
    val list = mutableListOf<AdvancedRoutingRuleConfig>()
    runCatching {
        val array = JSONArray(jsonString)
        for (i in 0 until array.length()) {
            list.add(AdvancedRoutingRuleConfig.fromJSON(array.getJSONObject(i)))
        }
    }
    return list
}
