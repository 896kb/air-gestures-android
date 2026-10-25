package dev.kb896.airgestures

import android.content.Context
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

/** Where the drawings live: the templates in a file (they can be large), the shared actions in [Profiles]. */
object Drawings {
    @Volatile
    private var cached: DrawingLibrary? = null

    private fun file(context: Context) = File(context.filesDir, "drawings.json")

    fun load(context: Context): DrawingLibrary = cached ?: synchronized(this) {
        cached ?: (runCatching { DrawingLibrary.fromJson(file(context).readText()) }.getOrNull() ?: DrawingLibrary()).also { cached = it }
    }

    fun update(context: Context, change: (DrawingLibrary) -> DrawingLibrary): DrawingLibrary = synchronized(this) {
        change(load(context)).also {
            cached = it
            file(context).writeText(it.toJson())
        }
    }

    /** The action [name] runs in [profile]: its own, else the shared one ("" = none). */
    fun action(context: Context, profile: Profile, name: String) = profile.drawings[name] ?: Profiles.sharedDrawings(context)[name].orEmpty()
}
