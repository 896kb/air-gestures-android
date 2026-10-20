package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StrokesTest {
    @Test fun `a swipe round-trips, and bad ones are refused`() {
        val id = Strokes.swipe(.5f, .8f, .5f, .2f, 250)
        assertEquals("swipe:0.5000,0.8000,0.5000,0.2000,250", id)
        assertEquals(Strokes.Path(listOf(Strokes.Point(.5f, .8f, 0), Strokes.Point(.5f, .2f, 250))), Strokes.parse(id))
        assertNull(Strokes.parse("swipe:0.5,0.8,0.5"))
        assertNull(Strokes.parse("swipe:1.5,0.8,0.5,0.2,250"))
        assertNull(Strokes.parse("swipe:0.5,0.8,0.5,0.2,0"))
    }

    @Test fun `a drawn path keeps its shape and timing`() {
        val path = Strokes.Path(listOf(Strokes.Point(.1f, .5f, 0), Strokes.Point(.3f, .45f, 120), Strokes.Point(.6f, .5f, 300)))
        val id = Strokes.path(path)
        assertEquals("path:0.1000,0.5000,0|0.3000,0.4500,120|0.6000,0.5000,300", id)
        assertEquals(path, Strokes.parse(id))
        assertNull(Strokes.parse("path:0.1,0.5,0"))
        assertNull(Strokes.parse("path:0.1,0.5,100|0.2,0.5,50"))
    }

    @Test fun `a recording becomes taps, strokes and the waits between them`() {
        val touches = listOf(
            // A tap: hardly moves, short.
            Strokes.Path(listOf(Strokes.Point(.5f, .5f, 0), Strokes.Point(.501f, .5f, 90))) to 1_000L,
            // A swipe, 1.24 s after the tap ended.
            Strokes.Path(listOf(Strokes.Point(.5f, .8f, 0), Strokes.Point(.5f, .3f, 200))) to 2_330L,
        )
        assertEquals(
            listOf("tap:0.5005,0.5000", "wait:1200", "path:0.5000,0.8000,0|0.5000,0.3000,200"),
            Strokes.steps(touches),
        )
    }

    @Test fun `sequences round-trip and keep their steps in order`() {
        var library = Sequences.Library(emptyList())
        val (added, made) = library.added()
        library = added.renamed(made.id, "Morning").withSteps(made.id, listOf("app:com.spotify.music", "wait:2000", "tap:0.5000,0.4000"))
        assertEquals("q1", made.id)
        assertEquals("Sequence 1", made.name)
        assertEquals(library, Sequences.Library.fromJson(library.toJson()))
        assertEquals(listOf("wait:2000", "app:com.spotify.music", "tap:0.5000,0.4000"), library.moved("q1", 1, -1).get("q1")!!.steps)
        assertEquals(emptyList<Sequences.Sequence>(), library.deleted("q1").sequences)
    }
}
