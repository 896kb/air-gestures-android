package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlsTest {
    @Test fun `the standard band controls are today's gestures, Draw on none`() {
        val band = BandControls.standard()
        assertEquals(BandControl(Gesture.MIDDLE_HOLD), band[Builtin.CONTROLS])
        assertEquals(Gesture.INDEX_TRIPLE, band[Builtin.NEXT]?.gesture)
        assertEquals(Gesture.MIDDLE_TRIPLE, band[Builtin.PREVIOUS]?.gesture)
        assertNull(band[Builtin.DRAW])
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
        assertEquals(true, json.isNull("draw"))
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
