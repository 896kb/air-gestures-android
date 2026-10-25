package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout

/** One profile: its gestures and dial, its name, and the apps that switch to it. */
class ProfileActivity : GestureScreen() {
    private lateinit var profileId: String
    private lateinit var warnings: LinearLayout
    private lateinit var warningsCard: LinearLayout
    private lateinit var gestures: LinearLayout
    private lateinit var apps: LinearLayout
    private lateinit var actions: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE) ?: Profiles.load(this).picked
        warnings = column(gap = 8f)
        warningsCard = card(null, warnings)
        gestures = column()
        apps = column(gap = 8f)
        actions = row(gap = 10f)
        show(pageColumn().apply {
            addView(warningsCard)
            addView(card(null, gestures))
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
        showWarnings(list.profiles.indexOf(profile))
        gestures.removeAllViews()
        gestures.addView(profileRows(profileId))

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

    /**
     * What the band's layer finds missing in this profile (the [index]-th): no way to turn the
     * controls on and off, or to switch away. Only a note; nothing is blocked.
     */
    private fun showWarnings(index: Int) {
        val lines = runCatching { Bridge.profileWarnings(Prefs(this).profilesJson()) }.getOrDefault("").lines()
        val texts = lines.mapNotNull { line ->
            val (kind, at) = line.split(' ').takeIf { it.size == 2 } ?: return@mapNotNull null
            if (at.toIntOrNull() != index) return@mapNotNull null
            when (kind) {
                "no-controls" -> "No gesture turns the controls on and off in this profile. You can still do it from the app."
                "no-way-out" -> "No gesture switches away from this profile. Use the app or the widget to switch."
                else -> null
            }
        }
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
