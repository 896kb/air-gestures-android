package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.PathInterpolator

/**
 * Accessibility navigation: moves a coral outline between what's on screen and presses,
 * types into or scrolls it. Unlike the screen-gestures service it reads the screen's layout
 * (which items there are and where), only to find what can be focused; nothing is kept or
 * leaves the phone. A separate service, so only those who navigate switch it on.
 */
class NavigateService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var mark: Navigation.Mark? = null
    private var ring: FocusRing? = null
    private val hide = Runnable { ring?.hide() }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        stop()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun stop() {
        main.removeCallbacksAndMessages(null)
        ring?.remove()
        ring = null
        instance = null
    }

    /** The screen changed: keep the outline on its item if it's still there, else let it go. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (ring?.showing != true) return
        main.removeCallbacks(follow)
        main.postDelayed(follow, 120)
    }

    private val follow = Runnable {
        val items = items()
        val at = Navigation.find(items, mark)
        if (at < 0) ring?.hide() else ring?.show(items[at].bounds)
    }

    override fun onInterrupt() = Unit

    /** A screen element backed by the real node; its children are read when walked. */
    private class Item(val info: AccessibilityNodeInfo) : Navigation.NavNode {
        val bounds = Rect().also(info::getBoundsInScreen)
        override val id: String? get() = info.viewIdResourceName
        override val text: String? get() = (info.text ?: info.contentDescription)?.toString()
        override val left get() = bounds.left
        override val top get() = bounds.top
        override val right get() = bounds.right
        override val bottom get() = bounds.bottom
        override val clickable get() = info.isClickable
        override val editable get() = info.isEditable
        override val checkable get() = info.isCheckable
        override val visible get() = info.isVisibleToUser
        override val enabled get() = info.isEnabled
        override val children: List<Item> by lazy { (0 until info.childCount).mapNotNull { info.getChild(it)?.let(::Item) } }
    }

    @Suppress("UNCHECKED_CAST")
    private fun items(): List<Item> {
        val root = rootInActiveWindow ?: return emptyList()
        return Navigation.focusables(Item(root)) as List<Item>
    }

    private fun current(): Item? {
        val items = items()
        return items.getOrNull(Navigation.find(items, mark))
    }

    /** The outline [by] items on (+1 next, −1 previous); shown for 10 s. */
    fun move(by: Int): String? {
        val items = items()
        if (items.isEmpty()) return "nothing to focus here"
        val item = items[Navigation.step(items.size, Navigation.find(items, mark), by)]
        mark = Navigation.Mark.of(item)
        outline(item.bounds)
        return null
    }

    /** Press the outlined item (a text field: focus it, so the keyboard comes up). */
    fun select(): String? {
        val item = current() ?: return "nothing is focused (focus next first)"
        if (item.editable) item.info.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val pressed = generateSequence(item.info) { it.parent }.firstOrNull { it.isClickable }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        outline(item.bounds)
        return if (pressed || item.editable) null else "couldn't press it"
    }

    /** Scroll the outlined item's list, or else the largest scrollable thing on screen. */
    fun scroll(forward: Boolean): String? {
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        val around = current()?.info?.let { node -> generateSequence(node) { it.parent }.firstOrNull { it.isScrollable } }
        val target = around ?: largestScrollable() ?: return "nothing scrolls here"
        return if (target.performAction(action)) null else "can't scroll further"
    }

    private fun largestScrollable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var best: AccessibilityNodeInfo? = null
        var area = 0
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isScrollable && node.isVisibleToUser) {
                val r = Rect().also(node::getBoundsInScreen)
                if (r.width() * r.height() > area) {
                    area = r.width() * r.height()
                    best = node
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::walk)
        }
        walk(root)
        return best
    }

    private fun outline(bounds: Rect) {
        val shown = ring ?: FocusRing(this).also { ring = it }
        shown.show(bounds)
        main.removeCallbacks(hide)
        main.postDelayed(hide, 10_000)
    }

    companion object {
        /** Set while the user has the navigation service switched on. */
        @Volatile
        var instance: NavigateService? = null
            private set
    }
}

/**
 * The focus outline: a glowing coral rounded rectangle around the focused item, gliding to
 * the next one. Drawn in an accessibility overlay that lets every touch through.
 */
@SuppressLint("ViewConstructor")
private class FocusRing(private val service: AccessibilityService) : View(service) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var attached = false
    var showing = false
        private set

    private fun dp(value: Float) = Cb.dp(service, value)

    private val coral = Cb.Signal.STOP.fill
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = coral
    }
    /** The glow: wider, fainter strokes outside the line. */
    private val glows = listOf(9f to .10f, 6.5f to .18f, 4.5f to .3f).map { (width, alpha) ->
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(width)
            color = (coral and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
        }
    }
    private val from = RectF()
    private val to = RectF()
    private val now = RectF()
    private val glide = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 180
        interpolator = PathInterpolator(.2f, 0f, 0f, 1f)
        addUpdateListener {
            val t = it.animatedValue as Float
            now.set(
                from.left + (to.left - from.left) * t,
                from.top + (to.top - from.top) * t,
                from.right + (to.right - from.right) * t,
                from.bottom + (to.bottom - from.bottom) * t,
            )
            invalidate()
        }
    }

    fun show(bounds: Rect) {
        if (!attached) attach()
        val pad = dp(4f)
        to.set(bounds.left - pad, bounds.top - pad, bounds.right + pad, bounds.bottom + pad)
        if (!showing || now.isEmpty) {
            now.set(to)
            from.set(to)
            invalidate()
        } else {
            from.set(now)
            glide.cancel()
            glide.start()
        }
        showing = true
        visibility = VISIBLE
    }

    fun hide() {
        showing = false
        visibility = INVISIBLE
    }

    fun remove() {
        glide.cancel()
        if (attached) runCatching { windows.removeView(this) }
        attached = false
        showing = false
    }

    private fun attach() {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        windows.addView(this, params)
        attached = true
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        // The window spans the screen from its corner, so screen pixels are view pixels.
        val at = IntArray(2).also(::getLocationOnScreen)
        canvas.save()
        canvas.translate(-at[0].toFloat(), -at[1].toFloat())
        val radius = dp(12f)
        for (glow in glows) canvas.drawRoundRect(now, radius, radius, glow)
        canvas.drawRoundRect(now, radius, radius, line)
        canvas.restore()
    }
}
