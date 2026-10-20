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

    /** The During calls layer: an action id, [Mappings.AS_USUAL] or [Mappings.NOTHING]. */
    fun callActionId(gesture: Gesture): String =
        prefs.getString("call.${gesture.key}", gesture.callDefault) ?: gesture.callDefault

    fun setCallAction(gesture: Gesture, id: String) =
        prefs.edit().putString("call.${gesture.key}", id).apply()

    /** `gesture=action;…` plus `dial_up`/`dial_down` for the bridge, with the call layer while in a call. */
    fun mapping(inCall: Boolean = CallWatch.inCall): String = Mappings.effective(
        Gesture.entries.associateTo(LinkedHashMap()) { it.key to actionId(it) },
        Gesture.entries.associate { it.key to callActionId(it) },
        dial.up,
        dial.down,
        hand,
        inCall,
    )

    /** Every action id a gesture can run, in or out of a call. */
    private fun allIds() = Gesture.entries.flatMap { listOf(actionId(it), callActionId(it)) }

    /**
     * Something needs the accessibility service: a screen gesture, a tap, an app or the voice
     * assistant (Android lets the service start those from the background).
     */
    fun usesScreen() = allIds().any { id ->
        id.startsWith("screen.") || id.startsWith(APP_PREFIX) || id.startsWith(Tap.PREFIX) || id == Action.ASSISTANT.id
    }

    /** An app is mapped somewhere (on Xiaomi it needs the background-windows permission). */
    fun usesApps() = allIds().any { it.startsWith(APP_PREFIX) }

    /** Brightness is mapped somewhere (it needs Modify system settings). */
    fun usesBrightness() = dial == DialChoice.BRIGHTNESS || allIds().any { it.startsWith("brightness.") }

    /** Notification access: the seek dial reaches the playing app through it, the notification gestures read or clear. */
    fun usesNotifications() = dial == DialChoice.SEEK || usesNotificationGestures()

    fun usesNotificationGestures() = allIds().any { it.startsWith("notification.") }

    /** A call action is set in the During calls layer (it needs the Phone calls permission). */
    fun usesCalls() = Gesture.entries.any { callActionId(it).startsWith("call.") }

    var associationId: Int
        get() = prefs.getInt("association", -1)
        set(value) = prefs.edit().putInt("association", value).apply()
}
