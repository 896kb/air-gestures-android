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

    @Test fun `only what a move would newly pause asks, and a slider paused twice counts once`() {
        val now = Report("""{"paused":[{"control":"draw","gesture":"middle_double","place":0,"palm":null,"positions":["up"]}],
            "paused_sliders":[{"control":"previous","gesture":"middle_drag_left","profile":1,"finger":"middle","pair":0,"positions":["down"]},
                              {"control":"next","gesture":"middle_drag_right","profile":1,"finger":"middle","pair":0,"positions":["up"]}]}""")
        assertEquals(1, now.count(1))
        assertEquals(setOf(Palm.DOWN, Palm.UP), now.pausedSlider(1, Part.MIDDLE, 0)!!.positions)
        val move = Report.choices("""[{"gesture":"middle_double","pauses":[
            {"control":"draw","gesture":"middle_double","place":0,"palm":null,"positions":["up"]},
            {"control":"draw","gesture":"middle_double","place":0,"palm":null,"positions":["down","up"]},
            {"control":"draw","gesture":"middle_double","place":"calls","palm":null,"positions":["up"]}],"sliders":[
            {"control":"draw","gesture":"middle_drag_left","profile":1,"finger":"middle","pair":0,"positions":["down"]}]}]""")[0]
        // Already paused palm up there: not new; palm down there is; During calls is; the slider isn't.
        assertEquals(listOf(false, true, true), move.pauses.map { now.isNew(it) })
        assertFalse(now.isNew(move.sliders[0]))
        assertEquals(2, now.newCount(move))
        assertEquals("bad-json", Report.choicesError("""{"error":"bad-json"}"""))
        assertNull(Report.choicesError("[]"))
    }
}
