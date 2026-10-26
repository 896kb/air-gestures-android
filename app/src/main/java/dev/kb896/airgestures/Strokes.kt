package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import kotlin.math.roundToLong

/**
 * "Swipe here" and drawn swipes as action ids, and a recording turned into sequence steps.
 * Coordinates are fractions of the screen, times in ms from the touch's start. Free of
 * Android, so it's unit-tested.
 */
object Strokes {
    const val SWIPE = "swipe:"
    const val PATH = "path:"

    data class Point(val x: Float, val y: Float, val t: Long)

    data class Path(val points: List<Point>) {
        val duration get() = points.last().t - points.first().t
    }

    /** A straight swipe from (x1, y1) to (x2, y2) over [ms]. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) =
        String.format(Locale.ROOT, "%s%.4f,%.4f,%.4f,%.4f,%d", SWIPE, x1, y1, x2, y2, ms)

    /** A drawn swipe, its shape and timing as drawn. */
    fun path(path: Path) = PATH + path.points.joinToString("|") { String.format(Locale.ROOT, "%.4f,%.4f,%d", it.x, it.y, it.t) }

    /** The stroke behind a `swipe:` or `path:` id, or null for anything else or anything off the screen. */
    fun parse(id: String): Path? = runCatching {
        when {
            id.startsWith(SWIPE) -> {
                val v = id.removePrefix(SWIPE).split(',')
                require(v.size == 5)
                val ms = v[4].toLong()
                require(ms > 0)
                Path(listOf(Point(v[0].toFloat(), v[1].toFloat(), 0), Point(v[2].toFloat(), v[3].toFloat(), ms)))
            }
            id.startsWith(PATH) -> Path(id.removePrefix(PATH).split('|').map { part ->
                val v = part.split(',')
                require(v.size == 3)
                Point(v[0].toFloat(), v[1].toFloat(), v[2].toLong())
            })
            else -> null
        }?.also { path ->
            require(path.points.size >= 2)
            require(path.points.all { it.x in 0f..1f && it.y in 0f..1f })
            require(path.points.zipWithNext().all { (a, b) -> b.t >= a.t } && path.duration > 0)
        }
    }.getOrNull()

    /** A touch that barely moves and is short is a tap. */
    private fun isTap(path: Path): Boolean {
        val xs = path.points.map { it.x }
        val ys = path.points.map { it.y }
        return path.duration < 300 && (xs.max() - xs.min()) < .02f && (ys.max() - ys.min()) < .02f
    }

    /**
     * What a recording becomes: each touch (its stroke, and when it started, in ms) a tap or a
     * drawn swipe, with the time between touches as waits (to the nearest 0.1 s).
     */
    fun steps(touches: List<Pair<Path, Long>>): List<String> = buildList {
        var endedAt: Long? = null
        for ((path, start) in touches) {
            endedAt?.let { ended -> add(Sequences.wait(((start - ended) / 100.0).roundToLong().coerceAtLeast(1) * 100)) }
            add(
                if (isTap(path)) {
                    Tap.format(path.points.map { it.x }.average().toFloat(), path.points.map { it.y }.average().toFloat())
                } else {
                    path(path)
                },
            )
            endedAt = start + path.duration
        }
    }
}

/** Named lists of actions a gesture can run in one go (shared by every profile). */
object Sequences {
    const val PREFIX = "seq:"
    const val WAIT = "wait:"

    fun wait(ms: Long) = "$WAIT$ms"

    fun waitMs(step: String) = step.removePrefix(WAIT).toLongOrNull()?.takeIf { step.startsWith(WAIT) && it > 0 }

    /**
     * A sequence action as the bridge reports it: its id, and the profile whose gesture started
     * it (`seq:<id>/<URL-encoded profile>`, see Mappings.profilesJson); null for a plain `seq:<id>`.
     */
    fun started(action: String): Pair<String, String?> {
        val rest = action.removePrefix(PREFIX)
        if ('/' !in rest) return rest to null
        val profile = rest.substringAfter('/')
        return rest.substringBefore('/') to runCatching { URLDecoder.decode(profile, "UTF-8") }.getOrDefault(profile)
    }

    /**
     * What a Send to automation step of sequence [sequenceName] sends (`gesture=sequence`, its
     * name, [profile] and the step's device); null for any other step, which runs as it is.
     */
    fun stepAutomation(sequenceName: String, profile: String?, step: String) =
        if (Mappings.isAutomation(step)) Mappings.Automation.Sequence(sequenceName, profile, Mappings.deviceOf(step)) else null

    data class Sequence(val id: String, val name: String, val steps: List<String>)

    /**
     * The sequences, and the number the next one's id gets ([next]): ids are never given out
     * again, so a gesture still set to a deleted sequence doesn't run a new one.
     */
    data class Library(val sequences: List<Sequence>, val next: Int = 1) {
        fun get(id: String?) = sequences.firstOrNull { it.id == id }

        /** A new, empty sequence: a fresh id, and the lowest free "Sequence n" name. */
        fun added(): Pair<Library, Sequence> {
            val id = maxOf(next, (sequences.mapNotNull { it.id.removePrefix("q").toIntOrNull() }.maxOrNull() ?: 0) + 1)
            val n = generateSequence(1) { it + 1 }.first { n -> sequences.none { it.name == "Sequence $n" } }
            val made = Sequence("q$id", "Sequence $n", emptyList())
            return Library(sequences + made, id + 1) to made
        }

        fun renamed(id: String, name: String): Library {
            val clean = name.trim().takeIf { it.isNotEmpty() } ?: return this
            return change(id) { it.copy(name = clean) }
        }

        fun withSteps(id: String, steps: List<String>) = change(id) { it.copy(steps = steps) }

        /** The step at [index] moved [by] places (−1 up, +1 down), when there's room. */
        fun moved(id: String, index: Int, by: Int) = change(id) { sequence ->
            val to = index + by
            if (index !in sequence.steps.indices || to !in sequence.steps.indices) return@change sequence
            sequence.copy(steps = sequence.steps.toMutableList().apply { add(to, removeAt(index)) })
        }

        fun deleted(id: String) = copy(sequences = sequences.filter { it.id != id })

        private fun change(id: String, how: (Sequence) -> Sequence) = copy(sequences = sequences.map { if (it.id == id) how(it) else it })

        fun toJson(): String = JSONObject().apply {
            put("next", next)
            put("sequences", JSONArray().apply {
                for (sequence in sequences) {
                    put(JSONObject().apply {
                        put("id", sequence.id)
                        put("name", sequence.name)
                        put("steps", JSONArray(sequence.steps))
                    })
                }
            })
        }.toString()

        companion object {
            /** Throws on anything it can't read. (A plain list is how it was first stored.) */
            fun fromJson(json: String): Library {
                val text = json.trim()
                val (list, next) = if (text.startsWith("[")) JSONArray(text) to 1 else JSONObject(text).let { it.getJSONArray("sequences") to it.optInt("next", 1) }
                return Library((0 until list.length()).map { i ->
                    val item = list.getJSONObject(i)
                    val steps = item.getJSONArray("steps")
                    Sequence(item.getString("id"), item.getString("name"), (0 until steps.length()).map(steps::getString))
                }, next)
            }
        }
    }
}
