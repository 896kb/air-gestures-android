package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
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
    /** The During calls layer's name, as its screen shows it: the `profile` its automation sends. */
    const val CALLS = "During calls"
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

    /**
     * The bridge reports only action names, so a stored Send to automation becomes one that says
     * what sends: `<stored>/<profile>:<name>`, e.g. `automation@d1/Music:index_tap:up` ([sent]
     * reads it back). [profile]: the name of the profile (or [CALLS]) it's in, URL-encoded so it
     * can hold anything; null for none (the shared drawings), `<stored>:<name>` as before profiles.
     */
    private fun named(id: String, name: String, profile: String?) =
        if (isAutomation(id)) id + profile?.let { "/" + URLEncoder.encode(it, "UTF-8") }.orEmpty() + ":" + name else id

    /**
     * A dial's two actions (null: nothing); automation steps carry the dial's name (`dial` for the
     * index dial, as 0.7.7), its device and [profile]'s name.
     */
    fun dial(key: String, name: String, profile: String?): Pair<String, String>? = when (val choice = DialChoice.of(key)) {
        DialChoice.NONE -> null
        DialChoice.AUTOMATION -> named(key, "$DIAL$name:up", profile) to named(key, "$DIAL$name:down", profile)
        else -> choice.up to choice.down
    }

    private fun dialJson(key: String, name: String, profile: String): Any =
        dial(key, name, profile)?.let { (up, down) -> JSONObject().put("up", up).put("down", down) } ?: JSONObject.NULL

    private fun finger(profile: Profile, part: Part): JSONObject {
        val f = part.name.lowercase()
        val drags = profile.drags(part)
        return JSONObject().apply {
            put("dial", dialJson(profile.dialOf(part), if (part == Part.INDEX) "dial" else "${f}_dial", profile.name))
            put("drags", if (!drags.sliders) "gestures" else JSONObject().put("sliders",
                JSONArray(drags.targets.mapIndexed { i, key -> dialJson(key, "${f}_${PAIRS[i]}", profile.name) })))
        }
    }

    private fun drawingsJson(map: Map<String, String>, profile: String?) =
        JSONObject(map.filterValues { it.isNotEmpty() }.mapValues { (name, id) -> named(id, "$DRAWING$name", profile) })

    /**
     * [profile]'s own drawings, and the shared ones that send to automation where it has none
     * of its own: band-core would run the shared action, which can't say which profile it's in.
     */
    private fun drawingsOf(profile: Profile, shared: Map<String, String>) =
        shared.filterValues(::isAutomation) + profile.drawings.filterValues { it.isNotEmpty() }

    fun profilesJson(input: Input): String {
        val list = input.list
        val profiles = JSONArray()
        for (profile in list.profiles) {
            val map = JSONObject()
            for (gesture in Gesture.entries) map.put(gesture.key, named(profile.map[gesture.key] ?: gesture.default.id, gesture.key, profile.name))
            val palm = JSONObject()
            for ((key, own) in profile.palm) palm.put(key, JSONObject(own.mapValues { (at, id) -> named(id, "$key:$at", profile.name) }))
            profiles.put(JSONObject().apply {
                put("name", profile.name)
                put("map", map)
                put("palm", palm)
                put("index", finger(profile, Part.INDEX))
                put("middle", finger(profile, Part.MIDDLE))
                put("drawings", drawingsJson(drawingsOf(profile, input.drawings), profile.name))
            })
        }
        val index = { id: String -> list.profiles.indexOfFirst { it.id == id } }
        return JSONObject().apply {
            put("hand", input.hand)
            put("picked", index(list.picked).coerceAtLeast(0))
            put("apps", JSONObject(list.apps.mapValues { index(it.value) }.filterValues { it >= 0 }))
            put("band", input.band.toJson())
            put("calls", JSONObject(input.calls.filterValues { it != AS_USUAL }.mapValues { (key, id) -> if (id == NOTHING) "-" else named(id, key, CALLS) }))
            put("drawings", drawingsJson(input.drawings, null))
            put("calibration", JSONObject().apply {
                for (hand in listOf("left", "right")) put(hand, input.calibrations[hand]?.toJson() ?: JSONObject.NULL)
            })
            put("profiles", profiles)
        }.toString()
    }

    /**
     * What a Send to automation sends (Actions.automation): from [profile] (its name, or [CALLS];
     * null: unknown), for [device] (an id; null: none).
     */
    sealed class Automation {
        abstract val profile: String?
        abstract val device: String?

        /** A gesture; [palm]: the position its override is for. */
        data class Gesture(val name: String, val palm: String?, override val profile: String? = null, override val device: String? = null) : Automation()

        /** A dial or slider step. */
        data class Step(val name: String, val step: Int, override val profile: String? = null, override val device: String? = null) : Automation() {
            /** What its steps are gathered by: the dial or slider, the device and the profile. */
            val key get() = copy(step = 0)
        }

        data class Drawing(val name: String, override val profile: String? = null, override val device: String? = null) : Automation()
    }

    /** What an action name from the bridge sends ([named]), or null when it isn't automation. */
    fun sent(action: String): Automation? {
        if (':' !in action) return null
        val head = action.substringBefore(':')
        val stored = head.substringBefore('/')
        if (!isAutomation(stored)) return null
        val profile = if ('/' in head) head.substringAfter('/').let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) } else null
        val device = deviceOf(stored)
        val rest = action.substringAfter(':')
        return when {
            rest.startsWith(DIAL) -> rest.removePrefix(DIAL).let {
                Automation.Step(it.substringBeforeLast(':'), if (it.endsWith(":down")) -1 else +1, profile, device)
            }
            rest.startsWith(DRAWING) -> Automation.Drawing(rest.removePrefix(DRAWING), profile, device)
            ':' in rest -> Automation.Gesture(rest.substringBefore(':'), rest.substringAfter(':'), profile, device)
            else -> Automation.Gesture(rest, null, profile, device)
        }
    }

    /**
     * The broadcast's extras for [made]: `gesture` (a dial or slider's name, or `drawing`), then
     * `palm` (an override), `name` (a drawing), `steps` (a dial or slider's, when given), `profile`,
     * and `device` = [device], the device's current name (left out without one).
     */
    fun extras(made: Automation, device: String?, steps: Int? = null): Map<String, Any> = buildMap {
        when (made) {
            is Automation.Gesture -> {
                put("gesture", made.name)
                made.palm?.let { put("palm", it) }
            }
            is Automation.Drawing -> {
                put("gesture", "drawing")
                put("name", made.name)
            }
            is Automation.Step -> put("gesture", made.name)
        }
        steps?.let { put("steps", it) }
        made.profile?.let { put("profile", it) }
        device?.let { put("device", it) }
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
