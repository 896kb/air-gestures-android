package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/**
 * One palm position held still, for the calibration: rolls (degrees) come in at any rate;
 * once they've stayed within [STEADY] of each other for [SETTLE] ms, [HOLD] ms of them are
 * kept. A move past [STEADY], or no reading, starts again. band-core checks the result
 * (PalmCalibration::from_rolls).
 */
class PalmSampler {
    private val recent = ArrayDeque<Pair<Long, Double>>()
    private val kept = mutableListOf<Double>()
    private var keptFrom: Long? = null

    /** 0…1: how much of [HOLD] is kept. */
    var progress = 0f
        private set
    val done get() = progress >= 1f
    val rolls: DoubleArray get() = kept.toDoubleArray()

    fun add(roll: Double?, now: Long) {
        if (done) return
        if (roll == null) return reset()
        recent.addLast(now to roll)
        while (now - recent.first().first > SETTLE) recent.removeFirst()
        if (recent.any { angle(it.second - roll) > STEADY }) {
            reset()
            recent.addLast(now to roll)
            return
        }
        val from = keptFrom ?: if (now - recent.first().first >= SETTLE * 4 / 5) now.also { keptFrom = it } else return
        kept += roll
        progress = ((now - from).toFloat() / HOLD).coerceIn(0f, 1f)
    }

    fun reset() {
        recent.clear()
        kept.clear()
        keptFrom = null
        progress = 0f
    }

    companion object {
        const val SETTLE = 500L
        const val HOLD = 2000L
        const val STEADY = 6.0

        /** How far apart two angles are (degrees), across ±180. */
        fun angle(difference: Double) = Math.abs(((difference % 360) + 540) % 360 - 180)
    }
}

/** Teach your drags: the turn and the six drags, one pinch each, in this order. */
class DragTeach {
    enum class Step(val key: String, val title: String, val how: String) {
        TURN("turn", "Turn", "Pinch and turn your wrist the way that should turn the volume up."),
        LEFT("left", "Drag left", "Pinch and move your hand to the left."),
        RIGHT("right", "Drag right", "Pinch and move your hand to the right."),
        TOWARD("toward", "Drag toward", "Pinch and pull your hand toward you."),
        AWAY("away", "Drag away", "Pinch and push your hand away from you."),
        UP("up", "Drag up", "Pinch and lift your hand."),
        DOWN("down", "Drag down", "Pinch and lower your hand."),
    }

    private val samples = arrayOfNulls<DoubleArray>(Step.entries.size)
    private var roll: Double? = null

    /** The step the next pinch is for (null: all done). */
    val current get() = Step.entries.firstOrNull { samples[it.ordinal] == null }
    fun done(step: Step) = samples[step.ordinal] != null

    /** A `@drag-sample`: its direction and the roll it was read in. */
    fun sample(direction: DoubleArray, roll: Double) {
        val step = current ?: return
        samples[step.ordinal] = direction
        this.roll = roll
    }

    fun redo(step: Step) {
        samples[step.ordinal] = null
    }

    /** For Bridge.dragPrototypes, once every step has its sample. */
    fun json(): String? {
        if (current != null) return null
        val r = roll ?: return null
        return JSONObject().put("roll", r).put("turn", JSONArray(samples[0]!!.toList()))
            .put("drags", JSONArray((1..6).map { JSONArray(samples[it]!!.toList()) })).toString()
    }

    /** The bridge's refusal: the steps to do again (already cleared) and what to say. */
    fun refused(error: String): Pair<List<Step>, String> {
        val words = error.split(' ')
        fun step(key: String?) = Step.entries.firstOrNull { it.key == key }
        val (redo, message) = when (words[0]) {
            "too-alike" -> listOfNotNull(step(words.getOrNull(1)), step(words.getOrNull(2))).let {
                it to "${it.getOrNull(0)?.title} and ${it.getOrNull(1)?.title?.lowercase()} went the same way. Do them again, moving further."
            }
            "like-the-turn" -> listOfNotNull(step(words.getOrNull(1))).let { it to "${it.firstOrNull()?.title} turned your wrist like the turn. Move your whole hand, without turning." }
            "no-turn" -> listOf(Step.TURN) to "The turn didn't move. Turn further."
            "no-drag" -> listOfNotNull(step(words.getOrNull(1))).let { it to "${it.firstOrNull()?.title} didn't move. Move further." }
            else -> Step.entries to "That didn't work. Start again."
        }
        redo.forEach(::redo)
        return redo to message
    }
}

/** Teach a drawing: [TRIES] tries, then the bridge's checks (Bridge.checkTries, Bridge.looksLike). */
class DrawTeach {
    val tries = mutableListOf<List<Stroke>>()
    val full get() = tries.size >= TRIES

    fun add(strokes: List<Stroke>) {
        if (!full) tries += strokes
    }

    fun json() = JSONArray(tries.map(DrawingLibrary::tryJson)).toString()

    /** Bridge.checkTries' answer: null when they're alike, else what to say (the tries it names go). */
    fun refused(error: String): String? = when {
        error.isEmpty() -> null
        error.startsWith("too-small") -> {
            val i = error.substringAfter(' ').toIntOrNull() ?: 0
            if (i in tries.indices) tries.removeAt(i)
            "Try ${i + 1} was too small: draw it bigger."
        }
        error.startsWith("not-alike") -> {
            tries.clear()
            "These don't look alike: try again, or draw bigger."
        }
        else -> {
            tries.clear()
            "Draw it $TRIES times."
        }
    }

    companion object {
        const val TRIES = 3

        /** A name no other drawing has ([own]: the one being taught again keeps its own). */
        fun nameFree(name: String, names: Collection<String>, own: String?): Boolean {
            val clean = name.trim()
            return clean.isNotEmpty() && (clean == own || names.none { it.equals(clean, ignoreCase = true) })
        }
    }
}
