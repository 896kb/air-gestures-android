package dev.kb896.airgestures

import org.json.JSONObject

/**
 * The Activity screen's line for the bridge's `@activity` JSON (rust/bridge/src/activity.rs):
 * the gesture and how the palm was, then what it did in the app's words, e.g.
 * "index tap · palm up → Next track", "index dial · palm down → Volume down ×6",
 * "middle double tap · sideways → paused (Draw)", "Lightbulb (drawn) → Flashlight on or off".
 * Free of Android, so it's unit-tested.
 */
object ActivityLine {
    /**
     * [label]: an action id in words (see [label] below); [profile]: a profile index's name.
     * Null for anything that isn't such a line.
     */
    fun format(json: String, label: (String) -> String, profile: (Int) -> String?): String? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (o.optBoolean("not_drawn")) return "drawing → not one of your drawings"
        val head = if (o.has("drawn")) "${o.getString("drawn")} (drawn)" else {
            val palm = Palm.of(o.optString("palm"))?.label?.lowercase() ?: o.optString("palm")
            "${o.optString("what")} · $palm" + if (o.optBoolean("calibrated", true)) "" else " (not calibrated)"
        }
        val did = when {
            o.has("run") -> label(o.getString("run"))
            o.has("practiced") -> "would: " + label(o.getString("practiced"))
            o.has("steps") -> o.getJSONArray("steps").let { a ->
                (0 until a.length()).joinToString(", ") { i ->
                    val step = a.getJSONArray(i)
                    val n = step.optInt(1, 1)
                    label(step.getString(0)) + if (n > 1) " ×$n" else ""
                }
            }
            o.has("band") -> band(o, profile)
            o.has("paused") -> "paused (${Builtin.of(o.getString("paused"))?.label ?: o.getString("paused")})"
            o.optBoolean("off") -> "nothing (controls off)"
            else -> "nothing"
        }
        return "$head → $did"
    }

    /** What a band control did. */
    private fun band(o: JSONObject, profile: (Int) -> String?): String {
        val builtin = Builtin.of(o.getString("band"))
        val name = builtin?.label ?: o.getString("band")
        return when {
            o.has("held") -> "$name: not here (this app has its own)"
            o.has("profile") -> "$name: " + (profile(o.getInt("profile")) ?: "profile ${o.getInt("profile") + 1}")
            builtin == Builtin.CONTROLS -> if (o.optString("result") == "on") "Controls on" else "Controls off"
            builtin == Builtin.DRAW -> if (o.optString("result") == "opened") "Draw (pad open)" else "Draw closed"
            else -> name
        }
    }

    /**
     * An action id in words: automation and the dials' own steps here, everything else by
     * [others] (the app's labels: Labels.action).
     */
    fun label(id: String, others: (String) -> String): String = when {
        id.startsWith("${Mappings.AUTOMATION}:${Mappings.DIAL}") -> "Automation " + if (id.endsWith(":down")) "down" else "up"
        id.startsWith("${Mappings.AUTOMATION}:") -> Action.AUTOMATION.label
        id == DialChoice.SEEK.up -> "Seek forward"
        id == DialChoice.SEEK.down -> "Seek back"
        id.isEmpty() -> "nothing"
        else -> others(id)
    }
}
