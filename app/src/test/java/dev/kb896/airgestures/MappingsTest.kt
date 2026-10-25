package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingsTest {
    private val list = ProfileList(
        listOf(
            Profile("p1", "Music", mapOf("swipe_up" to "media.previous", "index_double" to "", "middle_tap" to "automation"), "volume"),
            Profile("p2", "Reading", mapOf("swipe_up" to "nav.next", "middle_hold" to "media.next"), "none"),
            Profile("p3", "Lights", emptyMap(), "automation"),
        ),
        picked = "p2",
        apps = mapOf("org.videolan.vlc" to "p1", "gone.app" to "p9"),
    )
    private val calls = mapOf("index_double" to "call.answer", "middle_double" to "-", "swipe_up" to "", "middle_tap" to "automation")
    private val dials = { key: String -> DialChoice.of(key).let { if (it.up.isEmpty() && it.down.isEmpty()) null else it.up to it.down } }

    private fun json() = org.json.JSONObject(Mappings.profilesJson(list, calls, dials, "left"))
    private fun map(profile: Int) = json().getJSONArray("profiles").getJSONObject(profile).getJSONObject("map")

    @Test fun `every profile gets every gesture, defaults for keys it never stored`() {
        val first = map(0)
        assertEquals(Gesture.entries.map { it.key }.toSet(), first.keys().asSequence().toSet())
        assertEquals("media.previous", first.getString("swipe_up"))
        assertEquals("", first.getString("index_double"))
        assertEquals("media.next", first.getString("swipe_down"))
        assertEquals("", first.getString("middle_hold"))
        assertEquals("", first.getString("index_triple"))
        assertEquals("", first.getString("middle_triple"))
        assertEquals("media.next", map(1).getString("middle_hold"))
        assertEquals("Reading", json().getJSONArray("profiles").getJSONObject(1).getString("name"))
    }

    @Test fun `automation is named after its gesture, in the map and in the calls`() {
        assertEquals("automation:middle_tap", map(0).getString("middle_tap"))
        assertEquals("automation:middle_tap", json().getJSONObject("calls").getString("middle_tap"))
    }

    @Test fun `the calls leave out as usual, nothing is a dash`() {
        val calls = json().getJSONObject("calls")
        assertEquals(setOf("index_double", "middle_double", "middle_tap"), calls.keys().asSequence().toSet())
        assertEquals("call.answer", calls.getString("index_double"))
        assertEquals("-", calls.getString("middle_double"))
    }

    @Test fun `the pick and the apps are indexes, the hand passes through`() {
        val json = json()
        assertEquals(1, json.getInt("picked"))
        assertEquals(0, json.getJSONObject("apps").getInt("org.videolan.vlc"))
        // An app whose profile is gone has none of its own, as in ProfileList.active.
        assertFalse(json.getJSONObject("apps").has("gone.app"))
        assertEquals("left", json.getString("hand"))
    }

    @Test fun `the dial is its two actions, a dial set to nothing is null`() {
        val profiles = json().getJSONArray("profiles")
        assertEquals("volume.up", profiles.getJSONObject(0).getJSONObject("dial").getString("up"))
        assertEquals("volume.down", profiles.getJSONObject(0).getJSONObject("dial").getString("down"))
        assertTrue(profiles.getJSONObject(1).isNull("dial"))
        assertEquals("automation:dial_up", profiles.getJSONObject(2).getJSONObject("dial").getString("up"))
    }

    @Test fun `a tap spot round-trips with four decimals`() {
        assertEquals("tap:0.5000,0.8125", Tap.format(.5f, .8125f))
        assertEquals(.5f to .8125f, Tap.parse("tap:0.5000,0.8125"))
    }

    @Test fun `bad or off-screen tap spots are refused`() {
        assertNull(Tap.parse("tap:1.2,0.5"))
        assertNull(Tap.parse("tap:abc"))
        assertNull(Tap.parse("media.next"))
    }

    @Test fun `unlocked the whole notification is read, locked only app and title`() {
        assertEquals("WhatsApp. Ana: are you coming tonight?", Speech.sentence("WhatsApp", "Ana", "are you coming tonight?", locked = false))
        assertEquals("WhatsApp. Ana.", Speech.sentence("WhatsApp", "Ana", "are you coming tonight?", locked = true))
        assertEquals("Gmail. 3 new emails", Speech.sentence("Gmail", null, "3 new emails", locked = false))
        assertEquals("Gmail.", Speech.sentence("Gmail", " ", null, locked = true))
    }

    @Test fun `the newest notification skips ongoing, ours and empty summaries`() {
        val notes = listOf(
            Notes.Note("old", 1, clearable = true, ongoing = false, ours = false, emptySummary = false),
            Notes.Note("nav", 9, clearable = false, ongoing = true, ours = false, emptySummary = false),
            Notes.Note("ours", 8, clearable = true, ongoing = false, ours = true, emptySummary = false),
            Notes.Note("summary", 7, clearable = true, ongoing = false, ours = false, emptySummary = true),
            Notes.Note("chat", 5, clearable = true, ongoing = false, ours = false, emptySummary = false),
        )
        assertEquals("chat", Notes.newest(notes)?.key)
        assertNull(Notes.newest(notes.take(0)))
    }
}
