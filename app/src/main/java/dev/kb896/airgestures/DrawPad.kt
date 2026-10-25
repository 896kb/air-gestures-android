package dev.kb896.airgestures

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Strokes in degrees (x right, y up), fitted into the view. */
class StrokesView(context: Context, private val ink: Int, private val widthDp: Float = 3f) : View(context) {
    var strokes: List<List<Pair<Float, Float>>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = Cb.dp(context, widthDp); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = ink
    }

    override fun onDraw(canvas: Canvas) {
        val points = strokes.flatten()
        if (points.isEmpty()) return
        val minX = points.minOf { it.first }; val maxX = points.maxOf { it.first }
        val minY = points.minOf { it.second }; val maxY = points.maxOf { it.second }
        val margin = Cb.dp(context, 12f)
        // A tiny drawing isn't blown up: at least 10° across.
        val span = maxOf(maxX - minX, maxY - minY, 10f)
        val scale = minOf(width - 2 * margin, height - 2 * margin) / span
        val cx = (minX + maxX) / 2; val cy = (minY + maxY) / 2
        for (stroke in strokes) {
            val path = android.graphics.Path()
            stroke.forEachIndexed { i, (x, y) ->
                val px = width / 2f + (x - cx) * scale
                val py = height / 2f - (y - cy) * scale
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            // A one-point stroke is a dot.
            if (stroke.size == 1) path.lineTo(width / 2f + (stroke[0].first - cx) * scale + .1f, height / 2f - (stroke[0].second - cy) * scale)
            canvas.drawPath(path, paint)
        }
    }
}

/**
 * The draw pad (mockup 14): a dark card over the app in front while a draw session is open,
 * the strokes as the band draws them, a "Pen down" light, then what it was ("Lightbulb → Light
 * on/off") or "Not one of your drawings". Drawn by the accessibility service: it needs no other
 * permission, and only its close button takes touches.
 */
class DrawPad(private val service: AccessibilityService) : BandService.Drawing {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var card: View? = null
    private lateinit var strokesView: StrokesView
    private lateinit var hint: TextView
    private lateinit var penDot: View
    private lateinit var penText: TextView
    private lateinit var result: TextView
    private val strokes = sortedMapOf<Int, MutableList<Pair<Float, Float>>>()
    /** The pen just went down: the next point starts its stroke (judgement 14). */
    private var fresh = false
    private val hideNow = Runnable { hide() }

    private fun dp(value: Float) = Cb.dp(service, value)

    override fun started() {
        main.removeCallbacks(hideNow)
        strokes.clear()
        fresh = false
        show()
        update()
        light(false)
        result.text = ""
    }

    override fun pen(down: Boolean) {
        if (down) fresh = true
        light(down)
    }

    override fun point(stroke: Int, x: Float, y: Float) {
        if (card == null) return
        if (fresh) {
            // A dropped stroke's number comes again: drawn afresh.
            strokes[stroke] = mutableListOf(x to y)
            fresh = false
        } else {
            strokes.getOrPut(stroke) { mutableListOf() } += x to y
        }
        update()
    }

    override fun finished(strokes: String) = Unit

    override fun drawn(name: String) = finish(result(service, name))

    override fun notDrawn() = finish("Not one of your drawings")

    override fun cancelled() = hide()

    private fun finish(text: String) {
        if (card == null) return
        light(false)
        result.text = text
        main.removeCallbacks(hideNow)
        main.postDelayed(hideNow, RESULT_MS)
    }

    private fun update() {
        strokesView.strokes = strokes.values.map { it.toList() }
        hint.visibility = if (strokes.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun light(down: Boolean) {
        if (card == null) return
        (penDot.background as GradientDrawable).setColor(if (down) Cb.Signal.LIVE.fill else Cb.INK_500)
        penText.setTextColor(if (down) Cb.PAGE else Cb.INK_500)
    }

    fun hide() {
        main.removeCallbacks(hideNow)
        card?.let { runCatching { windows.removeView(it) } }
        card = null
    }

    private fun show() {
        if (card != null) return
        val view = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(Cb.INK_900)
            }
            elevation = dp(24f)
            val pad = dp(16f).toInt()
            setPadding(pad, dp(12f).toInt(), pad, pad)
            addView(LinearLayout(service).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(Cb.label(service, "Draw").apply { setTextColor(Cb.INK_300) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                penDot = View(service).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Cb.INK_500) } }
                addView(penDot, LinearLayout.LayoutParams(dp(8f).toInt(), dp(8f).toInt()).apply { marginEnd = dp(6f).toInt() })
                penText = Cb.text(service, 12f, R.font.geist_medium, Cb.INK_500).apply { text = "Pen down" }
                addView(penText)
                addView(FrameLayout(service).apply {
                    addView(ImageView(service).apply {
                        setImageResource(R.drawable.ic_x)
                        imageTintList = ColorStateList.valueOf(Cb.INK_300)
                    }, FrameLayout.LayoutParams(dp(18f).toInt(), dp(18f).toInt(), Gravity.CENTER))
                    contentDescription = "Close"
                    isClickable = true
                    setOnClickListener {
                        BandService.instance?.cancelDrawing()
                        hide()
                    }
                }, LinearLayout.LayoutParams(dp(40f).toInt(), dp(40f).toInt()).apply { marginStart = dp(8f).toInt() })
            })
            addView(FrameLayout(service).apply {
                strokesView = StrokesView(service, ink = Cb.PAGE)
                addView(strokesView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                hint = Cb.text(service, 14f, color = Cb.INK_500).apply { text = "Pinch and draw" }
                addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            result = Cb.text(service, 15f, R.font.geist_medium, Cb.PAGE).apply {
                gravity = Gravity.CENTER
                minHeight = dp(22f).toInt()
            }
            addView(result, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val params = WindowManager.LayoutParams(
            dp(300f).toInt(),
            dp(340f).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        windows.addView(view, params)
        card = view
    }

    companion object {
        /** How long a result stays before the pad goes. */
        private const val RESULT_MS = 1500L

        /** "Lightbulb → Light on/off": the drawing and what it runs in the profile in force. */
        fun result(context: Context, name: String) =
            "$name → ${Labels.action(context, Drawings.action(context, Profiles.active(context), name))}"
    }
}
