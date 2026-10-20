package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepBatchTest {
    @Test fun `the first step goes out at once`() {
        val batch = StepBatch(250)
        assertEquals(1, batch.add(+1, now = 1_000))
    }

    @Test fun `steps inside the window wait and add up, both ways`() {
        val batch = StepBatch(250)
        batch.add(+1, now = 1_000)
        assertNull(batch.add(+1, now = 1_050))
        assertNull(batch.add(+1, now = 1_100))
        assertNull(batch.add(-1, now = 1_150))
        assertEquals(1_250, batch.due())
        assertEquals(1, batch.take(now = 1_250))
    }

    @Test fun `after the window a step goes out at once again`() {
        val batch = StepBatch(250)
        batch.add(+1, now = 1_000)
        assertEquals(-1, batch.add(-1, now = 1_300))
    }

    @Test fun `steps that cancel out send nothing`() {
        val batch = StepBatch(250)
        batch.add(+1, now = 1_000)
        batch.add(+1, now = 1_100)
        batch.add(-1, now = 1_200)
        assertEquals(0, batch.take(now = 1_250))
    }
}
