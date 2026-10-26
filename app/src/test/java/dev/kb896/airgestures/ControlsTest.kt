package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlsTest {
    @Test fun `the standard band controls are today's gestures, Draw on the index hold`() {
        val band = BandControls.standard()
        assertEquals(BandControl(Gesture.MIDDLE_HOLD), band[Builtin.CONTROLS])
        assertEquals(Gesture.INDEX_TRIPLE, band[Builtin.NEXT]?.gesture)
        assertEquals(Gesture.MIDDLE_TRIPLE, band[Builtin.PREVIOUS]?.gesture)
        assertEquals(BandControl(Gesture.INDEX_HOLD), band[Builtin.DRAW])
    }

    @Test fun `an upgrade gets Draw on the index hold only when nothing uses it`() {
        val defaults = Gesture.entries.associate { it.key to it.default.id }
        val list = { map: Map<String, String>, palm: Map<String, Map<String, String>> ->
            ProfileList(listOf(Profile("p1", "A", defaults, "volume"), Profile("p2", "B", map, "volume", palm = palm)), "p1", emptyMap())
        }
        val off = BandControls.standard().with(Builtin.DRAW, null)
        // A fresh install's defaults, and a hold mapped to nothing or overridden with nothing.
        assertEquals(BandControls.standard(), BandControls.migrated(list(defaults, emptyMap()), emptyMap()))
        assertEquals(BandControls.standard(), BandControls.migrated(list(mapOf("index_hold" to ""), mapOf("index_hold" to mapOf("up" to ""))), emptyMap()))
        // Any profile's action, a palm override's, or During calls' keeps Draw off.
        assertEquals(off, BandControls.migrated(list(mapOf("index_hold" to "screen.home"), emptyMap()), emptyMap()))
        assertEquals(off, BandControls.migrated(list(emptyMap(), mapOf("index_hold" to mapOf("sideways" to "media.next"))), emptyMap()))
        assertEquals(off, BandControls.migrated(list(defaults, emptyMap()), mapOf("index_hold" to "call.end")))
        // As usual or Nothing during calls uses nothing there.
        for (call in listOf(Mappings.AS_USUAL, Mappings.NOTHING)) {
            assertEquals(BandControls.standard(), BandControls.migrated(list(defaults, emptyMap()), mapOf("index_hold" to call)))
        }
        // The middle tap (the Navigate profile's Home) is no matter.
        assertEquals(BandControls.standard(), BandControls.migrated(list(mapOf("middle_tap" to "screen.home"), emptyMap()), emptyMap()))
    }

    @Test fun `the one on a gesture follows the palm, Controls first`() {
        val band = BandControls.standard()
            .with(Builtin.NEXT, BandControl(Gesture.SWIPE_LEFT, setOf(Palm.UP)))
            .with(Builtin.DRAW, BandControl(Gesture.MIDDLE_HOLD))
        assertEquals(Builtin.NEXT, band.on(Gesture.SWIPE_LEFT, Palm.UP))
        assertNull(band.on(Gesture.SWIPE_LEFT, Palm.DOWN))
        assertEquals(Builtin.CONTROLS, band.on(Gesture.MIDDLE_HOLD, Palm.SIDE))
        assertEquals(listOf(Builtin.CONTROLS, Builtin.DRAW), band.onGesture(Gesture.MIDDLE_HOLD))
    }

    @Test fun `band controls round-trip as the bridge reads them`() {
        val band = BandControls.standard().with(Builtin.NEXT, BandControl(Gesture.INDEX_DRAG_LEFT, setOf(Palm.UP, Palm.DOWN)))
        val json = band.toJson()
        // org.json's JVM JSONObject keeps no key order: compare fields, not strings.
        assertEquals("index_drag_left", json.getJSONObject("next").getString("gesture"))
        assertEquals("""["down","up"]""", json.getJSONObject("next").getJSONArray("palm").toString())
        assertEquals("index_hold", json.getJSONObject("draw").getString("gesture"))
        assertEquals(true, BandControls.standard().with(Builtin.DRAW, null).toJson().isNull("draw"))
        assertEquals(true, json.getJSONObject("controls").isNull("palm"))
        assertEquals(band, BandControls.fromJson(json.toString()))
        assertEquals(BandControls(), BandControls.fromJson("""{"controls":{"gesture":"wave"}}"""))
    }

    @Test fun `gesture keys and labels are band-core's`() {
        assertEquals(24, Gesture.entries.size)
        assertEquals("index hold", Gesture.INDEX_HOLD.label.lowercase())
        assertEquals("middle drag toward", Gesture.MIDDLE_DRAG_TOWARD.label.lowercase())
        assertEquals(listOf("Tap", "Double tap", "Triple tap", "Hold"), Gesture.entries.filter { it.part == Part.INDEX && !it.drag }.map { it.short })
        assertEquals(6, Gesture.entries.count { it.part == Part.MIDDLE && it.drag })
    }

    @Test fun `no palm position or all three is any position`() {
        val band = BandControls.fromJson("""{"next":{"gesture":"index_triple","palm":[]},"previous":{"gesture":"middle_triple","palm":["up","down","sideways"]}}""")
        assertNull(band[Builtin.NEXT]!!.palms)
        assertNull(band[Builtin.PREVIOUS]!!.palms)
    }

    @Test fun `palm words`() {
        assertEquals("palm up", setOf(Palm.UP).words())
        assertEquals("palm down or sideways", setOf(Palm.SIDE, Palm.DOWN).words())
        assertEquals("palm down, sideways", setOf(Palm.SIDE, Palm.DOWN).words(", "))
    }
}
