package dev.airgestures

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
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
import java.io.File

/** One screen in the CargoBolt soft-UI language: the connection, its state, the gesture map, setup. */
class MainActivity : Activity() {
    private val watch: (BandService.State) -> Unit = ::render

    private lateinit var chip: FrameLayout
    private lateinit var subtitle: TextView
    private lateinit var power: NeuDrawable
    private lateinit var powerState: TextView
    private lateinit var battery: TextView
    private lateinit var hand: TextView
    private lateinit var gestures: TextView
    private lateinit var lastGesture: TextView
    private lateinit var controls: Pair<TextView, NeuDrawable>
    private lateinit var dialVolume: TextView
    private lateinit var dialBrightness: TextView
    private lateinit var setup: LinearLayout
    private lateinit var activity: TextView

    private fun dp(value: Float) = Cb.dp(this, value)
    private fun dpi(value: Float) = dp(value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(Cb.PAGE)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
        val page = column(gap = 20f).apply {
            // Edge-to-edge is enforced from API 35: keep clear of the system bars.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left + dpi(20f), bars.top + dpi(16f), bars.right + dpi(20f), bars.bottom + dpi(24f))
                insets
            }
            addView(header())
            addView(powerBlock())
            addView(statusCard())
            addView(togglesBlock())
            addView(gestureCard())
            addView(setupCard())
            addView(activityCard())
        }
        setContentView(ScrollView(this).apply { addView(page) })
        requestPermissions(permissions(), 1)
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
        renderSetup()
    }

    override fun onPause() {
        BandService.state.remove(watch)
        super.onPause()
    }

    // ---- layout pieces ----

    private fun column(gap: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(1, dpi(gap)) }
        }
    }

    private fun row(gap: Float = 0f) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        if (gap > 0) {
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dpi(gap), 1) }
        }
    }

    private fun weighted() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

    private fun card(title: String, content: View) = column(gap = 12f).apply {
        background = Cb.hairlineCard(context)
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(16f))
        addView(Cb.text(context, 17f, R.font.geist_semibold, Cb.INK_900, -.015f).apply { text = title })
        addView(content)
    }

    private fun icon(res: Int, size: Float = 16f, color: Int = Cb.INK_900) = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
    }

    private fun header() = column(gap = 6f).apply {
        addView(row().apply {
            // The wordmark: Geist Bold at −0.03em with a faint full stop.
            val mark = SpannableString("Air Gestures.").apply {
                setSpan(ForegroundColorSpan(Cb.INK_400), length - 1, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            addView(Cb.text(context, 28f, R.font.geist_bold, Cb.INK_900, -.03f).apply { text = mark }, weighted())
            chip = FrameLayout(context)
            addView(chip)
        })
        subtitle = Cb.text(context, 14f, color = Cb.INK_500)
        addView(subtitle)
    }

    /** The connection switch: raised while off, pushed while the band is kept connected. */
    @SuppressLint("ClickableViewAccessibility")
    private fun powerBlock() = column(gap = 10f).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        power = NeuDrawable(context, 999f, soft = true)
        val button = FrameLayout(context).apply {
            background = power
            val size = dpi(128f) + 2 * power.margin
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(icon(R.drawable.ic_power, 30f), FrameLayout.LayoutParams(dpi(30f), dpi(30f), Gravity.CENTER))
            contentDescription = "Band connection"
            setOnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).setDuration(Cb.DURATION).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        view.animate().scaleX(1f).scaleY(1f).setDuration(Cb.DURATION).start()
                }
                false
            }
            setOnClickListener { toggleService() }
        }
        addView(button)
        addView(column(gap = 4f).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(Cb.label(context, "Band connection"))
            powerState = Cb.text(context, 16f, R.font.geist_medium, Cb.INK_900, -.01f)
            addView(powerState)
        })
    }

    private fun stat(label: String): Pair<View, TextView> {
        val value = Cb.data(this)
        return column(gap = 6f).apply {
            addView(Cb.label(context, label))
            addView(value)
        } to value
    }

    private fun statusCard(): View {
        val (b, bv) = stat("Battery"); battery = bv
        val (h, hv) = stat("Hand"); hand = hv
        val (g, gv) = stat("Gestures"); gestures = gv
        val (l, lv) = stat("Last gesture"); lastGesture = lv.apply { typeface = Cb.font(context, R.font.geist_medium); textSize = 15f }
        return card("Status", column(gap = 16f).apply {
            addView(row().apply { addView(b, weighted()); addView(h, weighted()) })
            addView(row().apply { addView(g, weighted()); addView(l, weighted()) })
        })
    }

    /** A pill that sits on the page and presses into it; `latched` keeps it pushed. */
    @SuppressLint("ClickableViewAccessibility")
    private fun pill(text: String, iconRes: Int? = null, onClick: () -> Unit): Pair<TextView, NeuDrawable> {
        val shape = NeuDrawable(this, 999f, soft = false)
        val view = Cb.text(this, 14f, R.font.geist_medium, Cb.INK_900, -.005f).apply {
            this.text = text
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
    private fun segment(text: String, onClick: () -> Unit) = Cb.text(this, 13f, R.font.geist_medium, Cb.INK_700).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(8f))
        setOnClickListener { onClick() }
    }

    private fun togglesBlock() = row(gap = 16f).apply {
        controls = pill("Controls on", R.drawable.ic_hand) {
            BandService.instance?.setPaused(!BandService.state.value.paused)
                ?: Prefs(context).let { it.paused = !it.paused; render(BandService.state.value) }
        }
        addView(column(gap = 8f).apply {
            addView(Cb.label(context, "Controls"))
            addView(controls.first)
        }, weighted())
        dialVolume = segment("Volume") { setDial("volume") }
        dialBrightness = segment("Brightness") { setDial("brightness") }
        val track = NeuDrawable(context, 999f, soft = false).apply { pressed = 1f }
        addView(column(gap = 8f).apply {
            addView(Cb.label(context, "Dial"))
            addView(row().apply {
                background = track
                val m = track.margin
                setPadding(m + dpi(4f), m + dpi(4f), m + dpi(4f), m + dpi(4f))
                addView(dialVolume)
                addView(dialBrightness)
            })
        })
    }

    private fun setDial(dial: String) {
        BandService.instance?.setDial(dial) ?: run {
            Prefs(this).dial = dial
            render(BandService.state.value)
        }
    }

    private fun gestureCard(): View {
        val rows = listOf(
            Triple(R.drawable.ic_skip_back, "Swipe up", "Previous track"),
            Triple(R.drawable.ic_skip_forward, "Swipe down", "Next track"),
            Triple(R.drawable.ic_play, "Index tap", "Play or pause"),
            Triple(R.drawable.ic_rotate_cw, "Index double tap", "Switch the dial"),
            Triple(R.drawable.ic_volume_2, "Pinch and turn", "Volume or brightness"),
            Triple(R.drawable.ic_volume_x, "Middle double tap", "Mute"),
            Triple(R.drawable.ic_power, "Middle hold", "Controls on or off"),
        )
        return card("Gestures", column(gap = 12f).apply {
            for ((iconRes, gesture, action) in rows) {
                addView(row(gap = 12f).apply {
                    addView(icon(iconRes))
                    addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = gesture }, weighted())
                    addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = action })
                })
            }
        })
    }

    private fun setupCard(): View {
        setup = column(gap = 14f)
        return card("Setup", setup)
    }

    private fun activityCard(): View {
        activity = Cb.text(this, 12f, R.font.geist_mono_regular, Cb.INK_500).apply { setLineSpacing(0f, 1.35f) }
        return card("Activity", activity)
    }

    // ---- state ----

    private fun toggleService() {
        if (!granted()) {
            requestPermissions(permissions(), 1)
            return
        }
        if (BandService.instance != null) BandService.stop(this) else BandService.start(this)
    }

    private fun render(state: BandService.State) {
        val running = BandService.instance != null
        val paused = if (running) state.paused else Prefs(this).paused
        val dial = if (running) state.dial else Prefs(this).dial
        val (signal, label) = when {
            !running || state.phase == Phase.STOPPED -> Cb.Signal.MUTE to "Off"
            state.phase == Phase.CONNECTED && paused -> Cb.Signal.WARN to "Controls off"
            state.phase == Phase.CONNECTED -> Cb.Signal.LIVE to "Connected"
            else -> Cb.Signal.INFO to "Searching"
        }
        chip.removeAllViews()
        chip.addView(Cb.chip(this, signal, label))
        subtitle.text = listOfNotNull(
            state.band ?: "Meta Neural Band",
            state.hand?.let { "${it} wrist" },
        ).joinToString(" · ")

        if ((power.pressed > .5f) != running) power.press(running)
        powerState.text = when {
            !running -> "Off"
            state.phase == Phase.CONNECTED -> "On"
            else -> "Waiting for the band"
        }
        battery.text = state.battery?.let { "$it %" + if (state.charging) " · charging" else "" } ?: "—"
        hand.text = state.hand?.replaceFirstChar { it.uppercase() } ?: "—"
        gestures.text = state.gestures.toString()
        lastGesture.text = state.lastGesture?.replaceFirstChar { it.uppercase() } ?: "None yet"

        // Toggled-on states are pushed, toggled-off states raised.
        controls.first.text = if (paused) "Controls off" else "Controls on"
        if ((controls.second.pressed > .5f) == paused) controls.second.press(!paused)
        for ((view, name) in listOf(dialVolume to "volume", dialBrightness to "brightness")) {
            val active = dial == name
            view.setTextColor(if (active) android.graphics.Color.WHITE else Cb.INK_700)
            view.background = if (active) GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(Cb.INK_900)
            } else null
        }
        activity.text = state.log.takeLast(8).joinToString("\n").ifEmpty { "Nothing yet." }
    }

    private fun renderSetup() {
        setup.removeAllViews()
        val ownerKey = File(filesDir, "owner.key").exists()
        setupRow("Owner key", if (ownerKey) "Loaded from the desktop" else "Run scripts/push-identity.sh", ownerKey, null)
        setupRow("Bluetooth and notifications", if (granted()) "Allowed" else "Needed to connect", granted(), "Allow") {
            requestPermissions(permissions(), 1)
        }
        val linked = Companion.linked(this)
        setupRow("Companion link", if (linked) "Linked; the system wakes the app" else "Hold the band's button 3 s, then link", linked, "Link") {
            Companion.link(this, 2) { line -> BandService.state.update { copy(log = log + line) }; renderSetup() }
        }
        val brightness = Settings.System.canWrite(this)
        setupRow("Brightness access", if (brightness) "Allowed" else "Needed for the brightness dial", brightness, "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
        }
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        setupRow("Battery", if (unrestricted) "Unrestricted" else "Set to no restrictions", unrestricted, "Open") {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun setupRow(title: String, detail: String, ok: Boolean, button: String?, onClick: (() -> Unit)? = null) {
        setup.addView(row(gap = 12f).apply {
            addView(icon(if (ok) R.drawable.ic_shield_check else R.drawable.ic_circle_alert, 16f, if (ok) Cb.INK_900 else Cb.INK_500))
            addView(column(gap = 3f).apply {
                addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = title })
                addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = detail })
            }, weighted())
            if (!ok && button != null && onClick != null) addView(pill(button, null, onClick).first)
        })
    }

    private fun permissions() = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private fun granted() = permissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSetup()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        renderSetup()
    }
}
