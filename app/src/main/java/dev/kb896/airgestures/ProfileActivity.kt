package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * One profile (mockups 5, 9): its gestures in groups with what the band controls pause there,
 * its dials, its name, and the apps that switch to it.
 */
class ProfileActivity : GestureScreen() {
    private lateinit var profileId: String
    private lateinit var gestures: LinearLayout
    private lateinit var note: android.widget.TextView
    private lateinit var dials: LinearLayout
    private lateinit var drawings: LinearLayout
    private lateinit var apps: LinearLayout
    private lateinit var actions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE) ?: Profiles.load(this).picked
        gestures = column()
        note = Cb.text(this, 13f, color = Cb.INK_500).apply { setLineSpacing(0f, 1.3f) }
        dials = column(gap = 4f)
        drawings = column()
        apps = column(gap = 8f)
        actions = row(gap = 10f)
        show(pageColumn().apply {
            addView(card(null, column(gap = 14f).apply {
                addView(gestures)
                addView(note)
            }))
            addView(card("Dials · pinch and turn", dials))
            addView(card(null, drawings))
            addView(card("Apps", apps))
            addView(actions)
        }, title = Profiles.load(this).profile(profileId)?.name ?: "Profile")
        titleButton(R.drawable.ic_pencil, "Rename") {
            val name = Profiles.load(this).profile(profileId)?.name ?: return@titleButton
            ask("Rename", name, "Save") { new ->
                Profiles.update(applicationContext) { it.renamed(profileId, new) }
                BandService.instance?.remap()
                render()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        val list = Profiles.load(this)
        val profile = list.profile(profileId) ?: return finish()
        titleView?.text = profile.name
        // Re-rendered on resume: a band control moved elsewhere shows its paused actions as normal again (mockup 7).
        val report = Report.of(this)
        gestures.removeAllViews()
        gestures.addView(profileRows(profileId, report))
        val band = bandNote(Profiles.band(this))
        note.text = band.orEmpty()
        note.visibility = if (band == null) android.view.View.GONE else android.view.View.VISIBLE

        dials.removeAllViews()
        for (part in listOf(Part.INDEX, Part.MIDDLE)) {
            val dial = DialChoice.of(profile.dialOf(part))
            val guide = if (dial == DialChoice.AUTOMATION) ({ automationGuide() }) else null
            dials.addView(gestureRow(dial.icon, "${part.label} dial", dial.label, dial == DialChoice.NONE, info = guide) {
                pickDial(profileId, part)
            })
        }
        // Judgement 3: each finger's drags are a row here, opening the Drags screen (mockup 13b).
        val index = list.profiles.indexOf(profile)
        for (part in listOf(Part.INDEX, Part.MIDDLE)) {
            val drags = profile.drags(part)
            // What's paused behind it: sliders, or drag gestures' actions.
            val paused = report.pausedSliders.filter { it.profile == index && it.part == part }.distinctBy { it.pair }.size +
                report.paused.count { it.place == index && it.gesture.drag && it.gesture.part == part }
            dials.addView(gestureRow(R.drawable.ic_move_up_right, "${part.label} drags", if (drags.sliders) "Sliders" else "Gestures", false, count = paused) {
                DragsActivity.open(this, profileId, part)
            })
        }

        drawings.removeAllViews()
        val own = profile.drawings.keys.count { Drawings.load(this).get(it) != null }
        drawings.addView(navRow(R.drawable.ic_pencil, "Drawings", detail = if (own == 0) "The shared ones" else "$own of its own") {
            DrawingsActivity.open(this, profileId)
        })

        apps.removeAllViews()
        apps.addView(Cb.text(this, 13f, color = Cb.INK_500).apply {
            text = "Opening one of these switches to ${profile.name}; Next and Previous profile don't switch while it's open."
        })
        val mine = list.apps.filterValues { it == profileId }.keys.sortedBy { appName(it).lowercase() }
        for (pkg in mine) apps.addView(appRow(pkg))
        apps.addView(row().apply {
            addView(pill("Add app", R.drawable.ic_app_open) {
                pickApp("Add an app", exclude = mine.toSet()) { pkg ->
                    // An app belongs to one profile: this moves it from another.
                    Profiles.update(applicationContext) { it.assigned(pkg, profileId) }
                    BandService.instance?.remap()
                }
            }.first)
        })

        actions.removeAllViews()
        if (list.profiles.size > 1) {
            actions.addView(pill("Delete") {
                confirm(
                    "Delete ${profile.name}?",
                    "Its gestures are gone, and its apps go back to the profile you pick.",
                    "Delete",
                ) {
                    Profiles.update(applicationContext) { it.deleted(profileId) }
                    BandService.instance?.remap()
                    finish()
                }
            }.first)
        }
    }

    private fun appRow(pkg: String) = row(gap = 12f).apply {
        setPadding(0, dpi(6f), 0, dpi(6f))
        addView(ImageView(context).apply {
            runCatching { setImageDrawable(packageManager.getApplicationIcon(pkg)) }
            layoutParams = LinearLayout.LayoutParams(dpi(24f), dpi(24f))
        })
        addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = appName(pkg) }, weighted())
        addView(pill("Remove") {
            Profiles.update(applicationContext) { it.unassigned(pkg) }
            BandService.instance?.remap()
            render()
        }.first)
    }

    companion object {
        const val EXTRA_PROFILE = "profile"

        fun open(context: Context, profile: String) =
            context.startActivity(Intent(context, ProfileActivity::class.java).putExtra(EXTRA_PROFILE, profile))
    }
}
