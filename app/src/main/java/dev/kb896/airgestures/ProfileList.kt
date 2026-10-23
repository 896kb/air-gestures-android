package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/** One gesture set: a name, an action id per gesture key, and what pinch and turn does. */
data class Profile(
    val id: String,
    val name: String,
    val map: Map<String, String>,
    val dial: String,
    val musicBluetoothOnly: Boolean = false,
)

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

    /** The picked profile [by] places on (+1 next, −1 previous), wrapping round. */
    fun step(by: Int): ProfileList {
        val at = profiles.indexOfFirst { it.id == picked }.coerceAtLeast(0)
        return copy(picked = profiles[Math.floorMod(at + by, profiles.size)].id)
    }

    /** [id] becomes the manual profile (the widget's picker); an unknown id changes nothing. */
    fun picking(id: String) = if (profile(id) == null) this else copy(picked = id)

    /** A new profile at the end, with the lowest free number for its id and name. */
    fun added(map: Map<String, String>, dial: String): Pair<ProfileList, Profile> {
        val n = generateSequence(1) { it + 1 }.first { n -> profiles.none { it.id == "p$n" || it.name == "Profile $n" } }
        val made = Profile("p$n", "Profile $n", map, dial)
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

    fun withMusicBluetoothOnly(id: String, enabled: Boolean) =
        change(id) { it.copy(musicBluetoothOnly = enabled) }

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
                put("musicBluetoothOnly", profile.musicBluetoothOnly)
            })
        }
        return list.toString() to JSONObject(apps).toString()
    }

    companion object {
        /** Before profiles: the one mapping there was becomes Profile 1. */
        fun migrated(map: Map<String, String>, dial: String) =
            ProfileList(listOf(Profile("p1", "Profile 1", map, dial)), "p1", emptyMap())

        /** Throws on anything it can't read, including a list with no profiles. */
        fun fromJson(profiles: String, apps: String, picked: String): ProfileList {
            val list = JSONArray(profiles)
            val parsed = (0 until list.length()).map { i ->
                val item = list.getJSONObject(i)
                val map = item.getJSONObject("map")
                Profile(
                    item.getString("id"),
                    item.getString("name"),
                    map.keys().asSequence().associateWith { map.getString(it) },
                    item.getString("dial"),
                    item.optBoolean("musicBluetoothOnly", false),
                )
            }
            require(parsed.isNotEmpty()) { "no profiles" }
            val owners = JSONObject(apps)
            return ProfileList(parsed, picked, owners.keys().asSequence().associateWith { owners.getString(it) })
        }
    }
}
