package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawingsTest {
    private val stroke = listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0)
    private val bulb = Drawing("Bulb", List(3) { listOf(stroke) })

    @Test fun `the library round-trips as the bridge reads it`() {
        val library = DrawingLibrary(listOf(bulb))
        val json = org.json.JSONArray(library.toJson()).getJSONObject(0)
        assertEquals("Bulb", json.getString("name"))
        // tries → strokes → points → [x, y]
        assertEquals(10.0, json.getJSONArray("tries").getJSONArray(2).getJSONArray(0).getJSONArray(1).getDouble(0), 0.0)
        assertEquals(library, DrawingLibrary.fromJson(library.toJson()))
    }

    @Test fun `a session's strokes read as a try`() {
        assertEquals(listOf(listOf(0.0 to 0.0, 1.5 to 2.0)), DrawingLibrary.strokes("[[[0.0,0.0],[1.5,2.0]]]"))
    }

    @Test fun `teaching a drawing again replaces it, rename and delete follow the name`() {
        var library = DrawingLibrary(listOf(bulb, Drawing("Z", bulb.tries)))
        library = library.with(Drawing("Bulb", listOf(listOf(stroke))))
        assertEquals(1, library.get("Bulb")!!.tries.size)
        assertEquals(listOf("Bulb", "Z"), library.names)
        library = library.renamed("Z", "Zed").without("Bulb")
        assertEquals(listOf("Zed"), library.names)
        assertTrue(library.without("Zed").drawings.isEmpty())
    }
}
