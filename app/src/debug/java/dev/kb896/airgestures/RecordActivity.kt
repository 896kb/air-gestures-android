package dev.kb896.airgestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug builds only: record handwriting for a letter recogniser. Each letter is shown, then a
 * timed window to write it on a table with the index finger; the band's raw sEMG and the
 * prompts go to a folder (emg.bin, marks.csv; see rust/bridge/src/recorder.rs) to pull with
 * adb. Nothing runs while it records (the service is in practice mode).
 */
class RecordActivity : Screen() {
    private val main = Handler(Looper.getMainLooper())
    private var prompts = emptyList<String>()
    private var at = 0
    private var running = false
    private var paused = false
    private var folder: File? = null
    /** What the last recording ended with, and where it is. */
    private var saved: String? = null
    private var reps = 5
    private val settingsStore by lazy { getSharedPreferences("recorder", MODE_PRIVATE) }
    private var windowMs = 2000L
    /** From a letter appearing to its writing window. */
    private var gapMs = 500L

    private lateinit var count: TextView
    private lateinit var letter: TextView
    private lateinit var cue: TextView
    private lateinit var stats: TextView
    private lateinit var buttons: LinearLayout
    private lateinit var settings: LinearLayout

    /** Gestures still reach the recording (as marks); nothing runs meanwhile. */
    private val quiet = object : BandService.Practice {
        override fun gesture(label: String) = Unit
        override fun action(id: String) = Unit
        override fun held() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The last session's settings.
        reps = settingsStore.getInt("reps", reps)
        windowMs = settingsStore.getLong("write_ms", windowMs)
        gapMs = settingsStore.getLong("ready_ms", gapMs)
        count = Cb.label(this, "")
        letter = Cb.text(this, 120f, R.font.geist_bold, Cb.INK_900, -.02f).apply { gravity = Gravity.CENTER }
        cue = Cb.text(this, 20f, R.font.geist_semibold, Cb.INK_500).apply { gravity = Gravity.CENTER }
        stats = Cb.text(this, 13f, R.font.geist_mono_medium, Cb.INK_500)
        buttons = row(gap = 10f).apply { gravity = Gravity.END }
        settings = row(gap = 10f).apply { gravity = Gravity.END }
        show(pageColumn().apply {
            addView(card(null, column(gap = 12f).apply {
                addView(count)
                addView(letter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(cue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(stats)
            }))
            addView(settings, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, title = "Handwriting")
        render()
    }

    /** Before starting: the text follows the band connecting. */
    private val watch: (BandService.State) -> Unit = { if (!running) render() }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
    }

    override fun onPause() {
        BandService.state.remove(watch)
        // Leaving the screen ends the recording; what was written is kept.
        if (running) stop("Stopped: the screen was left.")
        super.onPause()
    }

    private fun service() = BandService.instance?.takeIf { BandService.state.value.phase == Phase.CONNECTED }

    private fun render() {
        buttons.removeAllViews()
        settings.removeAllViews()
        when {
            running -> {
                buttons.addView(pill(if (paused) "Resume" else "Pause") { if (paused) resume() else pause() }.first)
                if (at > 0) buttons.addView(pill("Redo last") { redo() }.first)
                buttons.addView(pill("Stop") { stop("Stopped.") }.first)
            }
            else -> {
                val last = saved
                if (last != null) {
                    count.text = "SAVED"
                    letter.text = "✓"
                    letter.setTextColor(Cb.INK_900)
                    cue.text = last
                } else {
                    count.text = "RECORD HANDWRITING"
                    letter.text = "a"
                    cue.text = if (service() == null) "Connect the band first." else
                        "Tap Start, then rest your index finger on the table: it counts down from $COUNTDOWN. Each letter shows grey for ${seconds(gapMs)}, then turns dark: write it within ${seconds(windowMs)}. $reps of each letter, in random order."
                }
                settings.addView(pill("Reps: $reps") { reps = if (reps >= 10) 1 else reps + 1; remember() }.first)
                settings.addView(pill("Write: ${seconds(windowMs)}") { windowMs = following(WRITE_MS, windowMs); remember() }.first)
                settings.addView(pill("Ready: ${seconds(gapMs)}") { gapMs = following(READY_MS, gapMs); remember() }.first)
                buttons.addView(pill("Start", R.drawable.ic_check) { start() }.first)
            }
        }
    }

    private fun start() {
        val service = service() ?: return run { cue.text = "Connect the band first." }
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(getExternalFilesDir("handwriting"), name)
        if (!service.recording.recordStart(dir.path)) return run { cue.text = "The band isn't connected." }
        folder = dir
        service.startPractice(quiet)
        service.rawEmg(true)
        service.recording.recordMark("session", "reps=$reps window_ms=$windowMs gap_ms=$gapMs")
        prompts = ('a'..'z').flatMap { c -> List(reps) { c.toString() } }.shuffled()
        at = 0
        running = true
        paused = false
        render()
        countdown(COUNTDOWN)
        main.post(statsTick)
    }

    /** Show the next letter, then open its writing window. */
    private fun next() {
        if (!running || paused) return
        if (at >= prompts.size) return stop("Done: all ${prompts.size} letters.")
        val shown = prompts[at]
        val recording = BandService.instance?.recording ?: return stop("The band service stopped.")
        count.text = "LETTER ${at + 1} OF ${prompts.size}"
        letter.text = shown
        letter.setTextColor(Cb.INK_400)
        cue.text = "Get ready…"
        recording.recordMark("prompt", shown)
        main.postDelayed({
            if (!running || paused) return@postDelayed
            recording.recordMark("start", shown)
            letter.setTextColor(Cb.INK_900)
            cue.text = "Write it now"
            main.postDelayed({
                if (!running) return@postDelayed
                recording.recordMark("end", shown)
                at++
                next()
            }, windowMs)
        }, gapMs)
    }

    private fun remember() {
        settingsStore.edit().putInt("reps", reps).putLong("write_ms", windowMs).putLong("ready_ms", gapMs).apply()
        render()
    }

    /** The choice after [current] in [choices], wrapping round. */
    private fun following(choices: List<Long>, current: Long) = choices.firstOrNull { it > current } ?: choices.first()

    private fun seconds(ms: Long) = "${java.math.BigDecimal(ms).movePointLeft(3).stripTrailingZeros().toPlainString()} s"

    /** "Starting in N…", then the next letter. */
    private fun countdown(seconds: Int) {
        if (!running || paused) return
        if (seconds == 0) return next()
        count.text = "GET READY"
        letter.text = seconds.toString()
        letter.setTextColor(Cb.INK_400)
        cue.text = "Rest your index finger on the table"
        main.postDelayed({ countdown(seconds - 1) }, 1000)
    }

    private fun pause() {
        paused = true
        // The letter on screen (if its window was open) is asked again after the pause.
        main.removeCallbacksAndMessages(null)
        main.post(statsTick)
        BandService.instance?.recording?.recordMark("pause", prompts.getOrNull(at) ?: "")
        cue.text = "Paused"
        render()
    }

    private fun resume() {
        paused = false
        render()
        countdown(3)
    }

    /** The last letter was written wrong: mark it to be left out, and ask for it again. */
    private fun redo() {
        val last = prompts.getOrNull(at - 1) ?: return
        BandService.instance?.recording?.recordMark("discard", last)
        prompts = prompts.toMutableList().apply { add(at, last) }
        cue.text = "“$last” will come again"
    }

    private val statsTick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val counts = BandService.instance?.recording?.recordStats()
                ?: return stop("Stopped: the band reconnected, so the recording ended. What was written is kept.")
            val batches = counts.optLong("batches")
            val missing = counts.optLong("missing")
            val lost = if (batches + missing > 0) 100.0 * missing / (batches + missing) else 0.0
            stats.text = String.format(Locale.ROOT, "%d batches · %.1f %% lost · %d marks", batches, lost, counts.optLong("marks"))
            main.postDelayed(this, 1000)
        }
    }

    private fun stop(why: String) {
        running = false
        main.removeCallbacksAndMessages(null)
        BandService.instance?.let {
            it.rawEmg(false)
            it.recording.recordStop()
            it.endPractice()
        }
        saved = "$why\n\nadb pull ${folder?.path}"
        folder = null
        render()
    }

    companion object {
        /** Seconds of "get ready" before the first letter. */
        const val COUNTDOWN = 5
        /** What the Write and Ready buttons step through. */
        val WRITE_MS = listOf(1000L, 1500L, 2000L, 2500L, 3000L, 4000L, 5000L)
        val READY_MS = listOf(250L, 500L, 1000L, 1500L, 2000L, 3000L)
    }
}
