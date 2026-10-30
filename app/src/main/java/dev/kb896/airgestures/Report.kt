package dev.kb896.airgestures

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What band-core says about the settings (Bridge.profileReport), for the screens: the warnings,
 * what's invalid, the actions and sliders the band controls pause (places: profile indexes,
 * null for During calls), and whose hold waits for the release. Free of Android, so it's unit-tested.
 */
class Report(json: String) {
    data class Paused(val control: Builtin, val gesture: Gesture, val place: Int?, val palm: Palm?, val positions: Set<Palm>)
    data class PausedSlider(val control: Builtin, val gesture: Gesture, val profile: Int, val part: Part, val pair: Int, val positions: Set<Palm>)
    class Choice(val gesture: Gesture, val pauses: List<Paused>, val sliders: List<PausedSlider>) {
        val count get() = pauses.size + sliders.size
    }

    private val root = runCatching { JSONObject(json) }.getOrDefault(JSONObject().put("error", "bad-json"))
    /** Why there's no report (`bad-json`: the bridge couldn't read the settings), else null. */
    val error: String? = root.optString("error").ifEmpty { null }
    val warnings: List<String> = strings(root.optJSONArray("warnings"))
    /** `binding <place> <gesture>`, `band <control>`, `draw-cant-cancel`. */
    val invalid: List<String> = objects(root.optJSONArray("invalid")).map { o ->
        when (o.optString("kind")) {
            "binding" -> "binding ${o.opt("place")} ${o.optString("gesture")}"
            "band" -> "band ${o.optString("control")}"
            else -> o.optString("kind")
        }
    }
    val paused: List<Paused> = paused(root.optJSONArray("paused"))
    val pausedSliders: List<PausedSlider> = sliders(root.optJSONArray("paused_sliders"))
    private val holds = root.optJSONArray("hold_waits")

    fun count(control: Builtin) = paused.count { it.control == control } + pausedSliders.count { it.control == control }
    /** In profile [place] (an index), or During calls (null); a slider two band controls pause counts once. */
    fun count(place: Int?) = paused.count { it.place == place } +
        if (place == null) 0 else pausedSliders.filter { it.profile == place }.distinctBy { it.part to it.pair }.size
    /** What's paused there, in every position any band control pauses it (the first band control's name). */
    fun paused(place: Int?, gesture: Gesture, palm: Palm?): Paused? {
        val all = paused.filter { it.place == place && it.gesture == gesture && it.palm == palm }
        return all.firstOrNull()?.copy(positions = all.flatMap { it.positions }.toSet())
    }
    /** A slider's pause, in every position any band control pauses it (the first band control's name). */
    fun pausedSlider(profile: Int, part: Part, pair: Int): PausedSlider? {
        val all = pausedSliders.filter { it.profile == profile && it.part == part && it.pair == pair }
        return all.firstOrNull()?.copy(positions = all.flatMap { it.positions }.toSet())
    }

    /** Moving a band control would pause this where nothing pauses it now (place, gesture, override and position). */
    fun isNew(p: Paused) = !paused.filter { it.place == p.place && it.gesture == p.gesture && it.palm == p.palm }
        .flatMap { it.positions }.containsAll(p.positions)

    fun isNew(s: PausedSlider) = !pausedSliders.filter { it.profile == s.profile && it.part == s.part && it.pair == s.pair }
        .flatMap { it.positions }.containsAll(s.positions)

    /** What [choice] would newly pause: what's worth asking about. */
    fun newCount(choice: Choice) = choice.pauses.count(::isNew) + choice.sliders.count(::isNew)
    /** Where [part]'s hold acts when you let go in profile [profile] (band-core's `Profiles::hold_waits`): the palm positions. */
    fun holdWaits(profile: Int, part: Part): Set<Palm> =
        palms(holds?.optJSONObject(profile)?.optJSONArray(if (part == Part.MIDDLE) "middle" else "index"))

    companion object {
        /** For the stored settings, from band-core. */
        fun of(context: Context) = Report(runCatching { Bridge.profileReport(Prefs(context).profilesJson(all = true)) }.getOrDefault("{}"))

        /** Bridge.bandChoices' refusal (`bad-json`, `unknown-control`), or null for a list. */
        fun choicesError(json: String): String? = runCatching { JSONObject(json).optString("error").ifEmpty { null } }.getOrNull()

        /** Bridge.bandChoices' answer. */
        fun choices(json: String): List<Choice> = objects(runCatching { JSONArray(json) }.getOrNull()).mapNotNull { o ->
            Choice(Gesture.of(o.optString("gesture")) ?: return@mapNotNull null, paused(o.optJSONArray("pauses")), sliders(o.optJSONArray("sliders")))
        }

        private fun objects(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
        private fun palms(a: JSONArray?) = strings(a).mapNotNull { Palm.of(it) }.toSet()
        private fun place(o: JSONObject) = (o.opt("place") as? Number)?.toInt()
        private fun palm(o: JSONObject) = if (o.isNull("palm")) null else Palm.of(o.optString("palm"))

        private fun paused(a: JSONArray?) = objects(a).mapNotNull { o ->
            Paused(Builtin.of(o.optString("control")) ?: return@mapNotNull null, Gesture.of(o.optString("gesture")) ?: return@mapNotNull null,
                place(o), palm(o), palms(o.optJSONArray("positions")))
        }

        private fun sliders(a: JSONArray?) = objects(a).mapNotNull { o ->
            PausedSlider(Builtin.of(o.optString("control")) ?: return@mapNotNull null, Gesture.of(o.optString("gesture")) ?: return@mapNotNull null,
                o.optInt("profile"), if (o.optString("finger") == "middle") Part.MIDDLE else Part.INDEX, o.optInt("pair"), palms(o.optJSONArray("positions")))
        }
    }
}
