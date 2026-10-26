package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MappingsTest {
    private val list = ProfileList(
        listOf(
            Profile("p1", "Music", mapOf("swipe_up" to "media.previous", "index_double" to "", "middle_tap" to "automation"), "volume",
                palm = mapOf("index_tap" to mapOf("up" to "automation", "sideways" to "")),
                middleDrags = Drags(sliders = true, targets = listOf("automation", "none", "brightness")),
                drawings = mapOf("Bulb" to "automation")),
            Profile("p2", "Reading", mapOf("swipe_up" to "nav.next"), "none", middleDial = "seek"),
            Profile("p3", "Café: night/2", emptyMap(), "automation@d1"),
        ),
        picked = "p2",
        apps = mapOf("org.videolan.vlc" to "p1", "gone.app" to "p9"),
    )
    private val calls = mapOf("index_double" to "call.answer", "middle_double" to "-", "swipe_up" to "", "middle_tap" to "automation")
    private val band = BandControls.standard().with(Builtin.NEXT, BandControl(Gesture.INDEX_TRIPLE, setOf(Palm.UP)))
    private val input = Mappings.Input(list, band, calls, mapOf("Bulb" to "torch.toggle", "Star" to "automation@d2"),
        mapOf("left" to Calibration(palm = -140.0 to -12.0), "right" to Calibration()), "left")

    private fun json() = org.json.JSONObject(Mappings.profilesJson(input))
    private fun profile(i: Int) = json().getJSONArray("profiles").getJSONObject(i)
    private fun map(i: Int) = profile(i).getJSONObject("map")

    @Test fun `every profile gets every gesture, defaults for keys it never stored`() {
        val first = map(0)
        assertEquals(Gesture.entries.map { it.key }.toSet(), first.keys().asSequence().toSet())
        assertEquals("media.previous", first.getString("swipe_up"))
        assertEquals("media.next", first.getString("swipe_down"))
        assertEquals("", first.getString("middle_hold"))
        assertEquals("", first.getString("index_triple"))
        assertEquals("", first.getString("index_drag_left"))
    }

    @Test fun `the band controls and the calibrations go along`() {
        val json = json()
        val next = json.getJSONObject("band").getJSONObject("next")
        assertEquals("index_triple", next.getString("gesture"))
        assertEquals("up", next.getJSONArray("palm").getString(0))
        assertEquals(-140.0, json.getJSONObject("calibration").getJSONObject("left").getJSONObject("palm").getDouble("down"), 0.0)
        assertTrue(json.getJSONObject("calibration").getJSONObject("right").isNull("palm"))
        assertEquals("torch.toggle", json.getJSONObject("drawings").getString("Bulb"))
    }

    @Test fun `automation is named after what made it, with its profile and device`() {
        assertEquals("automation/Music:middle_tap", map(0).getString("middle_tap"))
        // During calls is named as its screen is.
        assertEquals("automation/During+calls:middle_tap", json().getJSONObject("calls").getString("middle_tap"))
        assertEquals("automation/Music:index_tap:up", profile(0).getJSONObject("palm").getJSONObject("index_tap").getString("up"))
        assertEquals("", profile(0).getJSONObject("palm").getJSONObject("index_tap").getString("sideways"))
        assertEquals("automation/Music:drawing:Bulb", profile(0).getJSONObject("drawings").getString("Bulb"))
        val sliders = profile(0).getJSONObject("middle").getJSONObject("drags").getJSONArray("sliders")
        assertEquals("automation/Music:dial:middle_left_right:up", sliders.getJSONObject(0).getString("up"))
        assertTrue(sliders.isNull(1))
        assertEquals("brightness.down", sliders.getJSONObject(2).getString("down"))
        // The profile's name is encoded: it can hold anything.
        assertEquals("automation@d1/Caf%C3%A9%3A+night%2F2:dial:dial:down", profile(2).getJSONObject("index").getJSONObject("dial").getString("down"))
    }

    @Test fun `a shared drawing that sends to automation goes into each profile, to carry its name`() {
        assertEquals("automation@d2/Music:drawing:Star", profile(0).getJSONObject("drawings").getString("Star"))
        assertEquals("automation@d2/Reading:drawing:Star", profile(1).getJSONObject("drawings").getString("Star"))
        // Other shared actions stay shared only; the shared list keeps both.
        assertFalse(profile(1).getJSONObject("drawings").has("Bulb"))
        assertEquals("automation@d2:drawing:Star", json().getJSONObject("drawings").getString("Star"))
        // A profile's own action wins, as band-core's own-then-shared rule.
        val own = Mappings.Input(list.withDrawing("p2", "Star", "media.next"), band, calls, input.drawings, input.calibrations, "left")
        val reading = org.json.JSONObject(Mappings.profilesJson(own)).getJSONArray("profiles").getJSONObject(1)
        assertEquals("media.next", reading.getJSONObject("drawings").getString("Star"))
    }

    @Test fun `dials are their two actions, nothing is null, drags in gestures mode say so`() {
        assertEquals("volume.up", profile(0).getJSONObject("index").getJSONObject("dial").getString("up"))
        assertTrue(profile(1).getJSONObject("index").isNull("dial"))
        assertEquals("dial.seek_forward", profile(1).getJSONObject("middle").getJSONObject("dial").getString("up"))
        assertTrue(profile(0).getJSONObject("middle").isNull("dial"))
        assertEquals("gestures", profile(0).getJSONObject("index").getString("drags"))
    }

    @Test fun `automation names read back, the ones from before profiles and devices too`() {
        assertEquals(Mappings.Automation.Gesture("index_tap", null), Mappings.sent("automation:index_tap"))
        assertEquals(Mappings.Automation.Gesture("index_tap", "up"), Mappings.sent("automation:index_tap:up"))
        assertEquals(Mappings.Automation.Step("dial", +1), Mappings.sent("automation:dial:dial:up"))
        assertEquals(Mappings.Automation.Step("middle_left_right", -1), Mappings.sent("automation:dial:middle_left_right:down"))
        assertEquals(Mappings.Automation.Drawing("Light: on"), Mappings.sent("automation:drawing:Light: on"))
        assertEquals(Mappings.Automation.Gesture("index_tap", "up", "Music", "d1"), Mappings.sent("automation@d1/Music:index_tap:up"))
        assertEquals(Mappings.Automation.Drawing("Light: on", "During calls", null), Mappings.sent("automation/During+calls:drawing:Light: on"))
        assertNull(Mappings.sent("media.next"))
        assertNull(Mappings.sent("automation"))
    }

    @Test fun `what the bridge gets reads back as its profile and device`() {
        val down = Mappings.sent(profile(2).getJSONObject("index").getJSONObject("dial").getString("down"))
        assertEquals(Mappings.Automation.Step("dial", -1, "Café: night/2", "d1"), down)
        assertEquals(Mappings.Automation.Gesture("middle_tap", null, "During calls", null), Mappings.sent(json().getJSONObject("calls").getString("middle_tap")))
        assertEquals(Mappings.Automation.Drawing("Star", "Reading", "d2"), Mappings.sent(profile(1).getJSONObject("drawings").getString("Star")))
        // The practice screen names the dial's steps the same way.
        assertEquals("automation@d1/Caf%C3%A9%3A+night%2F2:dial:dial:up", Mappings.dial("automation@d1", "dial", "Café: night/2")!!.first)
    }

    @Test fun `steps gather per dial or slider, device and profile`() {
        val step = Mappings.Automation.Step("dial", +1, "Music", "d1")
        assertEquals(step.key, step.copy(step = -1).key)
        assertFalse(step.key == step.copy(device = "d2").key)
        assertFalse(step.key == step.copy(device = null).key)
        assertFalse(step.key == step.copy(profile = "Reading").key)
        assertFalse(step.key == step.copy(name = "middle_dial").key)
    }

    @Test fun `the broadcast's extras, profile always and device when it has one`() {
        assertEquals(mapOf("gesture" to "index_tap", "palm" to "up", "profile" to "Music", "device" to "Yeelight"),
            Mappings.extras(Mappings.Automation.Gesture("index_tap", "up", "Music", "d1"), device = "Yeelight"))
        assertEquals(mapOf("gesture" to "swipe_left", "profile" to "During calls"),
            Mappings.extras(Mappings.Automation.Gesture("swipe_left", null, "During calls", "d9"), device = null))
        assertEquals(mapOf("gesture" to "drawing", "name" to "Star", "profile" to "Reading", "device" to "Desk lamp"),
            Mappings.extras(Mappings.Automation.Drawing("Star", "Reading", "d2"), device = "Desk lamp"))
        assertEquals(mapOf("gesture" to "middle_dial", "steps" to -3, "profile" to "Music"),
            Mappings.extras(Mappings.Automation.Step("middle_dial", -1, "Music", null), device = null, steps = -3))
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
