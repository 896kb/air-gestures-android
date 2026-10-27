package dev.kb896.airgestures

import android.content.Context

/** How many announced apps get the band's state. */
private const val ANNOUNCED_KEPT = 8

/**
 * The few settings that outlive a connection; the band's own ones ([Bands]) are [slot]'s, the
 * band the home screen shows unless said.
 */
class Prefs(context: Context, val slot: Int = Bands.selected(context)) {
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    /** This band's own setting's name. */
    private fun own(name: String) = Bands.key(slot, name)

    /** Handwriting in text fields: when one has the focus, the band writes into it (one band at most). */
    var writeInFields: Boolean
        get() = prefs.getBoolean(own("write_in_fields"), false)
        set(value) = prefs.edit().apply {
            if (value) for (other in Bands.slots(context)) putBoolean(Bands.key(other, "write_in_fields"), false)
            putBoolean(own("write_in_fields"), value)
        }.apply()

    /** A word's suggestions (swipe up in a text field) show in a popup, else in the field itself. */
    var choicesPopup: Boolean
        get() = prefs.getBoolean("choices_popup", true)
        set(value) = prefs.edit().putBoolean("choices_popup", value).apply()

    /** The firmware an install committed, until the band reconnects and is checked (0: none). */
    var otaExpect: Long
        get() = prefs.getLong(own("ota_expect"), 0)
        set(value) = prefs.edit().putLong(own("ota_expect"), value).apply()

    var paused: Boolean
        get() = prefs.getBoolean(own("paused"), false)
        set(value) = prefs.edit().putBoolean(own("paused"), value).apply()

    /** Let app feedback use the Band's haptics (default on for new installs). */
    var bandVibration: Boolean
        get() = prefs.getBoolean("band.vibration", true)
        set(value) = prefs.edit().putBoolean("band.vibration", value).apply()

    var notificationVibration: Boolean
        get() = prefs.getBoolean("band.notification_vibration", false)
        set(value) = prefs.edit().putBoolean("band.notification_vibration", value).apply()

    /** "left" or "right" once chosen in the app (the band is set to it on every connection), else "band". */
    var hand: String
        get() = prefs.getString(own("hand"), "band") ?: "band"
        set(value) = prefs.edit().putString(own("hand"), value).apply()

    /** The band's Bluetooth address, as BandLink last connected to it: calibrations are kept per band. */
    var bandAddress: String?
        get() = prefs.getString(own("band.address"), null)
        set(value) = prefs.edit().putString(own("band.address"), value).apply()

    /** The wrist band-core reads gestures for: the band's, else the one it's set to (as the bridge's `Setup::hand_in_use`). */
    fun handInUse(): String? = BandService.stateOf(slot).value.hand ?: hand.takeIf { it != "band" }

    fun setDial(profile: String, dial: String) = Profiles.update(context) { it.withDial(profile, dial) }

    /** This band is kept connected while the service runs (its own power button; on until turned off). */
    var connect: Boolean
        get() = prefs.getBoolean(own("connect"), true)
        set(value) = prefs.edit().putBoolean(own("connect"), value).apply()

    /** The service should keep the bands connected. */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) = prefs.edit().putBoolean("running", value).apply()

    /** The action id in force for [gesture] (the active profile's), or [profile]'s: an [Action]'s id, `app:…` or `tap:…`. */
    fun actionId(gesture: Gesture, profile: String? = null): String {
        val list = Bands.profiles(context, slot)
        val chosen = if (profile == null) list.active(Profiles.foreground) else list.profile(profile)
        return chosen?.map?.get(gesture.key) ?: gesture.default.id
    }

    fun setAction(profile: String, gesture: Gesture, id: String) =
        Profiles.update(context) { it.withAction(profile, gesture.key, id) }

    /** The During calls layer: an action id, [Mappings.AS_USUAL] or [Mappings.NOTHING]. */
    fun callActionId(gesture: Gesture): String =
        prefs.getString("call.${gesture.key}", gesture.callDefault) ?: gesture.callDefault

    fun setCallAction(gesture: Gesture, id: String) =
        prefs.edit().putString("call.${gesture.key}", id).apply()

    /** The During calls layer: gesture key → action id, [Mappings.AS_USUAL] or [Mappings.NOTHING]. */
    fun calls() = Gesture.entries.associate { it.key to callActionId(it) }

    /**
     * Everything the bridge needs to know about gestures, as JSON (see [Mappings.profilesJson]):
     * the profiles this band uses, or [all] of them (the screens' reports, which go by the list's indexes).
     */
    fun profilesJson(all: Boolean = false): String = Mappings.profilesJson(Mappings.Input(
        if (all) Profiles.load(context) else Bands.profiles(context, slot),
        Profiles.band(context),
        calls(),
        Profiles.sharedDrawings(context),
        listOf("left", "right").associateWith { Calibrations.get(context, bandAddress, it) },
        hand,
        Drawings.load(context).drawings.map { it.name }.toSet(),
        // A text field waiting for Handwriting on/off: that gesture turns it on, on the writing band.
        if (!all && Bands.writer(context) == slot) WriteService.instance?.toggleOverlay().orEmpty() else emptyMap(),
    ))

    /**
     * Every action id a gesture can run, in any profile (palm overrides and drawings too) and
     * during calls, with the shared drawings' and the steps of the sequences a gesture is set to
     * (one no gesture uses doesn't make Setup ask for anything).
     */
    private fun allIds(): List<String> {
        val ids = Profiles.load(context).profiles.flatMap { profile ->
            Gesture.entries.map { profile.map[it.key] ?: it.default.id } +
                profile.palm.values.flatMap { it.values } + profile.drawings.values
        } + Gesture.entries.map { callActionId(it) } + Profiles.sharedDrawings(context).values
        val used = ids.filter { it.startsWith(Sequences.PREFIX) }.map { it.removePrefix(Sequences.PREFIX) }.toSet()
        return ids + Profiles.sequences(context).sequences.filter { it.id in used }.flatMap { it.steps }
    }

    /** Every profile's dials: the index dial, the middle dial and the sliders' targets (only while a finger's drags are sliders). */
    private fun dials() = Profiles.load(context).profiles.flatMap { profile ->
        val sliders = listOf(profile.indexDrags, profile.middleDrags).filter { it.sliders }.flatMap { it.targets }
        (listOf(profile.dial, profile.middleDial) + sliders).map { DialChoice.of(it) }
    }

    /**
     * Something needs the accessibility service: a screen gesture, a tap, an app or the voice
     * assistant (Android lets the service start those from the background).
     */
    fun usesScreen() = usesAppProfiles() || allIds().any { id ->
        id.startsWith("screen.") || id.startsWith(APP_PREFIX) || id.startsWith(Tap.PREFIX) || id == Action.ASSISTANT.id ||
            id.startsWith(Strokes.SWIPE) || id.startsWith(Strokes.PATH)
    }

    /** An app has its own profile: the accessibility service tells which app is in front. */
    fun usesAppProfiles() = Profiles.load(context).apps.isNotEmpty()

    /** An app is mapped somewhere (on Xiaomi it needs the background-windows permission). */
    fun usesApps() = allIds().any { it.startsWith(APP_PREFIX) }

    /** Brightness is mapped somewhere (it needs Modify system settings). */
    fun usesBrightness() = DialChoice.BRIGHTNESS in dials() || allIds().any { it.startsWith("brightness.") }

    /** Notification access: the seek dial reaches the playing app through it, the notification gestures read or clear. */
    fun usesNotifications() = notificationVibration || DialChoice.SEEK in dials() || usesNotificationGestures()

    /** Some profile's dial seeks. */
    fun usesSeek() = DialChoice.SEEK in dials()

    fun usesNotificationGestures() = allIds().any { it.startsWith("notification.") }

    /** Navigation is mapped somewhere (its own accessibility service, which reads the screen's layout). */
    fun usesNavigation() = DialChoice.NAVIGATE in dials() || allIds().any { it.startsWith("nav.") }

    /** Anything is set in the During calls layer: it needs the phone permissions to know when a call is on. */
    fun usesCalls() = Gesture.entries.any { callActionId(it) != Mappings.AS_USUAL }

    /** The apps that announced themselves ([ProfileReceiver]), newest first: they get the band's state. */
    val announced: List<String>
        get() = prefs.getString("announced", "").orEmpty().split(";").filter { it.isNotEmpty() }

    fun recordAnnounced(pkg: String) {
        val kept = (listOf(pkg) + announced.filter { it != pkg }).take(ANNOUNCED_KEPT)
        prefs.edit().putString("announced", kept.joinToString(";")).apply()
    }

    /**
     * Debug: extra stream flags to ask the band for on each connection (`1,4,5,7`), logging
     * what it sends that the app doesn't know. Empty = off. Set by DebugActions.
     */
    var probe: String
        get() = prefs.getString("probe", "").orEmpty()
        set(value) = prefs.edit().putString("probe", value).apply()

    var associationId: Int
        get() = prefs.getInt(own("association"), -1)
        set(value) = prefs.edit().putInt(own("association"), value).apply()
}
