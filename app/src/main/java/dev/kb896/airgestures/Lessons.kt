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

        /** The middle hold: the controls flipping. */
        data object Hold : Done()

        /** A triple tap: the profile action the bridge sends. */
        data class Action(val action: String) : Done()

        fun matches(gesture: String? = null, action: String? = null, held: Boolean = false, dial: Set<String> = emptySet()) = when (this) {
            is Gesture -> gesture == label
            Dial -> action != null && action in dial
            Hold -> held
            is Action -> action == this.action
        }
    }

    /** One lesson: what to do, how, what finishes it, and the gesture it maps (for "would: …"). */
    class Step(val title: String, val how: String, val done: Done, val gesture: Gesture? = null)

    private fun tap(gesture: Gesture, title: String, how: String) = Step(title, how, Done.Gesture(gesture.label.lowercase()), gesture)

    /** The lessons for [profiles] profiles; pinch and turn only when the profile's [dial] does something. */
    fun steps(profiles: Int, dial: Boolean): List<Step> = buildList {
        add(tap(Gesture.SWIPE_UP, "Swipe up", "Slide your thumb up along the side of your index finger."))
        add(tap(Gesture.SWIPE_DOWN, "Swipe down", "Slide your thumb down along the side of your index finger."))
        add(tap(Gesture.SWIPE_LEFT, "Swipe left", "Slide your thumb across your index finger, to the left."))
        add(tap(Gesture.SWIPE_RIGHT, "Swipe right", "Slide your thumb across your index finger, to the right."))
        add(tap(Gesture.INDEX_TAP, "Tap your index finger", "Touch your thumb to your index finger, once."))
        add(tap(Gesture.INDEX_DOUBLE, "Double-tap your index finger", "Two quick taps of your thumb on your index finger."))
        add(tap(Gesture.MIDDLE_TAP, "Tap your middle finger", "Touch your thumb to your middle finger, once."))
        add(tap(Gesture.MIDDLE_DOUBLE, "Double-tap your middle finger", "Two quick taps of your thumb on your middle finger."))
        if (dial) add(Step("Pinch and turn", "Pinch thumb and index finger together, hold, and turn your wrist.", Done.Dial))
        add(Step("Hold your middle finger", "Keep your thumb on your middle finger for a second. It turns the controls off and on.", Done.Hold))
        if (profiles >= 2) {
            add(Step("Triple-tap your index finger", "Three quick taps of your thumb on your index finger: the next profile.", Done.Action("profile.next")))
            add(Step("Triple-tap your middle finger", "Three quick taps of your thumb on your middle finger: the previous profile.", Done.Action("profile.previous")))
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
        /** Added as an optional field so older version-1 backups migrate to the enabled default. */
        val bandVibration: Boolean = true,
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
            put("bandVibration", settings.bandVibration)
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
            json.optBoolean("bandVibration", true),
        )
    }.getOrNull()
}
