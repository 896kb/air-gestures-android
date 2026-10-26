package dev.kb896.airgestures

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * During calls (mockup 5): what the gestures do while the phone rings or a call is on, in every
 * profile; one action per gesture (no palm overrides), the drags as usual. A gesture a band
 * control is on shows its action Paused, and the note under the rows says which.
 */
class CallsActivity : GestureScreen() {
    private lateinit var rows: LinearLayout
    private lateinit var note: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        rows = column()
        note = Cb.text(this, 13f, color = Cb.INK_500).apply { setLineSpacing(0f, 1.3f) }
        show(pageColumn().apply {
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                text = "While the phone rings or a call is on, in every profile. Phone calls only; WhatsApp and other app calls can't be answered by another app."
                setLineSpacing(0f, 1.3f)
            })
            addView(card(null, rows))
            addView(note)
        }, title = "During calls")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        rows.removeAllViews()
        rows.addView(callRows(Report.of(this)))
        val text = bandNote(Profiles.band(this))
        note.text = text.orEmpty()
        note.visibility = if (text == null) View.GONE else View.VISIBLE
    }
}
