package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDevicesTest {
    private val two = HomeDevices().added("Yeelight")!!.first.added("Desk lamp")!!.first

    @Test fun `a new device gets the next id and a trimmed name, a taken name picks that one`() {
        assertEquals(listOf(HomeDevice("d1", "Yeelight"), HomeDevice("d2", "Desk lamp")), two.devices)
        val (same, picked) = two.added("  desk   LAMP ")!!
        assertEquals(two, same)
        assertEquals("d2", picked.id)
        assertNull(two.added("   "))
        // Never an id still in use, even after a delete.
        assertEquals("d3", two.deleted("d1").added("Fan")!!.second.id)
    }

    @Test fun `rename keeps the id, refuses a blank or taken name`() {
        assertEquals(HomeDevice("d1", "Ceiling"), two.renamed("d1", " Ceiling ").get("d1"))
        assertEquals(two, two.renamed("d1", "  "))
        assertEquals(two, two.renamed("d1", "desk lamp"))
        // Its own name in other letters is fine.
        assertEquals("YEELIGHT", two.renamed("d1", "YEELIGHT").get("d1")!!.name)
    }

    @Test fun `the list round-trips, skipping what it can't read`() {
        assertEquals(two, HomeDevices.fromJson(two.toJson()))
        val odd = """[{"id":"d1","name":"Lamp"},{"id":"d1","name":"Again"},{"name":"No id"},{"id":"d:2","name":"Bad id"},{"id":"d3","name":" "},7]"""
        assertEquals(listOf(HomeDevice("d1", "Lamp")), HomeDevices.fromJson(odd).devices)
        assertEquals(HomeDevices(), HomeDevices.fromJson("not json"))
    }

    @Test fun `a stored automation keeps its device after an at, plain automation has none`() {
        assertEquals("automation", Mappings.automationId(null))
        assertEquals("automation@d1", Mappings.automationId("d1"))
        assertTrue(Mappings.isAutomation("automation"))
        assertTrue(Mappings.isAutomation("automation@d1"))
        assertFalse(Mappings.isAutomation("media.next"))
        assertFalse(Mappings.isAutomation("automation:swipe_left"))
        assertNull(Mappings.deviceOf("automation"))
        assertEquals("d1", Mappings.deviceOf("automation@d1"))
        assertNull(Mappings.deviceOf("media.next"))
        // As actions and as dial choices.
        assertEquals(Action.AUTOMATION, Action.of("automation@d1"))
        assertEquals(DialChoice.AUTOMATION, DialChoice.of("automation@d1"))
        assertEquals(DialChoice.AUTOMATION, DialChoice.of("automation"))
    }

    private val list = ProfileList(
        listOf(
            Profile("p1", "Music", mapOf("middle_tap" to "automation@d1", "swipe_up" to "automation"), "automation@d1",
                palm = mapOf("index_tap" to mapOf("up" to "automation@d1", "down" to "automation@d2")),
                middleDrags = Drags(sliders = true, targets = listOf("automation@d1", "none", "automation@d2")),
                drawings = mapOf("Bulb" to "automation@d1")),
            Profile("p2", "Reading", mapOf("swipe_up" to "automation@d2"), "none", middleDial = "automation@d1",
                // Kept while the index finger's drags are gestures: not shown, so not a use.
                indexDrags = Drags(sliders = false, targets = listOf("automation@d1", "none", "automation@d7"))),
        ),
        picked = "p1",
        apps = emptyMap(),
    )
    private val calls = mapOf("middle_tap" to "automation@d1", "index_double" to "call.answer")
    private val shared = mapOf("Bulb" to "automation@d1", "Star" to "torch.toggle")
    private val sequences = Sequences.Library(listOf(
        Sequences.Sequence("q1", "Goodnight", listOf("torch.toggle", "automation@d1", "wait:1000", "automation@d8", "automation")),
    ))
    private val none = Sequences.Library(emptyList())

    @Test fun `a device's uses are counted everywhere`() {
        // Gesture, override, index dial, slider and drawing in Music; the middle dial in Reading; a call; a shared drawing.
        assertEquals(8, HomeDevices.uses("d1", list, calls, shared, none))
        assertEquals(3, HomeDevices.uses("d2", list, calls, shared, none))
        assertEquals(0, HomeDevices.uses("d9", list, calls, shared, none))
        assertEquals(0, HomeDevices.uses("d7", list, calls, shared, none))
    }

    @Test fun `a new device's id is above every id still in the mappings`() {
        // d7 is only a kept slider target, d5 only During calls', d6 only a shared drawing's: a new device never inherits them.
        val used = HomeDevices.referenced(list, calls + ("swipe_up" to "automation@d5"), shared + ("Star" to "automation@d6"), none)
        assertEquals(setOf("d1", "d2", "d5", "d6", "d7"), used)
        assertEquals("d8", HomeDevices().added("Fan", used)!!.second.id)
        assertEquals("d3", two.added("Fan", setOf("d1", "x"))!!.second.id)
    }

    @Test fun `deleting a device leaves its uses sending without one`() {
        val cleared = list.withoutDevice("d1")
        val music = cleared.profile("p1")!!
        assertEquals("automation", music.map["middle_tap"])
        assertEquals("automation", music.map["swipe_up"])
        assertEquals("automation", music.dial)
        assertEquals(mapOf("up" to "automation", "down" to "automation@d2"), music.palm["index_tap"])
        assertEquals(listOf("automation", "none", "automation@d2"), music.middleDrags.targets)
        // Unseen ones too.
        assertEquals(listOf("automation", "none", "automation@d7"), cleared.profile("p2")!!.indexDrags.targets)
        assertEquals("automation", music.drawings["Bulb"])
        assertEquals("automation", cleared.profile("p2")!!.middleDial)
        assertEquals("automation@d2", cleared.profile("p2")!!.map["swipe_up"])
        assertEquals(mapOf("middle_tap" to "automation", "index_double" to "call.answer"), HomeDevices.withoutDevice(calls, "d1"))
        assertEquals(0, HomeDevices.uses("d1", cleared, HomeDevices.withoutDevice(calls, "d1"), HomeDevices.withoutDevice(shared, "d1"), none))
    }

    @Test fun `a rename changes no mapping, they keep the id`() {
        val renamed = two.renamed("d1", "Ceiling")
        assertEquals("Ceiling", renamed.get(Mappings.deviceOf(list.profile("p1")!!.map["middle_tap"]))!!.name)
    }

    @Test fun `a sequence's steps count as uses and keep their device's id taken`() {
        assertEquals(9, HomeDevices.uses("d1", list, calls, shared, sequences))
        assertEquals(1, HomeDevices.uses("d8", list, calls, shared, sequences))
        // d8 is only in a sequence: a new device doesn't take it over.
        val used = HomeDevices.referenced(list, calls, shared, sequences)
        assertTrue("d8" in used)
        assertEquals("d9", HomeDevices().added("Lamp", used)!!.second.id)
    }

    @Test fun `deleting a device leaves a sequence's steps sending without one`() {
        val cleared = sequences.withoutDevice("d1")
        assertEquals(listOf("torch.toggle", "automation", "wait:1000", "automation@d8", "automation"), cleared.get("q1")!!.steps)
        assertEquals(0, HomeDevices.uses("d1", list.withoutDevice("d1"), HomeDevices.withoutDevice(calls, "d1"), HomeDevices.withoutDevice(shared, "d1"), cleared))
    }

    @Test fun `a Send to automation step is labelled with its device, a deleted one with none`() {
        val curtains = HomeDevices().added("Curtains")!!.first
        assertEquals("Send to automation · Curtains", Labels.automation(curtains, "automation@d1"))
        assertEquals("Send to automation", Labels.automation(curtains.deleted("d1"), "automation@d1"))
        assertEquals("Send to automation", Labels.automation(curtains, "automation"))
    }
}
