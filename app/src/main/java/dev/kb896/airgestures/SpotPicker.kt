package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.cos

/**
 * Picks the spot for "Tap here", over whatever app is open: first a bubble while the
 * user opens the app ("Open the app, then tap here"), then a full-screen layer with a
 * glowing orb to drag onto the spot. Accessibility overlays need no extra permission.
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

    /** Gone without a spot (the user cancelled, or the service is going away). */
    fun cancel() = finish(null)

    /** Replaced by a newer pick: gone without telling anyone, so its caller doesn't react. */
    fun drop() {
        finished = true
        remove()
    }

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
        /** The orb's radius; its glow reaches about three times as far. */
        private val radius = dp(22f)
        private val coral = Cb.Signal.STOP.fill

        /** The soft coral light around the orb (breathes with [phase]). */
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                0f, 0f, radius * 3f,
                intArrayOf(withAlpha(coral, .55f), withAlpha(coral, .22f), withAlpha(coral, 0f)),
                floatArrayOf(0f, .42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        /** The orb: lit from the top left, peach to coral to a deeper coral, a little see-through. */
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                -radius * .35f, -radius * .4f, radius * 1.45f,
                intArrayOf(0xF2FFD9CF.toInt(), withAlpha(0xFFFF7A6E.toInt(), .92f), withAlpha(0xFFE5484D.toInt(), .9f)),
                floatArrayOf(0f, .55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }

        /** A faint ring rippling out now and then. */
        private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = coral
        }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73FFFFFF }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = 0x40FFFFFF
        }
        /** Where the tap lands, exactly. */
        private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        private val pointEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            color = withAlpha(Cb.INK_900, .6f)
        }
        private val oval = RectF()

        /** 0…1, round and round: the glow breathes, the ripple spreads. */
        private var phase = 0f
        /** 0 at rest, 1 while the finger is on the orb (bigger, brighter). */
        private var lift = 0f
        private var held = false
        private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                lift += ((if (held) 1f else 0f) - lift) * .18f
                invalidate()
            }
        }

        init {
            setWillNotDraw(false)
            setBackgroundColor(0x26111214) // 15% ink
            addView(pills(
                Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply {
                    text = "Drag the orb onto the spot"
                    setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
                    background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(Cb.PAGE) }
                },
            ), LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(56f) })
            // Cancel and Set on one raised bar in the page tone, so they read as the app's.
            addView(pills(
                pill(context, "Cancel", main = false) { cancel() },
                pill(context, "Set", main = true) { finish(x / bounds.width() to y / bounds.height()) },
            ).apply {
                setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f))
                background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(Cb.PAGE) }
                elevation = dp(10f)
            }, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(56f) })
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            clock.start()
        }

        override fun onDetachedFromWindow() {
            clock.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // The window covers the whole screen from its corner, so screen pixels are view pixels.
            val at = IntArray(2).also(::getLocationOnScreen)
            canvas.save()
            canvas.translate(x - at[0], y - at[1])
            val breath = (1f - cos(phase * 2f * PI.toFloat())) / 2f
            val grow = 1f + .12f * lift

            // The glow: breathing a little, stronger while held.
            val glowScale = grow * (1f + .1f * breath)
            canvas.save()
            canvas.scale(glowScale, glowScale)
            glow.alpha = (190 + 65 * lift).toInt()
            canvas.drawCircle(0f, 0f, radius * 3f, glow)
            canvas.restore()

            // The ripple: out from the orb's edge and fading, once a cycle.
            ripple.alpha = ((1f - phase) * 110).toInt()
            canvas.drawCircle(0f, 0f, radius * grow * (1f + phase * 1.6f), ripple)

            canvas.save()
            canvas.scale(grow, grow)
            canvas.drawCircle(0f, 0f, radius, core)
            canvas.drawCircle(0f, 0f, radius - rim.strokeWidth / 2f, rim)
            oval.set(-radius * .62f, -radius * .72f, -radius * .08f, -radius * .36f)
            canvas.drawOval(oval, shine)
            canvas.restore()

            canvas.drawCircle(0f, 0f, dp(3f), point)
            canvas.drawCircle(0f, 0f, dp(3f), pointEdge)
            canvas.restore()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    held = true
                    x = event.rawX.coerceIn(0f, bounds.width().toFloat())
                    y = event.rawY.coerceIn(0f, bounds.height().toFloat())
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> held = false
            }
            return true
        }
    }
}

/** [color] with its alpha set to [alpha] (0…1). */
private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
