package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldWritingTest {
    private val connected = BandService.State(phase = Phase.CONNECTED)

    @Test fun `a connected band with the controls on writes into fields`() {
        assertNull(FieldWriting.refusal(setting = true, state = connected))
    }

    @Test fun `the controls off, the setting off, no band or an update keep it out of fields`() {
        assertEquals("the controls are off", FieldWriting.refusal(true, connected.copy(paused = true)))
        assertEquals("the setting was turned off", FieldWriting.refusal(false, connected))
        assertEquals("the band disconnected", FieldWriting.refusal(true, connected.copy(phase = Phase.SEARCHING)))
        assertEquals("the band is updating", FieldWriting.refusal(true, connected.copy(otaBusy = true)))
    }
}
