package dev.kb896.airgestures

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.TextView
import org.json.JSONObject

/**
 * The band's fit, from a recording's counts: sensors resting far noisier than the others
 * aren't touching the skin well, and they spoil reading letters. Hidden while the fit is fine.
 */
object Fit {
    fun view(context: Context) = Cb.text(context, 14f, R.font.geist_medium, Cb.Signal.WARN.label).apply {
        background = GradientDrawable().apply {
            cornerRadius = 12f * resources.displayMetrics.density
            setColor(Cb.Signal.WARN.fill)
        }
        val pad = (12 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
        visibility = View.GONE
    }

    fun show(view: TextView, counts: JSONObject) {
        val noisy = counts.optJSONArray("noisy")
        val sensors = (0 until (noisy?.length() ?: 0)).map { noisy!!.getInt(it) }
        view.visibility = if (sensors.isEmpty()) View.GONE else View.VISIBLE
        if (sensors.isEmpty()) return
        val which = if (sensors.size == 1) "Sensor ${sensors[0]} is" else "Sensors ${sensors.joinToString(", ")} are"
        view.text = "$which picking up noise, not muscle. Snug the band up a notch, turn it so it sits " +
            "evenly around the wrist, or dampen the skin under it."
    }
}
