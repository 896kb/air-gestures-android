package dev.kb896.airgestures

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.animation.PathInterpolator
import android.widget.TextView

/**
 * The CargoBolt design system (~/Projects/cargobolt/design-system) for Android
 * views: a greyscale page tone that is also the light source, hairline cards,
 * soft-UI pills that sit on the page and press into it, Geist type, neon status
 * colour only on near-black chips.
 */
object Cb {
    // tokens/colors.css
    const val INK_900 = 0xFF111214.toInt()
    const val INK_800 = 0xFF1E1F21.toInt()
    const val INK_700 = 0xFF2A2B2E.toInt()
    const val INK_500 = 0xFF5C5E63.toInt()
    const val INK_400 = 0xFF7C7F85.toInt()
    const val INK_300 = 0xFFA3A5AA.toInt()
    /**
     * The page: CargoBolt's lightest tone (ink-25 #f0f0f2) instead of ink-100, so
     * the band's product video (its white regraded to exactly this) sits on the
     * page with no frame (#f1f1f3 is what the phone's decoder makes of it).
     * Still the light source: highlights are pure white.
     */
    const val PAGE = 0xFFF1F1F3.toInt()
    const val HAIRLINE = 0x21111214 // rgba(17,18,20,.13)
    const val NEU_LIGHT = 0xC7FFFFFF.toInt() // rgba(255,255,255,.78)
    const val NEU_DARK = 0x33111214 // rgba(17,18,20,.20)
    const val SOLID_DROP = 0x42111214 // rgba(17,18,20,.26)

    /** A status: neon fill with its near-black label (never neon on the page tone). */
    enum class Signal(val fill: Int, val label: Int) {
        LIVE(0xFF4DFFA3.toInt(), 0xFF0E1A13.toInt()),
        WARN(0xFFFFC23D.toInt(), 0xFF1C1408.toInt()),
        STOP(0xFFFF5F5F.toInt(), 0xFF1E0F0E.toInt()),
        INFO(0xFF54DAFF.toInt(), 0xFF0C1620.toInt()),
        MUTE(0xFFB9BCC2.toInt(), 0xFF17181A.toInt()),
    }

    // tokens/motion.css: 140 ms controls, cubic-bezier(.2,0,0,1)
    const val DURATION = 140L
    val EASE = PathInterpolator(.2f, 0f, 0f, 1f)

    /**
     * A damped spring (overshoots about 10%, settles by the end) for the hero band.
     * CargoBolt's controls never spring; this is the one exception, on request.
     */
    val SPRING = android.animation.TimeInterpolator { t ->
        val zeta = .6f
        val omega = 12f
        val decay = kotlin.math.exp(-zeta * omega * t)
        val damped = omega * kotlin.math.sqrt(1 - zeta * zeta)
        1 - decay * (kotlin.math.cos(damped * t) + zeta * omega / damped * kotlin.math.sin(damped * t))
    }

    fun dp(context: Context, value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    private val fonts = mutableMapOf<Int, Typeface>()
    fun font(context: Context, res: Int): Typeface = fonts.getOrPut(res) { context.resources.getFont(res) }

    /** A Geist text view: size in sp, tracking in em (as the tokens give it). */
    fun text(
        context: Context,
        size: Float,
        font: Int = R.font.geist_regular,
        color: Int = INK_700,
        tracking: Float = 0f,
    ) = TextView(context).apply {
        typeface = font(context, font)
        textSize = size
        setTextColor(color)
        letterSpacing = tracking
        includeFontPadding = false
    }

    /** The 10 px uppercase micro label above every field and stat. */
    fun label(context: Context, text: String) =
        text(context, 10f, R.font.geist_medium, INK_500, .10f).apply { this.text = text.uppercase() }

    /** Numbers a user compares: Geist Mono, tabular. */
    fun data(context: Context, size: Float = 17f) =
        text(context, size, R.font.geist_mono_medium, INK_900, 0f).apply { fontFeatureSettings = "tnum" }

    /** A panel: hairline only, 14 dp radius, no shadow. */
    fun hairlineCard(context: Context, radius: Float = 14f) = GradientDrawable().apply {
        cornerRadius = dp(context, radius)
        setStroke(maxOf(1, dp(context, .75f).toInt()), HAIRLINE)
        setColor(Color.TRANSPARENT)
    }

    /** Status chip: a small bright plate on the grey page. */
    fun chip(context: Context, signal: Signal, text: String) = text(context, 10f, R.font.geist_semibold, signal.label, .10f).apply {
        this.text = text.uppercase()
        val h = dp(context, 10f).toInt()
        val v = dp(context, 5f).toInt()
        setPadding(h, v, h, v)
        background = GradientDrawable().apply {
            cornerRadius = dp(context, 999f)
            setColor(signal.fill)
        }
    }
}

/**
 * A soft-UI shape (tokens/elevation.css): raised sits on the page (white
 * highlight top-left, ink shadow bottom-right), pushed is pressed into it
 * (the same pair as inset shadows). [pressed] 0…1 cross-fades between them.
 * Solid (ink-filled) shapes use the single drop shadow and the darker press.
 * The shape is drawn inset by [margin] so the outer shadows fit in the bounds.
 */
class NeuDrawable(
    context: Context,
    private val radiusDp: Float,
    private val fill: Int = Cb.PAGE,
    private val solid: Boolean = false,
    private val soft: Boolean = true,
) : Drawable() {
    private val density = context.resources.displayMetrics.density
    val margin = (8 * density).toInt()
    var pressed = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidateSelf()
        }
    private var animator: ValueAnimator? = null

    private val shape = RectF()
    private val path = Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Animate to raised (false) or pushed (true). */
    fun press(down: Boolean) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(pressed, if (down) 1f else 0f).apply {
            duration = Cb.DURATION
            interpolator = Cb.EASE
            addUpdateListener { pressed = it.animatedValue as Float }
            start()
        }
    }

    private fun blurred(color: Int, blurPx: Float, alpha: Float) = shadowPaint.apply {
        this.color = color
        this.alpha = (Color.alpha(color) * alpha).toInt()
        // A CSS blur radius is about twice the Gaussian sigma; BlurMaskFilter takes ~1.7σ.
        maskFilter = BlurMaskFilter(maxOf(.5f, blurPx * .85f), BlurMaskFilter.Blur.NORMAL)
    }

    override fun draw(canvas: Canvas) {
        shape.set(bounds)
        shape.inset(margin.toFloat(), margin.toFloat())
        val radius = minOf(radiusDp * density, shape.height() / 2)
        path.reset()
        path.addRoundRect(shape, radius, radius, Path.Direction.CW)
        val d = density
        val raised = 1f - pressed

        if (raised > 0f) {
            if (solid) {
                drawOffset(canvas, radius, 2 * d, 3 * d, blurred(Cb.SOLID_DROP, 7 * d, raised))
            } else {
                val (lo, dx, dy, lb, db) = if (soft) listOf(2f, 3f, 4f, 4f, 7f) else listOf(1f, 1.5f, 2f, 2f, 3f)
                drawOffset(canvas, radius, -lo * d, -lo * d, blurred(Cb.NEU_LIGHT, lb * d, raised))
                drawOffset(canvas, radius, dx * d, dy * d, blurred(Cb.NEU_DARK, db * d, raised))
            }
        }
        canvas.drawPath(path, fillPaint)
        if (pressed > 0f) {
            canvas.save()
            canvas.clipPath(path)
            if (solid) {
                drawInset(canvas, 2 * d, 3 * d, blurred(0x6B000000, 5 * d, pressed))
                drawInset(canvas, -1.5f * d, -1.5f * d, blurred(0x1AFFFFFF, 3 * d, pressed))
            } else {
                val (dx, dy, db, lo, lb) = if (soft) listOf(2f, 3f, 5f, 2f, 4f) else listOf(1.5f, 2f, 3f, 1.5f, 3f)
                drawInset(canvas, dx * d, dy * d, blurred(Cb.NEU_DARK, db * d, pressed))
                drawInset(canvas, -lo * d, -lo * d, blurred(Cb.NEU_LIGHT, lb * d, pressed))
            }
            canvas.restore()
        }
    }

    private fun drawOffset(canvas: Canvas, radius: Float, dx: Float, dy: Float, paint: Paint) {
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawRoundRect(shape, radius, radius, paint)
        canvas.restore()
    }

    /** An inner shadow: the frame around the shape, offset, blurred, clipped to the shape. */
    private fun drawInset(canvas: Canvas, dx: Float, dy: Float, paint: Paint) {
        val frame = Path().apply {
            val outer = RectF(shape).apply { inset(-shape.height(), -shape.height()) }
            addRect(outer, Path.Direction.CW)
            addPath(path)
            fillType = Path.FillType.EVEN_ODD
        }
        canvas.save()
        canvas.translate(dx, dy)
        canvas.drawPath(frame, paint)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
