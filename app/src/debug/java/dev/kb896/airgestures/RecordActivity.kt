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
 * Debug builds only: record handwriting for a letter recogniser. Each letter is shown and
 * written on a table with the index finger: self-paced (the next letter comes once the
 * muscles have been still for a moment, so an i's dot still counts) or in timed windows. The
 * band's raw sEMG and the marks go to a folder (emg.bin, marks.csv; see
 * rust/bridge/src/recorder.rs) to pull with adb. Nothing runs while it records (the service
 * is in practice mode). Each letter is also read as it ends, by the trained model when one is
 * in the recordings folder or else against the letters in earlier recordings and written since
 * (rust/bridge/src/letters.rs), with a running score.
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
    /** What's asked for: letters, then also the strokes (space, delete), then also punctuation. */
    private var set = 0
    private val settingsStore by lazy { getSharedPreferences("recorder", MODE_PRIVATE) }
    private var windowMs = 2000L
    /** From a letter appearing to its writing window. */
    private var gapMs = 500L
    /** Self-paced: a letter is done once the muscles have been still this long. */
    private var auto = true
    private var stillMs = 700L

    /** Self-paced: when the letter on screen appeared, and when its writing began (NaN: not yet). */
    private var promptAt = 0.0
    private var writingAt = Double.NaN
    /** Timed: when the letter's window opened. */
    private var windowAt = 0.0

    /** Letters read so far, how many right first time and in the first three, and the last one's result. */
    private var read = 0
    private var right = 0
    private var inThree = 0
    private var lastRead: Pair<Boolean, Boolean>? = null

    private lateinit var count: TextView
    private lateinit var letter: TextView
    private lateinit var cue: TextView
    private lateinit var reading: TextView
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
        set = settingsStore.getInt("set", set)
        windowMs = settingsStore.getLong("write_ms", windowMs)
        gapMs = settingsStore.getLong("ready_ms", gapMs)
        auto = settingsStore.getBoolean("auto", auto)
        stillMs = settingsStore.getLong("still_ms", stillMs)
        count = Cb.label(this, "")
        letter = Cb.text(this, 120f, R.font.geist_bold, Cb.INK_900, -.02f).apply { gravity = Gravity.CENTER }
        cue = Cb.text(this, 20f, R.font.geist_semibold, Cb.INK_500).apply { gravity = Gravity.CENTER }
        reading = Cb.text(this, 15f, R.font.geist_semibold, Cb.INK_700).apply { gravity = Gravity.CENTER }
        stats = Cb.text(this, 13f, R.font.geist_mono_medium, Cb.INK_500)
        buttons = row(gap = 10f).apply { gravity = Gravity.END }
        settings = row(gap = 10f).apply { gravity = Gravity.END }
        show(pageColumn().apply {
            addView(card(null, column(gap = 12f).apply {
                addView(count)
                addView(letter, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(cue, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(reading, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
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
                    letter.textSize = 120f
                    letter.setTextColor(Cb.INK_900)
                    cue.text = last
                } else {
                    count.text = "RECORD HANDWRITING"
                    letter.text = "a"
                    letter.textSize = 120f
                    cue.text = when {
                        service() == null -> "Connect the band first."
                        auto -> "Tap Start, then rest your index finger on the table: it counts down from $COUNTDOWN. Write each letter as it shows; the next comes once your finger has been still for ${seconds(stillMs)}. $reps of each letter, in random order."
                        else -> "Tap Start, then rest your index finger on the table: it counts down from $COUNTDOWN. Each letter shows grey for ${seconds(gapMs)}, then turns dark: write it within ${seconds(windowMs)}. $reps of each letter, in random order."
                    }
                }
                settings.addView(pill(SETS[set]) { set = (set + 1) % SETS.size; remember() }.first)
                settings.addView(pill("Reps: $reps") { reps = if (reps >= 10) 1 else reps + 1; remember() }.first)
                if (auto) {
                    settings.addView(pill("Still: ${seconds(stillMs)}") { stillMs = following(STILL_MS, stillMs); remember() }.first)
                } else {
                    settings.addView(pill("Write: ${seconds(windowMs)}") { windowMs = following(WRITE_MS, windowMs); remember() }.first)
                    settings.addView(pill("Ready: ${seconds(gapMs)}") { gapMs = following(READY_MS, gapMs); remember() }.first)
                }
                buttons.addView(pill(if (auto) "Self-paced" else "Timed") { auto = !auto; remember() }.first)
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
        service.recording.recordMark("session", if (auto) "reps=$reps self_paced still_ms=$stillMs" else "reps=$reps window_ms=$windowMs gap_ms=$gapMs")
        val letters = ('a'..'z').map { it.toString() }
        prompts = when (set) {
            0 -> letters
            1 -> letters + STROKES.keys.take(2)
            2 -> letters + STROKES.keys
            3 -> STROKES.keys.toList()
            else -> emptyList()
        }.flatMap { label -> List(reps) { label } }.shuffled()
        // Whole words, written without stopping between letters: for reading joined-up writing.
        if (set == WORDS_SET) prompts = commonWords().shuffled().take(WORDS_PER_REP * reps)
        at = 0
        read = 0
        right = 0
        inThree = 0
        lastRead = null
        reading.text = ""
        running = true
        paused = false
        render()
        countdown(COUNTDOWN)
        main.post(statsTick)
    }

    /** Show the next letter, then open its writing window. */
    private fun next() {
        if (!running || paused) return
        if (auto) return nextSelfPaced()
        if (at >= prompts.size) return stop("Done: all ${prompts.size} letters.")
        val shown = prompts[at]
        val recording = BandService.instance?.recording ?: return stop("The band service stopped.")
        count.text = "LETTER ${at + 1} OF ${prompts.size}"
        showPrompt(shown)
        letter.setTextColor(Cb.INK_400)
        cue.text = "Get ready…"
        recording.recordMark("prompt", shown)
        main.postDelayed({
            if (!running || paused) return@postDelayed
            windowAt = recording.clock()
            recording.recordMark("start", shown, windowAt)
            letter.setTextColor(Cb.INK_900)
            cue.text = "Write it now"
            main.postDelayed({
                if (!running) return@postDelayed
                val end = recording.clock()
                recording.recordMark("end", shown, end)
                judge(shown, windowAt, end)
                at++
                next()
            }, windowMs)
        }, gapMs)
    }

    /** Self-paced: show the letter at once and watch for it being written. */
    private fun nextSelfPaced() {
        if (at >= prompts.size) return stop("Done: all ${prompts.size} letters.")
        val shown = prompts[at]
        val recording = BandService.instance?.recording ?: return stop("The band service stopped.")
        count.text = "LETTER ${at + 1} OF ${prompts.size}"
        showPrompt(shown)
        letter.setTextColor(Cb.INK_900)
        cue.text = STROKES[shown]?.let { "Draw ${it.second}" }
            ?: if (set == WORDS_SET) "Write the whole word without stopping, then rest" else "Write it"
        promptAt = recording.clock()
        writingAt = Double.NaN
        recording.recordMark("prompt", shown, promptAt)
        main.postDelayed(watchWriting, POLL_MS)
    }

    /**
     * Self-paced, every [POLL_MS]: the letter starts when the muscles become active after it
     * appeared (or are active already), and is done once they've been still for [stillMs], or
     * after [MAX_LETTER_S] at most. Its window runs from just before the start to just after.
     */
    private val watchWriting: Runnable = object : Runnable {
        override fun run() {
            if (!running || paused || !auto) return
            val recording = BandService.instance?.recording ?: return stop("The band service stopped.")
            val counts = recording.recordStats() ?: return stop("Stopped: the band reconnected, so the recording ended. What was written is kept.")
            val now = recording.clock()
            val active = counts.optBoolean("active")
            val onset = counts.optDouble("onset", Double.NaN)
            val offset = counts.optDouble("offset", Double.NaN)
            if (writingAt.isNaN()) {
                when {
                    onset > promptAt -> writingAt = maxOf(promptAt, onset - PRE_S)
                    active -> writingAt = promptAt
                    now - promptAt > 10 -> cue.text = "Waiting for you to write it…"
                }
                if (!writingAt.isNaN()) {
                    cue.text = "…"
                    letter.setTextColor(Cb.INK_400)
                }
            } else {
                val still = !active && offset > writingAt && now - offset >= stillMs / 1000.0
                if (still || now - writingAt > MAX_LETTER_S) {
                    val shown = prompts[at]
                    val end = if (still) minOf(now, offset + POST_S) else now
                    if (set == WORDS_SET) {
                        // Not letters: kept apart from them (read_windows skips these).
                        recording.recordMark("word_start", shown, writingAt)
                        recording.recordMark("word_end", shown, end)
                    } else {
                        recording.recordMark("start", shown, writingAt)
                        recording.recordMark("end", shown, end)
                        judge(shown, writingAt, end)
                    }
                    at++
                    return nextSelfPaced()
                }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    /** Read the letter just written ([shown]) and keep score. */
    private fun judge(shown: String, start: Double, end: Double) {
        val guesses = BandService.instance?.recording?.recordGuess(shown, start, end).orEmpty()
        if (guesses.isEmpty()) {
            lastRead = null
            reading.text = "“$shown”: nothing to compare with yet; it's learnt for next time."
            return
        }
        val result = Pair(guesses.first() == shown, shown in guesses.take(3))
        lastRead = result
        read++
        if (result.first) right++
        if (result.second) inThree++
        reading.text = "“${glyph(shown)}” read as ${glyph(guesses.first())} ${if (result.first) "✓" else "✗"}   then ${guesses.drop(1).take(2).joinToString(", ", transform = ::glyph)}\n" + score()
    }

    private fun score() = if (read == 0) "" else
        "$right of $read right first time (${100 * right / read} %), $inThree in the first three (${100 * inThree / read} %)"

    private fun remember() {
        settingsStore.edit().putInt("reps", reps).putInt("set", set).putLong("write_ms", windowMs).putLong("ready_ms", gapMs)
            .putBoolean("auto", auto).putLong("still_ms", stillMs).apply()
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
        letter.textSize = 120f
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
        lastRead?.let { (first, three) ->
            read--
            if (first) right--
            if (three) inThree--
            lastRead = null
            reading.text = score()
        }
        // Right after the letter on screen, which keeps its place.
        prompts = prompts.toMutableList().apply { add(minOf(at + 1, size), last) }
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
            val reader = if (counts.optBoolean("model")) "read by the trained model" else "${counts.optLong("known")} letters known"
            stats.text = String.format(Locale.ROOT, "%d batches · %.1f %% lost · %d marks · %s", batches, lost, counts.optLong("marks"), reader)
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
        saved = listOf(why, score(), "adb pull ${folder?.path}").filter { it.isNotEmpty() }.joinToString("\n\n")
        folder = null
        render()
    }

    /** Common words of 3–7 letters from the dictionary, for the whole-words set. */
    private fun commonWords(): List<String> = assets.open("words.txt").bufferedReader().useLines { lines ->
        lines.filterNot { it.startsWith("#") }.map { it.substringBefore(' ') }.filter { it.length in 3..7 }.take(400).toList()
    }

    /** The prompt, smaller when it's a whole word. */
    private fun showPrompt(shown: String) {
        letter.text = glyph(shown)
        letter.textSize = if (letter.text.length > 2) 64f else 120f
    }

    /** How a prompt shows: a letter as itself, a stroke or mark as its symbol. */
    private fun glyph(label: String) = STROKES[label]?.first ?: label

    companion object {
        /** Strokes and punctuation as labelled in recordings: what's shown, and what to draw. */
        val STROKES = linkedMapOf(
            "space" to ("→" to "a line to the right (space)"),
            "backspace" to ("←" to "a line to the left (delete)"),
            "period" to ("." to "a full stop: a dot"),
            "comma" to ("," to "a comma"),
            "question" to ("?" to "a question mark"),
        )
        val SETS = listOf("Letters", "+ space, delete", "+ punctuation", "Strokes only", "Whole words")
        const val WORDS_SET = 4
        const val WORDS_PER_REP = 10
        /** Seconds of "get ready" before the first letter. */
        const val COUNTDOWN = 5
        /** What the Write and Ready buttons step through. */
        val WRITE_MS = listOf(1000L, 1500L, 2000L, 2500L, 3000L, 4000L, 5000L)
        val READY_MS = listOf(250L, 500L, 1000L, 1500L, 2000L, 3000L)
        /** What the Still button steps through: long enough for an i's dot, short enough to flow. */
        val STILL_MS = listOf(250L, 300L, 400L, 500L, 700L, 1000L, 1500L)
        const val POLL_MS = 50L
        /** A letter's window starts this long before the muscles became active, and ends this long after. */
        const val PRE_S = 0.15
        const val POST_S = 0.1
        /** The most a self-paced letter can take. */
        const val MAX_LETTER_S = 6.0
    }
}
