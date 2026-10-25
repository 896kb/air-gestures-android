package dev.kb896.airgestures

import android.os.Bundle
import android.widget.LinearLayout

/**
 * What's worth a warning in the whole set, the band controls (above every profile, mockup 1),
 * the profiles (each its own gesture set, see [ProfileActivity]) with the coral count of what
 * the band controls pause there, the sequences, and During calls (its own screen, [CallsActivity]).
 */
class GesturesActivity : GestureScreen() {
    private lateinit var warnings: LinearLayout
    private lateinit var warningsCard: LinearLayout
    private lateinit var band: LinearLayout
    private lateinit var profiles: LinearLayout
    private lateinit var calls: LinearLayout
    private lateinit var sequences: LinearLayout
    private lateinit var drawings: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        warnings = column(gap = 8f)
        warningsCard = card(null, warnings)
        band = column()
        profiles = column(gap = 8f)
        calls = column()
        sequences = column(gap = 8f)
        drawings = column()
        show(pageColumn().apply {
            addView(warningsCard)
            addView(card(null, column(gap = 4f).apply {
                addView(row(gap = 12f).apply {
                    addView(Cb.text(context, 17f, R.font.geist_semibold, Cb.INK_900, -.015f).apply { text = "Band controls" }, weighted())
                    addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = "All profiles" })
                })
                addView(band)
            }))
            addView(card("Profiles", profiles))
            addView(card(null, drawings))
            addView(card("Sequences", sequences))
            addView(card("During calls", column(gap = 8f).apply {
                addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                    text = "While the phone rings or a call is on, in every profile. Phone calls only; WhatsApp and other app calls can't be answered by another app."
                })
                addView(calls)
            }))
        }, title = "Gestures")
    }

    override fun onResume() {
        super.onResume()
        // An app picked for a gesture may have been uninstalled meanwhile.
        render()
    }

    override fun render() {
        val list = Profiles.load(this)
        val active = list.active(Profiles.foreground)
        // Moving a band control and coming back re-renders (mockup 7: no toast).
        val report = Report.of(this)
        val controls = Profiles.band(this)
        showWarnings(report)
        band.removeAllViews()
        for (builtin in Builtin.entries) {
            val at = controls[builtin]
            // The palm positions on a line of their own.
            val detail = at?.let { c -> c.gesture.label + (c.palms?.let { "\n" + it.words() } ?: "") } ?: "Nothing"
            band.addView(navRow(builtin.icon, builtin.label, detail = detail, count = report.count(builtin)) {
                BandControlActivity.open(this, builtin)
            })
        }
        profiles.removeAllViews()
        profiles.addView(column().apply {
            list.profiles.forEachIndexed { index, profile ->
                val count = list.apps.values.count { it == profile.id }
                val detail = listOfNotNull(
                    "In use".takeIf { profile.id == active.id && list.profiles.size > 1 },
                    when (count) {
                        0 -> null
                        1 -> "1 app"
                        else -> "$count apps"
                    },
                ).joinToString(" · ").ifEmpty { null }
                addView(navRow(R.drawable.ic_layers, profile.name, detail = detail, count = report.count(index)) {
                    ProfileActivity.open(context, profile.id)
                })
            }
        })
        profiles.addView(Cb.text(this, 13f, color = Cb.INK_500).apply {
            text = if (list.profiles.size > 1) {
                "Next profile and Previous profile are band controls (above): by default an index and a middle triple tap. A tap waits a moment when its triple tap does something, to tell them apart."
            } else {
                "Add a profile for another set of gestures; switch with Next profile and Previous profile, or give apps their own."
            }
        })
        profiles.addView(row(gap = 10f).apply {
            addView(pill("New profile", R.drawable.ic_layers) {
                // Empty (the defaults) or a ready-made one.
                choose(
                    "New profile",
                    listOf(Option("Empty", false, R.drawable.ic_layers), Option("Navigate", false, R.drawable.ic_scan_line)),
                    heading = "Profile",
                ) { index ->
                    val navigate = index == 1
                    val made = Profiles.update(applicationContext) { list ->
                        val (added, profile) = if (navigate) {
                            list.added(Profiles.defaultMap() + Presets.navigate, DialChoice.NAVIGATE.key)
                        } else {
                            list.added(Profiles.defaultMap(), DialChoice.VOLUME.key)
                        }
                        if (navigate) added.renamed(profile.id, "Navigate") else added
                    }.profiles.last()
                    BandService.instance?.remap()
                    ProfileActivity.open(context, made.id)
                }
            }.first)
        })
        if (list.profiles.size > 1) {
            val speak = Profiles.speak(this)
            profiles.addView(settingRow("Say the name", "When switching, as well as the pop-up", pill(if (speak) "On" else "Off") {
                Profiles.setSpeak(applicationContext, !speak)
                render()
            }.first))
            val vibrate = Profiles.vibrate(this)
            profiles.addView(settingRow("Vibrate", "Once for the next profile, twice for the previous", pill(if (vibrate) "On" else "Off") {
                Profiles.setVibrate(applicationContext, !vibrate)
                render()
            }.first))
        }
        drawings.removeAllViews()
        val taught = Drawings.load(this).drawings.size
        drawings.addView(navRow(R.drawable.ic_pencil, "Drawings", detail = when (taught) {
            0 -> "None yet"
            1 -> "1 drawing"
            else -> "$taught drawings"
        }, count = report.count(Builtin.DRAW)) { DrawingsActivity.open(this) })
        sequences.removeAllViews()
        val library = Profiles.sequences(this).sequences
        sequences.addView(column().apply {
            for (sequence in library) {
                val steps = sequence.steps.count { Sequences.waitMs(it) == null }
                addView(navRow(R.drawable.ic_list_ordered, sequence.name, detail = if (steps == 1) "1 step" else "$steps steps") {
                    SequenceActivity.open(context, sequence.id)
                })
            }
        })
        if (library.isEmpty()) {
            sequences.addView(Cb.text(this, 13f, color = Cb.INK_500).apply {
                text = "Several actions in a row on one gesture, with waits between: build one, or record what you do in an app."
            })
        }
        sequences.addView(row().apply {
            addView(pill("New sequence", R.drawable.ic_list_ordered) {
                val made = Profiles.updateSequences(applicationContext) { it.added().first }.sequences.last()
                SequenceActivity.open(context, made.id)
            }.first)
        })
        calls.removeAllViews()
        val prefs = Prefs(this)
        val set = Gesture.entries.count { !it.drag && prefs.callActionId(it) != Mappings.AS_USUAL }
        calls.addView(navRow(R.drawable.ic_phone, "During calls", detail = if (set > 0) "$set set" else "As usual", count = report.count(null as Int?)) {
            open<CallsActivity>()
        })
    }

    /**
     * What band-core finds worth a warning in the whole set, and what it can't use as set
     * (invalid); only notes, nothing is blocked.
     */
    private fun showWarnings(report: Report) {
        val list = Profiles.load(this)
        val notes = report.warnings.mapNotNull { kind ->
            when (kind) {
                "no-controls" -> "No gesture turns the controls on and off. You can still do it from the app."
                "no-way-out" -> "No gesture switches profiles. Use the app or the widget to switch."
                "draw-on-tap" -> "Draw is on a single tap: while drawing, a short pinch is that tap, and it cancels the drawing."
                else -> null
            }
        }
        val invalid = report.invalid.mapNotNull { line ->
            val words = line.split(' ')
            when (words[0]) {
                "binding" -> {
                    val place = words.getOrNull(1)?.toIntOrNull()?.let { list.profiles.getOrNull(it)?.name } ?: "During calls"
                    val gesture = Gesture.of(words.getOrNull(2))?.label ?: words.getOrNull(2)
                    "$place: $gesture has more than two palm overrides, so its normal action never runs. Remove one."
                }
                "band" -> Builtin.of(words.getOrNull(1))?.let { "${it.label} is limited to no palm position or to all three: pick Any position or one or two." }
                "draw-cant-cancel" -> "Draw on a hold, a triple tap or a drag can't cancel its drawing: it ends by itself, or with the pad's close button."
                else -> null
            }
        }
        val error = report.error?.let { "The band's rules couldn't read your settings ($it): what's paused or wrong isn't shown." }
        val texts = listOfNotNull(error) + invalid + notes
        warnings.removeAllViews()
        for (warning in texts) {
            warnings.addView(row(gap = 12f).apply {
                gravity = android.view.Gravity.TOP
                addView(icon(R.drawable.ic_circle_alert, 16f, Cb.INK_500).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dpi(3f)
                })
                addView(Cb.text(context, 14f, color = Cb.INK_500).apply {
                    text = warning
                    setLineSpacing(0f, 1.3f)
                }, weighted())
            })
        }
        warningsCard.visibility = if (texts.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}
