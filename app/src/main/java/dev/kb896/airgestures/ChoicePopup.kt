package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A row of choices over the text field being written in, the pick highlighted: a word's
 * suggestions (above the field, or below it near the top), or the edit menu (just above the
 * keyboard). It only shows; the band's left/right swipes move the pick and a tap takes it (see
 * WriteService). Drawn by the accessibility service, so it needs no other permission and takes
 * no touches. A row wider than the screen scrolls to keep the pick in view.
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
        val chips = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        val views = items.mapIndexed { i, item ->
            TextView(service).apply {
                text = item
                textSize = 17f
                maxLines = 1
                typeface = Cb.font(service, if (i == pick) R.font.geist_semibold else R.font.geist_medium)
                setTextColor(if (i == pick) android.graphics.Color.WHITE else Cb.INK_900)
                setPadding(dp(12f).toInt(), dp(7f).toInt(), dp(12f).toInt(), dp(7f).toInt())
                if (i == pick) background = GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(Cb.INK_900)
                }
            }.also(chips::addView)
        }
        val scroll = HorizontalScrollView(service).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        }
        panel.addView(scroll)
        // Once laid out, scroll so the pick sits in the middle where it can.
        views.getOrNull(pick)?.let { chip ->
            scroll.post { scroll.scrollTo(chip.left + chip.width / 2 - scroll.width / 2, 0) }
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
        val margin = dp(8f).toInt()
        val params = WindowManager.LayoutParams(
            service.resources.displayMetrics.widthPixels - 2 * margin,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        val screen = service.resources.displayMetrics.heightPixels
        if (aboveKeyboard) {
            val keyboard = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { Rect().also(it::getBoundsInScreen) }
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = (screen - (keyboard?.top ?: screen)) + margin
        } else if (bounds.top > dp(140f)) {
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = screen - bounds.top + dp(6f).toInt()
        } else {
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = bounds.bottom + dp(6f).toInt()
        }
        windows.addView(view, params)
        panel = view
    }
}
