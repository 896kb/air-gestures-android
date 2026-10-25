package dev.kb896.airgestures

import android.content.Context
import org.json.JSONObject

/**
 * Where the [ProfileList] lives (the app's settings, as JSON) and which app is in front.
 * The first read moves the mapping from before profiles into Profile 1. Also the band controls
 * (once, above the profiles) and the shared drawing actions.
 */
object Profiles {
    private const val PROFILES = "profiles"
    private const val APPS = "profile.apps"
    private const val PICKED = "profile.current"
    private const val SPEAK = "profile.speak"
    private const val VIBRATE = "profile.vibrate"
    private const val SEQUENCES = "sequences"
    private const val BROKEN = "profiles.broken"
    private const val BAND = "band.controls"
    private const val SHARED_DRAWINGS = "drawings.shared"
    private const val VERSION_KEY = "controls.version"
    /** 2: band controls (from 0.7.7, and from the first controls dev build with built-in actions). */
    private const val VERSION = 2

    @Volatile
    private var cached: ProfileList? = null

    /** The app in front, as the accessibility service last saw it (null = unknown). */
    @Volatile
    var seen: String? = null

    /**
     * The app that said it's in front ([ProfileReceiver]), null once it says it left. It wins
     * over [seen]: it knows, e.g. while it shows on an external screen with the phone folded.
     */
    @Volatile
    var announced: String? = null

    /** The app whose own profile counts: the one that announced itself, else the one seen. */
    val foreground: String?
        get() = announced ?: seen

    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    /** A new profile's gestures: the defaults. */
    fun defaultMap() = Gesture.entries.associate { it.key to it.default.id }

    fun load(context: Context): ProfileList = cached ?: synchronized(this) {
        cached ?: read(context).also { cached = it }
    }

    private fun read(context: Context): ProfileList {
        val prefs = prefs(context)
        val profiles = prefs.getString(PROFILES, null)
        if (profiles != null) {
            val picked = prefs.getString(PICKED, null) ?: "p1"
            val apps = prefs.getString(APPS, null) ?: "{}"
            // Unreadable app assignments cost only the assignments, not the profiles.
            runCatching { return migrated(context, ProfileList.fromJson(profiles, apps, picked)) }
            runCatching { return migrated(context, ProfileList.fromJson(profiles, "{}", picked)) }
            // The profiles themselves are unreadable: keep a copy before starting over.
            android.util.Log.w("AirGestures", "profiles unreadable; kept as $BROKEN, starting from the old mapping")
            prefs.edit().putString(BROKEN, profiles).apply()
        }
        // Before profiles (or unreadable): the stored mapping becomes Profile 1.
        val map = Gesture.entries.associate { it.key to (prefs.getString("map.${it.key}", null) ?: it.default.id) }
        return migrated(context, ProfileList.migrated(map, DialChoice.of(prefs.getString("dial.choice", null)).key).also { write(context, it) })
    }

    /**
     * Once: built-in action ids (the first controls dev build stored them in profiles and During
     * calls) go, their gestures back to their defaults; the band controls start on today's
     * gestures (0.7.7 had them fixed there, so its profiles keep doing what they did).
     */
    private fun migrated(context: Context, list: ProfileList): ProfileList {
        val prefs = prefs(context)
        if (prefs.getInt(VERSION_KEY, 0) >= VERSION) return list
        val clean = list.withoutBuiltins()
        val calls = Gesture.entries.associate { it.key to (prefs.getString("call.${it.key}", null) ?: "") }
        prefs.edit().apply {
            for (key in ProfileList.builtinCalls(calls)) remove("call.$key")
            if (!prefs.contains(BAND)) putString(BAND, BandControls.standard().toJson().toString())
            putInt(VERSION_KEY, VERSION)
        }.apply()
        if (clean != list) write(context, clean)
        return clean
    }

    private fun write(context: Context, list: ProfileList) {
        val (profiles, apps) = list.toJson()
        prefs(context).edit().putString(PROFILES, profiles).putString(APPS, apps).putString(PICKED, list.picked).apply()
    }

    /** Change the list and store it; returns the new one. */
    fun update(context: Context, change: (ProfileList) -> ProfileList): ProfileList = synchronized(this) {
        change(load(context)).also {
            cached = it
            write(context, it)
        }
    }

    @Volatile
    private var band: BandControls? = null

    /** The band controls; today's gestures until changed (or when unreadable). */
    fun band(context: Context): BandControls = band ?: synchronized(this) {
        band ?: (prefs(context).getString(BAND, null)?.let { runCatching { BandControls.fromJson(it) }.getOrNull() }
            ?: BandControls.standard()).also { band = it }
    }

    fun updateBand(context: Context, change: (BandControls) -> BandControls): BandControls = synchronized(this) {
        change(band(context)).also {
            band = it
            prefs(context).edit().putString(BAND, it.toJson().toString()).apply()
        }
    }

    /** Drawing name → its action in every profile without its own. */
    fun sharedDrawings(context: Context): Map<String, String> =
        prefs(context).getString(SHARED_DRAWINGS, null)?.let { runCatching { JSONObject(it).let { o -> o.keys().asSequence().associateWith(o::getString) } }.getOrNull() }.orEmpty()

    /**
     * The drawings' actions, the profiles' own and the shared ones, changed together and
     * stored in one edit (under the store's lock).
     */
    fun updateDrawingActions(context: Context, change: (ProfileList, Map<String, String>) -> Pair<ProfileList, Map<String, String>>) =
        synchronized(this) {
            val before = load(context) to sharedDrawings(context)
            val after = change(before.first, before.second)
            if (after != before) writeDrawingActions(context, after.first, after.second)
        }

    private fun writeDrawingActions(context: Context, list: ProfileList, shared: Map<String, String>) {
        cached = list
        val (profiles, apps) = list.toJson()
        prefs(context).edit().putString(PROFILES, profiles).putString(APPS, apps).putString(PICKED, list.picked)
            .putString(SHARED_DRAWINGS, JSONObject(shared).toString()).apply()
    }

    /** Actions only for drawings that exist ([names]): drawings.json is the source of truth. Worked out under the lock, on what's stored then. */
    fun pruneDrawingActions(context: Context, names: Set<String>) =
        updateDrawingActions(context) { list, shared -> list.prunedDrawings(names) to DrawingLibrary.pruned(shared, names) }

    /** Read, change and store under the store's lock, so two changes don't lose one. */
    fun updateSharedDrawings(context: Context, change: (Map<String, String>) -> Map<String, String>) = synchronized(this) {
        prefs(context).edit().putString(SHARED_DRAWINGS, JSONObject(change(sharedDrawings(context))).toString()).apply()
    }

    /** The profile in force now (the app in front's own, else the picked one). */
    fun active(context: Context) = load(context).active(foreground)

    /** Also say a profile's name when switching to it. */
    fun speak(context: Context) = prefs(context).getBoolean(SPEAK, false)

    fun setSpeak(context: Context, on: Boolean) = prefs(context).edit().putBoolean(SPEAK, on).apply()

    @Volatile
    private var sequences: Sequences.Library? = null

    /** The sequences (shared by every profile); empty when there are none or they're unreadable. */
    fun sequences(context: Context): Sequences.Library = sequences ?: synchronized(this) {
        sequences ?: (
            prefs(context).getString(SEQUENCES, null)
                ?.let { runCatching { Sequences.Library.fromJson(it) }.getOrNull() }
                ?: Sequences.Library(emptyList())
            ).also { sequences = it }
    }

    fun updateSequences(context: Context, change: (Sequences.Library) -> Sequences.Library): Sequences.Library = synchronized(this) {
        change(sequences(context)).also {
            sequences = it
            prefs(context).edit().putString(SEQUENCES, it.toJson()).apply()
        }
    }

    /** Buzz on a profile switch (the band already buzzes for its gestures). */
    fun vibrate(context: Context) = prefs(context).getBoolean(VIBRATE, false)

    fun setVibrate(context: Context, on: Boolean) = prefs(context).edit().putBoolean(VIBRATE, on).apply()
}
