package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/** A finger's six drags: gestures (their actions in [Profile.map]), or three sliders. */
data class Drags(
    val sliders: Boolean = false,
    /** Left/right, toward/away, up/down: a [DialChoice] key each (kept in gestures mode). */
    val targets: List<String> = List(3) { DialChoice.NONE.key },
) {
    companion object {
        /** The sliders' pairs in band-core's order: the name, and which way is up. */
        val PAIRS = listOf("Left/right" to "right = forward", "Toward/away" to "away = up", "Up/down" to "up = up")
    }
}

/**
 * One gesture set: a name, an action id per gesture key, palm overrides, both fingers' dials
 * and drags, and its own drawings' actions.
 */
data class Profile(
    val id: String,
    val name: String,
    val map: Map<String, String>,
    /** The index finger's pinch and turn (a [DialChoice] key; the name 0.7.7 stored it under). */
    val dial: String,
    /** Gesture key → palm key → action id ("" = nothing there); at most [ProfileList.MAX_OVERRIDES] each. */
    val palm: Map<String, Map<String, String>> = emptyMap(),
    val middleDial: String = DialChoice.NONE.key,
    val indexDrags: Drags = Drags(),
    val middleDrags: Drags = Drags(),
    /** Drawing name → this profile's own action (wins over the shared one). */
    val drawings: Map<String, String> = emptyMap(),
    /**
     * Send everything to automation: every gesture, both dials, each drag and every drawing, by
     * its own name (see [Mappings.profilesJson]); what's stored above is kept for when it's off.
     */
    val sendAll: Boolean = false,
) {
    fun dialOf(part: Part) = if (part == Part.MIDDLE) middleDial else dial
    fun drags(part: Part) = if (part == Part.MIDDLE) middleDrags else indexDrags

    /** Whether [gesture] does something here: its action (its default when never stored) or a palm override's. */
    fun does(gesture: Gesture) = sendAll ||
        (map[gesture.key] ?: gesture.default.id).isNotEmpty() || palm[gesture.key].orEmpty().values.any { it.isNotEmpty() }
}

/**
 * The profiles, the one the user picked, and the apps that have their own (package → profile
 * id). Immutable: every change returns a new list. Free of Android, so it's unit-tested;
 * [Profiles] stores it.
 */
data class ProfileList(val profiles: List<Profile>, val picked: String, val apps: Map<String, String>) {
    fun profile(id: String?) = profiles.firstOrNull { it.id == id }

    /** The profile in force: the app's own while [foreground] has one, else the picked one. */
    fun active(foreground: String?): Profile = appProfile(foreground) ?: profile(picked) ?: profiles.first()

    /** The app in front has its own profile: the triple taps don't switch there. */
    fun locked(foreground: String?) = appProfile(foreground) != null

    private fun appProfile(foreground: String?) = foreground?.let { profile(apps[it]) }

    /**
     * The list as a band uses it: the profiles [on] names, in that order (null, or none left: all
     * of them), [picked] if it's among them (else the first), and the apps whose profile is.
     */
    fun forBand(on: List<String>?, picked: String): ProfileList {
        val mine = on?.mapNotNull(::profile)?.distinct()?.takeIf { it.isNotEmpty() } ?: profiles
        val ids = mine.map { it.id }.toSet()
        return ProfileList(mine, picked.takeIf { it in ids } ?: mine.first().id, apps.filterValues { it in ids })
    }

    /** The picked profile [by] places on (+1 next, −1 previous), wrapping round. */
    fun step(by: Int): ProfileList {
        val at = profiles.indexOfFirst { it.id == picked }.coerceAtLeast(0)
        return copy(picked = profiles[Math.floorMod(at + by, profiles.size)].id)
    }

    /** [id] becomes the manual profile (the widget's picker); an unknown id changes nothing. */
    fun picking(id: String) = if (profile(id) == null) this else copy(picked = id)

    /** Whether any profile does something on [gesture] (see [Profile.does]). */
    fun uses(gesture: Gesture) = profiles.any { it.does(gesture) }

    /** A new profile at the end, with the lowest free number for its id and name; its middle dial is [NEW_MIDDLE_DIAL]. */
    fun added(map: Map<String, String>, dial: String): Pair<ProfileList, Profile> {
        val n = generateSequence(1) { it + 1 }.first { n -> profiles.none { it.id == "p$n" || it.name == "Profile $n" } }
        val made = Profile("p$n", "Profile $n", map, dial, middleDial = NEW_MIDDLE_DIAL)
        return copy(profiles = profiles + made) to made
    }

    fun renamed(id: String, name: String): ProfileList {
        val clean = name.trim().takeIf { it.isNotEmpty() } ?: return this
        return change(id) { it.copy(name = clean) }
    }

    /** Never the last one; its apps go back to the picked profile, and a deleted pick moves to the first. */
    fun deleted(id: String): ProfileList {
        if (profiles.size <= 1 || profile(id) == null) return this
        val rest = profiles.filter { it.id != id }
        return ProfileList(rest, if (picked == id) rest.first().id else picked, apps.filterValues { it != id })
    }

    /** An app belongs to one profile: adding it here moves it from another. */
    fun assigned(pkg: String, id: String) = if (profile(id) == null) this else copy(apps = apps + (pkg to id))

    fun unassigned(pkg: String) = copy(apps = apps - pkg)

    fun withAction(id: String, gesture: String, action: String) = change(id) { it.copy(map = it.map + (gesture to action)) }

    fun withDial(id: String, dial: String) = change(id) { it.copy(dial = dial) }

    /** [palm] runs [action] on [gesture] instead of the normal one ("" = nothing); a third is refused. */
    fun withOverride(id: String, gesture: String, palm: Palm, action: String) = change(id) {
        val own = it.palm[gesture].orEmpty()
        if (palm.key !in own && own.size >= MAX_OVERRIDES) it else it.copy(palm = it.palm + (gesture to own + (palm.key to action)))
    }

    fun withoutOverride(id: String, gesture: String, palm: Palm) = change(id) {
        val own = it.palm[gesture].orEmpty() - palm.key
        it.copy(palm = if (own.isEmpty()) it.palm - gesture else it.palm + (gesture to own))
    }

    fun withSendAll(id: String, on: Boolean) = change(id) { it.copy(sendAll = on) }

    fun withMiddleDial(id: String, dial: String) = change(id) { it.copy(middleDial = dial) }

    fun withDrags(id: String, part: Part, drags: Drags) =
        change(id) { if (part == Part.MIDDLE) it.copy(middleDrags = drags) else it.copy(indexDrags = drags) }

    /** This profile's own action for a drawing (null: none of its own, the shared one applies). */
    fun withDrawing(id: String, name: String, action: String?) =
        change(id) { it.copy(drawings = if (action == null) it.drawings - name else it.drawings + (name to action)) }

    /** A drawing renamed, or gone (null), in every profile. */
    fun renamedDrawing(old: String, new: String?) = copy(profiles = profiles.map { p ->
        val action = p.drawings[old] ?: return@map p
        p.copy(drawings = (p.drawings - old) + (if (new == null) emptyMap() else mapOf(new to action)))
    })

    /** [old]'s own actions also under [new] (a rename's first step: nothing is lost if it stops there). */
    fun copiedDrawing(old: String, new: String) = copy(profiles = profiles.map { p ->
        p.drawings[old]?.let { p.copy(drawings = p.drawings + (new to it)) } ?: p
    })

    /** Only actions for drawings that exist ([names]), and no "" ones. */
    fun prunedDrawings(names: Set<String>) = copy(profiles = profiles.map { p -> p.copy(drawings = DrawingLibrary.pruned(p.drawings, names)) })

    /**
     * Every stored action id and dial choice: gestures, overrides, both dials, the sliders'
     * targets (kept in gestures mode too; [shown]: only a finger's in sliders mode, as the
     * screens show them) and the drawings' own actions.
     */
    fun ids(shown: Boolean = false) = profiles.flatMap { p ->
        fun targets(drags: Drags) = if (shown && !drags.sliders) emptyList() else drags.targets
        p.map.values + p.palm.values.flatMap { it.values } + listOf(p.dial, p.middleDial) +
            targets(p.indexDrags) + targets(p.middleDrags) + p.drawings.values
    }

    /** Wherever Send to automation is for [device] (an action or a dial), it sends without a device. */
    fun withoutDevice(device: String) = copy(profiles = profiles.map { p ->
        fun clear(id: String) = Mappings.withoutDevice(id, device)
        p.copy(
            map = p.map.mapValues { clear(it.value) },
            dial = clear(p.dial),
            palm = p.palm.mapValues { (_, own) -> own.mapValues { clear(it.value) } },
            middleDial = clear(p.middleDial),
            indexDrags = p.indexDrags.copy(targets = p.indexDrags.targets.map(::clear)),
            middleDrags = p.middleDrags.copy(targets = p.middleDrags.targets.map(::clear)),
            drawings = p.drawings.mapValues { clear(it.value) },
        )
    })

    /** The first controls dev build's built-in action ids out of every profile (their gestures go back to their defaults). */
    fun withoutBuiltins() = copy(profiles = profiles.map { p ->
        p.copy(
            map = p.map.filterValues { it !in BUILTIN_IDS },
            palm = p.palm.mapValues { (_, own) -> own.filterValues { it !in BUILTIN_IDS } }.filterValues { it.isNotEmpty() },
        )
    })

    private fun change(id: String, how: (Profile) -> Profile) = copy(profiles = profiles.map { if (it.id == id) how(it) else it })

    /** The profiles and the apps, as the JSON the store keeps (the pick is stored on its own). */
    fun toJson(): Pair<String, String> {
        val list = JSONArray()
        for (profile in profiles) {
            list.put(JSONObject().apply {
                put("id", profile.id)
                put("name", profile.name)
                put("map", JSONObject(profile.map))
                put("dial", profile.dial)
                put("palm", JSONObject(profile.palm.mapValues { JSONObject(it.value) }))
                put("middle_dial", profile.middleDial)
                put("drags", JSONObject().put("index", drags(profile.indexDrags)).put("middle", drags(profile.middleDrags)))
                put("drawings", JSONObject(profile.drawings))
                put("send_all", profile.sendAll)
            })
        }
        return list.toString() to JSONObject(apps).toString()
    }

    companion object {
        const val MAX_OVERRIDES = 2

        /**
         * A new profile's middle dial (a fresh install's Profile 1 too). One stored without it
         * (0.7.7 had none) reads as nothing, so an upgrade changes no profile.
         */
        val NEW_MIDDLE_DIAL = DialChoice.BRIGHTNESS.key

        /** The first controls dev build's built-in actions (band controls now). */
        val BUILTIN_IDS = setOf("controls.toggle", "profile.next", "profile.previous")

        /** The During calls gestures that held a built-in action (they go back to their defaults). */
        fun builtinCalls(calls: Map<String, String>) = calls.filterValues { it in BUILTIN_IDS }.keys

        private fun drags(d: Drags) = JSONObject().put("sliders", d.sliders).put("targets", JSONArray(d.targets))

        private fun drags(o: JSONObject?): Drags {
            o ?: return Drags()
            val targets = o.optJSONArray("targets")?.let { a -> (0 until a.length()).map { a.getString(it) } }
            return Drags(o.optBoolean("sliders", false), targets?.takeIf { it.size == 3 } ?: Drags().targets)
        }

        /** The entries that are strings (a JSON null or a number is skipped, never "null"). */
        private fun strings(o: JSONObject?): Map<String, String> =
            o?.keys()?.asSequence()?.mapNotNull { key -> (o.opt(key) as? String)?.let { key to it } }?.toMap().orEmpty()

        /** Before profiles: the one mapping there was becomes Profile 1. */
        fun migrated(map: Map<String, String>, dial: String) =
            ProfileList(listOf(Profile("p1", "Profile 1", map, dial)), "p1", emptyMap())

        /** A fresh install: Profile 1 with [map] and [dial], and a new profile's middle dial. */
        fun fresh(map: Map<String, String>, dial: String) =
            ProfileList(listOf(Profile("p1", "Profile 1", map, dial, middleDial = NEW_MIDDLE_DIAL)), "p1", emptyMap())

        /** Throws on anything it can't read, including a list with no profiles. */
        fun fromJson(profiles: String, apps: String, picked: String): ProfileList {
            val list = JSONArray(profiles)
            val parsed = (0 until list.length()).map { i ->
                val item = list.getJSONObject(i)
                val map = item.getJSONObject("map")
                val palm = item.optJSONObject("palm")
                val drags = item.optJSONObject("drags")
                Profile(
                    item.getString("id"),
                    item.getString("name"),
                    strings(map),
                    item.getString("dial"),
                    // A gesture whose overrides aren't an object is skipped, not the whole list.
                    palm = palm?.keys()?.asSequence()?.mapNotNull { key -> palm.optJSONObject(key)?.let { key to strings(it) } }
                        ?.filter { it.second.isNotEmpty() }?.toMap().orEmpty(),
                    middleDial = item.optString("middle_dial", DialChoice.NONE.key),
                    indexDrags = drags(drags?.optJSONObject("index")),
                    middleDrags = drags(drags?.optJSONObject("middle")),
                    drawings = strings(item.optJSONObject("drawings")),
                    sendAll = item.optBoolean("send_all", false),
                )
            }
            require(parsed.isNotEmpty()) { "no profiles" }
            val owners = JSONObject(apps)
            return ProfileList(parsed, picked, owners.keys().asSequence().associateWith { owners.getString(it) })
        }
    }
}
