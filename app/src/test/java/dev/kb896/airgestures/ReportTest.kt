package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportTest {
    private val report = Report("""{"warnings":["no-way-out"],"invalid":[{"kind":"band","control":"next"}],
        "paused":[{"control":"controls","gesture":"middle_hold","place":0,"palm":null,"positions":["down","sideways","up"]},
                  {"control":"next","gesture":"index_triple","place":0,"palm":"up","positions":["up"]},
                  {"control":"next","gesture":"index_double","place":"calls","palm":null,"positions":["up"]}],
        "paused_sliders":[{"control":"previous","gesture":"middle_drag_left","profile":1,"finger":"middle","pair":0,"positions":["down"]}],
        "uses":{},"hold_waits":[{"index":[],"middle":["down","sideways","up"]},{"index":["down","sideways","up"],"middle":["up"]}]}""")

    @Test fun `counts by band control and by place`() {
        assertEquals(1, report.count(Builtin.CONTROLS))
        assertEquals(2, report.count(Builtin.NEXT))
        assertEquals(1, report.count(Builtin.PREVIOUS))
        assertEquals(2, report.count(0))
        assertEquals(1, report.count(1))
        assertEquals(1, report.count(null as Int?))
    }

    @Test fun `what's paused where, and the holds`() {
        assertEquals(setOf(Palm.UP), report.paused(0, Gesture.INDEX_TRIPLE, Palm.UP)!!.positions)
        assertNull(report.paused(0, Gesture.INDEX_TRIPLE, null))
        assertEquals(Builtin.PREVIOUS, report.pausedSlider(1, Part.MIDDLE, 0)!!.control)
        assertTrue(report.holdWaits(0, Part.INDEX).isEmpty())
        assertEquals(Palm.entries.toSet(), report.holdWaits(1, Part.INDEX))
        assertEquals(setOf(Palm.UP), report.holdWaits(1, Part.MIDDLE))
        assertEquals(listOf("no-way-out"), report.warnings)
        assertEquals(listOf("band next"), report.invalid)
        assertEquals(0, Report("not json").count(Builtin.CONTROLS))
        assertEquals("bad-json", Report("""{"error":"bad-json"}""").error)
        assertNull(report.error)
    }

    @Test fun `band choices read with what they'd pause`() {
        val choices = Report.choices("""[{"gesture":"index_double","pauses":[{"control":"previous","gesture":"index_double","place":"calls","palm":null,"positions":["down"]}],"sliders":[]},
            {"gesture":"swipe_left","pauses":[],"sliders":[]}]""")
        assertEquals(listOf(Gesture.INDEX_DOUBLE, Gesture.SWIPE_LEFT), choices.map { it.gesture })
        assertEquals(1, choices[0].count)
        assertNull(choices[0].pauses[0].place)
        assertEquals(0, choices[1].count)
    }
}
