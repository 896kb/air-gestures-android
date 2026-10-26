package dev.kb896.airgestures

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationBuzzTest {
    @Test fun `existing notifications and updates do not buzz`() {
        val buzz = NotificationBuzz()
        buzz.connected(mapOf("old" to 1L))
        assertFalse(buzz.posted("old", 1L, true))
        assertTrue(buzz.posted("message", 2L, true))
        assertFalse(buzz.posted("message", 2L, true))
        assertTrue(buzz.posted("message", 3L, true))
        assertFalse(buzz.posted("message", 4L, true, onlyOnce = true))
        buzz.removed("message")
        assertTrue(buzz.posted("message", 4L, true))
    }

    @Test fun `an ongoing notification becoming an alert does not repeat`() {
        val buzz = NotificationBuzz()
        assertFalse(buzz.posted("ongoing", 1L, false))
        assertFalse(buzz.posted("ongoing", 1L, true))
        buzz.connected(mapOf("new" to 2L))
        assertFalse(buzz.posted("new", 2L, true))
    }
}
