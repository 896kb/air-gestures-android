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

    @Test fun `a deleted sequence's id isn't given to a new one`() {
        val (one, first) = Sequences.Library(emptyList()).added()
        val (again, second) = one.deleted(first.id).added()
        assertEquals("q1", first.id)
        assertEquals("q2", second.id)
        assertEquals("q3", Sequences.Library.fromJson(again.toJson()).added().second.id)
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

    @Test fun `a started sequence reads its id and profile, a plain id has none`() {
        assertEquals("q1" to null, Sequences.started("seq:q1"))
        assertEquals("q1" to "Music", Sequences.started("seq:q1/Music"))
        assertEquals("q12" to "Café: night/2", Sequences.started("seq:q12/Caf%C3%A9%3A+night%2F2"))
    }

    @Test fun `a sequence's automation steps send its name, the other steps run as they are`() {
        val steps = listOf("torch.toggle", "automation@d1", "wait:1000", "automation")
        assertEquals(
            listOf(null, Mappings.Automation.Sequence("Goodnight", "Music", "d1"), null, Mappings.Automation.Sequence("Goodnight", "Music", null)),
            steps.map { Sequences.stepAutomation("Goodnight", "Music", it) },
        )
        // Try it: no profile.
        assertEquals(Mappings.Automation.Sequence("Goodnight", null, "d1"), Sequences.stepAutomation("Goodnight", null, "automation@d1"))
    }
}
