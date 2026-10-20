package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Picks the spot for "Tap here", over whatever app is open: first a bubble while the
 * user opens the app ("Open the app, then tap here"), then a full-screen layer with a
 * target to drag onto the spot. Accessibility overlays need no extra permission.
 * [done] gets the spot as fractions of the screen, or null when cancelled.
 */
class SpotPicker(
    private val service: AccessibilityService,
    private val start: Pair<Float, Float>?,
    private val done: (Pair<Float, Float>?) -> Unit,
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private var shown: View? = null
    private var finished = false

    private fun dp(value: Float) = Cb.dp(service, value)
    private fun dpi(value: Float) = dp(value).toInt()

    fun show() = put(bubble(), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dpi(56f))

    /** Gone without a spot (another pick started, or the service is going away). */
    fun cancel() = finish(null)

    private fun finish(spot: Pair<Float, Float>?) {
        if (finished) return
        finished = true
        remove()
        done(spot)
    }

    private fun remove() {
        shown?.let { runCatching { windows.removeView(it) } }
        shown = null
    }

    private fun put(view: View, size: Int, gravity: Int, y: Int = 0) {
        remove()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            this.y = y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        windows.addView(view, params)
        shown = view
    }

    /** A pill in the app's style: coral for the main choice, page-white for the other. */
    private fun pill(context: Context, text: String, main: Boolean, onClick: () -> Unit) =
        Cb.text(context, 14f, R.font.geist_semibold, if (main) Cb.Signal.STOP.label else Cb.INK_900, -.005f).apply {
            this.text = text
            isSingleLine = true
            gravity = Gravity.CENTER
            setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(if (main) Cb.Signal.STOP.fill else Cb.PAGE)
            }
            elevation = dp(6f)
            setOnClickListener { onClick() }
        }

    private fun pills(vararg views: TextView) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) marginStart = dpi(10f) })
        }
        setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
    }

    private fun bubble() = pills(
        pill(service, "Open the app, then tap here", main = true) {
            put(Target(service), WindowManager.LayoutParams.MATCH_PARENT, Gravity.TOP or Gravity.START)
        },
        pill(service, "Cancel", main = false) { cancel() },
    )

    /** The full-screen layer: a faint scrim, the target under the finger, Cancel and Set. */
    @SuppressLint("ViewConstructor")
    private inner class Target(context: Context) : FrameLayout(context) {
        private val bounds = windows.currentWindowMetrics.bounds
        /** The target's centre, in screen pixels. */
        private var x = bounds.width() * (start?.first ?: .5f)
        private var y = bounds.height() * (start?.second ?: .5f)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            color = Cb.Signal.STOP.fill
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(6f)
            color = 0xCCFFFFFF.toInt()
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Cb.INK_900 }

        init {
            setWillNotDraw(false)
            setBackgroundColor(0x26111214) // 15% ink
            addView(pills(
                Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply {
                    text = "Drag the target onto the spot"
                    setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
                    background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(Cb.PAGE) }
                },
            ), LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(56f) })
            addView(pills(
                pill(context, "Cancel", main = false) { cancel() },
                pill(context, "Set", main = true) { finish(x / bounds.width() to y / bounds.height()) },
            ), LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(56f) })
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // The window covers the whole screen from its corner, so screen pixels are view pixels.
            val at = IntArray(2).also(::getLocationOnScreen)
            val cx = x - at[0]
            val cy = y - at[1]
            val r = dp(26f)
            canvas.drawCircle(cx, cy, r, halo)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawLine(cx - r * 1.5f, cy, cx - r * .5f, cy, ring)
            canvas.drawLine(cx + r * .5f, cy, cx + r * 1.5f, cy, ring)
            canvas.drawLine(cx, cy - r * 1.5f, cx, cy - r * .5f, ring)
            canvas.drawLine(cx, cy + r * .5f, cx, cy + r * 1.5f, ring)
            canvas.drawCircle(cx, cy, dp(2.5f), dot)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    x = event.rawX.coerceIn(0f, bounds.width().toFloat())
                    y = event.rawY.coerceIn(0f, bounds.height().toFloat())
                    invalidate()
                }
            }
            return true
        }
    }
}
