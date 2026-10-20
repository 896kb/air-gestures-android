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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profiles = column(gap = 8f)
        calls = column()
        show(pageColumn().apply {
            addView(card("Profiles", profiles))
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
                "Index triple tap: next profile. Middle triple tap: previous. With more than one profile, index and middle taps wait a moment to tell a triple tap apart."
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
        calls.removeAllViews()
        calls.addView(callRows())
    }
}
