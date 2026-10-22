package dev.kb896.airgestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Debug builds only: write words with the band. Letters are written one at a time on a table
 * with the index finger (a letter ends once the finger has been still for the recorder's Still
 * time); the dictionary (assets/words.txt) turns them into words and completions
 * (rust/bridge/src/words.rs). A line drawn right is a space and one drawn left deletes, and
 * full stops, commas and question marks can be written, once the model knows them (record them
 * with the recorder's "+ space, delete" and "+ punctuation" sets). The band's gestures also edit:
 * swipe right (or an index tap) takes the highlighted word and a space, swipe left deletes,
 * swipe up and down move through the suggestions. Everything is recorded like the recorder's
 * sessions; words taken whole label their letters for training.
 */
class WriteActivity : Screen() {
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var folder: File? = null
    private var stillMs = 700L
    private var phrase = PHRASES.random()

    /** The letter being written began here (NaN: none); the last one ended here. */
    private var writingAt = Double.NaN
    private var lastEnd = 0.0
    /** After a gesture, activity before this isn't a letter (it's the gesture). */
    private var quietUntil = 0.0

    private lateinit var prompt: TextView
    private lateinit var written: TextView
    private lateinit var asRead: TextView
    private lateinit var suggestions: LinearLayout
    private lateinit var hint: TextView
    private lateinit var stats: TextView
    private lateinit var buttons: LinearLayout

    private val gestures = object : BandService.Practice {
        override fun gesture(label: String) = onGesture(label)
        override fun action(id: String) = Unit
        override fun held() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stillMs = getSharedPreferences("recorder", MODE_PRIVATE).getLong("still_ms", stillMs)
        loadWords()
        prompt = Cb.text(this, 15f, R.font.geist_medium, Cb.INK_500)
        written = Cb.text(this, 30f, R.font.geist_semibold, Cb.INK_900).apply { minLines = 2 }
        asRead = Cb.text(this, 13f, R.font.geist_mono_medium, Cb.INK_400)
        suggestions = column(gap = 6f)
        hint = Cb.text(this, 13f, R.font.geist_regular, Cb.INK_500).apply {
            text = "Draw a line to the right for a space, to the left to delete (once they're recorded and trained). " +
                "Or gestures: swipe right or index tap = take the word + space · swipe left = delete · swipe up/down = choose a suggestion"
        }
        stats = Cb.text(this, 13f, R.font.geist_mono_medium, Cb.INK_500)
        buttons = row(gap = 10f).apply { gravity = Gravity.END }
        val wide = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        show(pageColumn().apply {
            addView(card(null, column(gap = 12f).apply {
                addView(Cb.label(this@WriteActivity, "Write"))
                addView(prompt, wide)
                addView(written, wide)
                addView(asRead, wide)
                addView(suggestions, wide)
                addView(stats, wide)
            }))
            addView(hint, wide)
            addView(buttons, wide)
        }, title = "Handwriting: words")
        render(null)
    }

    override fun onPause() {
        if (running) stop()
        super.onPause()
    }

    private fun recording() = BandService.instance?.recording

    private fun start() {
        val service = BandService.instance?.takeIf { BandService.state.value.phase == Phase.CONNECTED }
            ?: return run { prompt.text = "Connect the band first." }
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(getExternalFilesDir("handwriting"), name)
        if (!service.recording.recordStart(dir.path)) return run { prompt.text = "The band isn't connected." }
        folder = dir
        service.startPractice(gestures)
        service.rawEmg(true)
        service.recording.recordMark("session", "words still_ms=$stillMs")
        running = true
        writingAt = Double.NaN
        lastEnd = service.recording.clock()
        render(service.recording.recordEdit("clear"))
        main.post(watch)
        main.post(statsTick)
    }

    private fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        BandService.instance?.let {
            it.rawEmg(false)
            it.recording.recordStop()
            it.endPractice()
        }
        render(null)
        prompt.text = "Saved: adb pull ${folder?.path}"
        folder = null
    }

    /** Every [POLL_MS]: a letter starts when the muscles become active and ends once they've been still. */
    private val watch: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val recording = recording() ?: return stop()
            val counts = recording.recordStats() ?: return stop()
            val now = recording.clock()
            val active = counts.optBoolean("active")
            val onset = counts.optDouble("onset", Double.NaN)
            val offset = counts.optDouble("offset", Double.NaN)
            if (writingAt.isNaN()) {
                if (onset > maxOf(lastEnd, quietUntil)) writingAt = onset - PRE_S
            } else {
                val still = !active && offset > writingAt && now - offset >= stillMs / 1000.0
                if (still || now - writingAt > MAX_LETTER_S) letter(writingAt, if (still) minOf(now, offset + POST_S) else now)
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun letter(start: Double, end: Double) {
        writingAt = Double.NaN
        lastEnd = end
        render(recording()?.recordWrite(start, end))
    }

    private fun onGesture(label: String) {
        if (!running) return
        val edit = when (label) {
            "swipe right", "index tap" -> "space"
            "swipe left" -> "backspace"
            "swipe up" -> "previous"
            "swipe down" -> "next"
            else -> return
        }
        val recording = recording() ?: return
        val now = recording.clock()
        // The hand moving for the gesture isn't a letter. A letter still being waited on is kept
        // if it was written well before the gesture (the gesture came before the finger was still).
        if (!writingAt.isNaN()) {
            val end = now - GESTURE_LEAD_S
            if (end - writingAt >= MIN_LETTER_S) letter(writingAt, end) else writingAt = Double.NaN
        }
        quietUntil = now + GESTURE_QUIET_S
        lastEnd = maxOf(lastEnd, now)
        render(recording.recordEdit(edit))
    }

    private fun edit(edit: String) {
        if (running) render(recording()?.recordEdit(edit))
    }

    private fun render(state: JSONObject?) {
        buttons.removeAllViews()
        if (running) {
            buttons.addView(pill("Delete") { edit("backspace") }.first)
            buttons.addView(pill("Space") { edit("space") }.first)
            buttons.addView(pill("Clear") { edit("clear") }.first)
            buttons.addView(pill("Stop") { stop() }.first)
        } else {
            buttons.addView(pill("New phrase") { phrase = PHRASES.random(); render(null) }.first)
            buttons.addView(pill("Start", R.drawable.ic_check) { start() }.first)
        }
        prompt.text = "Try writing: $phrase"
        suggestions.removeAllViews()
        if (state == null) {
            if (!running) written.text = ""
            asRead.text = ""
            return
        }
        val word = state.optString("word")
        written.text = SpannableStringBuilder(state.optString("text")).apply {
            val at = length
            append(word)
            setSpan(UnderlineSpan(), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(Cb.INK_500), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("▏")
        }
        val letters = state.optString("letters")
        asRead.text = if (letters.isEmpty()) "" else "letters as read: $letters"
        val offered = state.optJSONArray("suggestions")
        val highlight = state.optInt("highlight")
        for (i in 0 until (offered?.length() ?: 0)) {
            val chosen = i == highlight
            suggestions.addView(Cb.text(this, if (chosen) 20f else 17f, if (chosen) R.font.geist_semibold else R.font.geist_regular, if (chosen) Cb.INK_900 else Cb.INK_500).apply {
                text = (if (chosen) "›  " else "    ") + offered!!.getString(i)
            })
        }
    }

    private val statsTick: Runnable = object : Runnable {
        override fun run() {
            if (!running) return
            val counts = recording()?.recordStats() ?: return stop()
            val batches = counts.optLong("batches")
            val missing = counts.optLong("missing")
            val lost = if (batches + missing > 0) 100.0 * missing / (batches + missing) else 0.0
            val reader = if (counts.optBoolean("model")) "trained model" else "${counts.optLong("known")} letters known"
            val words = if (wordCount > 0) "$wordCount words" else "no dictionary"
            stats.text = String.format(Locale.ROOT, "%.1f %% lost · %s · %s", lost, reader, words)
            main.postDelayed(this, 1000)
        }
    }

    /** The dictionary into the bridge, once per process (it's shared). */
    private fun loadWords() {
        if (wordCount > 0) return
        thread(name = "words") {
            val text = assets.open("words.txt").bufferedReader().use { it.readText() }
            wordCount = Bridge.wordsLoad(text)
        }
    }

    companion object {
        @Volatile private var wordCount = 0
        const val POLL_MS = 50L
        /** A letter's window starts this long before the muscles became active, and ends this long after. */
        const val PRE_S = 0.15
        const val POST_S = 0.1
        const val MAX_LETTER_S = 6.0
        /** A gesture arrives about this long after the hand began making it. */
        const val GESTURE_LEAD_S = 0.5
        /** Activity this soon after a gesture is still the gesture. */
        const val GESTURE_QUIET_S = 0.3
        /** Shorter than this, activity isn't a letter. */
        const val MIN_LETTER_S = 0.3
        /** Something to write. */
        val PHRASES = listOf(
            "see you soon", "on my way", "how are you", "thank you", "running late",
            "call me later", "sounds good", "what time is it", "where are you", "good morning",
            "talk to you tomorrow", "i will be there", "love you", "can you help me", "be right back",
            "let me know", "what do you think", "have a good day", "i am home", "pick up some milk",
        )
    }
}
