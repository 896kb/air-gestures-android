package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service that turns band gestures into screen gestures:
 * a swipe through the middle of the screen, a tap at a chosen spot, or Back /
 * Home / Recents. It also opens mapped apps and the voice assistant, which Android
 * allows an accessibility service from the background, and shows the overlay that
 * picks a tap spot. It performs gestures only; it reads nothing on screen.
 */
class TouchService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        picker?.cancel()
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        picker?.cancel()
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
        stroke(Path().apply {
            moveTo(x, if (up) from else to)
            lineTo(x, if (up) to else from)
        })
    }

    /**
     * A finger moving left (the next page, photo or story) or right, over 50% of
     * the screen's width; it starts away from the edges so it's never a system Back.
     */
    fun swipeSideways(left: Boolean) {
        val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val y = bounds.exactCenterY()
        val (from, to) = bounds.width() * .75f to bounds.width() * .25f
        stroke(Path().apply {
            moveTo(if (left) from else to, y)
            lineTo(if (left) to else from, y)
        })
    }

    /** A short press at a spot given as fractions of the screen ("Tap here"). */
    fun tap(x: Float, y: Float) {
        val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val path = Path().apply { moveTo(bounds.width() * x, bounds.height() * y) }
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build(),
            null,
            null,
        )
    }

    /** Android lets an enabled accessibility service start activities from the background. */
    fun startFromBackground(intent: Intent): Boolean =
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess

    private var picker: SpotPicker? = null

    /** Let the user pick a "Tap here" spot over any app; a pick already showing is cancelled. */
    fun pickSpot(start: Pair<Float, Float>?, onPicked: (Pair<Float, Float>?) -> Unit) {
        picker?.cancel()
        picker = SpotPicker(this, start) { spot ->
            picker = null
            onPicked(spot)
        }.also { it.show() }
    }

    private fun stroke(path: Path) {
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
