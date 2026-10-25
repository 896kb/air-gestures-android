package dev.kb896.airgestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug builds only: the band's own handwriting (firmware build 64 and later). Start puts the
 * band in its handwriting state; it then sends 100 scores per frame, read here as Meta AI does
 * (best symbol per frame, repeats merged, blanks dropped) and shown as text and as the tokens
 * that came, each with how sure the band was (its margin over the runner-up). Stop, or leaving
 * the screen, puts the band back to gestures.
 */
class BandWriteActivity : Screen() {
    private val main = Handler(Looper.getMainLooper())
    private var writing = false

    private lateinit var text: TextView
    private lateinit var tokens: TextView
    private lateinit var stats: TextView
    private lateinit var buttons: LinearLayout

    /** Gestures go nowhere while the band writes. */
    private val quiet = object : BandService.Practice {
        override fun gesture(label: String) = Unit
        override fun action(id: String) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        text = Cb.text(this, 32f, R.font.geist_bold, Cb.INK_900, -.02f)
        tokens = Cb.text(this, 14f, R.font.geist_mono_medium, Cb.INK_700).apply { setLineSpacing(0f, 1.3f) }
        stats = Cb.text(this, 12f, R.font.geist_mono_medium, Cb.INK_500)
        buttons = row(gap = 10f)
        show(pageColumn().apply {
            addView(card(null, column(gap = 12f).apply {
                addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                    text = "Start, then write letters with your index finger on a table. The band's own " +
                        "handwriting model reads them; below is what it sends."
                    setLineSpacing(0f, 1.3f)
                })
                addView(Cb.label(context, "Text"))
                addView(text)
                addView(Cb.label(context, "Tokens (symbol, index, margin)"))
                addView(tokens)
                addView(stats)
            }))
            addView(buttons)
        }, title = "Band handwriting")
        render()
    }

    override fun onPause() {
        if (writing) stop()
        super.onPause()
    }

    private fun render() {
        buttons.removeAllViews()
        buttons.addView(pill(if (writing) "Stop" else "Start") { if (writing) stop() else start() }.first)
        buttons.addView(pill("Clear") { BandService.instance?.bandHandwriting(clear = true); refresh() }.first)
    }

    private fun start() {
        val service = BandService.instance
        if (service == null || BandService.state.value.phase != Phase.CONNECTED) {
            stats.text = "Connect the band first."
            return
        }
        service.startPractice(quiet)
        service.setBandHandwriting(true)
        writing = true
        render()
        main.post(tick)
    }

    private fun stop() {
        writing = false
        BandService.instance?.let {
            it.setBandHandwriting(false)
            it.endPractice()
        }
        main.removeCallbacks(tick)
        refresh()
        render()
    }

    private val tick: Runnable = object : Runnable {
        override fun run() {
            refresh()
            if (writing) main.postDelayed(this, 200)
        }
    }

    private fun refresh() {
        val state = BandService.instance?.bandHandwriting() ?: return
        text.text = state.optString("text").ifEmpty { "—" }
        val list = state.optJSONArray("tokens")
        tokens.text = if (list == null || list.length() == 0) "none yet" else (maxOf(0, list.length() - 60) until list.length()).joinToString("  ") { i ->
            val token = list.getJSONObject(i)
            "${token.getString("symbol")} ${token.getInt("index")} ${"%.1f".format(token.getDouble("margin"))}"
        }
        stats.text = "frames ${state.optInt("frames")} · samples ${state.optInt("samples")} · " +
            "pipeline ${state.optInt("pipeline")} · ${state.optInt("floats")} scores per sample"
    }
}
