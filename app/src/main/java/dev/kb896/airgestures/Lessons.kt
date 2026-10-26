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

        /** Pinch and turn with the middle finger: any of the middle dial's actions. */
        data object MiddleDial : Done()

        /** The middle hold (the bridge's label `middle hold`). */
        data object Hold : Done()

        fun matches(gesture: String? = null, action: String? = null, dial: Set<String> = emptySet(), middleDial: Set<String> = emptySet()) =
            when (this) {
                is Gesture -> gesture == label
                Dial -> action != null && action in dial
                MiddleDial -> action != null && action in middleDial
                Hold -> gesture == "middle hold"
            }
    }

    /**
     * Whether the middle dial gets a lesson: its turns must be told from the index dial's, and
     * `@practiced` names only the action, so a middle dial on the same actions has none.
     */
    fun middleDialLesson(index: Set<String>, middle: Set<String>) = middle.isNotEmpty() && (middle intersect index).isEmpty()

    /** One lesson: what to do, how, what finishes it, and the gesture it maps (for "would: …"). */
    class Step(val title: String, val how: String, val done: Done, val gesture: Gesture? = null)

    private fun tap(gesture: Gesture, title: String, how: String) = Step(title, how, Done.Gesture(gesture.label.lowercase()), gesture)

    /**
     * The lessons for [profiles] profiles whose active one has [map] (gesture key → action id,
     * every key resolved) and the [band] controls; pinch and turn only when its [dial] does
     * something. A triple tap or the index hold only when it does something there (a profile
     * switch needs 2+ profiles), as band-core decides whether its taps wait: the band otherwise
     * doesn't wait for it, and three taps are three taps. Lessons are palm down. The middle
     * dial when [middleDial] does something; a drag when it does something and its finger's
     * drags are gestures (not one of [sliders]).
     */
    fun steps(
        profiles: Int,
        dial: Boolean,
        map: Map<String, String>,
        band: BandControls,
        middleDial: Boolean = false,
        sliders: Set<Part> = emptySet(),
    ): List<Step> = buildList {
        fun does(gesture: Gesture): Boolean = when (band.on(gesture, Palm.DOWN)) {
            Builtin.CONTROLS, Builtin.DRAW -> true
            Builtin.NEXT, Builtin.PREVIOUS -> profiles >= 2
            // An action of the profile's own; one paused by a band control doesn't run.
            null -> (map[gesture.key] ?: gesture.default.id).isNotEmpty()
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
        if (middleDial) {
            add(Step("Pinch and turn with your middle finger", "Pinch thumb and middle finger together, hold, and turn your wrist.", Done.MiddleDial))
        }
        add(
            Step(
                "Hold your middle finger",
                "Keep your thumb on your middle finger for a second. It runs what the middle hold does (by default the controls on/off, a band control).",
                Done.Hold,
                Gesture.MIDDLE_HOLD,
            ),
        )
        if (does(Gesture.INDEX_HOLD)) {
            add(
                Step(
                    "Hold your index finger",
                    "Keep your thumb on your index finger for a second, without moving your wrist. It runs what the index hold does (by default Draw, a band control).",
                    Done.Gesture("index hold"),
                    Gesture.INDEX_HOLD,
                ),
            )
        }
        if (does(Gesture.INDEX_TRIPLE)) {
            add(tap(Gesture.INDEX_TRIPLE, "Triple-tap your index finger", "Three quick taps of your thumb on your index finger."))
        }
        if (does(Gesture.MIDDLE_TRIPLE)) {
            add(tap(Gesture.MIDDLE_TRIPLE, "Triple-tap your middle finger", "Three quick taps of your thumb on your middle finger."))
        }
        // The drags that do something, in the words of Teach your drags.
        for (part in listOf(Part.INDEX, Part.MIDDLE)) {
            if (part in sliders) continue
            for (gesture in Gesture.drags(part)) {
                if (!does(gesture)) continue
                val move = DragTeach.Step.entries.firstOrNull { gesture.key.endsWith("_drag_${it.key}") } ?: continue
                val how = move.how.replaceFirst("Pinch and ", "Pinch thumb and ${part.label.lowercase()} finger, then ")
                add(tap(gesture, "Pinch and ${move.title.lowercase()}, ${part.label.lowercase()} finger", how))
            }
        }
    }
}

/**
 * The settings backup file: everything but the owner key (that has its own export) and the
 * calibrations (they belong to a band). Version 2 adds the band controls and the drawings,
 * version 3 the Home devices (an app without them refuses it: their gestures would read as
 * Nothing there), and version 4 the Band vibration preference. Older backups restore with
 * Band vibration on, matching the default for new installs.
 */
object Backup {
    const val VERSION = 4

    data class Settings(
        val profiles: ProfileList,
        /** The During calls layer, gesture key → action id / As usual / Nothing. */
        val calls: Map<String, String>,
        val hand: String,
        val speak: Boolean,
        val vibrate: Boolean,
        /** Null in a backup made before sequences: restoring it leaves the sequences as they are. */
        val sequences: Sequences.Library? = null,
        val band: BandControls = BandControls.standard(),
        /** Drawing name → its action in every profile without its own; null in a version 1 backup (left as they are). */
        val sharedDrawings: Map<String, String>? = emptyMap(),
        /** Null in a version 1 backup: restoring it leaves the drawings as they are. */
        val drawings: DrawingLibrary? = null,
        /** The frame [drawings] were taught in (null: a backup from before it was stored, the old frame). */
        val drawingsFrame: Int? = DrawingLibrary.FRAME,
        /** Of [drawings], the ones still to teach again (see Drawings.toReteach). */
        val drawingsReteach: Set<String> = emptySet(),
        /** Null in a backup made before Home devices: restoring it leaves them as they are. */
        val devices: HomeDevices? = null,
        /** Added in version 4; older backups restore with Band vibration enabled. */
        val bandVibration: Boolean = true,
    )

    /** Every gesture's call action after a restore: the backup's, else its default (a 0.7.7 backup lacks the newer gestures). */
    fun restoredCalls(calls: Map<String, String>) = Gesture.entries.associate { it.key to (calls[it.key] ?: it.callDefault) }

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
            put("band", settings.band.toJson())
            settings.sharedDrawings?.let { put("shared_drawings", JSONObject(it)) }
            settings.drawings?.let {
                put("drawings", JSONArray(it.toJson()))
                settings.drawingsFrame?.let { frame -> put("drawings_frame", frame) }
                put("drawings_reteach", JSONArray(settings.drawingsReteach.sorted()))
            }
            settings.devices?.let { put("devices", JSONArray(it.toJson())) }
            put("band_vibration", settings.bandVibration)
        }.toString(2)
    }

    /** The settings in [text], or null for anything that isn't a whole backup of version 1 to 4. */
    fun read(text: String): Settings? = runCatching {
        val json = JSONObject(text)
        val version = json.getInt("version")
        require(version in 1..VERSION) { "another version" }
        val stored = json.getJSONObject("calls")
        val calls = stored.keys().asSequence().associateWith { stored.getString(it) }
        val profiles = ProfileList.fromJson(json.getJSONArray("profiles").toString(), json.getJSONObject("apps").toString(), json.getString("picked"))
        val shared = json.optJSONObject("shared_drawings")
        Settings(
            if (version == 1) profiles.withoutBuiltins() else profiles,
            if (version == 1) calls - ProfileList.builtinCalls(calls) else calls,
            json.getString("hand"),
            json.optBoolean("speak", false),
            json.optBoolean("vibrate", false),
            json.opt("sequences")?.let { Sequences.Library.fromJson(it.toString()) },
            band = json.optJSONObject("band")?.let { BandControls.fromJson(it.toString()) }
                ?: BandControls.migrated(profiles.withoutBuiltins(), calls - ProfileList.builtinCalls(calls)),
            sharedDrawings = if (version == 1) null else
                shared?.keys()?.asSequence()?.mapNotNull { key -> (shared.opt(key) as? String)?.let { key to it } }?.toMap().orEmpty(),
            drawings = json.optJSONArray("drawings")?.let { DrawingLibrary.fromJson(it.toString()) },
            drawingsFrame = if (json.has("drawings_frame")) json.getInt("drawings_frame") else null,
            drawingsReteach = json.optJSONArray("drawings_reteach")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifEmpty { null } }.toSet() }
                .orEmpty(),
            devices = json.optJSONArray("devices")?.let { HomeDevices.fromJson(it.toString()) },
            bandVibration = json.optBoolean("band_vibration", true),
        )
    }.getOrNull()
}
