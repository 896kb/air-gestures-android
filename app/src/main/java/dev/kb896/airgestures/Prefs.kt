package dev.kb896.airgestures

import android.content.Context

/** The few settings that outlive a connection. */
class Prefs(context: Context) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    /** "left" or "right" once chosen in the app (the band is set to it on every connection), else "band". */
    var hand: String
        get() = prefs.getString("hand", "band") ?: "band"
        set(value) = prefs.edit().putString("hand", value).apply()

    /** What pinch and turn does in the profile in force. */
    val dial: DialChoice
        get() = DialChoice.of(Profiles.active(context).dial)

    /** What pinch and turn does in [profile]. */
    fun dial(profile: String): DialChoice = DialChoice.of(Profiles.load(context).profile(profile)?.dial)

    fun setDial(profile: String, dial: DialChoice) = Profiles.update(context) { it.withDial(profile, dial.key) }

    /** A practice lesson is open (its controls state is [practiceWasPaused]). */
    var practising: Boolean
        get() = prefs.getBoolean("practice.on", false)
        set(value) = prefs.edit().putBoolean("practice.on", value).apply()

    var practiceWasPaused: Boolean
        get() = prefs.getBoolean("practice.was_paused", false)
        set(value) = prefs.edit().putBoolean("practice.was_paused", value).apply()

    /** The service should keep the band connected. */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) = prefs.edit().putBoolean("running", value).apply()

    /** The action id in force for [gesture] (the active profile's), or [profile]'s: an [Action]'s id, `app:…` or `tap:…`. */
    fun actionId(gesture: Gesture, profile: String? = null): String {
        val list = Profiles.load(context)
        val chosen = if (profile == null) list.active(Profiles.foreground) else list.profile(profile)
        return chosen?.map?.get(gesture.key) ?: gesture.default.id
    }

    fun action(gesture: Gesture, profile: String? = null): Action = Action.of(actionId(gesture, profile))

    fun setAction(profile: String, gesture: Gesture, id: String) =
        Profiles.update(context) { it.withAction(profile, gesture.key, id) }

    /** The During calls layer: an action id, [Mappings.AS_USUAL] or [Mappings.NOTHING]. */
    fun callActionId(gesture: Gesture): String =
        prefs.getString("call.${gesture.key}", gesture.callDefault) ?: gesture.callDefault

    fun setCallAction(gesture: Gesture, id: String) =
        prefs.edit().putString("call.${gesture.key}", id).apply()

    /**
     * `gesture=action;…` plus `dial_up`/`dial_down` for the bridge: the profile in force, with
     * the call layer while in a call, and `triples=profiles` once there are profiles to move between.
     */
    fun mapping(inCall: Boolean = CallWatch.inCall): String {
        val list = Profiles.load(context)
        val active = list.active(Profiles.foreground)
        val dial = DialChoice.of(active.dial)
        return Mappings.effective(
            Gesture.entries.associateTo(LinkedHashMap()) { it.key to (active.map[it.key] ?: it.default.id) },
            Gesture.entries.associate { it.key to callActionId(it) },
            dial.up,
            dial.down,
            hand,
            inCall,
        ) + if (list.profiles.size >= 2) ";triples=profiles" else ""
    }

    /** Every action id a gesture can run, in any profile and during calls. */
    private fun allIds() = Profiles.load(context).profiles.flatMap { profile ->
        Gesture.entries.map { profile.map[it.key] ?: it.default.id }
    } + Gesture.entries.map { callActionId(it) }

    /** Every profile's dial. */
    private fun dials() = Profiles.load(context).profiles.map { DialChoice.of(it.dial) }

    /**
     * Something needs the accessibility service: a screen gesture, a tap, an app or the voice
     * assistant (Android lets the service start those from the background).
     */
    fun usesScreen() = usesAppProfiles() || allIds().any { id ->
        id.startsWith("screen.") || id.startsWith(APP_PREFIX) || id.startsWith(Tap.PREFIX) || id == Action.ASSISTANT.id
    }

    /** An app has its own profile: the accessibility service tells which app is in front. */
    fun usesAppProfiles() = Profiles.load(context).apps.isNotEmpty()

    /** An app is mapped somewhere (on Xiaomi it needs the background-windows permission). */
    fun usesApps() = allIds().any { it.startsWith(APP_PREFIX) }

    /** Brightness is mapped somewhere (it needs Modify system settings). */
    fun usesBrightness() = DialChoice.BRIGHTNESS in dials() || allIds().any { it.startsWith("brightness.") }

    /** Notification access: the seek dial reaches the playing app through it, the notification gestures read or clear. */
    fun usesNotifications() = DialChoice.SEEK in dials() || usesNotificationGestures()

    /** Some profile's dial seeks. */
    fun usesSeek() = DialChoice.SEEK in dials()

    fun usesNotificationGestures() = allIds().any { it.startsWith("notification.") }

    /** Anything is set in the During calls layer: it needs the phone permissions to know when a call is on. */
    fun usesCalls() = Gesture.entries.any { callActionId(it) != Mappings.AS_USUAL }

    var associationId: Int
        get() = prefs.getInt("association", -1)
        set(value) = prefs.edit().putInt("association", value).apply()
}
