package dev.kb896.airgestures

import org.json.JSONArray
import org.json.JSONObject

/** A lamp, a speaker…: what Send to automation can say a gesture is for. The mappings keep its [id]. */
data class HomeDevice(val id: String, val name: String)

/**
 * The Home devices. A Send to automation for one is stored as `automation@<id>`
 * ([Mappings.automationId]) and sends its current name as `device`, so a rename reaches every
 * use. Immutable and free of Android, so it's unit-tested; [Profiles] stores it.
 */
data class HomeDevices(val devices: List<HomeDevice> = emptyList()) {
    fun get(id: String?) = devices.firstOrNull { it.id == id }

    private fun named(name: String) = devices.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** A new device called [name], or the one already called that; null for a blank name. */
    fun added(name: String): Pair<HomeDevices, HomeDevice>? {
        val clean = clean(name) ?: return null
        named(clean)?.let { return this to it }
        // Above every id in use, so a deleted device's id isn't handed out while the list has a higher one.
        val n = (devices.mapNotNull { it.id.removePrefix("d").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val made = HomeDevice("d$n", clean)
        return copy(devices = devices + made) to made
    }

    /** Unchanged for a blank name or another device's. */
    fun renamed(id: String, name: String): HomeDevices {
        val clean = clean(name) ?: return this
        if (named(clean).let { it != null && it.id != id }) return this
        return copy(devices = devices.map { if (it.id == id) it.copy(name = clean) else it })
    }

    fun deleted(id: String) = copy(devices = devices.filter { it.id != id })

    fun toJson(): String = JSONArray(devices.map { JSONObject().put("id", it.id).put("name", it.name) }).toString()

    companion object {
        /** A name on one line, trimmed (null: blank). */
        fun clean(name: String) = name.replace(Regex("\\s+"), " ").trim().ifEmpty { null }

        /** Ids go inside action ids (`automation@<id>`): letters, digits, `_` and `-` only. */
        private val ID = Regex("[A-Za-z0-9_-]+")

        /** Skips what it can't read (no id or name, an odd id, a repeated id); empty for anything else. */
        fun fromJson(text: String): HomeDevices = runCatching {
            val a = JSONArray(text)
            val read = (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { ID.matches(it) } ?: return@mapNotNull null
                HomeDevice(id, clean(o.optString("name")) ?: return@mapNotNull null)
            }
            HomeDevices(read.distinctBy { it.id })
        }.getOrDefault(HomeDevices())

        /** How many stored actions and dial choices are for [device]: in the profiles, During calls ([calls]) and the [shared] drawings. */
        fun uses(device: String, list: ProfileList, calls: Map<String, String>, shared: Map<String, String>) =
            (list.ids() + calls.values + shared.values).count { Mappings.deviceOf(it) == device }

        /** [map] with [device]'s automation sending without a device. */
        fun withoutDevice(map: Map<String, String>, device: String) = map.mapValues { (_, id) -> Mappings.withoutDevice(id, device) }
    }
}
