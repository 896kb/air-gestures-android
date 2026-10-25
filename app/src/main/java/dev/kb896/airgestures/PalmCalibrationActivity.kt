package dev.kb896.airgestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * Palm positions (mockup 13): palm down, then palm up, each held still for two seconds; band-core
 * checks the rolls (Bridge.palmCalibration) and the result is saved for this band and wrist.
 * Sideways is halfway.
 */
class PalmCalibrationActivity : Screen() {
    private lateinit var content: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var sampler = PalmSampler()
    private var step = Palm.DOWN
    /** The band and wrist this run measures (item: a change starts again). */
    private var flow: Pair<String, String>? = null
    /** The band's reading this tick (read once per tick). */
    private var reading: JSONObject? = null
    private var down = DoubleArray(0)
    private var saved = false
    private var message: String? = null
    /** The wrist picked here, when the band hasn't said and the app is set to "band" (judgement 15). */
    private var picked: String? = null

    private var ring: Ring? = null
    private var states: List<TextView> = emptyList()
    private var now: TextView? = null
    /** What [content] was built for; it's rebuilt only when this changes. */
    private var built = ""

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            main.postDelayed(this, TICK_MS)
        }
    }

    private val prefs get() = Prefs(this)
    private val address get() = prefs.bandAddress
    private val hand get() = prefs.handInUse() ?: picked

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        content = column(gap = 20f)
        show(pageColumn().apply { addView(content) }, title = "Palm positions")
    }

    override fun onResume() {
        super.onResume()
        render()
        main.post(ticker)
    }

    override fun onPause() {
        main.removeCallbacks(ticker)
        // Away from the screen isn't holding still: the current hold starts again.
        sampler.restart()
        super.onPause()
    }

    private fun tick() {
        reading = BandService.instance?.palmNow()
        val address = address
        val hand = hand
        if (address != null && hand != null && !saved) {
            // Measured for one band and wrist: another one starts again.
            val now = address to hand
            if (flow != null && flow != now) {
                restart()
                message = "The band or the wrist changed: starting again."
            }
            flow = now
            val roll = reading?.takeUnless { it.isNull("roll") }?.optDouble("roll")
            sampler.add(roll, SystemClock.uptimeMillis())
            if (sampler.done) next()
        }
        render()
    }

    /** A short buzz: a step is done. */
    private fun buzz() {
        val vibrator = getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator ?: return
        runCatching { vibrator.vibrate(android.os.VibrationEffect.createOneShot(40, android.os.VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun next() {
        buzz()
        if (step == Palm.DOWN) {
            down = sampler.rolls
            step = Palm.UP
            // Palm up counts only once the hand has turned well away from palm down.
            sampler = PalmSampler(awayFrom = PalmSampler.mean(down))
            return
        }
        val result = JSONObject(Bridge.palmCalibration(down, sampler.rolls))
        when (val error = result.optString("error")) {
            "" -> {
                Calibrations.update(this, address ?: return, hand ?: return) { it.copy(palm = result.getDouble("down") to result.getDouble("up")) }
                BandService.instance?.remap()
                saved = true
                message = null
            }
            else -> {
                message = when {
                    error.startsWith("not-still") -> "Your hand moved. Rest your elbow and try again."
                    error == "too-close" -> "Palm down and palm up came out too close. Turn your hand all the way over."
                    else -> "The band sent no position. Is it connected?"
                }
                step = Palm.DOWN
                sampler = PalmSampler()
            }
        }
    }

    private fun restart() {
        step = Palm.DOWN
        down = DoubleArray(0)
        saved = false
        message = null
        flow = null
        sampler = PalmSampler()
        render()
    }

    private fun render() {
        val key = listOf(address == null, hand, step, saved, message).joinToString("|")
        if (key != built) {
            built = key
            build()
        }
        ring?.progress = if (saved) 1f else sampler.progress
        val done = { palm: Palm -> saved || (palm == Palm.DOWN && step == Palm.UP) }
        Palm.entries.filter { it != Palm.SIDE }.forEachIndexed { i, palm ->
            states.getOrNull(i)?.text = when {
                done(palm) -> "Done ✓"
                palm == step && sampler.waiting -> "Turn your palm up"
                palm == step -> if (sampler.progress > 0f) "holding…" else "Hold still"
                else -> "Next"
            }
        }
        now?.text = rightNow()
    }

    /** "Right now: Palm down · −141°", from the band as band-core reads it. */
    private fun rightNow(): String {
        val reading = reading ?: return "Right now: no reading (is the band connected?)"
        if (reading.isNull("roll")) return "Right now: no reading (is the band connected?)"
        val palm = Palm.of(reading.optString("palm"))?.label ?: "?"
        val roll = Math.round(reading.optDouble("roll")).toInt()
        return "Right now: $palm · ${if (roll < 0) "−${-roll}" else "$roll"}°"
    }

    private fun build() {
        content.removeAllViews()
        ring = null
        states = emptyList()
        now = null
        if (address == null) {
            content.addView(note("Connect the band first: positions are measured per band."))
            return
        }
        val hand = hand
        if (hand == null) {
            content.addView(card(null, column(gap = 12f).apply {
                addView(note("Which wrist is the band on? The band hasn't said yet."))
                val left = segment("Left") { picked = "left"; render() }
                val right = segment("Right") { picked = "right"; render() }
                addView(row().apply { addView(segmented(left, right)) })
            }))
            return
        }
        content.addView(note("For this band on your $hand wrist. Hold each position still for two seconds, your elbow resting if you can. Sideways is halfway."))
        content.addView(card(null, column(gap = 16f).apply {
            val r = Ring(context).apply { label = if (step == Palm.DOWN && !saved) "1/2" else "2/2" }
            ring = r
            addView(LinearLayout(context).apply {
                gravity = android.view.Gravity.CENTER
                addView(r, LinearLayout.LayoutParams(dpi(120f), dpi(120f)))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val list = mutableListOf<TextView>()
            for ((i, palm) in listOf(Palm.DOWN, Palm.UP).withIndex()) {
                addView(row(gap = 12f).apply {
                    // One width for both numbers, so the names line up.
                    addView(Cb.text(context, 15f, R.font.geist_semibold, Cb.INK_400).apply {
                        text = "${i + 1}"
                        fontFeatureSettings = "tnum"
                        minWidth = dpi(14f)
                    })
                    addView(Cb.text(context, 15f, R.font.geist_medium, Cb.INK_900).apply { text = palm.label }, weighted())
                    addView(Cb.text(context, 13f, color = Cb.INK_500).also { list += it })
                })
            }
            states = list
            now = Cb.text(context, 13f, color = Cb.INK_500).also { addView(it) }
        }))
        message?.let { content.addView(note(it).apply { setTextColor(Cb.INK_900) }) }
        content.addView(row(gap = 10f).apply {
            addView(pill("Start again") { restart() }.first)
            if (saved) addView(pill("Done", R.drawable.ic_check) { finish() }.first)
        })
        if (saved) content.addView(note("Saved for this band and your $hand wrist."))
        content.addView(navRow(R.drawable.ic_move_up_right, "Teach your drags") { open<TeachDragsActivity>() })
    }

    /** The progress ring, the step in the middle. */
    private class Ring(context: Context) : View(context) {
        var progress = 0f
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }
        var label = ""
        private val stroke = Cb.dp(context, 6f)
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = Cb.HAIRLINE }
        private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; color = Cb.INK_900
        }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Cb.font(context, R.font.geist_semibold); textSize = Cb.dp(context, 20f); color = Cb.INK_900; textAlign = Paint.Align.CENTER
        }

        override fun onDraw(canvas: Canvas) {
            val box = android.graphics.RectF(stroke, stroke, width - stroke, height - stroke)
            canvas.drawOval(box, track)
            canvas.drawArc(box, -90f, 360f * progress, false, arc)
            canvas.drawText(label, width / 2f, height / 2f - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    companion object {
        private const val TICK_MS = 50L
    }
}
