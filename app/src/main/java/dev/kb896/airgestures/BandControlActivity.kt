package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout

/**
 * One band control (mockup 12): its gesture (the picker, mockup 2, asks before pausing what's
 * there, mockup 3), and the palm positions it's limited to. Band controls are above every
 * profile; what a profile has on the same gesture waits until the band control moves.
 */
class BandControlActivity : GestureScreen() {
    private lateinit var builtin: Builtin
    private lateinit var gestureCard: LinearLayout
    private lateinit var palmCard: LinearLayout
    private lateinit var palmBody: LinearLayout
    private lateinit var notes: LinearLayout

    /** Shown under the palm chips until the next change: "Up to two…". */
    private var palmNote: String? = null

    /** Where it is now (null: no gesture). */
    private val at get() = Profiles.band(this)[builtin]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        builtin = Builtin.of(intent.getStringExtra(EXTRA_CONTROL)) ?: return finish()
        gestureCard = column()
        palmBody = column(gap = 12f)
        palmCard = card("Palm position", palmBody)
        notes = column(gap = 12f)
        show(pageColumn().apply {
            addView(card(null, gestureCard))
            addView(palmCard)
            addView(notes)
        }, title = builtin.label)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        val at = at
        val report = Report.of(this)
        gestureCard.removeAllViews()
        gestureCard.addView(navRow(R.drawable.ic_hand, "Gesture", detail = at?.gesture?.label ?: "Nothing") { pickGesture() })

        palmCard.visibility = if (at == null) View.GONE else View.VISIBLE
        palmBody.removeAllViews()
        if (at != null) palms(at)

        notes.removeAllViews()
        val paused = report.count(builtin)
        if (at != null && paused > 0) {
            notes.addView(row(gap = 10f).apply {
                addView(Cb.count(context, paused))
                addView(note("${at.label} is used in $paused ${if (paused == 1) "place" else "places"}: they're paused"), weighted())
            })
        }
        if (builtin == Builtin.DRAW) {
            if ("draw-cant-cancel" in report.invalid) {
                notes.addView(note("Draw on a hold, a triple tap or a drag can't cancel its drawing: it ends by itself, or with the pad's close button."))
            }
            if ("draw-on-tap" in report.warnings) {
                notes.addView(note("Draw is on a single tap: while drawing, a short pinch is that tap, and it cancels the drawing."))
            }
        }
    }

    /** The other band control on [gesture] in [palm], if any (judgement 4: they may share a gesture in other positions). */
    private fun heldBy(gesture: Gesture, palm: Palm): Builtin? {
        val band = Profiles.band(this)
        return Builtin.entries.firstOrNull { it != builtin && band[it]?.let { c -> c.gesture == gesture && c.covers(palm) } == true }
    }

    /** Any position, or only with the palm a way or two (chips the other band controls hold are off). */
    private fun palms(at: BandControl) {
        val only = at.palms != null
        val free = Palm.entries.filter { heldBy(at.gesture, it) == null }
        val any = segment("Any position") {
            if (!only) return@segment
            val other = Palm.entries.firstNotNullOfOrNull { heldBy(at.gesture, it) }
            if (other != null) {
                palmNote = "${other.label} is on ${at.gesture.label} in some positions: pick the others here."
                return@segment render()
            }
            changePalms(at, null)
        }
        val limited = segment("Only with the palm…") {
            if (only) return@segment
            val start = if (Palm.UP in free) Palm.UP else free.firstOrNull() ?: return@segment
            changePalms(at, setOf(start))
        }
        markSegment(any, !only)
        markSegment(limited, only)
        palmBody.addView(row().apply { addView(segmented(any, limited)) })
        if (!only) return
        val chosen = at.palms.orEmpty()
        palmBody.addView(row(gap = 10f).apply {
            for (palm in Palm.entries) {
                val held = heldBy(at.gesture, palm)
                val (chip, shape) = pill(palm.label.removePrefix("Palm ").replaceFirstChar { it.uppercase() }) {
                    when {
                        palm in chosen -> if (chosen.size > 1) changePalms(at, chosen - palm)
                        chosen.size >= 2 -> {
                            palmNote = "Up to two: with all three, pick Any position."
                            render()
                        }
                        else -> changePalms(at, chosen + palm)
                    }
                }
                if (palm in chosen) {
                    // Chosen: ink-filled, as the segmented control's active segment.
                    chip.background = NeuDrawable(this@BandControlActivity, 999f, fill = Cb.INK_900, solid = true, soft = false)
                    chip.setTextColor(android.graphics.Color.WHITE)
                } else {
                    shape.pressed = 0f
                }
                chip.isSelected = palm in chosen
                if (held != null) {
                    // Another band control's position here: off (judgement 4).
                    chip.isEnabled = false
                    chip.alpha = .4f
                }
                addView(chip)
            }
        })
        for (palm in Palm.entries) {
            val held = heldBy(at.gesture, palm) ?: continue
            palmBody.addView(note("${palm.label} is ${held.label}'s here."))
        }
        palmNote?.let { palmBody.addView(note(it)) }
        palmBody.addView(note("In the other positions, ${at.gesture.label} does what each profile says."))
    }

    private fun palmKeys(palms: Set<Palm>?) = palms?.let { p -> Palm.entries.filter { it in p }.joinToString(",") { it.key } }.orEmpty()

    /**
     * The bridge's answer for [palms], or null after saying what went wrong (the settings
     * unreadable: not shown as a list of nothing).
     */
    private fun choices(palms: Set<Palm>?): List<Report.Choice>? {
        val json = Bridge.bandChoices(Prefs(this).profilesJson(), builtin.key, palmKeys(palms))
        Report.choicesError(json)?.let { error ->
            sheet("Can't list the gestures", note("The band's rules couldn't read your settings ($error). Nothing was changed."), "OK", cancel = false) { true }
            return null
        }
        return Report.choices(json)
    }

    /** New positions for the same gesture: asked first when they'd pause anything not paused now. */
    private fun changePalms(at: BandControl, palms: Set<Palm>?) {
        palmNote = null
        val control = BandControl(at.gesture, palms)
        val choice = (choices(palms) ?: return).firstOrNull { it.gesture == at.gesture }
        val report = Report.of(this)
        if (choice != null && report.newCount(choice) > 0) prompt(choice, control, report) else save(control)
    }

    private fun pickGesture() {
        val at = at
        val choices = choices(at?.palms) ?: return
        val options = listOf(Option("Nothing", at == null, R.drawable.ic_minus, detail = if (at == null) "Now" else null)) +
            choices.map { c ->
                val now = c.gesture == at?.gesture
                Option(c.gesture.label, now, R.drawable.ic_hand, detail = when {
                    now -> "Now"
                    c.count == 0 -> "free"
                    else -> "used in ${c.count}"
                })
            }
        choose(builtin.label, options, heading = "Band control") { index ->
            if (index == 0) return@choose save(null)
            val choice = choices[index - 1]
            val control = BandControl(choice.gesture, at?.palms)
            val report = Report.of(this)
            if (choice.gesture == at?.gesture || report.newCount(choice) == 0) save(control) else prompt(choice, control, report)
        }
    }

    /**
     * Mockup 3: "<gesture> is used in N places", each with its action; a Will pause chip on what
     * the move would newly pause ([report]: now), a Paused one on what's paused already.
     */
    private fun prompt(choice: Report.Choice, control: BandControl, report: Report) {
        val list = Profiles.load(this)
        fun place(name: String, action: String, new: Boolean) = row(gap = 10f).apply {
            addView(column(gap = 2f).apply {
                addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = name })
                addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = action })
            }, weighted())
            addView(if (new) willPauseChip() else pausedChip())
        }
        val body = column(gap = 10f).apply {
            for (p in choice.pauses) {
                val profile = p.place?.let { list.profiles.getOrNull(it) }
                val name = profile?.name ?: "During calls"
                val id = when {
                    profile == null -> Prefs(context).callActionId(p.gesture)
                    p.palm == null -> profile.map[p.gesture.key] ?: p.gesture.default.id
                    else -> profile.palm[p.gesture.key]?.get(p.palm.key).orEmpty()
                }
                val where = p.palm?.let { " · ${it.label.lowercase()}" }.orEmpty()
                addView(place(name + where, label(id, calls = profile == null), report.isNew(p)))
            }
            for (s in choice.sliders) {
                val profile = list.profiles.getOrNull(s.profile) ?: continue
                val target = profile.drags(s.part).targets.getOrNull(s.pair) ?: DialChoice.NONE.key
                val pair = Drags.PAIRS.getOrNull(s.pair)?.first?.lowercase().orEmpty()
                addView(place("${profile.name} · ${s.part.label} $pair slider", DialChoice.of(target).label, report.isNew(s)))
            }
            addView(note("They stay saved and come back if you move ${builtin.label} to another gesture."))
        }
        val n = choice.count
        sheet("${control.label} is used in $n ${if (n == 1) "place" else "places"}", body, "Use it anyway") {
            save(control)
            true
        }
    }

    private fun save(control: BandControl?) {
        palmNote = null
        Profiles.updateBand(applicationContext) { it.with(builtin, control) }
        BandService.instance?.remap()
        render()
    }

    companion object {
        const val EXTRA_CONTROL = "control"

        fun open(context: Context, builtin: Builtin) =
            context.startActivity(Intent(context, BandControlActivity::class.java).putExtra(EXTRA_CONTROL, builtin.key))
    }
}
