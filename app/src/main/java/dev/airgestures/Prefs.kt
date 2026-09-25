package dev.airgestures

import android.content.Context

/** The few settings that outlive a connection. */
class Prefs(context: Context) {
    private val prefs = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    var paused: Boolean
        get() = prefs.getBoolean("paused", false)
        set(value) = prefs.edit().putBoolean("paused", value).apply()

    /** "volume" or "brightness". */
    var dial: String
        get() = prefs.getString("dial", "volume") ?: "volume"
        set(value) = prefs.edit().putString("dial", value).apply()

    /** The service should keep the band connected. */
    var running: Boolean
        get() = prefs.getBoolean("running", false)
        set(value) = prefs.edit().putBoolean("running", value).apply()

    var associationId: Int
        get() = prefs.getInt("association", -1)
        set(value) = prefs.edit().putInt("association", value).apply()
}
