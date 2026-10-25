package dev.kb896.airgestures

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Guided lessons: one gesture at a time, waiting until the band sees it. Nothing runs while
 * this screen is open (the service is in practice mode); each step says what the gesture would
 * do in the profile in force.
 */
class PracticeActivity : Screen() {
    private lateinit var steps: List<Lessons.Step>
    private var at = 0
    private var done = false
    private lateinit var count: TextView
    private lateinit var title: TextView
    private lateinit var how: TextView
    private lateinit var result: TextView
    private lateinit var buttons: LinearLayout

    private val watch: (BandService.State) -> Unit = { render() }

    private val lesson = object : BandService.Practice {
        override fun gesture(label: String) = heard(gesture = label)
        override fun action(id: String) = heard(action = id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A rotation or a fold keeps the lesson where it was.
        at = savedInstanceState?.getInt("step") ?: 0
        val active = Profiles.active(this)
        steps = Lessons.steps(
            Profiles.load(this).profiles.size,
            dial = DialChoice.of(active.dial) != DialChoice.NONE,
            map = Gesture.entries.associate { it.key to (active.map[it.key] ?: it.default.id) },
            band = Profiles.band(this),
        )
        count = Cb.label(this, "")
        title = Cb.text(this, 24f, R.font.geist_semibold, Cb.INK_900, -.02f)
        how = Cb.text(this, 15f, color = Cb.INK_500).apply { setLineSpacing(0f, 1.3f) }
        result = Cb.text(this, 15f, R.font.geist_medium, Cb.INK_900)
        buttons = row(gap = 10f).apply { gravity = Gravity.END }
        show(pageColumn().apply {
            addView(card(null, column(gap = 12f).apply {
                addView(count)
                addView(title)
                addView(how)
                addView(result)
            }))
            addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }, title = "Practice")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("step", at)
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
    }

    override fun onPause() {
        BandService.state.remove(watch)
        BandService.instance?.endPractice()
        super.onPause()
    }

    private fun connected() = BandService.instance != null && BandService.state.value.phase == Phase.CONNECTED

    private fun render() {
        val service = BandService.instance
        // The lesson listens only while the band is connected; it may connect from step 0.
        if (connected() && service?.practice == null) service?.startPractice(lesson)
        buttons.removeAllViews()
        when {
            !connected() -> {
                count.text = "BEFORE YOU START"
                title.text = "Connect the band"
                how.text = if (service == null) "The lessons need the band connected." else "Waiting for the band; hold its button if it's asleep."
                result.text = ""
                if (service == null) buttons.addView(pill("Connect", R.drawable.ic_power) { connect() }.first)
            }
            at >= steps.size -> {
                count.text = "ALL DONE"
                title.text = "You know every gesture"
                how.text = "Map them in Gestures, and come back here whenever you want to practise."
                result.text = ""
                buttons.addView(pill("Again") { at = 0; done = false; render() }.first)
                buttons.addView(pill("Close") { finish() }.first)
            }
            else -> {
                val step = steps[at]
                count.text = "STEP ${at + 1} OF ${steps.size}"
                title.text = step.title
                how.text = step.how
                result.text = if (done) "✓ Got it · would: ${would(step)}" else "◌ waiting…"
                result.setTextColor(if (done) Cb.INK_900 else Cb.INK_400)
                if (at > 0) buttons.addView(pill("Back") { at--; done = false; render() }.first)
                if (!done) buttons.addView(pill("Skip") { at++; done = false; render() }.first)
                if (done) buttons.addView(pill("Next", R.drawable.ic_check) { at++; done = false; render() }.first)
            }
        }
    }

    private fun connect() {
        if (!Surfaces.ready(this)) return open<SetupActivity>()
        BandService.start(this)
    }

    /** The band reported something: does it finish the step on screen? */
    private fun heard(gesture: String? = null, action: String? = null) {
        val step = steps.getOrNull(at) ?: return
        if (done) return
        val dial = DialChoice.of(Profiles.active(this).dial).let { setOf(it.up, it.down) - "" }
        if (step.done.matches(gesture, action, dial)) {
            done = true
            render()
        }
    }

    /** What the step's gesture would do now, in words. */
    private fun would(step: Lessons.Step): String {
        val list = Profiles.load(this)
        val profile = list.active(Profiles.foreground)
        return when (step.done) {
            is Lessons.Done.Gesture, Lessons.Done.Hold -> {
                val gesture = step.gesture ?: return ""
                // The band controls come first (lessons are palm down).
                when (val builtin = Profiles.band(this).on(gesture, Palm.DOWN)) {
                    Builtin.CONTROLS -> "turn the controls off (band control)"
                    Builtin.DRAW -> "open the drawing pad (band control)"
                    // The band's own switch: where it would go (an app's own profile stays).
                    Builtin.NEXT, Builtin.PREVIOUS -> if (list.locked(Profiles.foreground) || list.profiles.size < 2) {
                        "nothing here"
                    } else {
                        "switch to ${list.step(if (builtin == Builtin.NEXT) 1 else -1).active(null).name}"
                    }
                    null -> when (val action = Action.of(Prefs(this).actionId(gesture, profile.id))) {
                        Action.OPEN_APP -> "Open an app (${profile.name})"
                        else -> "${action.label} (${profile.name})"
                    }
                }
            }
            Lessons.Done.Dial -> "${DialChoice.of(profile.dial).label} (${profile.name})"
        }
    }
}
