package dev.kb896.airgestures

import android.content.Context

/** The few settings that outlive a connection. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    /** "left" or "right" once chosen in the app (the band is set to it on every connection), else "band". */
    var hand: String
        get() = prefs.getString("hand", "band") ?: "band"
        set(value) = prefs.edit().putString("hand", value).apply()

    /** What pinch and turn does. */
    var dial: DialChoice
        get() = DialChoice.of(prefs.getString("dial.choice", null))
        set(value) = prefs.edit().putString("dial.choice", value.key).apply()

    /** The service should keep the band connected. */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) = prefs.edit().putBoolean("running", value).apply()

    /** The stored action id: an [Action]'s id, or `app:<package>`. */
    fun actionId(gesture: Gesture): String =
        prefs.getString("map.${gesture.key}", gesture.default.id) ?: gesture.default.id

    fun action(gesture: Gesture): Action = Action.of(actionId(gesture))

    fun setAction(gesture: Gesture, id: String) =
        prefs.edit().putString("map.${gesture.key}", id).apply()

    /** `gesture=action;…` plus `dial_up`/`dial_down` for the bridge. */
    fun mapping(): String =
        Gesture.entries.joinToString(";") { "${it.key}=${actionId(it)}" } +
            ";dial_up=${dial.up};dial_down=${dial.down};hand=$hand"

    /**
     * A screen gesture or an app is mapped somewhere: both need the accessibility
     * service (Android lets it open apps from the background).
     */
    fun usesScreen() = Gesture.entries.any { actionId(it).let { id -> id.startsWith("screen.") || id.startsWith(APP_PREFIX) } }

    /** An app is mapped somewhere (on Xiaomi it needs the background-windows permission). */
    fun usesApps() = Gesture.entries.any { actionId(it).startsWith(APP_PREFIX) }

    /** Brightness is mapped somewhere (it needs Modify system settings). */
    fun usesBrightness() = dial == DialChoice.BRIGHTNESS ||
        Gesture.entries.any { action(it).id.startsWith("brightness.") }

    /** The dial seeks (it needs notification access to reach the playing app). */
    fun usesMedia() = dial == DialChoice.SEEK

    var associationId: Int
        get() = prefs.getInt("association", -1)
        set(value) = prefs.edit().putInt("association", value).apply()
}
