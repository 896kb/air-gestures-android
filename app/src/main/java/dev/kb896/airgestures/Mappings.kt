package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The plain logic behind the mapping and the new actions, kept free of Android so it's unit-tested. */
object Mappings {
    /** In the call layer: the gesture keeps its normal action. */
    const val AS_USUAL = ""
    /** In the call layer: the gesture does nothing during a call. */
    const val NOTHING = "-"
    const val AUTOMATION = "automation"
    /** Send to automation for a Home device: `automation@<device id>`. */
    private const val FOR_DEVICE = "$AUTOMATION@"

    const val DIAL = "dial:"
    const val DRAWING = "drawing:"
    private val PAIRS = listOf("left_right", "toward_away", "up_down")

    /**
     * Everything the bridge needs (rust/bridge/src/profiles.rs): every profile with every
     * gesture key resolved (defaults for keys a profile never stored), its palm overrides, both
     * fingers' dials and drags and its own drawings, the picked profile and the apps' own (as
     * indexes), the band controls, the call layer, the shared drawings and this band's calibrations.
     */
    class Input(
        val list: ProfileList,
        val band: BandControls,
        /** Gesture key → action id, [AS_USUAL] or [NOTHING]. */
        val calls: Map<String, String>,
        /** Drawing name → its action in every profile without its own. */
        val drawings: Map<String, String>,
        /** "left" / "right" → this band's calibration of that wrist. */
        val calibrations: Map<String, Calibration>,
        val hand: String,
    )

    /**
     * Send to automation as stored (an action id, or a dial's or slider's [DialChoice] key):
     * plain [AUTOMATION] without a device, as before devices, else `automation@<device id>`.
     */
    fun automationId(device: String?) = if (device == null) AUTOMATION else FOR_DEVICE + device

    /** A stored Send to automation, for a device or none. */
    fun isAutomation(id: String?) = id == AUTOMATION || id?.startsWith(FOR_DEVICE) == true

    /** The device a stored Send to automation is for (null: none, or not automation). */
    fun deviceOf(id: String?) = id?.takeIf { it.startsWith(FOR_DEVICE) }?.removePrefix(FOR_DEVICE)?.ifEmpty { null }

    /** [id], sending without a device if it was for [device]. */
    fun withoutDevice(id: String, device: String) = if (deviceOf(id) == device) AUTOMATION else id

    /** A stored [AUTOMATION] becomes `automation:<name>`: the bridge reports only action names. */
    private fun named(id: String, name: String) = if (id == AUTOMATION) "$AUTOMATION:$name" else id

    /** A dial's two actions (null: nothing); automation steps carry the dial's name (`dial` for the index dial, as 0.7.7). */
    fun dial(key: String, name: String): Pair<String, String>? = when (val choice = DialChoice.of(key)) {
        DialChoice.NONE -> null
        DialChoice.AUTOMATION -> "$AUTOMATION:$DIAL$name:up" to "$AUTOMATION:$DIAL$name:down"
        else -> choice.up to choice.down
    }

    private fun dialJson(key: String, name: String): Any =
        dial(key, name)?.let { (up, down) -> JSONObject().put("up", up).put("down", down) } ?: JSONObject.NULL

    private fun finger(profile: Profile, part: Part): JSONObject {
        val f = part.name.lowercase()
        val drags = profile.drags(part)
        return JSONObject().apply {
            put("dial", dialJson(profile.dialOf(part), if (part == Part.INDEX) "dial" else "${f}_dial"))
            put("drags", if (!drags.sliders) "gestures" else JSONObject().put("sliders",
                JSONArray(drags.targets.mapIndexed { i, key -> dialJson(key, "${f}_${PAIRS[i]}") })))
        }
    }

    private fun drawingsJson(map: Map<String, String>) =
        JSONObject(map.filterValues { it.isNotEmpty() }.mapValues { (name, id) -> named(id, "$DRAWING$name") })

    fun profilesJson(input: Input): String {
        val list = input.list
        val profiles = JSONArray()
        for (profile in list.profiles) {
            val map = JSONObject()
            for (gesture in Gesture.entries) map.put(gesture.key, named(profile.map[gesture.key] ?: gesture.default.id, gesture.key))
            val palm = JSONObject()
            for ((key, own) in profile.palm) palm.put(key, JSONObject(own.mapValues { (at, id) -> named(id, "$key:$at") }))
            profiles.put(JSONObject().apply {
                put("name", profile.name)
                put("map", map)
                put("palm", palm)
                put("index", finger(profile, Part.INDEX))
                put("middle", finger(profile, Part.MIDDLE))
                put("drawings", drawingsJson(profile.drawings))
            })
        }
        val index = { id: String -> list.profiles.indexOfFirst { it.id == id } }
        return JSONObject().apply {
            put("hand", input.hand)
            put("picked", index(list.picked).coerceAtLeast(0))
            put("apps", JSONObject(list.apps.mapValues { index(it.value) }.filterValues { it >= 0 }))
            put("band", input.band.toJson())
            put("calls", JSONObject(input.calls.filterValues { it != AS_USUAL }.mapValues { (key, id) -> if (id == NOTHING) "-" else named(id, key) }))
            put("drawings", drawingsJson(input.drawings))
            put("calibration", JSONObject().apply {
                for (hand in listOf("left", "right")) put(hand, input.calibrations[hand]?.toJson() ?: JSONObject.NULL)
            })
            put("profiles", profiles)
        }.toString()
    }

    /** What `automation:<rest>` sends (Actions.automation). */
    sealed class Automation {
        /** A gesture; [palm]: the position its override is for. */
        data class Gesture(val name: String, val palm: String?) : Automation()
        /** A dial or slider step, gathered per [name]. */
        data class Step(val name: String, val step: Int) : Automation()
        data class Drawing(val name: String) : Automation()
    }

    fun automation(rest: String): Automation = when {
        rest.startsWith(DIAL) -> rest.removePrefix(DIAL).let { Automation.Step(it.substringBeforeLast(':'), if (it.endsWith(":down")) -1 else +1) }
        rest.startsWith(DRAWING) -> Automation.Drawing(rest.removePrefix(DRAWING))
        ':' in rest -> Automation.Gesture(rest.substringBefore(':'), rest.substringAfter(':'))
        else -> Automation.Gesture(rest, null)
    }
}

/** A "tap here" spot: `tap:<x>,<y>`, as fractions of the screen's width and height. */
object Tap {
    const val PREFIX = "tap:"

    fun format(x: Float, y: Float) = String.format(Locale.ROOT, "%s%.4f,%.4f", PREFIX, x, y)

    /** The spot, or null for anything that isn't a tap on the screen. */
    fun parse(id: String): Pair<Float, Float>? {
        if (!id.startsWith(PREFIX)) return null
        val (x, y) = id.removePrefix(PREFIX).split(',').takeIf { it.size == 2 }
            ?.map { it.toFloatOrNull() ?: return null } ?: return null
        return if (x in 0f..1f && y in 0f..1f) x to y else null
    }
}

/** What "read the newest notification" says. */
object Speech {
    /** App, title and text; only app and title while [locked] (as the lock screen hides content). */
    fun sentence(app: String, title: String?, text: String?, locked: Boolean): String {
        val heading = title?.takeIf { it.isNotBlank() }?.trim()
        val body = text?.takeIf { it.isNotBlank() && !locked }?.trim()
        return when {
            heading != null && body != null -> "$app. $heading: $body"
            body != null -> "$app. $body"
            heading != null -> "$app. $heading."
            else -> "$app."
        }
    }
}

/** Which notification the dismiss and read gestures act on. */
object Notes {
    class Note(
        val key: String,
        val postTime: Long,
        val clearable: Boolean,
        val ongoing: Boolean,
        val ours: Boolean,
        /** A group's summary with no text of its own. */
        val emptySummary: Boolean,
    )

    /** The latest one the user could swipe away themselves: not ongoing, not ours, not an empty summary. */
    fun newest(notes: List<Note>) =
        notes.filter { it.clearable && !it.ongoing && !it.ours && !it.emptySummary }.maxByOrNull { it.postTime }
}

/**
 * Dial steps for automation, gathered so a quick turn doesn't flood a smart light: a
 * step goes out at once when nothing went out in the last [window] ms, else it waits
 * and adds up until [due].
 */
class StepBatch(private val window: Long) {
    private var pending = 0
    private var lastSent = Long.MIN_VALUE / 2

    /** A step (+1 or −1) at [now]: the steps to send right away, or null when it waits for [due]. */
    fun add(step: Int, now: Long): Int? {
        pending += step
        return if (now - lastSent >= window) take(now) else null
    }

    /** When the waiting steps go out. */
    fun due() = lastSent + window

    /** What's gathered (0 when the steps cancelled out); the window starts again at [now]. */
    fun take(now: Long): Int {
        val steps = pending
        pending = 0
        lastSent = now
        return steps
    }
}

/** Which battery notification a band reading calls for, each once until it's re-armed. */
object BatteryAlerts {
    enum class Kind { LOW, CHARGED }

    data class Alert(val kind: Kind, val level: Int)

    /** What's been said since the last re-arm: the 20 % and 10 % warnings, and "charged". */
    data class Flags(val low20: Boolean = false, val low10: Boolean = false, val charged: Boolean = false)

    /**
     * A reading of [level] % ([charging] or not): the alert to post, if any, and the flags after.
     * Charging to at least half re-arms the low warnings; leaving the charger re-arms "charged".
     */
    fun check(level: Int, charging: Boolean, flags: Flags): Pair<Alert?, Flags> = when {
        charging && level >= 100 && !flags.charged -> Alert(Kind.CHARGED, level) to Flags(charged = true)
        charging && level >= 50 -> null to flags.copy(low20 = false, low10 = false)
        charging -> null to flags
        level <= 10 && !flags.low10 -> Alert(Kind.LOW, level) to Flags(low20 = true, low10 = true)
        level <= 20 && !flags.low20 -> Alert(Kind.LOW, level) to flags.copy(low20 = true, charged = false)
        else -> null to flags.copy(charged = false)
    }
}

/** The evening "charge your band" reminder. */
object Reminder {
    const val HOUR = 21

    /** The last reading (at [readAt] ms) was below half, not charging, and from the last day. */
    fun due(level: Int?, charging: Boolean, readAt: Long, now: Long) =
        level != null && level < 50 && !charging && now - readAt <= 24 * 3_600_000L

    /** The next 21:00 after [now], in its time zone. */
    fun next(now: java.time.ZonedDateTime): java.time.ZonedDateTime {
        val today = now.withHour(HOUR).withMinute(0).withSecond(0).withNano(0)
        return if (today.isAfter(now)) today else today.plusDays(1)
    }
}
