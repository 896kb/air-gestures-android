package dev.kb896.airgestures

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The runtime permissions' request code. */
const val PERMISSIONS = 1

private const val LIGHT_BARS =
    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

/**
 * Every screen in the CargoBolt soft-UI language: the page tone, light system bars, a
 * page that scrolls inside them, and the shared pieces (cards, pills, the picker,
 * confirmations).
 */
abstract class Screen : Activity() {
    fun dp(value: Float) = Cb.dp(this, value)
    fun dpi(value: Float) = dp(value).toInt()

    override fun onResume() {
        super.onResume()
        // The phone permission may have been granted in system settings meanwhile.
        BandService.instance?.watchCalls()
        // Bluetooth or a permission may have changed while the band was off: the widget follows.
        Surfaces.refresh(this)
    }

    /** A page's column: 20 dp at the sides, [bottom] dp of room under the last card. */
    fun pageColumn(bottom: Float = 24f) = column(gap = 20f).apply {
        setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(bottom))
    }

    /**
     * Shows [page] scrolling inside the system bars (edge-to-edge is enforced from API 35),
     * with a back row on top when it has a [title], and [floating] pinned bottom-right.
     */
    fun show(page: LinearLayout, title: String? = null, floating: View? = null) {
        window.decorView.setBackgroundColor(Cb.PAGE)
        window.insetsController?.setSystemBarsAppearance(LIGHT_BARS, LIGHT_BARS)
        if (title != null) page.addView(titleRow(title), 0)
        val scroll = ScrollView(this).apply {
            addView(page)
            clipToPadding = true
        }
        setContentView(FrameLayout(this).apply {
            addView(scroll)
            if (floating != null) addView(floating, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
            ))
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                if (floating != null) {
                    (floating.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, bars.right + dpi(16f), bars.bottom + dpi(16f))
                    floating.requestLayout()
                }
                insets
            }
        })
    }

    /** A sub-screen's top: back, then its title in the wordmark's type. */
    private fun titleRow(title: String) = row(gap = 8f).apply {
        addView(FrameLayout(context).apply {
            addView(icon(R.drawable.ic_arrow_left, 20f), FrameLayout.LayoutParams(dpi(20f), dpi(20f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dpi(40f), dpi(40f))
            contentDescription = "Back"
            isClickable = true
            background = ripple(999f)
            setOnClickListener { finish() }
        })
        titleView = Cb.text(context, 24f, R.font.geist_bold, Cb.INK_900, -.03f).apply {
            text = title
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        // The title takes its own width, so an edit button can sit right after it.
        addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 0f; width = LinearLayout.LayoutParams.WRAP_CONTENT })
        titleRow = this
    }

    private var titleRow: LinearLayout? = null

    /** A small button right after the title (a profile's rename), before the rest of the row. */
    fun titleButton(iconRes: Int, description: String, onClick: () -> Unit) {
        val row = titleRow ?: return
        row.addView(FrameLayout(this).apply {
            addView(icon(iconRes, 18f, Cb.INK_500), FrameLayout.LayoutParams(dpi(18f), dpi(18f), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dpi(36f), dpi(36f))
            contentDescription = description
            isClickable = true
            background = ripple(999f)
            setOnClickListener { onClick() }
        })
    }

    /** A sub-screen's title, to change it later (a renamed profile). */
    var titleView: TextView? = null
        private set

    inline fun <reified T : Activity> open() = startActivity(Intent(this, T::class.java))

    /** The pressed state of a tappable row: a quiet ink ripple inside rounded corners. */
    fun ripple(radius: Float = 10f) = RippleDrawable(
        ColorStateList.valueOf(0x14111214), null, GradientDrawable().apply { setColor(-1); cornerRadius = dp(radius) },
    )

    /** A row that opens another screen: icon, label, a quiet [detail] or a WARN [badge], chevron. */
    fun navRow(iconRes: Int, label: String, badge: String? = null, detail: String? = null, onClick: () -> Unit) = row(gap = 12f).apply {
        setPadding(dpi(4f), dpi(12f), dpi(4f), dpi(12f))
        addView(icon(iconRes, 18f))
        addView(Cb.text(context, 15f, R.font.geist_medium, Cb.INK_900).apply { text = label }, weighted())
        if (detail != null) addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = detail })
        if (badge != null) addView(Cb.chip(context, Cb.Signal.WARN, badge))
        addView(icon(R.drawable.ic_chevron_right, 14f, Cb.INK_400))
        isClickable = true
        background = ripple()
        setOnClickListener { onClick() }
    }

    /** A card; [title] null for a sub-screen's single card under its own title. */
    fun card(title: String?, content: View) = column(gap = 12f).apply {
        background = Cb.hairlineCard(context)
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f))
        if (title != null) addView(Cb.text(context, 17f, R.font.geist_semibold, Cb.INK_900, -.015f).apply { text = title })
        addView(content)
    }

    fun log(line: String) = BandService.state.update { copy(log = (log + line).takeLast(60)) }

    /** The band needs Bluetooth: say so and offer Android's own prompt to switch it on. */
    fun askForBluetooth() = confirm(
        "Bluetooth is off",
        "The band connects over Bluetooth. Turn it on to use the band.",
        "Turn on",
    ) {
        val settings = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        // The system's prompt needs the Bluetooth permission; without it, the settings page.
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            startActivity(settings)
        } else {
            runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }.onFailure { startActivity(settings) }
        }
    }

    /** Asks before stopping a claim in progress. */
    fun stopClaim() = confirm("Stop claiming?", "The claim stops here. If the band already accepted this phone, its key is kept.", "Stop") {
        BandClaim.cancel()
    }

    // ---- layout pieces ----

    fun column(gap: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dpi(gap)) }
        }
    }

    fun row(gap: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dpi(gap), 1) }
        }
    }

    fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    fun icon(res: Int, size: Float = 16f, color: Int = Cb.INK_900) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
    }

    /** A pill that sits on the page and presses into it; `latched` keeps it pushed. */
    @SuppressLint("ClickableViewAccessibility")
    fun pill(text: String, iconRes: Int? = null, onClick: () -> Unit): Pair<TextView, NeuDrawable> {
        val shape = NeuDrawable(this, 999f, soft = false)
        val view = Cb.text(this, 14f, R.font.geist_medium, Cb.INK_900, -.005f).apply {
            this.text = text
            isSingleLine = true
            gravity = Gravity.CENTER
            background = shape
            val m = shape.margin
            setPadding(m + dpi(16f), m + dpi(9f), m + dpi(16f), m + dpi(9f))
            if (iconRes != null) {
                val drawable = context.getDrawable(iconRes)!!.mutate().apply {
                    setBounds(0, 0, dpi(16f), dpi(16f))
                    setTint(Cb.INK_900)
                }
                setCompoundDrawables(drawable, null, null, null)
                compoundDrawablePadding = dpi(8f)
            }
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).setDuration(Cb.DURATION).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(Cb.DURATION).start()
                }
                false
            }
            setOnClickListener { onClick() }
        }
        return view to shape
    }

    /** Segmented control: an ink-filled active pill on a pushed track. */
    fun segment(text: String, onClick: () -> Unit) = Cb.text(this, 13f, R.font.geist_medium, Cb.INK_700).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(dpi(18f), dpi(8f), dpi(18f), dpi(8f))
        setOnClickListener { onClick() }
    }

    fun segmented(vararg segments: TextView) = row().apply {
        val track = NeuDrawable(context, 999f, soft = false).apply { pressed = 1f }
        background = track
        val m = track.margin
        setPadding(m + dpi(4f), m + dpi(4f), m + dpi(4f), m + dpi(4f))
        segments.forEach(::addView)
    }

    /** A setting: its label and a line of detail on the left, the control on the right. */
    fun settingRow(label: String, detail: String, control: View) = row(gap = 12f).apply {
        addView(column(gap = 4f).apply {
            addView(Cb.label(context, label))
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = detail })
        }, weighted())
        addView(control)
    }

    /** One line in the picker: a Lucide icon (tinted) or an app's own icon (as is). */
    class Option(val label: String, val selected: Boolean, val iconRes: Int = 0, val image: android.graphics.drawable.Drawable? = null)

    /** The picker: a modal on the scrim (CargoBolt: 55% ink scrim, overlay shadow). */
    fun choose(title: String, options: List<Option>, heading: String = "Gesture", onChoice: (Int) -> Unit): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        val list = column(gap = 2f)
        val sheet = column(gap = 12f).apply {
            setPadding(dpi(20f), dpi(18f), dpi(20f), dpi(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(Cb.PAGE)
            }
            elevation = dp(24f)
            addView(Cb.label(context, heading))
            addView(Cb.text(context, 22f, R.font.geist_semibold, Cb.INK_900, -.02f).apply { text = title })
            // Long lists (the actions, the apps) scroll inside the sheet.
            addView(object : ScrollView(context) {
                override fun onMeasure(widthSpec: Int, heightSpec: Int) {
                    val most = (resources.displayMetrics.heightPixels * .6f).toInt()
                    super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST))
                }
            }.apply {
                isVerticalScrollBarEnabled = false
                addView(list)
            })
        }
        options.forEachIndexed { index, option ->
            val selected = option.selected
            list.addView(row(gap = 12f).apply {
                setPadding(dpi(10f), dpi(11f), dpi(10f), dpi(11f))
                if (option.image != null) {
                    addView(ImageView(context).apply {
                        setImageDrawable(option.image)
                        layoutParams = LinearLayout.LayoutParams(dpi(24f), dpi(24f))
                    })
                } else {
                    addView(icon(option.iconRes, 16f))
                }
                addView(Cb.text(context, 15f, if (selected) R.font.geist_semibold else R.font.geist_regular, Cb.INK_900).apply {
                    text = option.label
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, weighted())
                if (selected) addView(icon(R.drawable.ic_check, 16f))
                background = if (selected) GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(0x14111214) // --action-quiet
                } else null
                // Dismissed first, so a choice that opens another picker isn't stacked on this one.
                setOnClickListener {
                    dialog.dismiss()
                    onChoice(index)
                }
            })
        }
        dialog.setContentView(FrameLayout(this).apply {
            setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(16f))
            addView(sheet)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setDimAmount(.55f)
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }

    /** A confirmation in the picker's style: title, body, Cancel and the action. */
    fun confirm(title: String, body: String, action: String, onConfirm: () -> Unit) =
        sheet(title, Cb.text(this, 14f, color = Cb.INK_500).apply { text = body; setLineSpacing(0f, 1.3f) }, action) {
            onConfirm()
            true
        }

    /** Ask for a line of text (a profile's name): the field starts with [initial]. */
    fun ask(title: String, initial: String, action: String, onDone: (String) -> Unit) {
        val field = android.widget.EditText(this).apply {
            setText(initial)
            setSelection(initial.length)
            isSingleLine = true
            textSize = 16f
            typeface = Cb.font(context, R.font.geist_medium)
            setTextColor(Cb.INK_900)
            background = Cb.hairlineCard(context, 10f)
            setPadding(dpi(14f), dpi(12f), dpi(14f), dpi(12f))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        val dialog = sheet(title, field, action) {
            onDone(field.text.toString())
            true
        }
        field.setOnEditorActionListener { _, _, _ ->
            onDone(field.text.toString())
            dialog.dismiss()
            true
        }
        field.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    /** A modal sheet in the picker's style: title, [body], Cancel (unless [cancel] is off) and [action]. */
    fun sheet(title: String, body: View, action: String, cancel: Boolean = true, onAction: () -> Boolean): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        val sheet = column(gap = 14f).apply {
            setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(18f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(Cb.PAGE)
            }
            elevation = dp(24f)
            addView(Cb.text(context, 22f, R.font.geist_semibold, Cb.INK_900, -.02f).apply { text = title })
            addView(body)
            addView(row(gap = 12f).apply {
                gravity = Gravity.END
                if (cancel) addView(pill("Cancel") { dialog.dismiss() }.first)
                addView(pill(action) { if (onAction()) dialog.dismiss() }.first)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        dialog.setContentView(FrameLayout(this).apply {
            setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(16f))
            addView(sheet)
        })
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0))
            setDimAmount(.55f)
            setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        return dialog
    }
}
