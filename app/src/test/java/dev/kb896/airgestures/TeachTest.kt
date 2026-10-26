package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeachTest {
    @Test fun `a still palm is kept for two seconds once it settled`() {
        val s = PalmSampler()
        for (t in 0..3000L step 50) s.add(-142.0 + (t % 100) / 200.0, t)
        assertTrue(s.done)
        assertTrue(s.rolls.size >= 40)
    }

    @Test fun `a move or a missing reading starts again`() {
        val s = PalmSampler()
        for (t in 0..1500L step 50) s.add(-142.0, t)
        assertTrue(s.progress > 0f)
        s.add(-100.0, 1550)
        assertEquals(0f, s.progress)
        for (t in 1600..2500L step 50) s.add(-100.0, t)
        s.add(null, 2550)
        assertEquals(0f, s.progress)
        assertEquals(2.0, PalmSampler.angle(179.0 - -179.0), 1e-9)
    }

    @Test fun `teach your drags, one sample per step, the bridge's JSON, and what to redo`() {
        val t = DragTeach()
        assertEquals(DragTeach.Step.TURN, t.current)
        repeat(7) { t.sample(doubleArrayOf(0.0, 1.0, it.toDouble()), -142.3) }
        assertNull(t.current)
        val json = org.json.JSONObject(t.json()!!)
        assertEquals(-142.3, json.getDouble("roll"), 0.0)
        assertEquals(6, json.getJSONArray("drags").length())
        val (redo, message) = t.refused("too-alike left right")
        assertEquals(listOf(DragTeach.Step.LEFT, DragTeach.Step.RIGHT), redo)
        assertTrue("Drag left" in message)
        assertEquals(DragTeach.Step.LEFT, t.current)
        assertNull(t.json())
    }

    @Test fun `teach a drawing, three tries, the bridge's refusals`() {
        val t = DrawTeach()
        val stroke = listOf(listOf(0.0 to 0.0, 9.0 to 9.0))
        repeat(3) { t.add(stroke) }
        assertTrue(t.full)
        assertNull(t.refused(""))
        assertEquals("Try 2 was too small: draw it bigger.", t.refused("too-small 1"))
        assertEquals(2, t.tries.size)
        assertTrue(t.refused("not-alike 0 1")!!.startsWith("These don't look alike"))
        assertTrue(t.tries.isEmpty())
        assertTrue(DrawTeach.nameFree("Bulb", listOf("Zed"), own = null))
        assertFalse(DrawTeach.nameFree("zed", listOf("Zed"), own = null))
        assertTrue(DrawTeach.nameFree("Zed", listOf("Zed"), own = "Zed"))
        assertFalse(DrawTeach.nameFree("  ", emptyList(), own = null))
    }

    @Test fun `a gap between readings starts the hold again`() {
        val s = PalmSampler()
        for (t in 0..1500L step 50) s.add(-142.0, t)
        assertTrue(s.progress > 0f)
        // A stall (or the screen paused): 300 ms without a reading.
        s.add(-142.0, 1800)
        assertEquals(0f, s.progress)
        for (t in 1850..4500L step 50) s.add(-142.0, t)
        assertTrue(s.done)
    }

    @Test fun `palm up is sampled only once the hand has turned away from palm down`() {
        val down = PalmSampler.mean(doubleArrayOf(-141.0, -143.0))
        assertEquals(-142.0, down, 1e-9)
        assertEquals(180.0, PalmSampler.mean(doubleArrayOf(179.0, -179.0)).let { Math.abs(it) }, 1e-9)
        val up = PalmSampler(awayFrom = down)
        for (t in 0..3000L step 50) up.add(-130.0, t)
        assertEquals(0f, up.progress)
        assertTrue(up.waiting)
        for (t in 3050..6000L step 50) up.add(-12.0, t)
        assertFalse(up.waiting)
        assertTrue(up.done)
    }

    @Test fun `a drag sample says whether it filled a step, and reset clears them all`() {
        val t = DragTeach()
        repeat(7) { assertTrue(t.sample(doubleArrayOf(0.0, 1.0, it.toDouble()), -142.3)) }
        assertFalse("all done: nothing filled", t.sample(doubleArrayOf(1.0, 0.0, 0.0), -142.3))
        t.reset()
        assertEquals(DragTeach.Step.TURN, t.current)
        assertNull(t.json())
    }

    @Test fun `renaming by case alone is allowed`() {
        assertTrue(DrawTeach.nameFree("bulb", listOf("Bulb", "Zed"), own = "Bulb"))
        assertFalse(DrawTeach.nameFree("zed", listOf("Bulb", "Zed"), own = "Bulb"))
    }

    @Test fun `a new drawing is called Drawing #N, the lowest number free`() {
        assertEquals("Drawing #1", DrawTeach.defaultName(emptyList()))
        assertEquals("Drawing #1", DrawTeach.defaultName(listOf("Bulb", "Drawing #2")))
        assertEquals("Drawing #3", DrawTeach.defaultName(listOf("Drawing #1", "drawing #2")))
        assertEquals("Drawing #2", DrawTeach.defaultName(listOf("Drawing #1", "Drawing #3")))
    }

    @Test fun `Save says what it waits for`() {
        fun blocked(tries: Int = 3, alike: Boolean = true, checkFailed: Boolean = false, name: String = "Bulb", free: Boolean = true, problem: String? = null) =
            DrawTeach.saveBlocked(tries, alike, checkFailed, name, free, problem)
        assertNull(blocked())
        assertEquals("A drawing needs a name: type one in Name.", blocked(name = " "))
        assertEquals("Draw it 2 more times to save.", blocked(tries = 1))
        assertEquals("Draw it 1 more time to save.", blocked(tries = 2))
        assertEquals("Save waits until three tries look alike.", blocked(alike = false))
        assertEquals("Save waits until the drawings can be checked: Start again to retry.", blocked(alike = false, checkFailed = true))
        assertEquals("Another drawing is called Bulb: pick another name.", blocked(free = false))
        assertEquals("broken", blocked(problem = "broken"))
    }
}
