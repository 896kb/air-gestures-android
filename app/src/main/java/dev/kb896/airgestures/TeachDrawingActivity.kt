package dev.kb896.airgestures

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONObject

/**
 * Teach a drawing (mockup 16): three tries drawn with the band (draw sessions this screen
 * opens; nothing runs meanwhile), band-core's checks (the tries alike, not like another
 * drawing), then its name, its action and where: all profiles or one profile's own.
 * [EXTRA_NAME]: teaching that drawing again.
 */
class TeachDrawingActivity : GestureScreen(), BandService.Drawing {
    private val main = Handler(Looper.getMainLooper())
    private val teach = DrawTeach()
    private var editing: String? = null
    private var profileId: String? = null
    private var name = ""
    private var action = ""
    /** True: only in [profileId]'s own; false: all profiles. */
    private var own = false
    private var message: String? = null
    private var alike = false
    /** The session ended with nothing drawn: wait for the user's Draw. */
    private var waiting = false
    private val live = sortedMapOf<Int, MutableList<Pair<Float, Float>>>()
    private var fresh = false
    private var liveView: StrokesView? = null
    private lateinit var body: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editing = intent.getStringExtra(EXTRA_NAME)
        val list = Profiles.load(this)
        val extra = intent.getStringExtra(EXTRA_PROFILE)
        profileId = extra ?: list.picked
        own = extra != null
        name = editing.orEmpty()
        action = editing?.let { e ->
            (if (own) list.profile(profileId)?.drawings?.get(e) else null) ?: Profiles.sharedDrawings(this)[e]
        }.orEmpty()
        body = column(gap = 20f)
        show(pageColumn().apply { addView(body) }, title = if (editing != null) "Teach $editing again" else "Teach a drawing")
    }

    override fun onResume() {
        super.onResume()
        listening = this
        BandService.instance?.startTeachingDrawing(this)
        if (!teach.full) BandService.instance?.drawTry()
        render()
    }

    override fun onPause() {
        if (listening === this) listening = null
        main.removeCallbacksAndMessages(null)
        BandService.instance?.endTeachingDrawing()
        super.onPause()
    }

    // BandService.Drawing: this screen's draw sessions.

    override fun started() {
        live.clear()
        fresh = false
        waiting = false
        liveView?.strokes = emptyList()
    }

    override fun pen(down: Boolean) {
        if (down) fresh = true
    }

    override fun point(stroke: Int, x: Float, y: Float) {
        if (fresh) {
            live[stroke] = mutableListOf(x to y)
            fresh = false
        } else {
            live.getOrPut(stroke) { mutableListOf() } += x to y
        }
        liveView?.strokes = live.values.map { it.toList() }
    }

    override fun finished(strokes: String) {
        val tried = runCatching { DrawingLibrary.strokes(strokes) }.getOrNull()
        live.clear()
        if (tried.isNullOrEmpty()) return cancelled()
        teach.add(tried)
        message = null
        if (!teach.full) {
            main.postDelayed({ BandService.instance?.drawTry() }, NEXT_MS)
            return render()
        }
        message = teach.refused(Bridge.checkTries(teach.json())) ?: lookalike()
        alike = message == null
        if (!teach.full) main.postDelayed({ BandService.instance?.drawTry() }, NEXT_MS)
        render()
    }

    /** "Looks like Circle": another drawing these tries would be taken for (then they start again). */
    private fun lookalike(): String? {
        val answer = runCatching { JSONObject(Bridge.looksLike(Drawings.load(this).except(editing).toJson(), teach.json())) }.getOrNull()
            ?: return null
        answer.optString("error").takeIf { it.isNotEmpty() }?.let { return "Couldn't compare with your drawings ($it)." }
        if (answer.isNull("like")) return null
        val like = answer.optString("like")
        teach.tries.clear()
        return "Looks like $like: draw something else, or teach $like again."
    }

    override fun cancelled() {
        // Ten seconds without a stroke (or the band's close): wait for the user.
        waiting = true
        message = "Draw when you're ready."
        render()
    }

    private fun startAgain() {
        teach.tries.clear()
        alike = false
        message = null
        waiting = false
        BandService.instance?.drawTry()
        render()
    }

    override fun render() {
        body.removeAllViews()
        if (BandService.instance == null) body.addView(note("Connect the band to draw your tries."))
        // The three tries.
        body.addView(row(gap = 10f).apply {
            liveView = null
            for (i in 0 until DrawTeach.TRIES) {
                addView(Square(context).apply {
                    background = Cb.hairlineCard(context, 12f)
                    val done = teach.tries.getOrNull(i)
                    when {
                        done != null -> addView(StrokesView(context, ink = Cb.INK_900, widthDp = 2.5f).apply {
                            strokes = done.map { s -> s.map { (x, y) -> x.toFloat() to y.toFloat() } }
                        })
                        i == teach.tries.size && !waiting -> addView(StrokesView(context, ink = Cb.INK_900, widthDp = 2.5f).also {
                            it.strokes = live.values.map { s -> s.toList() }
                            liveView = it
                        })
                        else -> addView(Cb.text(context, 12f, color = Cb.INK_400).apply { text = "Try ${i + 1}" },
                            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                    }
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        })
        when {
            message != null -> body.addView(note(message!!).apply { setTextColor(Cb.INK_900) })
            teach.full && alike -> body.addView(row().apply { addView(Cb.chip(context, Cb.Signal.LIVE, "Alike")) })
            else -> body.addView(note("Pinch and draw it, ${DrawTeach.TRIES} times: in the air or with the pen grip on a table."))
        }

        val list = Profiles.load(this)
        val profile = list.profile(profileId) ?: list.active(null)
        body.addView(card(null, column(gap = 4f).apply {
            addView(navRow(R.drawable.ic_pencil, "Name", detail = name.ifEmpty { "None yet" }) {
                ask("Name", name, "Save") { typed ->
                    name = DrawingsActivity.cleanName(typed)
                    render()
                }
            })
            addView(navRow(Action.of(action).icon, "Action", detail = Labels.action(context, action)) {
                pickAction(name.ifEmpty { "Drawing" }, action) { id ->
                    action = id
                    render()
                }
            })
            val all = segment("All profiles") { own = false; render() }
            val only = segment("Only in ${profile.name}") { own = true; profileId = profile.id; render() }
            markSegment(all, !own)
            markSegment(only, own)
            addView(row().apply {
                setPadding(0, dpi(8f), 0, 0)
                addView(segmented(all, only))
            })
        }))

        val nameOk = DrawTeach.nameFree(name, Drawings.load(this).names, own = editing)
        if (name.isNotEmpty() && !nameOk) body.addView(note("Another drawing is called $name: pick another name."))
        body.addView(row(gap = 10f).apply {
            val ready = teach.full && alike && nameOk
            addView(pill("Save", R.drawable.ic_check) { if (ready) save() }.first.apply {
                isEnabled = ready
                alpha = if (ready) 1f else .4f
            })
            addView(pill("Start again") { startAgain() }.first)
            if (waiting) addView(pill("Draw", R.drawable.ic_pencil) { waiting = false; message = null; BandService.instance?.drawTry(); render() }.first)
        })
    }

    private fun save() {
        val clean = DrawingsActivity.cleanName(name)
        val old = editing
        Drawings.update(applicationContext) { library ->
            // Taught again under another name: the old one goes, its actions move.
            (if (old != null && old != clean) library.without(old) else library).with(Drawing(clean, teach.tries.toList()))
        }
        if (old != null && old != clean) {
            Profiles.updateSharedDrawings(applicationContext) { shared -> shared[old]?.let { (shared - old) + (clean to it) } ?: shared }
            Profiles.update(applicationContext) { it.renamedDrawing(old, clean) }
        }
        val where = profileId
        if (own && where != null) {
            Profiles.update(applicationContext) { it.withDrawing(where, clean, action) }
        } else {
            Profiles.updateSharedDrawings(applicationContext) { it + (clean to action) }
        }
        BandService.instance?.drawingsChanged()
        BandService.instance?.remap()
        finish()
    }

    /** A tile as tall as it is wide: the three share the row. */
    private class Square(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) = super.onMeasure(widthSpec, widthSpec)
    }

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_PROFILE = "profile"

        /** The pause before the next try opens. */
        private const val NEXT_MS = 600L

        /** The teach screen in front, for the debug build's test hook (DebugActions). */
        @Volatile
        var listening: BandService.Drawing? = null
            private set

        fun open(context: Context, name: String?, profile: String?) = context.startActivity(
            Intent(context, TeachDrawingActivity::class.java).apply {
                name?.let { putExtra(EXTRA_NAME, it) }
                profile?.let { putExtra(EXTRA_PROFILE, it) }
            },
        )
    }
}
