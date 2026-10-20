package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonsBackupTest {
    @Test fun `one profile and a working dial leave out the triple taps`() {
        val steps = Lessons.steps(profiles = 1, dial = true)
        assertEquals(10, steps.size)
        assertTrue(steps.any { it.done == Lessons.Done.Dial })
        assertFalse(steps.any { it.done is Lessons.Done.Action })
    }

    @Test fun `two profiles add the triple taps and a dial set to nothing is left out`() {
        val steps = Lessons.steps(profiles = 2, dial = false)
        assertEquals(11, steps.size)
        assertFalse(steps.any { it.done == Lessons.Done.Dial })
        assertEquals(
            listOf("profile.next", "profile.previous"),
            steps.mapNotNull { (it.done as? Lessons.Done.Action)?.action },
        )
    }

    @Test fun `a lesson step matches its gesture's label only`() {
        val step = Lessons.steps(1, true).first { it.title.startsWith("Double-tap your index") }
        assertTrue(step.done.matches(gesture = "index double tap"))
        assertFalse(step.done.matches(gesture = "index tap"))
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
    )

    @Test fun `settings round-trip, tap spots and apps included`() =
        assertEquals(settings, Backup.read(Backup.write(settings)))

    @Test fun `a broken, empty or newer backup is refused`() {
        assertNull(Backup.read("not json"))
        assertNull(Backup.read("""{"version":1,"profiles":[],"picked":"p1","apps":{},"calls":{},"hand":"band"}"""))
        assertNull(Backup.read(Backup.write(settings).replace(Regex("\"version\":\\s*1"), "\"version\": 2")))
    }
}
