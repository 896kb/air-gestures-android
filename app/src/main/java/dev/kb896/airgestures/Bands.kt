package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * The bands this phone keeps, one per wrist: band 1 (slot 0: the files and settings from before
 * there were two) and, once added, band 2 (slot 1). Each has its own owner key and record, wrist,
 * controls, handwriting switch, firmware, calibrations and profiles in use; the profiles
 * themselves, the drawings, sequences and devices are shared. [selected] is the band the home
 * screen shows, which Setup, firmware, calibration and teaching follow.
 */
object Bands {
    const val MAX = 2
    private const val ADDED = "band2.added"
    private const val SELECTED = "band.selected"
    /** Band 2's settings: band 1's names with this in front. */
    private const val PREFIX = "band2."
    /** The settings each band has its own of (band 2's under [PREFIX]). */
    private val OWN = listOf("hand", "paused", "band.address", "ota_expect", "association", "write_in_fields", "connect", PICKED, ON)
    /** Band 2's pick (band 1's is the profile list's own, `profile.current`). */
    const val PICKED = "profile.current"
    /** The profiles a band uses, in its order (none stored: all of them). */
    const val ON = "profiles.on"

    private fun prefs(context: Context) = context.getSharedPreferences("air-gestures", Context.MODE_PRIVATE)

    /** A band's own setting's name. */
    fun key(slot: Int, name: String) = if (slot == 0) name else PREFIX + name

    fun count(context: Context) = if (prefs(context).getBoolean(ADDED, false)) 2 else 1

    fun two(context: Context) = count(context) > 1

    fun slots(context: Context) = 0 until count(context)

    @Volatile
    private var cached: Int? = null

    /** The band the home screen shows. */
    fun selected(context: Context): Int =
        (cached ?: prefs(context).getInt(SELECTED, 0).also { cached = it }).coerceIn(0, count(context) - 1)

    fun select(context: Context, slot: Int) {
        val now = slot.coerceIn(0, count(context) - 1)
        cached = now
        prefs(context).edit().putInt(SELECTED, now).apply()
        BandService.selectionChanged(context)
    }

    /** Where a band's owner key and record live: band 1's where they always were. */
    fun dir(context: Context, slot: Int): File =
        if (slot == 0) context.filesDir else File(context.filesDir, "band2").apply { mkdirs() }

    /** Band 2, on the other wrist from band 1 and without handwriting; selected. */
    fun add(context: Context) {
        if (two(context)) return
        val first = Prefs(context, 0).hand
        prefs(context).edit()
            .putBoolean(ADDED, true)
            .putString(key(1, "hand"), when (first) { "left" -> "right"; "right" -> "left"; else -> "band" })
            .putBoolean(key(1, "write_in_fields"), false)
            .apply()
        select(context, 1)
        BandService.instance?.bandsChanged()
    }

    /**
     * [slot] is gone from this phone (its key and record are already forgotten): band 2's
     * settings and files go, or, removing band 1, band 2's become band 1's.
     */
    fun remove(context: Context, slot: Int) {
        if (!two(context)) return
        val prefs = prefs(context)
        val edit = prefs.edit()
        if (slot == 0) {
            for (name in OWN) {
                val value = prefs.all[PREFIX + name]
                when (value) {
                    is String -> edit.putString(name, value)
                    is Boolean -> edit.putBoolean(name, value)
                    is Int -> edit.putInt(name, value)
                    is Long -> edit.putLong(name, value)
                    null -> if (name != PICKED) edit.remove(name)
                }
            }
            // Band 2's pick becomes the list's own.
            (prefs.all[PREFIX + PICKED] as? String)?.let { picked -> Profiles.update(context) { it.picking(picked) } }
            val two = dir(context, 1)
            for (file in two.listFiles().orEmpty()) file.renameTo(File(context.filesDir, file.name))
        }
        for (name in OWN) edit.remove(PREFIX + name)
        edit.remove(ADDED).apply()
        dir(context, 1).deleteRecursively()
        cached = 0
        prefs.edit().putInt(SELECTED, 0).apply()
        BandService.instance?.bandsChanged()
        BandService.selectionChanged(context)
    }

    /** The band handwriting in text fields is on for, if any (one at most). */
    fun writer(context: Context): Int? = slots(context).firstOrNull { Prefs(context, it).writeInFields }

    /** The band's wrist as chosen here ("left" / "right"), else what it reported, else null. */
    fun wrist(context: Context, slot: Int): String? =
        Prefs(context, slot).hand.takeIf { it != "band" } ?: BandService.stateOf(slot).value.hand

    /** The bands as they're shown side by side: the left wrist's first, the right wrist's last. */
    fun inOrder(context: Context): List<Int> = slots(context).sortedBy {
        when (wrist(context, it)) {
            "left" -> 0
            "right" -> 2
            else -> 1
        }
    }

    /** "Left band" / "Right band", or "Band 2" without a wrist, or when both say the same one. */
    fun name(context: Context, slot: Int): String {
        val wrist = wrist(context, slot)
        val other = slots(context).filter { it != slot }.map { wrist(context, it) }
        return if (wrist == null || wrist in other) "Band ${slot + 1}" else wrist.replaceFirstChar { it.uppercase() } + " band"
    }

    /**
     * What automation's `band` extra says (null with one band): the wrist, else `band1` /
     * `band2` when it's unknown or both say the same.
     */
    fun tag(context: Context, slot: Int): String? {
        if (!two(context)) return null
        val wrist = wrist(context, slot)
        val other = slots(context).filter { it != slot }.map { wrist(context, it) }
        return if (wrist == null || wrist in other) "band${slot + 1}" else wrist
    }

    /** The Activity log's mark for a band's lines (none with one band): L, R, or 1 / 2. */
    fun mark(context: Context, slot: Int): String? = tag(context, slot)?.let {
        when (it) {
            "left" -> "L"
            "right" -> "R"
            else -> it.removePrefix("band")
        }
    }

    /** The profiles [slot] uses, in its order; null: all of them, in the list's order. */
    fun on(context: Context, slot: Int): List<String>? =
        prefs(context).getString(key(slot, ON), null)?.let { json ->
            runCatching { JSONArray(json).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrNull()
        }

    fun setOn(context: Context, slot: Int, ids: List<String>?) {
        prefs(context).edit().apply { if (ids == null) remove(key(slot, ON)) else putString(key(slot, ON), JSONArray(ids).toString()) }.apply()
    }

    /** A new profile goes on every band that picks its own (one using all of them has it already). */
    fun profileAdded(context: Context, id: String) {
        for (slot in slots(context)) on(context, slot)?.let { setOn(context, slot, it + id) }
    }

    /** The profile [slot] picked (band 1's is the list's own). */
    fun picked(context: Context, slot: Int): String =
        if (slot == 0) Profiles.load(context).picked
        else prefs(context).getString(key(slot, PICKED), null) ?: Profiles.load(context).picked

    fun pick(context: Context, slot: Int, id: String) {
        if (slot == 0) Profiles.update(context) { it.picking(id) }
        else if (Profiles.load(context).profile(id) != null) prefs(context).edit().putString(key(slot, PICKED), id).apply()
    }

    /** The profiles as [slot] uses them: its own, its pick, and the apps whose profile it has. */
    fun profiles(context: Context, slot: Int): ProfileList = Profiles.load(context).forBand(on(context, slot), picked(context, slot))
}
