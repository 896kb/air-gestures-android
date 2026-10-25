package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE) ?: return finish()
        part = if (intent.getStringExtra(EXTRA_PART) == Part.MIDDLE.name) Part.MIDDLE else Part.INDEX
        gestures = segment("Gestures") { setSliders(false) }
        sliders = segment("Sliders") { setSliders(true) }
        rows = column(gap = 4f)
        show(pageColumn().apply {
            addView(row().apply { addView(segmented(gestures, sliders)) })
            addView(card(null, rows))
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                text = "Drags read the same with the palm down, up or sideways."
                setLineSpacing(0f, 1.3f)
            })
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

    /** The segmented control's active segment: ink-filled, as MainActivity's wrist control. */
    private fun mark(view: TextView, active: Boolean) {
        view.setTextColor(if (active) android.graphics.Color.WHITE else Cb.INK_700)
        view.background = if (active) GradientDrawable().apply {
            cornerRadius = dp(999f)
            setColor(Cb.INK_900)
        } else null
    }

    override fun render() {
        val list = Profiles.load(this)
        val index = list.profiles.indexOfFirst { it.id == profileId }
        val profile = list.profiles.getOrNull(index) ?: return finish()
        val report = Report.of(this)
        val drags = profile.drags(part)
        mark(gestures, !drags.sliders)
        mark(sliders, drags.sliders)
        rows.removeAllViews()
        if (!drags.sliders) {
            for (gesture in Gesture.drags(part)) rows.addView(profileRow(profile, index, gesture, report))
            return
        }
        for ((pair, name, detail) in PAIRS) {
            val target = DialChoice.of(drags.targets.getOrNull(pair))
            val paused = report.pausedSlider(index, part, pair)
            val tags = listOfNotNull(paused?.let { "${it.control.label} is on a drag of this pair" to false })
            rows.addView(gestureRow(target.icon, name, target.label, target == DialChoice.NONE, paused = paused != null, tags = tags, hint = detail) {
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

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_PART = "part"

        /** The sliders: band-core's pair index, the name, which way is up. */
        private val PAIRS = listOf(
            Triple(0, "Left/right", "right = forward"),
            Triple(1, "Toward/away", "away = up"),
            Triple(2, "Up/down", "up = up"),
        )

        fun open(context: Context, profile: String, part: Part) = context.startActivity(
            Intent(context, DragsActivity::class.java).putExtra(EXTRA_PROFILE, profile).putExtra(EXTRA_PART, part.name),
        )
    }
}
