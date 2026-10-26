package dev.kb896.airgestures

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `another app or a screen of ours ends a field, the keyboard and our pop-ups over it don't`() {
        val own = "dev.kb896.airgestures"
        assertTrue(FieldWriting.leaves("com.other", app = "com.chat", own = own, ownScreen = false))
        // A lesson opening over the chat: its gestures aren't the field's.
        assertTrue(FieldWriting.leaves(own, app = "com.chat", own = own, ownScreen = true))
        // The word choices' pop-up or the draw pad over the field.
        assertFalse(FieldWriting.leaves(own, app = "com.chat", own = own, ownScreen = false))
        assertFalse(FieldWriting.leaves("com.chat", app = "com.chat", own = own, ownScreen = false))
        assertFalse(FieldWriting.leaves("com.android.systemui", app = "com.chat", own = own, ownScreen = false))
        assertFalse(FieldWriting.leaves("com.google.android.inputmethod.latin", app = "com.chat", own = own, ownScreen = false))
        assertFalse(FieldWriting.leaves("com.swiftkey.keyboard", app = "com.chat", own = own, ownScreen = false))
        // Writing in a field of ours: our own screens are its app.
        assertFalse(FieldWriting.leaves(own, app = own, own = own, ownScreen = true))
    }
}
