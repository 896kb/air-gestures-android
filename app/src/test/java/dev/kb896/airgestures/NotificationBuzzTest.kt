package dev.kb896.airgestures

import android.app.Notification
import android.app.NotificationManager
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

    private fun alert(
        own: Boolean = false,
        ongoing: Boolean = false,
        summary: Boolean = false,
        importance: Int = NotificationManager.IMPORTANCE_DEFAULT,
        interrupts: Boolean = true,
        media: Boolean = false,
        category: String? = Notification.CATEGORY_MESSAGE,
    ) = buzzes(own, ongoing, summary, importance, interrupts, media, category)

    @Test fun `only alerts the phone would make buzz`() {
        assertTrue(alert())
        assertTrue(alert(importance = NotificationManager.IMPORTANCE_HIGH, category = null))
        assertFalse(alert(own = true))
        assertFalse(alert(ongoing = true))
        assertFalse(alert(summary = true))
        assertFalse(alert(importance = NotificationManager.IMPORTANCE_LOW))
        assertFalse(alert(importance = NotificationManager.IMPORTANCE_MIN))
        assertFalse(alert(interrupts = false))
    }

    @Test fun `player and progress updates do not buzz`() {
        assertFalse(alert(media = true))
        assertFalse(alert(category = Notification.CATEGORY_TRANSPORT))
        assertFalse(alert(category = Notification.CATEGORY_PROGRESS))
    }
}
