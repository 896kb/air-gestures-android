package dev.kb896.airgestures

import android.os.Bundle
import android.widget.TextView

/** The live log: connection, claim and gesture lines, newest last. */
class ActivityLogActivity : Screen() {
    private val watch: (BandService.State) -> Unit = { state ->
        lines.text = state.log.joinToString("\n").ifEmpty { "Nothing yet." }
    }
    private lateinit var lines: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lines = Cb.text(this, 12f, R.font.geist_mono_regular, Cb.INK_500).apply {
            setLineSpacing(0f, 1.35f)
            setTextIsSelectable(true)
        }
        show(pageColumn().apply { addView(card(null, lines)) }, title = "Activity")
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
    }

    override fun onPause() {
        BandService.state.remove(watch)
        super.onPause()
    }
}
