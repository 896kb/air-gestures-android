package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * One finger's drags in one profile (mockup 13b): six drag gestures, each with its own action
 * and palm overrides, or three sliders (left/right, toward/away, up/down), each a dial.
 */
class DragsActivity : GestureScreen() {
    private lateinit var profileId: String
    private lateinit var part: Part
    private lateinit var gestures: TextView
    private lateinit var sliders: TextView
    private lateinit var rows: LinearLayout
    private lateinit var teach: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE) ?: return finish()
        part = if (intent.getStringExtra(EXTRA_PART) == Part.MIDDLE.name) Part.MIDDLE else Part.INDEX
        gestures = segment("Gestures") { setSliders(false) }
        sliders = segment("Sliders") { setSliders(true) }
        rows = column(gap = 4f)
        teach = column()
        show(pageColumn().apply {
            addView(row().apply { addView(segmented(gestures, sliders)) })
            addView(card(null, rows))
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                text = "Drags read the same with the palm down, up or sideways."
                setLineSpacing(0f, 1.3f)
            })
            addView(teach)
        }, title = "${part.label} drags")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun setSliders(on: Boolean) {
        val drags = Profiles.load(this).profile(profileId)?.drags(part) ?: return
        if (drags.sliders == on) return
        // The targets are kept either way.
        BandService.setDrags(applicationContext, profileId, part, drags.copy(sliders = on))
        render()
    }

    override fun render() {
        val list = Profiles.load(this)
        val index = list.profiles.indexOfFirst { it.id == profileId }
        val profile = list.profiles.getOrNull(index) ?: return finish()
        val report = Report.of(this)
        val drags = profile.drags(part)
        markSegment(gestures, !drags.sliders)
        markSegment(sliders, drags.sliders)
        showTeach(drags.sliders || Gesture.drags(part).any(profile::does))
        rows.removeAllViews()
        if (!drags.sliders) {
            for (gesture in Gesture.drags(part)) rows.addView(profileRow(profile, index, gesture, report))
            return
        }
        for ((pair, names) in Drags.PAIRS.withIndex()) {
            val (name, detail) = names
            val target = DialChoice.of(drags.targets.getOrNull(pair))
            val paused = report.pausedSlider(index, part, pair)
            val tags = listOfNotNull(paused?.let { "${it.control.label} is on a drag of this pair" to false })
            val hint = listOfNotNull(pausedWhere(paused?.positions), detail).joinToString(" · ")
            rows.addView(gestureRow(target.icon, name, target.label, target == DialChoice.NONE, paused = paused != null, tags = tags, hint = hint) {
                val choices = dialChoices
                choose(name, choices.map { Option(it.label, it == target, it.icon) }, heading = "${part.label} slider") { i ->
                    val targets = drags.targets.toMutableList().also { it[pair] = choices[i].key }
                    BandService.setDrags(applicationContext, profileId, part, drags.copy(targets = targets))
                    render()
                    if (choices[i] == DialChoice.AUTOMATION) automationGuide(firstTime = true)
                }
            })
        }
    }

    /**
     * Drags or sliders on ([on]) but none taught for this band and wrist (an unknown wrist
     * counts as untaught): teach them first, the measured ones are another hand's.
     */
    private fun showTeach(on: Boolean) {
        val prefs = Prefs(this)
        val taught = prefs.handInUse()?.let { Calibrations.get(this, prefs.bandAddress, it).drags != null } == true
        teach.removeAllViews()
        if (on && !taught) {
            teach.addView(card(null, column(gap = 10f).apply {
                addView(note("Teach your drags first: untaught, away and right can mix up."))
                addView(row().apply { addView(pill("Teach your drags") { open<TeachDragsActivity>() }.first) })
            }))
        } else {
            teach.addView(navRow(R.drawable.ic_move_up_right, "Mixed up? Teach your drags") { open<TeachDragsActivity>() })
        }
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_PART = "part"

        fun open(context: Context, profile: String, part: Part) = context.startActivity(
            Intent(context, DragsActivity::class.java).putExtra(EXTRA_PROFILE, profile).putExtra(EXTRA_PART, part.name),
        )
    }
}
