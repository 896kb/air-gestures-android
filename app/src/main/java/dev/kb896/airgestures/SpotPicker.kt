package dev.kb896.airgestures

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Sets up a screen action over whatever app is open: first a bubble while the user opens
 * the app, then a full-screen layer for the [mode]: an orb to drag (Tap here), two orbs (Swipe
 * here), a finger drawing (a drawn swipe), or a recording of taps and swipes (a sequence).
 * Accessibility overlays need no extra permission. [done] gets the action ids (one, or a
 * recording's steps), or null when cancelled.
 */
class SpotPicker(
    private val service: TouchService,
    private val mode: Mode,
    /** The action being changed, to start from (`tap:` / `swipe:`), if any. */
    private val current: String?,
    private val done: (List<String>?) -> Unit,
) {
    enum class Mode(val bubble: String) {
        TAP("Place the tap"),
        SWIPE("Place the swipe"),
        DRAW("Draw the swipe"),
        RECORD("Start recording"),
    }

    private val windows = service.getSystemService(WindowManager::class.java)
    private val bounds = windows.currentWindowMetrics.bounds
    private var shown: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var finished = false

    private fun dp(value: Float) = Cb.dp(service, value)
    private fun dpi(value: Float) = dp(value).toInt()

    fun show() = put(bubble(), WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL, dpi(40f))

    /** Gone without a result (the user cancelled, or the service is going away). */
    fun cancel() = finish(null)

    /** Replaced by a newer pick: gone without telling anyone, so its caller doesn't react. */
    fun drop() {
        finished = true
        remove()
    }

    private fun finish(result: List<String>?) {
        if (finished) return
        finished = true
        remove()
        done(result)
    }

    private fun remove() {
        shown?.let { runCatching { windows.removeView(it) } }
        shown = null
        controls?.let { runCatching { windows.removeView(it) } }
        controls = null
    }

    /** The recorder's buttons, in a window of their own that can always be pressed. */
    private var controls: View? = null

    private fun putControls(view: View) {
        // Transparent room around the bar: the window is only as big as its content, and the
        // bar's shadow is drawn outside the bar.
        val room = FrameLayout(service).apply {
            setPadding(dpi(24f), dpi(24f), dpi(24f), dpi(24f))
            clipToPadding = false
            clipChildren = false
            addView(view)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = dpi(32f)
        }
        windows.addView(room, params)
        controls = room
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
        this.params = params
    }

    /** Let touches through the layer (while a recorded one is replayed), or catch them again. */
    private fun touchable(on: Boolean) {
        val view = shown ?: return
        val params = params ?: return
        params.flags = if (on) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windows.updateViewLayout(view, params) }
    }

    /** A pill in the app's style: coral for the main choice, page-white for the others. */
    private fun pill(context: Context, text: String, main: Boolean, onClick: () -> Unit) =
        Cb.text(context, 14f, R.font.geist_semibold, if (main) Cb.Signal.STOP.label else Cb.INK_900, -.005f).apply {
            this.text = text
            isSingleLine = true
            gravity = Gravity.CENTER
            // Raised the app's way: the drawable draws its own shadow inside its margin, so no
            // window or parent edge can cut it (elevation shadows don't hold up in overlays).
            val shape = NeuDrawable(context, 999f, fill = if (main) Cb.Signal.STOP.fill else Cb.PAGE, solid = main, soft = false)
            background = shape
            val m = shape.margin
            setPadding(m + dpi(18f), m + dpi(12f), m + dpi(18f), m + dpi(12f))
            tag = main
            setOnClickListener { onClick() }
        }

    private fun pills(vararg views: TextView) = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        views.forEachIndexed { index, view ->
            addView(view, LinearLayout.LayoutParams(-2, -2).apply { if (index > 0) marginStart = dpi(10f) })
        }
        setPadding(dpi(8f), dpi(8f), dpi(8f), dpi(8f))
        clipToPadding = false
        clipChildren = false
    }

    /** The buttons at the bottom, on one raised bar in the page tone. */
    private fun bar(vararg views: TextView) = pills(*views).apply {
        // Only the bar is raised; the pills on it are flat (coral for the main one).
        views.forEach { view ->
            val main = view.tag == true
            view.background = GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(if (main) Cb.Signal.STOP.fill else Cb.PAGE)
            }
            view.setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f))
        }
        val shape = NeuDrawable(service, 999f, soft = true)
        background = shape
        val m = shape.margin
        setPadding(m + dpi(6f), m + dpi(6f), m + dpi(6f), m + dpi(6f))
    }

    private fun hint(context: Context, text: String) = Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply {
        this.text = text
        setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f))
        background = GradientDrawable().apply { cornerRadius = dp(999f); setColor(Cb.PAGE) }
    }

    /** The first step, over the app: a short coral button for the mode and a round ✕ that never shrinks. */
    private fun bubble() = LinearLayout(service).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // Transparent room for the shadows: this window is only as big as its content.
        setPadding(dpi(24f), dpi(16f), dpi(24f), dpi(24f))
        clipToPadding = false
        clipChildren = false
        addView(hint(service, "Open the app first").apply {
            textSize = 12f
            setTextColor(Cb.INK_500)
            setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f))
        }, LinearLayout.LayoutParams(-2, -2))
        addView(LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // The buttons' shadows reach past this row: don't cut them at its edge.
            clipChildren = false
            addView(pill(service, mode.bubble, main = true) { start() }.apply { ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(close(), LinearLayout.LayoutParams(dpi(44f + 16f), dpi(44f + 16f)))
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(8f) })
    }

    /** A round ✕: cancels the pick. */
    private fun close() = ImageView(service).apply {
        setImageResource(R.drawable.ic_x)
        imageTintList = android.content.res.ColorStateList.valueOf(Cb.INK_900)
        val shape = NeuDrawable(service, 999f, soft = false)
        background = shape
        val m = shape.margin
        setPadding(m + dpi(12f), m + dpi(12f), m + dpi(12f), m + dpi(12f))
        contentDescription = "Cancel"
        setOnClickListener { cancel() }
    }

    /** The bubble's button: over to the layer for the mode. */
    private fun start() = put(
        when (mode) {
            Mode.TAP -> TapLayer(service)
            Mode.SWIPE -> SwipeLayer(service)
            Mode.DRAW -> DrawLayer(service)
            Mode.RECORD -> RecordLayer(service)
        },
        WindowManager.LayoutParams.MATCH_PARENT,
        Gravity.TOP or Gravity.START,
    )

    /** Screen pixels to fractions of the screen. */
    private fun fx(x: Float) = (x / bounds.width()).coerceIn(0f, 1f)
    private fun fy(y: Float) = (y / bounds.height()).coerceIn(0f, 1f)

    /** A full-screen layer: a faint scrim, a hint on top, a bar at the bottom, an animation clock. */
    @SuppressLint("ViewConstructor")
    private abstract inner class Layer(context: Context, hintText: String) : FrameLayout(context) {
        /** 0…1, round and round: the orbs breathe, the ripples spread. */
        var phase = 0f
        private val clock = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                tick()
                invalidate()
            }
        }
        val hintView = hint(context, hintText)

        init {
            setWillNotDraw(false)
            setBackgroundColor(0x26111214) // 15% ink
            addView(pills(hintView), LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(56f) })
        }

        fun bottom(vararg views: TextView) =
            addView(bar(*views), LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(56f) })

        /** Called every frame (ease things towards their targets). */
        open fun tick() = Unit

        /** Draw in screen pixels (the window spans the screen from its corner). */
        abstract fun paint(canvas: Canvas)

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
            val at = IntArray(2).also(::getLocationOnScreen)
            canvas.save()
            canvas.translate(-at[0].toFloat(), -at[1].toFloat())
            paint(canvas)
            canvas.restore()
        }
    }

    /** Tap here: one coral orb to drag onto the spot. */
    @SuppressLint("ViewConstructor")
    private inner class TapLayer(context: Context) : Layer(context, "Drag the orb onto the spot") {
        private val orb = Orb(Cb.Signal.STOP.fill)
        private val start = current?.let(Tap::parse)
        private var x = bounds.width() * (start?.first ?: .5f)
        private var y = bounds.height() * (start?.second ?: .5f)

        init {
            bottom(pill(context, "Cancel", main = false) { cancel() }, pill(context, "Set", main = true) { finish(listOf(Tap.format(fx(x), fy(y)))) })
        }

        override fun tick() = orb.ease()

        override fun paint(canvas: Canvas) = orb.draw(canvas, x, y, phase)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    orb.held = true
                    x = event.rawX.coerceIn(0f, bounds.width().toFloat())
                    y = event.rawY.coerceIn(0f, bounds.height().toFloat())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> orb.held = false
            }
            return true
        }
    }

    /** Swipe here: a coral start orb and a green end orb, the path glowing between them. */
    @SuppressLint("ViewConstructor")
    private inner class SwipeLayer(context: Context) : Layer(context, "Drag coral to start, green to end") {
        private val from = Orb(Cb.Signal.STOP.fill, marked = false)
        private val to = Orb(Cb.Signal.LIVE.fill, marked = false)
        private val start = current?.takeIf { it.startsWith(Strokes.SWIPE) }?.let(Strokes::parse)
        private var x1 = bounds.width() * (start?.points?.first()?.x ?: .5f)
        private var y1 = bounds.height() * (start?.points?.first()?.y ?: .72f)
        private var x2 = bounds.width() * (start?.points?.last()?.x ?: .5f)
        private var y2 = bounds.height() * (start?.points?.last()?.y ?: .32f)
        private var slow = (start?.duration ?: 250) > 500
        private var dragging: Orb? = null
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
            strokeCap = Paint.Cap.ROUND
        }
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(12f)
            strokeCap = Paint.Cap.ROUND
            alpha = 70
        }
        private val head = Path()

        init {
            val speed = pill(context, if (slow) "Slow" else "Quick", main = false) {}
            speed.setOnClickListener {
                slow = !slow
                speed.text = if (slow) "Slow" else "Quick"
            }
            bottom(
                speed,
                pill(context, "Cancel", main = false) { cancel() },
                pill(context, "Set", main = true) {
                    finish(listOf(Strokes.swipe(fx(x1), fy(y1), fx(x2), fy(y2), if (slow) 900 else 250)))
                },
            )
        }

        override fun tick() {
            from.ease()
            to.ease()
        }

        override fun paint(canvas: Canvas) {
            val shader = LinearGradient(x1, y1, x2, y2, Cb.Signal.STOP.fill, Cb.Signal.LIVE.fill, Shader.TileMode.CLAMP)
            line.shader = shader
            glow.shader = shader
            canvas.drawLine(x1, y1, x2, y2, glow)
            canvas.drawLine(x1, y1, x2, y2, line)
            // An arrowhead just before the end orb, pointing the way the swipe goes.
            val angle = atan2(y2 - y1, x2 - x1)
            val back = from.radius * 1.6f
            val tipX = x2 - cos(angle) * back
            val tipY = y2 - sin(angle) * back
            val wing = dp(10f)
            head.reset()
            head.moveTo(tipX - cos(angle - .5f) * wing, tipY - sin(angle - .5f) * wing)
            head.lineTo(tipX, tipY)
            head.lineTo(tipX - cos(angle + .5f) * wing, tipY - sin(angle + .5f) * wing)
            canvas.drawPath(head, line)
            from.draw(canvas, x1, y1, phase)
            to.draw(canvas, x2, y2, (phase + .5f) % 1f)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.rawX.coerceIn(0f, bounds.width().toFloat())
            val y = event.rawY.coerceIn(0f, bounds.height().toFloat())
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // The nearer orb follows the finger.
                    dragging = if (hypot(x - x1, y - y1) <= hypot(x - x2, y - y2)) from else to
                    dragging?.held = true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging?.held = false
                    dragging = null
                    return true
                }
            }
            if (dragging === from) {
                x1 = x
                y1 = y
            } else if (dragging === to) {
                x2 = x
                y2 = y
            }
            return true
        }
    }

    /** A drawn swipe: the finger's path glows as it's drawn; Redo or Set. */
    @SuppressLint("ViewConstructor")
    private inner class DrawLayer(context: Context) : Layer(context, "Draw the swipe with your finger") {
        private val points = mutableListOf<Strokes.Point>()
        private var downAt = 0L
        private val trail = Path()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(4f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = Cb.Signal.STOP.fill
        }
        private val glow = Paint(line).apply {
            strokeWidth = dp(14f)
            alpha = 60
        }

        init {
            bottom(
                pill(context, "Redo", main = false) {
                    points.clear()
                    trail.reset()
                },
                pill(context, "Cancel", main = false) { cancel() },
                pill(context, "Set", main = true) {
                    val path = Strokes.Path(points.toList())
                    if (points.size >= 2 && path.duration > 0) finish(listOf(Strokes.path(path)))
                },
            )
        }

        override fun paint(canvas: Canvas) {
            canvas.drawPath(trail, glow)
            canvas.drawPath(trail, line)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    trail.reset()
                    downAt = event.eventTime
                    trail.moveTo(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> trail.lineTo(event.rawX, event.rawY)
            }
            points.add(Strokes.Point(fx(event.rawX), fy(event.rawY), event.eventTime - downAt))
            return true
        }
    }

    /**
     * A recording: each tap or swipe is caught, passed straight on to the app underneath (the
     * layer lets touches through while it's replayed), and kept with its timing. Stop ends it.
     */
    @SuppressLint("ViewConstructor")
    private inner class RecordLayer(context: Context) : Layer(context, "● REC  One finger, one touch at a time. Don't type.") {
        private val touches = mutableListOf<Pair<Strokes.Path, Long>>()
        private val points = mutableListOf<Strokes.Point>()
        /** A recorded touch is being passed on: whatever reaches the layer now is that replay, not the user. */
        private var replaying = false
        /** The touch going on now isn't recorded (it began during a replay, or a second finger joined). */
        private var ignoring = false
        private var downAt = 0L
        private val trail = Path()
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
            strokeCap = Paint.Cap.ROUND
            color = Cb.Signal.STOP.fill
            alpha = 200
        }

        init {
            setBackgroundColor(0x0F111214) // fainter: the app should be easy to see
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            // Stop and Cancel live in their own window: this layer stops catching touches while
            // one is replayed, and the buttons must still work then.
            if (controls == null) {
                putControls(bar(
                    pill(context, "Cancel", main = false) { cancel() },
                    pill(context, "Stop", main = true) { finish(Strokes.steps(touches)) },
                ))
            }
        }

        override fun tick() {
            // The REC chip pulses, so it's clear the recording is on.
            hintView.alpha = .7f + .3f * (cos(phase * 2f * PI.toFloat()) + 1f) / 2f
        }

        override fun paint(canvas: Canvas) = canvas.drawPath(trail, line)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                // A touch that starts while one is replayed is that replay (or too quick to keep apart).
                MotionEvent.ACTION_DOWN -> ignoring = replaying
                // Two fingers can't be replayed as one: this touch isn't kept.
                MotionEvent.ACTION_POINTER_DOWN -> {
                    ignoring = true
                    trail.reset()
                }
            }
            if (ignoring || replaying) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) ignoring = false
                return true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_CANCEL -> {
                    trail.reset()
                    return true
                }
                MotionEvent.ACTION_DOWN -> {
                    points.clear()
                    trail.reset()
                    downAt = event.eventTime
                    trail.moveTo(event.rawX, event.rawY)
                }
                MotionEvent.ACTION_MOVE -> trail.lineTo(event.rawX, event.rawY)
            }
            points.add(Strokes.Point(fx(event.rawX), fy(event.rawY), event.eventTime - downAt))
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                // A tap can be a single instant: give it a moment so it has a duration.
                val path = Strokes.Path(points.toList()).let { if (it.duration > 0) it else Strokes.Path(it.points + it.points.last().copy(t = 40)) }
                touches.add(path to downAt)
                // Let the app have it, through the layer, as it was done. The window manager takes
                // a moment to let touches through: replaying at once would land on the layer
                // again (and be recorded again); so does catching touches the moment it ends.
                replaying = true
                touchable(false)
                postDelayed({
                    service.stroke(path) {
                        postDelayed({
                            trail.reset()
                            touchable(true)
                            replaying = false
                        }, 120)
                    }
                }, 80)
            }
            return true
        }
    }

    /**
     * A glowing orb in [color], breathing, bigger and brighter while held. [marked] puts a white
     * point on its centre, the exact spot (a tap's target; a swipe's ends don't need one).
     */
    private inner class Orb(color: Int, private val marked: Boolean = true) {
        val radius = dp(22f)
        var held = false
        private var lift = 0f
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                0f, 0f, radius * 3f,
                intArrayOf(withAlpha(color, .55f), withAlpha(color, .22f), withAlpha(color, 0f)),
                floatArrayOf(0f, .42f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                -radius * .35f, -radius * .4f, radius * 1.45f,
                intArrayOf(withAlpha(lighter(color), .95f), withAlpha(color, .92f), withAlpha(darker(color), .9f)),
                floatArrayOf(0f, .55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            this.color = color
        }
        private val shine = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0x73FFFFFF }
        private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            this.color = 0x40FFFFFF
        }
        private val point = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() }
        private val pointEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
            this.color = withAlpha(Cb.INK_900, .6f)
        }
        private val oval = RectF()

        fun ease() {
            lift += ((if (held) 1f else 0f) - lift) * .18f
        }

        fun draw(canvas: Canvas, x: Float, y: Float, phase: Float) {
            canvas.save()
            canvas.translate(x, y)
            val breath = (1f - cos(phase * 2f * PI.toFloat())) / 2f
            val grow = 1f + .12f * lift
            val glowScale = grow * (1f + .1f * breath)
            canvas.save()
            canvas.scale(glowScale, glowScale)
            glow.alpha = (190 + 65 * lift).toInt()
            canvas.drawCircle(0f, 0f, radius * 3f, glow)
            canvas.restore()
            ripple.alpha = ((1f - phase) * 110).toInt()
            canvas.drawCircle(0f, 0f, radius * grow * (1f + phase * 1.6f), ripple)
            canvas.save()
            canvas.scale(grow, grow)
            canvas.drawCircle(0f, 0f, radius, core)
            canvas.drawCircle(0f, 0f, radius - rim.strokeWidth / 2f, rim)
            oval.set(-radius * .62f, -radius * .72f, -radius * .08f, -radius * .36f)
            canvas.drawOval(oval, shine)
            canvas.restore()
            if (marked) {
                canvas.drawCircle(0f, 0f, dp(3f), point)
                canvas.drawCircle(0f, 0f, dp(3f), pointEdge)
            }
            canvas.restore()
        }
    }
}

/** [color] with its alpha set to [alpha] (0…1). */
private fun withAlpha(color: Int, alpha: Float) = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)

/** Halfway to white: the orb's lit side. */
private fun lighter(color: Int) = mix(color, 0xFFFFFFFF.toInt(), .55f)

/** A little towards black: the orb's far side. */
private fun darker(color: Int) = mix(color, 0xFF000000.toInt(), .12f)

private fun mix(a: Int, b: Int, t: Float): Int {
    fun channel(shift: Int) = ((((a shr shift) and 0xFF) * (1 - t)) + (((b shr shift) and 0xFF) * t)).toInt() shl shift
    return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
}
