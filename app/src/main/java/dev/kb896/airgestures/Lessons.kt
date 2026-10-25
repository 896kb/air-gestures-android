package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/** The practice lessons: which steps there are, and what counts as having done each. */
object Lessons {
    /** What finishes a step. */
    sealed class Done {
        /** The bridge's label for the gesture (`swipe up`, `index double tap`, …). */
        data class Gesture(val label: String) : Done()

        /** Pinch and turn: any of the dial's actions. */
        data object Dial : Done()

        /** The middle hold (the bridge's label `middle hold`). */
        data object Hold : Done()

        fun matches(gesture: String? = null, action: String? = null, dial: Set<String> = emptySet()) = when (this) {
            is Gesture -> gesture == label
            Dial -> action != null && action in dial
            Hold -> gesture == "middle hold"
        }
    }

    /** One lesson: what to do, how, what finishes it, and the gesture it maps (for "would: …"). */
    class Step(val title: String, val how: String, val done: Done, val gesture: Gesture? = null)

    private fun tap(gesture: Gesture, title: String, how: String) = Step(title, how, Done.Gesture(gesture.label.lowercase()), gesture)

    /**
     * The lessons for [profiles] profiles whose active one has [map] (gesture key → action id,
     * every key resolved); pinch and turn only when its [dial] does something. A triple tap only
     * when it does something there (a profile switch needs 2+ profiles): the band otherwise
     * doesn't wait for it, and three taps are three taps.
     */
    fun steps(profiles: Int, dial: Boolean, map: Map<String, String>): List<Step> = buildList {
        fun triples(gesture: Gesture): Boolean {
            val id = map[gesture.key] ?: gesture.default.id
            return id.isNotEmpty() && (profiles >= 2 || (id != Action.NEXT_PROFILE.id && id != Action.PREVIOUS_PROFILE.id))
        }
        add(tap(Gesture.SWIPE_UP, "Swipe up", "Slide your thumb up along the side of your index finger."))
        add(tap(Gesture.SWIPE_DOWN, "Swipe down", "Slide your thumb down along the side of your index finger."))
        add(tap(Gesture.SWIPE_LEFT, "Swipe left", "Slide your thumb across your index finger, to the left."))
        add(tap(Gesture.SWIPE_RIGHT, "Swipe right", "Slide your thumb across your index finger, to the right."))
        add(tap(Gesture.INDEX_TAP, "Tap your index finger", "Touch your thumb to your index finger, once."))
        add(tap(Gesture.INDEX_DOUBLE, "Double-tap your index finger", "Two quick taps of your thumb on your index finger."))
        add(tap(Gesture.MIDDLE_TAP, "Tap your middle finger", "Touch your thumb to your middle finger, once."))
        add(tap(Gesture.MIDDLE_DOUBLE, "Double-tap your middle finger", "Two quick taps of your thumb on your middle finger."))
        if (dial) add(Step("Pinch and turn", "Pinch thumb and index finger together, hold, and turn your wrist.", Done.Dial))
        add(
            Step(
                "Hold your middle finger",
                "Keep your thumb on your middle finger for a second. It runs what the middle hold is set to (by default the controls on/off).",
                Done.Hold,
                Gesture.MIDDLE_HOLD,
            ),
        )
        if (triples(Gesture.INDEX_TRIPLE)) {
            add(tap(Gesture.INDEX_TRIPLE, "Triple-tap your index finger", "Three quick taps of your thumb on your index finger."))
        }
        if (triples(Gesture.MIDDLE_TRIPLE)) {
            add(tap(Gesture.MIDDLE_TRIPLE, "Triple-tap your middle finger", "Three quick taps of your thumb on your middle finger."))
        }
    }
}

/** The settings backup file: everything but the owner key (that has its own export). */
object Backup {
    const val VERSION = 1

    data class Settings(
        val profiles: ProfileList,
        /** The During calls layer, gesture key → action id / As usual / Nothing. */
        val calls: Map<String, String>,
        val hand: String,
        val speak: Boolean,
        val vibrate: Boolean,
        /** Null in a backup made before sequences: restoring it leaves the sequences as they are. */
        val sequences: Sequences.Library? = null,
    )

    fun write(settings: Settings): String {
        val (profiles, apps) = settings.profiles.toJson()
        return JSONObject().apply {
            put("version", VERSION)
            put("profiles", JSONArray(profiles))
            put("picked", settings.profiles.picked)
            put("apps", JSONObject(apps))
            put("calls", JSONObject(settings.calls))
            put("hand", settings.hand)
            put("speak", settings.speak)
            put("vibrate", settings.vibrate)
            settings.sequences?.let { put("sequences", JSONObject(it.toJson())) }
        }.toString(2)
    }

    /** The settings in [text], or null for anything that isn't a whole backup of this version. */
    fun read(text: String): Settings? = runCatching {
        val json = JSONObject(text)
        require(json.getInt("version") == VERSION) { "another version" }
        val calls = json.getJSONObject("calls")
        Settings(
            ProfileList.fromJson(json.getJSONArray("profiles").toString(), json.getJSONObject("apps").toString(), json.getString("picked")),
            calls.keys().asSequence().associateWith { calls.getString(it) },
            json.getString("hand"),
            json.optBoolean("speak", false),
            json.optBoolean("vibrate", false),
            json.opt("sequences")?.let { Sequences.Library.fromJson(it.toString()) },
        )
    }.getOrNull()
}
