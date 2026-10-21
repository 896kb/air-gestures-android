package dev.kb896.airgestures

import android.content.Context

/**
 * Where the [ProfileList] lives (the app's settings, as JSON) and which app is in front.
 * The first read moves the mapping from before profiles into Profile 1.
 */
object Profiles {
    private const val PROFILES = "profiles"
    private const val APPS = "profile.apps"
    private const val PICKED = "profile.current"
    private const val SPEAK = "profile.speak"
    private const val VIBRATE = "profile.vibrate"
    private const val SEQUENCES = "sequences"
    private const val BROKEN = "profiles.broken"

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
            runCatching { return ProfileList.fromJson(profiles, apps, picked) }
            runCatching { return ProfileList.fromJson(profiles, "{}", picked) }
            // The profiles themselves are unreadable: keep a copy before starting over.
            android.util.Log.w("AirGestures", "profiles unreadable; kept as $BROKEN, starting from the old mapping")
            prefs.edit().putString(BROKEN, profiles).apply()
        }
        // Before profiles (or unreadable): the stored mapping becomes Profile 1.
        val map = Gesture.entries.associate { it.key to (prefs.getString("map.${it.key}", null) ?: it.default.id) }
        return ProfileList.migrated(map, DialChoice.of(prefs.getString("dial.choice", null)).key).also { write(context, it) }
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
