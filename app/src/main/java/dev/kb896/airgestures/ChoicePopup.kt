package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A list over the text field being written in, the pick highlighted: a word's suggestions
 * (above the field, or below it near the top), or the edit menu (just above the keyboard). It
 * only shows; the band's swipes move the pick and a tap takes it (see WriteService). Drawn by
 * the accessibility service, so it needs no other permission and takes no touches. Long lists
 * show the [WINDOW] entries around the pick.
 */
class ChoicePopup(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var shown: Triple<List<String>, Int, String>? = null

    private fun dp(value: Float) = Cb.dp(service, value)

    /** Show [items] with [pick] highlighted, under [hint]; over the keyboard when [aboveKeyboard]. */
    fun show(field: AccessibilityNodeInfo, items: List<String>, pick: Int, hint: String, aboveKeyboard: Boolean = false) {
        if (shown == Triple(items, pick, hint) && panel != null) return
        if (shown?.third != hint) hide()
        shown = Triple(items, pick, hint)
        val panel = panel ?: LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(Cb.PAGE)
                setStroke(dp(1f).toInt(), Cb.INK_400)
            }
            elevation = dp(8f)
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        }.also { add(it, field, aboveKeyboard) }
        panel.removeAllViews()
        panel.addView(Cb.label(service, hint).apply {
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(6f).toInt())
        })
        val first = (pick - WINDOW / 2).coerceIn(0, (items.size - WINDOW).coerceAtLeast(0))
        for (i in first until minOf(items.size, first + WINDOW)) {
            panel.addView(TextView(service).apply {
                text = items[i]
                textSize = 18f
                typeface = Cb.font(service, if (i == pick) R.font.geist_semibold else R.font.geist_medium)
                setTextColor(if (i == pick) android.graphics.Color.WHITE else Cb.INK_900)
                setPadding(dp(14f).toInt(), dp(7f).toInt(), dp(14f).toInt(), dp(7f).toInt())
                if (i == pick) background = GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(Cb.INK_900)
                }
            })
        }
    }

    fun hide() {
        panel?.let { runCatching { windows.removeView(it) } }
        panel = null
        shown = null
    }

    /** Above the keyboard (or the screen's bottom), or else above the field / below it near the top. */
    private fun add(view: LinearLayout, field: AccessibilityNodeInfo, aboveKeyboard: Boolean) {
        val bounds = Rect().also(field::getBoundsInScreen)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        if (aboveKeyboard) {
            val keyboard = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { Rect().also(it::getBoundsInScreen) }
            val screen = service.resources.displayMetrics.heightPixels
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = (screen - (keyboard?.top ?: screen)) + dp(8f).toInt()
        } else {
            val above = bounds.top > dp(320f)
            params.gravity = Gravity.TOP or Gravity.START
            params.x = bounds.left.coerceAtLeast(dp(8f).toInt())
            params.y = if (above) (bounds.top - dp(300f).toInt()).coerceAtLeast(0) else bounds.bottom + dp(6f).toInt()
        }
        windows.addView(view, params)
        panel = view
    }

    companion object {
        /** The most entries shown at once. */
        const val WINDOW = 7
    }
}
