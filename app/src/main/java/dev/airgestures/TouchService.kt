package dev.airgestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service that turns band gestures into screen gestures:
 * a vertical swipe through the middle of the screen, or Back / Home / Recents.
 * It performs gestures only; it reads nothing on screen.
 */
class TouchService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** A finger moving up (content scrolls down) or down, over 40% of the screen's height. */
    fun swipe(up: Boolean) {
        val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val x = bounds.exactCenterX()
        val (from, to) = bounds.height() * .7f to bounds.height() * .3f
        val path = Path().apply {
            moveTo(x, if (up) from else to)
            lineTo(x, if (up) to else from)
        }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build(),
            null,
            null,
        )
    }

    companion object {
        /** Set while the user has the service switched on. */
        var instance: TouchService? = null
            private set
    }
}
