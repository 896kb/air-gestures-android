package dev.kb896.airgestures

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Handwriting: whether the band writes into text fields (and which band, with two), how it
 * starts in a field, and the Handwriting layer, what each gesture does there while the pen
 * is down ([HandwritingLayer]); with the pen up the gestures are as usual.
 */
class HandwritingActivity : GestureScreen() {
    private lateinit var on: LinearLayout
    private lateinit var start: LinearLayout
    private lateinit var rows: LinearLayout
    private lateinit var note: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        on = column(gap = 8f)
        start = column(gap = 8f)
        rows = column(gap = 14f)
        note = Cb.text(this, 13f, color = Cb.INK_500).apply { setLineSpacing(0f, 1.3f) }
        show(pageColumn().apply {
            addView(card(null, column(gap = 16f).apply {
                addView(on)
                addView(start)
            }))
            addView(card("While writing", column(gap = 8f).apply {
                addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                    text = "What each gesture does in a text field while the pen is down, over the profile in force. " +
                        "As usual keeps the profile's action; with the pen up every gesture is as usual but the ones that bring it back."
                    setLineSpacing(0f, 1.3f)
                })
                addView(rows)
            }))
            addView(note)
        }, title = "Handwriting")
        titleButton(R.drawable.ic_help, "How handwriting works") { help() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        renderOn()
        renderStart()
        renderRows()
    }

    /** Off, or on in text fields (with two bands: which band writes). */
    private fun renderOn() {
        on.removeAllViews()
        val writer = Bands.writer(this)
        val two = Bands.two(this)
        val choices = listOf<Pair<String, Int?>>("Off" to null) +
            if (two) Bands.inOrder(this).map { Bands.name(this, it) to it } else listOf("In text fields" to 0)
        on.addView(Cb.label(this, "Handwriting"))
        on.addView(segmentedOf(choices.map { it.first }, choices.indexOfFirst { it.second == writer }) { i -> setWriter(choices[i].second) })
        on.addView(Cb.text(this, 13f, color = Cb.INK_500).apply {
            text = when {
                writer == null -> "Write letters with a finger on a table or your leg, into the text field in focus."
                BandService.instance?.bandWrites(writer) == false -> "Needs band firmware build 64 or later: Setup › Band firmware updates"
                !WriteService.enabled(this@HandwritingActivity) -> "Turn on its accessibility service to start"
                else -> "In a text field, write letters on a table with a finger."
            }
        })
    }

    /** How handwriting starts in a field. */
    private fun renderStart() {
        start.removeAllViews()
        val now = HandwritingLayer.start(this)
        start.addView(Cb.label(this, "In a text field it starts"))
        start.addView(segmentedOf(WriteStart.entries.map { it.label }, now.ordinal) { i ->
            HandwritingLayer.setStart(this, WriteStart.entries[i])
            render()
        })
        start.addView(Cb.text(this, 13f, color = Cb.INK_500).apply {
            text = when (now) {
                WriteStart.RIGHT_AWAY -> "The pen is down as the field gets the focus."
                WriteStart.DOTS -> "The pen starts up: your gestures stay as usual until you write two dots (..) or use Pen down."
                WriteStart.GESTURE -> if (HandwritingLayer.dotsStart(this)) {
                    "Off until you make the gesture set to Handwriting on/off below, or write two dots (..); the gesture turns it off again too. " +
                        "The band listens for the dots in a text field meanwhile, your gestures as usual."
                } else {
                    "Off until you make the gesture set to Handwriting on/off below; it turns it off again too."
                }
            }
        })
        // On a gesture with none set: it would never start.
        if (now == WriteStart.GESTURE && !HandwritingLayer.dotsStart(this) && HandwritingLayer.load(this).values.none { it == WriteAction.TOGGLE.id }) {
            start.addView(Cb.text(this, 13f, R.font.geist_semibold, WARNING_RED).apply {
                text = "No gesture is set to Handwriting on/off yet, so handwriting never starts. Set one under While writing."
                setLineSpacing(0f, 1.3f)
            })
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        val layer = HandwritingLayer.load(this)
        for (part in Part.entries) {
            rows.addView(Cb.label(this, part.label))
            rows.addView(column(gap = 4f).apply {
                for (gesture in Gesture.entries.filter { it.part == part }) {
                    val value = layer[gesture.key] ?: HandwritingLayer.default(gesture)
                    val action = WriteAction.of(value)
                    addView(gestureRow(action?.icon ?: R.drawable.ic_minus, gesture.short, label(value), action == null) {
                        pickFor(gesture, value)
                    })
                }
            })
        }
        // Dots tapped on the surface: only the band's handwriting sees them.
        rows.addView(row(gap = 6f).apply {
            addView(Cb.label(context, "On the surface"))
            addView(FrameLayout(context).apply {
                addView(icon(R.drawable.ic_help, 18f, Cb.INK_500), FrameLayout.LayoutParams(dpi(18f), dpi(18f), Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(dpi(30f), dpi(30f))
                contentDescription = "What dots on the surface are"
                isClickable = true
                background = ripple(999f)
                setOnClickListener { dotsHelp() }
            })
        })
        rows.addView(column(gap = 4f).apply {
            addView(dotsRow("Two dots (..)", HandwritingLayer.dots(this@HandwritingActivity), WriteAction.PEN_DOWN.id) { HandwritingLayer.setDots(applicationContext, it) })
            addView(dotsRow("Three dots (...)", HandwritingLayer.dots3(this@HandwritingActivity), HandwritingLayer.AS_USUAL) { HandwritingLayer.setDots3(applicationContext, it) })
        })
        rows.addView(Cb.label(this, "Dials and sliders"))
        val dials = HandwritingLayer.dials(this)
        rows.addView(gestureRow(R.drawable.ic_minus, "Pinch and turn, drags as sliders", if (dials) "As usual" else "Nothing", true) {
            choose("Dials and sliders", listOf(Option("As usual", dials, R.drawable.ic_minus), Option("Nothing", !dials, R.drawable.ic_minus)), heading = "While writing") { i ->
                HandwritingLayer.setDials(applicationContext, i == 0)
                render()
            }
        })
        // What the start needs and nothing gives.
        val text = when {
            HandwritingLayer.start(this) == WriteStart.DOTS && HandwritingLayer.dots(this) == HandwritingLayer.NOTHING ->
                "Two dots are set to Nothing, so the pen never comes down in a field: set them to Pen down."
            HandwritingLayer.start(this) == WriteStart.DOTS && layer.values.none { it == WriteAction.PEN_DOWN.id } ->
                "Write two dots (..) to put the pen down; no gesture does."
            else -> null
        }
        note.text = text.orEmpty()
        note.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    /**
     * A row for dots on the surface: [usual] is what they do when they aren't a gesture (two
     * dots: Pen down, else typed; three: typed, as two and a full stop).
     */
    private fun dotsRow(name: String, value: String, usual: String, set: (String) -> Unit): View {
        fun shown(v: String) = when {
            v == usual && v == WriteAction.PEN_DOWN.id -> "Pen down (else typed)"
            v == usual -> "Typed (not a gesture)"
            else -> label(v)
        }
        val action = WriteAction.of(value)
        return gestureRow(action?.icon ?: R.drawable.ic_minus, name, shown(value), value == usual && action == null) {
            val values = (listOf(usual, HandwritingLayer.NOTHING) + WriteAction.entries.map { it.id }).distinct()
            choose(name, values.map { Option(shown(it), it == value, WriteAction.of(it)?.icon ?: R.drawable.ic_minus) }, heading = "While writing") { i ->
                set(values[i])
                render()
            }
        }
    }

    companion object {
        /** Red, dark enough to read on the page (the coral signal is for fills). */
        private const val WARNING_RED = 0xFFC62828.toInt()
    }

    /** What dots on the surface are (the ? by them). */
    private fun dotsHelp() = sheet(
        "Dots on the surface",
        Cb.text(this, 14f, color = Cb.INK_700).apply {
            text = "Tap your index finger on the table (or whatever you write on) as if you were typing a full stop: " +
                "two taps are two dots (..), three are three (...). The band only reads them while handwriting is on " +
                "in a text field, so they can't start handwriting, but they can do anything else here.\n\n" +
                "By default two dots put the pen down when it's up, and type two full stops when it's down; three dots " +
                "just type. Set to anything else, they're never typed: they do that instead. A single dot always types."
            setLineSpacing(0f, 1.3f)
        },
        "Got it",
        cancel = false,
    ) { true }

    private fun label(value: String) = when (value) {
        HandwritingLayer.AS_USUAL -> "As usual"
        HandwritingLayer.NOTHING -> "Nothing"
        else -> WriteAction.of(value)?.label ?: "Nothing"
    }

    private fun pickFor(gesture: Gesture, current: String) {
        val values = listOf(HandwritingLayer.AS_USUAL, HandwritingLayer.NOTHING) + WriteAction.entries.map { it.id }
        choose(
            gesture.label,
            values.map { Option(label(it), it == current, WriteAction.of(it)?.icon ?: R.drawable.ic_minus) },
            heading = "While writing",
        ) { i ->
            HandwritingLayer.set(applicationContext, gesture, values[i])
            render()
        }
    }

    /** Handwriting on for [slot] (one band at a time), or off: on asks for the firmware and the service it needs. */
    private fun setWriter(slot: Int?) {
        if (slot == null) {
            Bands.slots(this).forEach { Prefs(this, it).writeInFields = false }
            return render()
        }
        if (BandService.instance?.bandWrites(slot) == false) {
            confirm(
                "Update the band first",
                "The band's firmware has no handwriting model. Handwriting came with firmware build 64; " +
                    "update the band in Setup › Band firmware updates, then turn this on.",
                "Open firmware",
            ) { Bands.select(this, slot); open<FirmwareActivity>() }
            return
        }
        Prefs(this, slot).writeInFields = true
        Words.load(this)
        render()
        if (!WriteService.enabled(this)) {
            confirm(
                "Turn on the text field service",
                "To type into text fields, Air Gestures needs its \"handwriting in text fields\" accessibility " +
                    "service. It notices which field you're in and changes only that field's text; password " +
                    "fields are left alone, and nothing leaves the phone.\n\nIn Accessibility, tap Installed apps, then " +
                    "turn on \"${applicationInfo.loadLabel(packageManager)} handwriting in text fields\".",
                "Open settings",
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    /** A segmented control with [selected] filled (−1: none). */
    private fun segmentedOf(labels: List<String>, selected: Int, onPick: (Int) -> Unit) = FrameLayout(this).apply {
        val segments = labels.mapIndexed { i, text -> segment(text) { onPick(i) } }
        segments.forEachIndexed { i, view ->
            val active = i == selected
            view.setTextColor(if (active) android.graphics.Color.WHITE else Cb.INK_700)
            view.background = if (active) GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(Cb.INK_900)
            } else null
        }
        addView(segmented(*segments.toTypedArray()), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START,
        ))
    }

    /** What writing in a text field does (the ? by the title). */
    private fun help() {
        val body = column(gap = 14f)
        for ((heading, text) in Sections.read(this, "fields-help.txt")) {
            body.addView(column(gap = 4f).apply {
                addView(Cb.text(context, 15f, R.font.geist_semibold, Cb.INK_900).apply { this.text = heading })
                addView(Cb.text(context, 14f, color = Cb.INK_700).apply {
                    this.text = text
                    setLineSpacing(0f, 1.3f)
                })
            })
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .6f).toInt())
        }
        sheet("Handwriting in text fields", scroll, "Got it", cancel = false) { true }
    }
}
