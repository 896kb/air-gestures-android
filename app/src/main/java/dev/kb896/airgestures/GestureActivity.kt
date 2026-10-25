package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

/**
 * One gesture in one profile (mockups 10, 6): a Paused banner per band control on it, the
 * palm strip (what it does palm down, sideways and up), its normal action, and up to two palm
 * overrides. Any action can be picked, paused or not: a paused one is kept and runs once the
 * band control moves.
 */
class GestureActivity : GestureScreen() {
    private lateinit var profileId: String
    private lateinit var gesture: Gesture
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE) ?: return finish()
        gesture = Gesture.of(intent.getStringExtra(EXTRA_GESTURE)) ?: return finish()
        body = column(gap = 20f)
        show(pageColumn().apply { addView(body) }, title = gesture.label)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        val list = Profiles.load(this)
        val index = list.profiles.indexOfFirst { it.id == profileId }
        val profile = list.profiles.getOrNull(index) ?: return finish()
        val report = Report.of(this)
        val band = Profiles.band(this)
        val normalId = profile.map[gesture.key] ?: gesture.default.id
        val overrides = profile.palm[gesture.key].orEmpty()
        val normalPaused = report.paused(index, gesture, null)
        body.removeAllViews()
        body.addView(Cb.label(this, profile.name))

        // Mockup 6: which band control is on it, and that the choice waits.
        for (b in band.onGesture(gesture)) {
            val c = band[b] ?: continue
            val where = c.palms?.let { p -> " with the ${Palm.entries.filter { it in p }.joinToString(" or ") { it.label.lowercase() }}" } ?: ""
            body.addView(banner(pausedChip(),
                "${b.label} is on ${gesture.label.lowercase()}$where, in every profile. What you pick here is kept and runs once it moves.",
                "Band controls") { BandControlActivity.open(this, b) })
        }

        // The strip: one tile per position; an override's is dark.
        body.addView(row(gap = 8f).apply {
            for (palm in Palm.entries) {
                val own = overrides[palm.key]
                val dark = own != null
                val paused = if (dark) report.paused(index, gesture, palm) != null else normalPaused?.positions?.contains(palm) == true
                addView(column(gap = 6f).apply {
                    setPadding(dpi(12f), dpi(12f), dpi(12f), dpi(12f))
                    background = if (dark) GradientDrawable().apply {
                        cornerRadius = dp(12f)
                        setColor(Cb.INK_900)
                    } else Cb.hairlineCard(context, 12f)
                    addView(Cb.label(context, palm.label).apply { if (dark) setTextColor(Cb.INK_300) })
                    addView(Cb.text(context, 14f, R.font.geist_medium, if (dark) Cb.PAGE else Cb.INK_900).apply {
                        text = label(own ?: normalId, calls = false)
                    })
                    if (paused) addView(compactChip(pausedChip()), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    isClickable = true
                    setOnClickListener {
                        when {
                            dark -> pickOverride(palm, own.orEmpty())
                            overrides.size < ProfileList.MAX_OVERRIDES -> pickOverride(palm, normalId)
                            else -> pickNormal(normalId)
                        }
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            }
        })

        // The normal action: every position without an override.
        val action = Action.of(normalId)
        body.addView(card("Normal action", gestureRow(
            action.icon,
            if (overrides.isEmpty()) "Every position" else "The other positions",
            label(normalId, calls = false),
            action == Action.NONE,
            paused = normalPaused != null,
            hint = pausedWhere(normalPaused),
        ) { pickNormal(normalId) }))

        // Palm overrides, up to two.
        body.addView(card("Palm overrides · up to ${ProfileList.MAX_OVERRIDES}", column(gap = 10f).apply {
            for (palm in Palm.entries) {
                val id = overrides[palm.key] ?: continue
                addView(row(gap = 12f).apply {
                    setPadding(0, dpi(4f), 0, dpi(4f))
                    addView(column(gap = 2f).apply {
                        addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = palm.label })
                        addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = label(id, calls = false) })
                    }, weighted())
                    if (report.paused(index, gesture, palm) != null) addView(pausedChip())
                    addView(pill("Remove") {
                        change { it.withoutOverride(profileId, gesture.key, palm) }
                    }.first)
                    isClickable = true
                    background = ripple()
                    setOnClickListener { pickOverride(palm, id) }
                })
            }
            val free = Palm.entries.filter { it.key !in overrides }
            if (overrides.size < ProfileList.MAX_OVERRIDES) {
                addView(row().apply {
                    addView(pill("Add an override", R.drawable.ic_hand) {
                        choose("Palm position", free.map { Option(it.label, false, R.drawable.ic_hand) }, heading = gesture.label) { i ->
                            pickOverride(free[i], normalId)
                        }
                    }.first)
                })
            }
            addView(note("One position always keeps the normal action."))
        }))

        // Calibration: the left wrist without one counts everything as palm down.
        val prefs = Prefs(this)
        if (prefs.handInUse() == "left" && Calibrations.get(this, prefs.bandAddress, "left").palm == null) {
            body.addView(card(null, column(gap = 10f).apply {
                addView(note("Your left wrist isn't calibrated: every gesture counts as palm down until you calibrate."))
                addView(row().apply { addView(pill("Calibrate") { open<PalmCalibrationActivity>() }.first) })
            }))
        } else {
            body.addView(navRow(R.drawable.ic_hand, "Palm positions feel off?", detail = "Calibrate") { open<PalmCalibrationActivity>() })
        }

        // A hold that waits for the release (band-core's hold_waits).
        if (gesture == Gesture.INDEX_HOLD || gesture == Gesture.MIDDLE_HOLD) {
            val waits = report.holdWaits(index, gesture.part)
            if (waits.isNotEmpty()) {
                val where = if (waits.size == Palm.entries.size) "" else
                    " (" + Palm.entries.filter { it in waits }.joinToString(" or ") { it.label.lowercase() } + ")"
                body.addView(note("Acts when you let go$where: this finger's dial or drags do something too."))
            }
        }
    }

    private fun note(text: String) = Cb.text(this, 13f, color = Cb.INK_500).apply {
        this.text = text
        setLineSpacing(0f, 1.3f)
        gravity = Gravity.START
    }

    private fun pickNormal(current: String) = pickAction(gesture.label, current) { id ->
        BandService.setAction(applicationContext, profileId, gesture, id)
        render()
    }

    private fun pickOverride(palm: Palm, current: String) = pickAction("${gesture.label} · ${palm.label.lowercase()}", current) { id ->
        change { it.withOverride(profileId, gesture.key, palm, id) }
    }

    private fun change(how: (ProfileList) -> ProfileList) {
        Profiles.update(applicationContext, how)
        BandService.instance?.remap()
        render()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_GESTURE = "gesture"

        fun open(context: Context, profile: String, gesture: Gesture) = context.startActivity(
            Intent(context, GestureActivity::class.java).putExtra(EXTRA_PROFILE, profile).putExtra(EXTRA_GESTURE, gesture.key),
        )
    }
}
