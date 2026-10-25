package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun `an own action of nothing is the shared one, stale names are pruned`() {
        assertEquals("torch.toggle", DrawingLibrary.action(own = mapOf("Bulb" to ""), shared = mapOf("Bulb" to "torch.toggle"), name = "Bulb"))
        assertEquals("media.next", DrawingLibrary.action(own = mapOf("Bulb" to "media.next"), shared = mapOf("Bulb" to "torch.toggle"), name = "Bulb"))
        assertEquals("", DrawingLibrary.action(own = emptyMap(), shared = emptyMap(), name = "Bulb"))
        val list = ProfileList(listOf(Profile("p1", "A", emptyMap(), "volume", drawings = mapOf("Bulb" to "x", "Gone" to "y", "Empty" to ""))), "p1", emptyMap())
        val pruned = list.prunedDrawings(setOf("Bulb", "Empty"))
        assertEquals(mapOf("Bulb" to "x"), pruned.profile("p1")!!.drawings)
        assertEquals(mapOf("Bulb" to "z"), DrawingLibrary.pruned(mapOf("Bulb" to "z", "Gone" to "w", "Empty" to ""), setOf("Bulb", "Empty")))
    }

    /** A drawings file in memory: null is no file yet. */
    private class FakeFile(var text: String?) {
        var brokenCopies = 0
        val store = DrawingStore(read = { text }, write = { text = it }, keepBroken = { brokenCopies++ })
    }

    @Test fun `an unreadable drawings file is kept, never written over, and nothing is pruned from it`() {
        val file = FakeFile("not json")
        assertTrue(file.store.load().drawings.isEmpty())
        assertFalse(file.store.readable)
        assertEquals(1, file.brokenCopies)
        assertEquals(null, file.store.update { it.with(bulb) })
        assertEquals("not json", file.text)
        assertTrue(file.store.problem!!.contains("drawings.broken"))
        // A backup's library replaces it on purpose (the broken copy is kept): readable again.
        file.store.replace(DrawingLibrary(listOf(bulb)))
        assertTrue(file.store.readable)
        assertEquals(listOf("Bulb"), DrawingLibrary.fromJson(file.text!!).names)
    }

    @Test fun `a readable or missing file loads and updates`() {
        val none = FakeFile(null)
        assertTrue(none.store.load().drawings.isEmpty() && none.store.readable)
        assertEquals(listOf("Bulb"), none.store.update { it.with(bulb) }!!.names)
        assertEquals(listOf("Bulb"), FakeFile(none.text).store.load().names)
    }
}
