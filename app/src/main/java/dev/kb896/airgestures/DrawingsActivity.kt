package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Drawings (mockup 15): the Draw band control, the drawings with what they do in every
 * profile, and the profiles' own actions for them (which replace the shared one there).
 * Opened from a profile ([EXTRA_PROFILE]), only that profile's own card shows.
 */
class DrawingsActivity : GestureScreen() {
    private var profileId: String? = null
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileId = intent.getStringExtra(EXTRA_PROFILE)
        body = column(gap = 20f)
        show(pageColumn().apply { addView(body) }, title = "Drawings")
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun render() {
        val band = Profiles.band(this)
        val report = Report.of(this)
        val library = Drawings.load(this)
        val shared = Profiles.sharedDrawings(this)
        val list = Profiles.load(this)
        body.removeAllViews()
        Drawings.problem(this)?.let { body.addView(note(it).apply { setTextColor(Cb.INK_900) }) }
        // Once after the update that drew drawings upright: the old ones won't match new tries.
        val reteach = Drawings.toReteach(this)
        if (reteach.isNotEmpty()) {
            body.addView(card(null, column(gap = 10f).apply {
                addView(note("Drawings taught before this update need teaching again: ${reteach.sorted().joinToString(", ")}.").apply {
                    setTextColor(Cb.INK_900)
                })
                addView(row().apply { addView(pill("Got it") { Drawings.retaught(applicationContext, null); render() }.first) })
            }))
        }

        val draw = band[Builtin.DRAW]
        body.addView(card(null, column(gap = 8f).apply {
            addView(navRow(Builtin.DRAW.icon, "Draw", detail = draw?.label ?: "Nothing", count = report.count(Builtin.DRAW)) {
                BandControlActivity.open(this@DrawingsActivity, Builtin.DRAW)
            })
            if (draw == null) addView(note("Put Draw on a gesture, then pinch and draw in the air."))
        }))

        body.addView(card("All profiles", column(gap = 8f).apply {
            // (While the file can't be read the note at the top says why there are none.)
            if (library.drawings.isEmpty() && Drawings.readable(context)) addView(note("No drawings yet: teach one, then a drawing runs its action."))
            for (drawing in library.drawings) {
                val action = shared[drawing.name].orEmpty()
                // No shared action but some profiles' own: say where it does something.
                val owners = list.profiles.filter { it.drawings[drawing.name].orEmpty().isNotEmpty() }.map { it.name }
                val label = if (action.isEmpty() && owners.isNotEmpty()) "only in ${owners.joinToString(", ")}" else Labels.action(context, action)
                addView(drawingRow(drawing, label) { sharedMenu(drawing.name) })
            }
            addView(row().apply { addView(pill("Teach a drawing", R.drawable.ic_pencil) { TeachDrawingActivity.open(this@DrawingsActivity, null, null) }.first) })
        }))

        val profiles = profileId?.let { id -> listOfNotNull(list.profile(id)) }
            ?: list.profiles.filter { p -> p.drawings.keys.any { library.get(it) != null } }
        for (profile in profiles) {
            body.addView(card("${profile.name}'s own", column(gap = 8f).apply {
                // An own action is never "" (that's the shared one): stored ones were pruned.
                val own = library.drawings.filter { profile.drawings[it.name].orEmpty().isNotEmpty() }
                if (own.isEmpty()) addView(note("None: every drawing does what All profiles says."))
                for (drawing in own) {
                    val tag = if (shared[drawing.name].orEmpty().isNotEmpty()) "replaces shared" else null
                    addView(drawingRow(drawing, Labels.action(context, profile.drawings[drawing.name].orEmpty()), tag) { ownMenu(profile, drawing.name) })
                }
                addView(row().apply {
                    addView(pill("Teach one for ${profile.name}", R.drawable.ic_pencil) {
                        TeachDrawingActivity.open(this@DrawingsActivity, null, profile.id)
                    }.first)
                })
            }))
        }
    }

    /** A drawing: its first try small, its name, what it does here, and a quiet [tag]. */
    private fun drawingRow(drawing: Drawing, action: String, tag: String? = null, onClick: () -> Unit) = row(gap = 12f).apply {
        setPadding(0, dpi(6f), 0, dpi(6f))
        addView(FrameLayout(context).apply {
            background = Cb.hairlineCard(context, 10f)
            addView(StrokesView(context, ink = Cb.INK_900, widthDp = 2f).apply { strokes = thumbnail(drawing) })
        }, LinearLayout.LayoutParams(dpi(40f), dpi(40f)))
        addView(column(gap = 2f).apply {
            addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply {
                text = drawing.name
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = action })
        }, weighted())
        if (tag != null) addView(Cb.text(context, 12f, color = Cb.INK_400).apply { text = tag })
        addView(icon(R.drawable.ic_chevron_right, 14f, Cb.INK_400))
        isClickable = true
        background = ripple()
        setOnClickListener { onClick() }
    }

    private fun sharedMenu(name: String) {
        val current = Profiles.sharedDrawings(this)[name].orEmpty()
        val options = listOf(
            Option("Action: ${Labels.action(this, current)}", false, R.drawable.ic_zap),
            Option("Teach it again", false, R.drawable.ic_pencil),
            Option("Rename", false, R.drawable.ic_pencil),
            Option("Delete", false, R.drawable.ic_x),
        )
        choose(name, options, heading = "Drawing") { i ->
            when (i) {
                0 -> pickAction(name, current) { id -> changed { Profiles.updateDrawingActions(applicationContext) { l, shared -> l to shared + (name to id) } } }
                1 -> TeachDrawingActivity.open(this, name, null)
                2 -> rename(name)
                3 -> confirm("Delete $name?", "It's gone from every profile, with its actions.", "Delete") { changed { remove(name) } }
            }
        }
    }

    private fun ownMenu(profile: Profile, name: String) {
        val current = profile.drawings[name].orEmpty()
        val options = listOf(
            Option("Action: ${Labels.action(this, current)}", false, R.drawable.ic_zap),
            Option("Teach it again", false, R.drawable.ic_pencil),
            Option("Remove from ${profile.name}", false, R.drawable.ic_x),
        )
        choose(name, options, heading = profile.name) { i ->
            when (i) {
                // Nothing here is "use the shared one": no own action, not an own "Nothing" (the shared one would run).
                0 -> pickAction(name, current) { id ->
                    changed { Profiles.updateDrawingActions(applicationContext) { l, shared -> l.withDrawing(profile.id, name, id.ifEmpty { null }) to shared } }
                }
                1 -> TeachDrawingActivity.open(this, name, profile.id)
                // The shared action applies there again.
                2 -> changed { Profiles.updateDrawingActions(applicationContext) { l, shared -> l.withDrawing(profile.id, name, null) to shared } }
            }
        }
    }

    private fun rename(old: String) = ask("Rename", old, "Save") { typed ->
        val new = cleanName(typed)
        if (new.isEmpty()) {
            sheet("A drawing needs a name", note("Type a name for $old."), "OK", cancel = false) { true }
            return@ask
        }
        if (!DrawTeach.nameFree(new, Drawings.load(this).names, own = old)) {
            sheet("That name is taken", note("Another drawing is called $new: pick another name."), "OK", cancel = false) { true }
            return@ask
        }
        if (new == old) return@ask
        changed { renameDrawing(applicationContext, old, new) }
    }

    /** A drawing and its actions, everywhere: the library first (the source of truth), then its actions. */
    private fun remove(name: String) {
        // The file can't be read: nothing changes (the screen says why).
        Drawings.update(applicationContext) { it.without(name) } ?: return
        Profiles.updateDrawingActions(applicationContext) { l, shared -> l.renamedDrawing(name, null) to (shared - name) }
    }

    /** After any change: the band matches against the new drawings, with the new actions. */
    private fun changed(change: () -> Unit) {
        change()
        BandService.instance?.drawingsChanged()
        BandService.instance?.remap()
        render()
    }

    companion object {
        const val EXTRA_PROFILE = "profile"

        fun open(context: Context, profile: String? = null) =
            context.startActivity(Intent(context, DrawingsActivity::class.java).apply { profile?.let { putExtra(EXTRA_PROFILE, it) } })

        /** A drawing's first try, as StrokesView draws it. */
        fun thumbnail(drawing: Drawing) = drawing.tries.firstOrNull().orEmpty().map { s -> s.map { (x, y) -> x.toFloat() to y.toFloat() } }

        /**
         * [old] becomes [new] without losing its actions if it stops halfway: the actions are
         * copied to the new name, the library is written, then the old name's actions go.
         * False (nothing changed) while the drawings file can't be read.
         */
        fun renameDrawing(context: Context, old: String, new: String): Boolean {
            if (!Drawings.readable(context)) return false
            Profiles.updateDrawingActions(context) { l, shared ->
                l.renamedDrawing(new, null).copiedDrawing(old, new) to ((shared - new) + (shared[old]?.let { mapOf(new to it) } ?: emptyMap()))
            }
            // Refused after all: the copies stay; the next good load prunes nothing that exists.
            Drawings.update(context) { it.renamed(old, new) } ?: return false
            Drawings.renamed(context, old, new)
            Profiles.updateDrawingActions(context) { l, shared -> l.renamedDrawing(old, null) to (shared - old) }
            return true
        }

        /** A name on one line (the band's `@drawn` line carries it), trimmed. */
        fun cleanName(name: String) = name.replace(Regex("\\s+"), " ").trim()
    }
}
