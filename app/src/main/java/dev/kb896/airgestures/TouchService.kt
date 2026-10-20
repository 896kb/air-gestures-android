package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service that turns band gestures into screen gestures:
 * a swipe through the middle of the screen, a tap at a chosen spot, or Back /
 * Home / Recents. It also opens mapped apps and the voice assistant, which Android
 * allows an accessibility service from the background, and shows the overlay that
 * picks a tap spot. For per-app profiles it notices which app is in front (the
 * package name only). It performs gestures only; it reads nothing on screen.
 */
class TouchService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        picker?.cancel()
        instance = null
        // Without the service nothing says which app is in front: no app's profile applies.
        BandService.foreground(this, null)
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        picker?.cancel()
        instance = null
        BandService.foreground(this, null)
        return super.onUnbind(intent)
    }

    /**
     * Which app is in front, for per-app profiles: only the package of a window that came
     * up, never its content. The notification shade, the keyboard and system pop-ups don't
     * count, so they don't flip the profile; the home screen does (leaving an app).
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (counts(pkg)) BandService.foreground(this, pkg)
    }

    private val counted = mutableMapOf<String, Boolean>()

    private fun counts(pkg: String): Boolean {
        if (pkg == "com.android.systemui") return false
        val keyboard = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
        if (pkg == keyboard) return false
        return counted.getOrPut(pkg) {
            pkg == packageName || pkg == home() || packageManager.getLaunchIntentForPackage(pkg) != null
        }
    }

    private fun home() = packageManager.resolveActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
        0,
    )?.activityInfo?.packageName
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

    /**
     * Set up a screen action over any app (a tap spot, a swipe, a drawn swipe, a recording);
     * one already showing is replaced. [onDone] gets the action ids, or null when cancelled.
     */
    fun pick(mode: SpotPicker.Mode, current: String?, onDone: (List<String>?) -> Unit) {
        picker?.drop()
        picker = SpotPicker(this, mode, current) { ids ->
            picker = null
            onDone(ids)
        }.also { it.show() }
    }

    /**
     * A finger along [path] (fractions of the screen) in its time: a Swipe here, a drawn swipe
     * or a recorded touch. The finger moves at an even speed along the path. [done] runs when
     * it's over (or couldn't start).
     */
    fun stroke(path: Strokes.Path, done: (() -> Unit)? = null) {
        val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val line = Path()
        path.points.forEachIndexed { i, point ->
            val x = bounds.width() * point.x
            val y = bounds.height() * point.y
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        val duration = path.duration.coerceIn(1, GestureDescription.getMaxGestureDuration())
        val started = dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(line, 0, duration)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    done?.invoke()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done?.invoke()
                }
            },
            null,
        )
        if (!started) done?.invoke()
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
