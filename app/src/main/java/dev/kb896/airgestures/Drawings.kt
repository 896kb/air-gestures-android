package dev.kb896.airgestures

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File

/** One stroke: points in degrees (x right, y up), as band-core draws them. */
typealias Stroke = List<Pair<Double, Double>>

/** A taught drawing: its name and the tries it was taught with (each a list of strokes). */
data class Drawing(val name: String, val tries: List<List<Stroke>>)

/** The taught drawings, as the bridge reads them (`teach::library`). Free of Android, so it's unit-tested. */
data class DrawingLibrary(val drawings: List<Drawing> = emptyList()) {
    val names get() = drawings.map { it.name }
    fun get(name: String) = drawings.firstOrNull { it.name == name }

    /** Taught (again): replaces the one with its name, else goes at the end. */
    fun with(drawing: Drawing) = if (get(drawing.name) == null) copy(drawings = drawings + drawing)
        else copy(drawings = drawings.map { if (it.name == drawing.name) drawing else it })

    fun renamed(old: String, new: String) = copy(drawings = drawings.map { if (it.name == old) it.copy(name = new) else it })
    fun without(name: String) = copy(drawings = drawings.filter { it.name != name })

    /** Without [name] (null: all): what a drawing being taught again mustn't "look like". */
    fun except(name: String?) = if (name == null) this else without(name)

    fun toJson(): String = JSONArray(drawings.map { d ->
        org.json.JSONObject().put("name", d.name).put("tries", JSONArray(d.tries.map(::tryJson)))
    }).toString()

    companion object {
        /**
         * The drawing frame the stored tries are in: 2 since band-core drew upright (2026-10-26;
         * before, a drawing came out turned 180°, so old tries don't match new ones).
         */
        const val FRAME = 2

        /**
         * A restored library's drawings to teach again: all of them when its backup has no frame
         * (made before the frame was stored) or an older one, else the ones its backup listed.
         */
        fun restoredReteach(frame: Int?, names: List<String>, listed: Set<String>): Set<String> =
            if (frame == null || frame < FRAME) names.toSet() else listed intersect names.toSet()

        /** The re-teach list after [old] became [new]: it keeps its place under the new name. */
        fun renamedIn(names: Set<String>, old: String, new: String) = if (old in names) names - old + new else names

        /** The drawings to teach again: all of them when they were taught in an older frame. */
        fun toReteach(storedFrame: Int, names: List<String>): Set<String> = if (storedFrame < FRAME) names.toSet() else emptySet()

        /** What a drawing runs in a profile: its own action, else the shared one; an own "" is no own action ("" = nothing). */
        fun action(own: Map<String, String>, shared: Map<String, String>, name: String) =
            own[name]?.takeIf { it.isNotEmpty() } ?: shared[name].orEmpty()

        /** Actions for drawings that exist ([names]), "" ones dropped: drawings.json is the source of truth. */
        fun pruned(actions: Map<String, String>, names: Set<String>) = actions.filter { it.key in names && it.value.isNotEmpty() }

        fun tryJson(strokes: List<Stroke>) = JSONArray(strokes.map { s -> JSONArray(s.map { (x, y) -> JSONArray().put(x).put(y) }) })

        /** One try's strokes from JSON (`@draw-finished`, a stored try). */
        fun strokes(json: String): List<Stroke> = strokes(JSONArray(json))

        private fun strokes(a: JSONArray): List<Stroke> = (0 until a.length()).map { i ->
            val s = a.getJSONArray(i)
            (0 until s.length()).map { j -> s.getJSONArray(j).let { it.getDouble(0) to it.getDouble(1) } }
        }

        fun fromJson(json: String): DrawingLibrary {
            val a = JSONArray(json)
            return DrawingLibrary((0 until a.length()).map { i ->
                val d = a.getJSONObject(i)
                val tries = d.getJSONArray("tries")
                Drawing(d.getString("name"), (0 until tries.length()).map { strokes(tries.getJSONArray(it)) })
            })
        }
    }
}

/**
 * The drawings file's state, free of Android so it's unit-tested. [read] gives the file's text
 * (null: no file yet; an exception: unreadable), [write] stores it, [keepBroken] copies an
 * unreadable file aside, [loaded] hears every good read. An unreadable file is never written
 * over ([update] refuses, [problem] says why) and isn't cached: the next load tries it again.
 */
class DrawingStore(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val keepBroken: () -> Unit,
    private val loaded: (DrawingLibrary) -> Unit = {},
) {
    @Volatile
    private var cached: DrawingLibrary? = null

    /** False while the file can't be read: nothing may write over it or prune actions from it. */
    @Volatile
    var readable = true
        private set

    /** What a screen shows while the file can't be read. */
    val problem get() = if (readable) null else PROBLEM

    fun load(): DrawingLibrary = cached ?: synchronized(this) { cached ?: fresh() }

    private fun fresh(): DrawingLibrary {
        val library = runCatching { read()?.let(DrawingLibrary::fromJson) ?: DrawingLibrary() }.getOrNull()
        if (library == null) {
            // Copied aside once, when it first fails.
            if (readable) keepBroken()
            readable = false
            return DrawingLibrary()
        }
        readable = true
        cached = library
        loaded(library)
        return library
    }

    /** The drawings changed and stored; null (nothing written) while the file can't be read. */
    fun update(change: (DrawingLibrary) -> DrawingLibrary): DrawingLibrary? = synchronized(this) {
        val now = load()
        if (!readable) return null
        change(now).also {
            write(it.toJson())
            cached = it
        }
    }

    /** All of them replaced on purpose (a backup's), even over an unreadable file (its copy is kept). */
    fun replace(library: DrawingLibrary) = synchronized(this) {
        write(library.toJson())
        cached = library
        readable = true
    }

    companion object {
        const val PROBLEM = "Your drawings file can't be read; it was kept as drawings.broken. Drawings can't be changed until it's fixed or a backup is restored."
    }
}

/** Where the drawings live: the templates in a file (they can be large), the shared actions in [Profiles]. */
object Drawings {
    @Volatile
    private var store: DrawingStore? = null

    private fun store(context: Context): DrawingStore = store ?: synchronized(this) {
        store ?: make(context.applicationContext).also { store = it }
    }

    private fun make(context: Context): DrawingStore {
        val file = AtomicFile(File(context.filesDir, "drawings.json"))
        return DrawingStore(
            // readFully, not the base file's existence: AtomicFile also restores a write a crash cut short.
            read = {
                try {
                    String(file.readFully())
                } catch (_: java.io.FileNotFoundException) {
                    null
                }
            },
            write = { text ->
                val out = file.startWrite()
                try {
                    out.write(text.toByteArray())
                    file.finishWrite(out)
                } catch (e: Exception) {
                    file.failWrite(out)
                    throw e
                }
            },
            keepBroken = {
                android.util.Log.w("AirGestures", "drawings unreadable; kept as drawings.broken")
                runCatching { file.baseFile.copyTo(File(context.filesDir, "drawings.broken"), overwrite = true) }
            },
            loaded = { library ->
                // Actions only for drawings that exist (the library is the source of truth).
                Profiles.pruneDrawingActions(context, library.names.toSet())
                upright(context, library)
            },
        )
    }

    fun load(context: Context): DrawingLibrary = store(context).load()

    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    /**
     * Once, on the first good read after band-core drew upright: the drawings taught before
     * (now upside down against new tries) are kept, and listed for the Drawings screen's note.
     */
    private fun upright(context: Context, library: DrawingLibrary) {
        val prefs = prefs(context)
        val frame = prefs.getInt(FRAME_KEY, 1)
        if (frame >= DrawingLibrary.FRAME) return
        prefs.edit().putInt(FRAME_KEY, DrawingLibrary.FRAME)
            .putStringSet(RETEACH_KEY, DrawingLibrary.toReteach(frame, library.names)).apply()
    }

    /** The drawings taught before the upright frame and not taught again since (the ones that still exist). */
    fun toReteach(context: Context): Set<String> {
        val names = load(context).names.toSet()
        return prefs(context).getStringSet(RETEACH_KEY, emptySet()).orEmpty().filter { it in names }.toSet()
    }

    /** [name] was taught again (or the note was read: null clears them all). */
    fun retaught(context: Context, name: String?) {
        val left = if (name == null) emptySet() else toReteach(context) - name
        prefs(context).edit().putStringSet(RETEACH_KEY, left).apply()
    }

    private const val FRAME_KEY = "drawings.frame"
    private const val RETEACH_KEY = "drawings.reteach"

    /** False while drawings.json can't be read (see [DrawingStore]). */
    fun readable(context: Context) = store(context).also { it.load() }.readable

    fun problem(context: Context) = store(context).also { it.load() }.problem

    /** Null (nothing written) while drawings.json can't be read. */
    fun update(context: Context, change: (DrawingLibrary) -> DrawingLibrary): DrawingLibrary? = store(context).update(change)

    /**
     * All of them replaced on purpose (a backup's), with what's left to teach again ([reteach]:
     * see [DrawingLibrary.restoredReteach]); the frame is the current one from then on, so a
     * later first read doesn't flag them again.
     */
    fun replace(context: Context, library: DrawingLibrary, reteach: Set<String>) {
        store(context).replace(library)
        prefs(context).edit().putInt(FRAME_KEY, DrawingLibrary.FRAME).putStringSet(RETEACH_KEY, reteach).apply()
    }

    /** [old] was renamed [new]: if it's on the re-teach note, it stays there under its new name. */
    fun renamed(context: Context, old: String, new: String) {
        val names = prefs(context).getStringSet(RETEACH_KEY, emptySet()).orEmpty()
        prefs(context).edit().putStringSet(RETEACH_KEY, DrawingLibrary.renamedIn(names, old, new)).apply()
    }

    /** The action [name] runs in [profile]: its own, else the shared one ("" = none). */
    fun action(context: Context, profile: Profile, name: String) = DrawingLibrary.action(profile.drawings, Profiles.sharedDrawings(context), name)
}
