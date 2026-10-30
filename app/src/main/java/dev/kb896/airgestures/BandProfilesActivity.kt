package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The profiles one band uses (with two bands): which of them, and in what order Next and
 * Previous profile go through them. The profiles themselves are shared: editing one changes
 * it on both bands.
 */
class BandProfilesActivity : Screen() {
    private var slot = 0
    private lateinit var rows: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        slot = intent.getIntExtra(EXTRA_SLOT, 0)
        rows = column(gap = 2f)
        show(pageColumn().apply {
            addView(card(null, Cb.text(context, 13f, color = Cb.INK_500).apply {
                text = "Tick the profiles this band uses; Next and Previous profile on it go through them in this order. " +
                    "A profile is shared: editing it changes it on every band that uses it. An app's own profile " +
                    "switches this band only when it's ticked here."
                setLineSpacing(0f, 1.3f)
            }))
            addView(card("On this band", rows))
        }, title = "Profiles · ${Bands.name(this, slot)}")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    /** The ticked profiles in their order, then the others. */
    private fun render() {
        val all = Profiles.load(this).profiles
        val on = Bands.profiles(this, slot).profiles.map { it.id }
        rows.removeAllViews()
        on.forEachIndexed { index, id -> all.firstOrNull { it.id == id }?.let { rows.addView(profileRow(it, on, index)) } }
        all.filter { it.id !in on }.forEach { rows.addView(profileRow(it, on, null)) }
    }

    private fun profileRow(profile: Profile, on: List<String>, index: Int?) = row(gap = 10f).apply {
        setPadding(0, dpi(6f), 0, dpi(6f))
        val ticked = index != null
        addView(FrameLayout(context).apply {
            addView(icon(if (ticked) R.drawable.ic_check else R.drawable.ic_minus, 16f, if (ticked) Cb.INK_900 else Cb.INK_300),
                FrameLayout.LayoutParams(dpi(16f), dpi(16f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dpi(32f), dpi(32f))
            contentDescription = if (ticked) "Used on this band" else "Not used on this band"
        })
        addView(Cb.text(context, 14f, R.font.geist_medium, if (ticked) Cb.INK_900 else Cb.INK_500).apply { text = profile.name }, weighted())
        isClickable = true
        background = ripple()
        setOnClickListener {
            // The last one stays: a band always has a profile.
            if (ticked && on.size == 1) return@setOnClickListener
            save(if (ticked) on - profile.id else on + profile.id)
        }
        if (index != null) {
            addView(small(R.drawable.ic_arrow_up, "Move up", index > 0) { save(on.moved(index, -1)) })
            addView(small(R.drawable.ic_arrow_down, "Move down", index < on.lastIndex) { save(on.moved(index, +1)) })
        }
    }

    private fun small(iconRes: Int, description: String, enabled: Boolean, onClick: () -> Unit) = FrameLayout(this).apply {
        addView(icon(iconRes, 16f, if (enabled) Cb.INK_700 else Cb.INK_300), FrameLayout.LayoutParams(dpi(16f), dpi(16f), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dpi(32f), dpi(32f))
        contentDescription = description
        if (enabled) {
            isClickable = true
            background = ripple(999f)
            setOnClickListener { onClick() }
        }
    }

    private fun List<String>.moved(index: Int, by: Int) = toMutableList().apply { add(index + by, removeAt(index)) }

    /** Every profile in the list's order is "all of them" (new ones join it). */
    private fun save(ids: List<String>) {
        val all = Profiles.load(this).profiles.map { it.id }
        Bands.setOn(this, slot, ids.takeIf { it != all })
        BandService.instance?.remap()
        render()
    }

    companion object {
        private const val EXTRA_SLOT = "slot"

        fun open(context: Context, slot: Int) =
            context.startActivity(Intent(context, BandProfilesActivity::class.java).putExtra(EXTRA_SLOT, slot))
    }
}
