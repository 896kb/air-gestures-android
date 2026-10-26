package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * One sequence: its steps in order (actions and waits), built by hand or recorded, and run
 * from here to try it.
 */
class SequenceActivity : GestureScreen() {
    private lateinit var sequenceId: String
    private lateinit var steps: LinearLayout
    private lateinit var actions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sequenceId = intent.getStringExtra(EXTRA_SEQUENCE) ?: return finish()
        steps = column(gap = 2f)
        actions = column(gap = 10f)
        show(pageColumn().apply {
            addView(card(null, steps))
            addView(actions)
        }, title = Profiles.sequences(this).get(sequenceId)?.name ?: "Sequence")
        titleButton(R.drawable.ic_pencil, "Rename") {
            val sequence = Profiles.sequences(this).get(sequenceId) ?: return@titleButton
            // Its automation steps send the name: rules matching it need the new one.
            val note = if (sequence.steps.any(Mappings::isAutomation)) "Home Assistant matches this name; update your automations too." else null
            ask("Rename", sequence.name, "Save", note) { new ->
                Profiles.updateSequences(applicationContext) { it.renamed(sequenceId, new) }
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun sequence() = Profiles.sequences(this).get(sequenceId)

    private fun setSteps(new: List<String>) {
        Profiles.updateSequences(applicationContext) { it.withSteps(sequenceId, new) }
        render()
    }

    override fun render() {
        val sequence = sequence() ?: return finish()
        titleView?.text = sequence.name
        steps.removeAllViews()
        if (sequence.steps.isEmpty()) {
            steps.addView(Cb.text(this, 14f, color = Cb.INK_500).apply {
                text = "No steps yet. Add actions and waits, or record what you do in an app."
            })
        }
        sequence.steps.forEachIndexed { index, step -> steps.addView(stepRow(sequence, index, step)) }

        actions.removeAllViews()
        actions.addView(row(gap = 10f).apply {
            addView(pill("Add step", R.drawable.ic_layers) {
                pickAction("Add a step", "", inSequence = true) { id -> setSteps(sequence().orEmpty() + id) }
            }.first)
            addView(pill("Add wait", R.drawable.ic_hourglass) {
                val waits = listOf(500L, 1000L, 2000L, 3000L, 5000L)
                choose("Wait", waits.map { Option(formatSeconds(it), false, R.drawable.ic_hourglass) }, heading = "Step") { i ->
                    setSteps(sequence().orEmpty() + Sequences.wait(waits[i]))
                }
            }.first)
        })
        actions.addView(row(gap = 10f).apply {
            addView(pill("Record", R.drawable.ic_circle_dot) {
                pickScreen(SpotPicker.Mode.RECORD, null) { recorded -> setSteps(sequence().orEmpty() + recorded) }
            }.first)
            if (sequence.steps.isNotEmpty()) {
                addView(pill("Try it", R.drawable.ic_play) { tryIt() }.first)
            }
        })
        actions.addView(row().apply {
            gravity = Gravity.END
            addView(pill("Delete") {
                confirm("Delete ${sequence.name}?", "Gestures set to it will do nothing.", "Delete") {
                    Profiles.updateSequences(applicationContext) { it.deleted(sequenceId) }
                    BandService.instance?.remap()
                    finish()
                }
            }.first)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun Sequences.Sequence?.orEmpty() = this?.steps.orEmpty()

    /** A step: its number, icon and words; move it up or down, or take it out. */
    private fun stepRow(sequence: Sequences.Sequence, index: Int, step: String) = row(gap = 10f).apply {
        setPadding(0, dpi(6f), 0, dpi(6f))
        addView(Cb.text(context, 13f, R.font.geist_mono_medium, Cb.INK_400).apply { text = "${index + 1}" })
        val wait = Sequences.waitMs(step)
        addView(icon(if (wait != null) R.drawable.ic_hourglass else Action.of(step).icon, 16f))
        val label = Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = stepLabel(step) }
        if (Mappings.isAutomation(step)) {
            // What Home Assistant gets: the sequence's name, not a gesture's.
            val sends = "Sends gesture \"sequence\", name \"${sequence.name}\""
            addView(column(gap = 2f).apply {
                addView(label)
                addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = sends })
            }, weighted())
        } else {
            addView(label, weighted())
        }
        fun small(iconRes: Int, description: String, enabled: Boolean, onClick: () -> Unit) = FrameLayout(context).apply {
            addView(icon(iconRes, 16f, if (enabled) Cb.INK_700 else Cb.INK_300), FrameLayout.LayoutParams(dpi(16f), dpi(16f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dpi(32f), dpi(32f))
            contentDescription = description
            if (enabled) {
                isClickable = true
                background = ripple(999f)
                setOnClickListener { onClick() }
            }
        }
        addView(small(R.drawable.ic_arrow_up, "Move up", index > 0) {
            Profiles.updateSequences(applicationContext) { it.moved(sequenceId, index, -1) }
            render()
        })
        addView(small(R.drawable.ic_arrow_down, "Move down", index < sequence.steps.lastIndex) {
            Profiles.updateSequences(applicationContext) { it.moved(sequenceId, index, +1) }
            render()
        })
        addView(small(R.drawable.ic_minus, "Remove", true) {
            setSteps(sequence.steps.filterIndexed { i, _ -> i != index })
        })
    }

    /** Run it now, as the gesture would (the app steps aside so its taps land in the other apps). */
    private fun tryIt() {
        val run = Intent(this, SequenceRunner::class.java).putExtra(EXTRA_SEQUENCE, sequenceId).setPackage(packageName)
        moveTaskToBack(true)
        // Give the app behind a moment to come forward, so the first tap lands in it.
        android.os.Handler(mainLooper).postDelayed({ applicationContext.sendBroadcast(run) }, 700)
    }

    companion object {
        const val EXTRA_SEQUENCE = "sequence"

        fun open(context: Context, sequence: String) =
            context.startActivity(Intent(context, SequenceActivity::class.java).putExtra(EXTRA_SEQUENCE, sequence))
    }
}

/** Runs a sequence for "Try it", through the band service's actions when it's on. */
class SequenceRunner : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(SequenceActivity.EXTRA_SEQUENCE) ?: return
        val action = Sequences.PREFIX + id
        // Not instance?.runAction(…) ?: …: a run that worked gives null too, and would run twice.
        val service = BandService.instance
        if (service != null) service.runAction(action) else runner(context).run(action)
    }

    companion object {
        private var actions: Actions? = null

        private fun runner(context: Context) =
            actions ?: Actions(context.applicationContext, log = { android.util.Log.i("AirGestures", it) }).also { actions = it }
    }
}
