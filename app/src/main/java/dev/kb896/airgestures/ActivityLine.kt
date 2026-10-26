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
            // A gesture's practiced action (a pinch's steps carry "practiced": true instead).
            o.opt("practiced") is String -> "would: " + label(o.getString("practiced"))
            // In practice a pinch's steps say what they would have done.
            o.has("steps") -> (if (o.optBoolean("practiced")) "would: " else "") + o.getJSONArray("steps").let { a ->
                (0 until a.length()).joinToString(", ") { i ->
                    val step = a.getJSONArray(i)
                    val n = step.optInt(1, 1)
                    label(step.getString(0)) + if (n > 1) " ×$n" else ""
                }
            }
            o.has("band") -> band(o, profile)
            o.has("paused") -> "paused (${Builtin.of(o.getString("paused"))?.label ?: o.getString("paused")})"
            o.optBoolean("off") -> "nothing (controls off)"
            // Just after connecting, or another action a moment ago (band-core's action gate).
            o.optBoolean("too_soon") -> "nothing (too soon after the last)"
            // While teaching drags, or a drawing is open: gestures do nothing else.
            o.optBoolean("teaching") -> "teaching drags"
            o.optBoolean("drawing") -> "drawing"
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
     * An action id in words: automation (with its device's name, by [device]) and the dials' own
     * steps here, everything else by [others] (the app's labels: Labels.action).
     */
    fun label(id: String, device: (String) -> String? = { null }, others: (String) -> String): String {
        val sent = Mappings.sent(id)
        val on = sent?.device?.let(device)?.let { " · $it" }.orEmpty()
        return when {
            sent is Mappings.Automation.Step -> "Automation " + (if (sent.step < 0) "down" else "up") + on
            sent != null -> Action.AUTOMATION.label + on
            id == DialChoice.SEEK.up -> "Seek forward"
            id == DialChoice.SEEK.down -> "Seek back"
            id.isEmpty() -> "nothing"
            else -> others(id)
        }
    }
}
