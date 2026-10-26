package dev.kb896.airgestures

import android.content.Context
import org.json.JSONObject

/**
 * One wrist on one band: palm down and palm up as rolls (degrees), and taught drags (the
 * bridge's JSON from Bridge.dragPrototypes, kept as it came). Free of Android, so it's unit-tested.
 */
data class Calibration(val palm: Pair<Double, Double>? = null, val drags: String? = null) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("palm", palm?.let { (down, up) -> JSONObject().put("down", down).put("up", up) } ?: JSONObject.NULL)
        put("drags", drags?.let(::JSONObject) ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(text: String): Calibration {
            val json = JSONObject(text)
            val palm = json.optJSONObject("palm")?.let { it.getDouble("down") to it.getDouble("up") }
            return Calibration(palm, json.optJSONObject("drags")?.toString())
        }
    }
}

/** Calibrations by band (its Bluetooth address) and wrist ("left" / "right"), in the app's settings. */
object Calibrations {
    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)
    private fun key(band: String, hand: String) = "calibration.$band.$hand"

    fun get(context: Context, band: String?, hand: String): Calibration {
        band ?: return Calibration()
        return prefs(context).getString(key(band, hand), null)?.let { runCatching { Calibration.fromJson(it) }.getOrNull() } ?: Calibration()
    }

    fun update(context: Context, band: String, hand: String, change: (Calibration) -> Calibration) =
        prefs(context).edit().putString(key(band, hand), change(get(context, band, hand)).toJson().toString()).apply()
}
