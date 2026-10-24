package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A word's suggestions in a small panel over the text field being written in (above it, or
 * below when there's no room), the pick highlighted. It only shows; the band's swipes move
 * the pick and a tap takes it (see WriteService). Drawn by the accessibility service, so it
 * needs no other permission and takes no touches.
 */
class ChoicePopup(private val service: AccessibilityService) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var panel: LinearLayout? = null
    private var shown: Pair<List<String>, Int>? = null

    private fun dp(value: Float) = Cb.dp(service, value)

    fun show(field: AccessibilityNodeInfo, choices: List<String>, pick: Int) {
        if (shown == choices to pick && panel != null) return
        shown = choices to pick
        val panel = panel ?: LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(14f)
                setColor(Cb.PAGE)
                setStroke(dp(1f).toInt(), Cb.INK_400)
            }
            elevation = dp(8f)
            setPadding(dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt(), dp(6f).toInt())
        }.also { add(it, field) }
        panel.removeAllViews()
        panel.addView(Cb.label(service, "Swipe to choose · tap to take · up to cancel").apply {
            setPadding(dp(10f).toInt(), dp(4f).toInt(), dp(10f).toInt(), dp(6f).toInt())
        })
        choices.forEachIndexed { i, word ->
            panel.addView(TextView(service).apply {
                text = word
                textSize = 18f
                typeface = Cb.font(service, if (i == pick) R.font.geist_semibold else R.font.geist_medium)
                setTextColor(if (i == pick) android.graphics.Color.WHITE else Cb.INK_900)
                setPadding(dp(14f).toInt(), dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt())
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

    /** Put the panel above the field, or below it when the field is near the top. */
    private fun add(view: LinearLayout, field: AccessibilityNodeInfo) {
        val bounds = Rect().also(field::getBoundsInScreen)
        val above = bounds.top > dp(260f)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left.coerceAtLeast(dp(8f).toInt())
            y = if (above) (bounds.top - dp(240f).toInt()).coerceAtLeast(0) else bounds.bottom + dp(6f).toInt()
        }
        windows.addView(view, params)
        panel = view
    }
}
