package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonsBackupTest {
    private val defaults = Gesture.entries.associate { it.key to it.default.id }

    @Test fun `one profile and a working dial leave out the triple taps`() {
        val steps = Lessons.steps(profiles = 1, dial = true, map = defaults, band = BandControls.standard())
        assertEquals(10, steps.size)
        assertTrue(steps.any { it.done == Lessons.Done.Dial })
        assertFalse(steps.any { it.gesture == Gesture.INDEX_TRIPLE || it.gesture == Gesture.MIDDLE_TRIPLE })
    }

    @Test fun `two profiles add the triple taps and a dial set to nothing is left out`() {
        val steps = Lessons.steps(profiles = 2, dial = false, map = defaults, band = BandControls.standard())
        assertEquals(11, steps.size)
        assertFalse(steps.any { it.done == Lessons.Done.Dial })
        assertEquals(
            listOf("index triple tap", "middle triple tap"),
            steps.mapNotNull { (it.done as? Lessons.Done.Gesture)?.label?.takeIf { label -> "triple" in label } },
        )
    }

    @Test fun `a triple tap is practised only where it does something`() {
        val none = BandControls()
        val one = Lessons.steps(1, true, defaults + ("index_triple" to "media.next"), none).map { it.gesture }
        assertTrue(Gesture.INDEX_TRIPLE in one)
        assertFalse(Gesture.MIDDLE_TRIPLE in one)
        // Next profile on it with one profile can't act, and the profile's action is paused.
        val paused = Lessons.steps(1, true, defaults + ("index_triple" to "media.next"), BandControls.standard()).map { it.gesture }
        assertFalse(Gesture.INDEX_TRIPLE in paused)
        val two = Lessons.steps(2, true, defaults, BandControls.standard()).map { it.gesture }
        assertTrue(Gesture.INDEX_TRIPLE in two && Gesture.MIDDLE_TRIPLE in two)
        assertTrue(Gesture.INDEX_HOLD in Lessons.steps(1, true, defaults + ("index_hold" to "media.next"), none).map { it.gesture })
    }

    @Test fun `a lesson step matches its gesture's label only, the hold on the middle hold`() {
        val step = Lessons.steps(1, true, defaults, BandControls.standard()).first { it.title.startsWith("Double-tap your index") }
        assertTrue(step.done.matches(gesture = "index double tap"))
        assertFalse(step.done.matches(gesture = "index tap"))
        val hold = Lessons.steps(1, true, defaults, BandControls.standard()).first { it.done == Lessons.Done.Hold }
        assertTrue(hold.done.matches(gesture = "middle hold"))
        assertFalse(hold.done.matches(action = "controls.toggle"))
    }

    private val profiles = ProfileList.migrated(mapOf("swipe_up" to "media.next", "swipe_left" to "tap:0.5000,0.2500"), "seek")
        .added(mapOf("swipe_up" to "volume.up"), "volume").first
        .renamed("p2", "Reading")
        .assigned("com.google.android.youtube", "p2")
        .step(+1)

    private val settings = Backup.Settings(
        profiles,
        calls = mapOf("index_double" to "call.answer", "swipe_up" to "-"),
        hand = "left",
        speak = true,
        vibrate = false,
        sequences = Sequences.Library(listOf(Sequences.Sequence("q1", "Morning", listOf("app:com.spotify.music", "wait:2000")))),
        band = BandControls.standard().with(Builtin.DRAW, BandControl(Gesture.SWIPE_RIGHT)),
        sharedDrawings = mapOf("Bulb" to "torch.toggle"),
        drawings = DrawingLibrary(listOf(Drawing("Bulb", listOf(listOf(listOf(0.0 to 0.0, 9.0 to 9.0)))))),
    )

    @Test fun `settings round-trip, tap spots and apps included`() =
        assertEquals(settings, Backup.read(Backup.write(settings)))

    @Test fun `a backup from before sequences leaves them out instead of empty`() {
        val old = Backup.write(settings.copy(sequences = null)).replace(Regex(",\\s*\"sequences\":[^\\]]*\\]"), "")
        assertNull(Backup.read(old)?.sequences)
    }

    @Test fun `a broken, empty or newer backup is refused`() {
        assertNull(Backup.read("not json"))
        assertNull(Backup.read("""{"version":1,"profiles":[],"picked":"p1","apps":{},"calls":{},"hand":"band"}"""))
        assertNull(Backup.read(Backup.write(settings).replace(Regex("\"version\":\\s*2"), "\"version\": 3")))
    }

    @Test fun `a version 1 backup restores with today's band controls and no built-in actions`() {
        val v1 = """{"version":1,"profiles":[{"id":"p1","name":"A","map":{"middle_hold":"controls.toggle","swipe_up":"media.next"},"dial":"volume"}],
            "picked":"p1","apps":{},"calls":{"index_double":"profile.next"},"hand":"band"}"""
        val restored = Backup.read(v1)!!
        assertEquals(BandControls.standard(), restored.band)
        assertEquals(mapOf("swipe_up" to "media.next"), restored.profiles.profile("p1")!!.map)
        assertFalse("index_double" in restored.calls)
        assertNull(restored.drawings)
    }
}
