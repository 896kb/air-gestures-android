package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/** Palm down, sideways or up (band-core's `Palm`, with its keys). */
enum class Palm(val key: String, val label: String) {
    DOWN("down", "Palm down"),
    SIDE("sideways", "Sideways"),
    UP("up", "Palm up");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** "palm up", "palm down or sideways": these positions in words, in [Palm] order. */
fun Set<Palm>.words(separator: String = " or ") = Palm.entries.filter { it in this }.joinToString(separator) { it.label.lowercase() }

/** The band controls (band-core's `Builtin`): above every profile, each on one gesture or none. */
enum class Builtin(val key: String, val label: String, val icon: Int) {
    CONTROLS("controls", "Controls on/off", R.drawable.ic_power),
    NEXT("next", "Next profile", R.drawable.ic_layers),
    PREVIOUS("previous", "Previous profile", R.drawable.ic_layers),
    DRAW("draw", "Draw", R.drawable.ic_pencil);

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

/** Where a band control is: a gesture, in every palm position ([palms] null) or only in 1–2. */
data class BandControl(val gesture: Gesture, val palms: Set<Palm>? = null) {
    fun covers(palm: Palm) = palms == null || palm in palms

    /** "Index triple tap", "Index triple tap · palm up or sideways". */
    val label: String
        get() = gesture.label + (palms?.let { " · " + it.words() } ?: "")
}

/** The four band controls; stored once, above the profiles. Free of Android, so it's unit-tested. */
data class BandControls(val at: Map<Builtin, BandControl> = emptyMap()) {
    operator fun get(builtin: Builtin) = at[builtin]

    /** The band control on [gesture] with the palm this way, as band-core picks it (Controls, Next, Previous, Draw). */
    fun on(gesture: Gesture, palm: Palm) = Builtin.entries.firstOrNull { at[it]?.let { c -> c.gesture == gesture && c.covers(palm) } == true }

    /** The band controls on [gesture], in any position. */
    fun onGesture(gesture: Gesture) = Builtin.entries.filter { at[it]?.gesture == gesture }

    fun with(builtin: Builtin, control: BandControl?) = BandControls(if (control == null) at - builtin else at + (builtin to control))

    /** As the profiles JSON's `band` (see rust/bridge/src/profiles.rs). */
    fun toJson(): JSONObject = JSONObject().apply {
        for (builtin in Builtin.entries) {
            val c = at[builtin]
            put(builtin.key, if (c == null) JSONObject.NULL else JSONObject().apply {
                put("gesture", c.gesture.key)
                put("palm", c.palms?.let { p -> JSONArray(Palm.entries.filter { it in p }.map { it.key }) } ?: JSONObject.NULL)
            })
        }
    }

    companion object {
        /** Today's gestures: middle hold, index triple tap, middle triple tap; no Draw. */
        fun standard() = BandControls(mapOf(
            Builtin.CONTROLS to BandControl(Gesture.MIDDLE_HOLD),
            Builtin.NEXT to BandControl(Gesture.INDEX_TRIPLE),
            Builtin.PREVIOUS to BandControl(Gesture.MIDDLE_TRIPLE),
        ))

        /**
         * Unknown gestures are no gesture, unknown positions are skipped, and no position or all
         * three is any position (as the bridge reads it); throws on bad JSON.
         */
        fun fromJson(text: String): BandControls {
            val json = JSONObject(text)
            val at = Builtin.entries.mapNotNull { builtin ->
                val c = json.optJSONObject(builtin.key) ?: return@mapNotNull null
                val gesture = Gesture.of(c.optString("gesture")) ?: return@mapNotNull null
                val palms = c.optJSONArray("palm")?.let { a -> (0 until a.length()).mapNotNull { Palm.of(a.optString(it)) }.toSet() }
                    ?.takeIf { it.isNotEmpty() && it.size < Palm.entries.size }
                builtin to BandControl(gesture, palms)
            }.toMap()
            return BandControls(at)
        }
    }
}
