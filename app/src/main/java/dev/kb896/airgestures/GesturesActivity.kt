package dev.kb896.airgestures

import android.os.Bundle
import android.widget.LinearLayout

/**
 * The profiles (each its own gesture set, see [ProfileActivity]), how to switch between them,
 * and what the gestures do instead while the phone rings or a call is on (shared by all).
 */
class GesturesActivity : GestureScreen() {
    private lateinit var profiles: LinearLayout
    private lateinit var calls: LinearLayout
    private lateinit var sequences: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = column(gap = 8f)
        calls = column()
        sequences = column(gap = 8f)
        show(pageColumn().apply {
            addView(card("Profiles", profiles))
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
        profiles.removeAllViews()
        profiles.addView(column().apply {
            for (profile in list.profiles) {
                val count = list.apps.values.count { it == profile.id }
                val detail = listOfNotNull(
                    "In use".takeIf { profile.id == active.id && list.profiles.size > 1 },
                    when (count) {
                        0 -> null
                        1 -> "1 app"
                        else -> "$count apps"
                    },
                ).joinToString(" · ").ifEmpty { null }
                addView(navRow(R.drawable.ic_layers, profile.name, detail = detail) { ProfileActivity.open(context, profile.id) })
            }
        })
        profiles.addView(Cb.text(this, 13f, color = Cb.INK_500).apply {
            text = if (list.profiles.size > 1) {
                "By default an index triple tap goes to the next profile and a middle triple tap to the previous; any gesture can, with Next or Previous profile. A tap waits a moment when its triple tap does something, to tell them apart."
            } else {
                "Add a profile for another set of gestures; switch between them with a triple tap, or give apps their own."
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
        calls.addView(callRows())
    }
}
