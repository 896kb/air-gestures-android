package dev.airgestures

import android.content.Context

/** The few settings that outlive a connection. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    /** What pinch and turn does. */
    var dial: DialChoice
        get() = DialChoice.of(prefs.getString("dial.choice", null))
        set(value) = prefs.edit().putString("dial.choice", value.key).apply()

    /** The service should keep the band connected. */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) = prefs.edit().putBoolean("running", value).apply()

    fun action(gesture: Gesture): Action =
        Action.of(prefs.getString("map.${gesture.key}", gesture.default.id) ?: gesture.default.id)

    fun setAction(gesture: Gesture, action: Action) =
        prefs.edit().putString("map.${gesture.key}", action.id).apply()

    /** `gesture=action;…` plus `dial_up`/`dial_down` for the bridge. */
    fun mapping(): String =
        Gesture.entries.joinToString(";") { "${it.key}=${action(it).id}" } +
            ";dial_up=${dial.up};dial_down=${dial.down}"

    /** A screen gesture is mapped somewhere (it needs the accessibility service). */
    fun usesScreen() = Gesture.entries.any { action(it).id.startsWith("screen.") }

    /** Brightness is mapped somewhere (it needs Modify system settings). */
    fun usesBrightness() = dial == DialChoice.BRIGHTNESS ||
        Gesture.entries.any { action(it).id.startsWith("brightness.") }

    var associationId: Int
        get() = prefs.getInt("association", -1)
        set(value) = prefs.edit().putInt("association", value).apply()
}
