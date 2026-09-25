package dev.airgestures

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView

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
    private lateinit var setup: LinearLayout
    private lateinit var activity: TextView
    private var player: MediaPlayer? = null
    /** `start`/`pause` are only legal once the player is prepared (else it drops into its error state). */
    private var prepared = false
    private var heroView: View? = null
    /** Whether the hero shows the band as live (full speed and opacity); null = not set yet. */
    private var heroLive: Boolean? = null

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
            setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(24f))
            addView(header())
            hero()?.let(::addView)
            addView(powerBlock())
            addView(statusCard())
            addView(togglesBlock())
            addView(gestureCard())
            addView(setupCard())
            addView(activityCard())
        }
        setContentView(ScrollView(this).apply {
            addView(page)
            // Edge-to-edge is enforced from API 35: scroll inside the system bars, not under them.
            clipToPadding = true
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        requestPermissions(permissions(), 1)
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
        renderSetup()
    }

    override fun onPause() {
        BandService.state.remove(watch)
        if (prepared) player?.pause()
        heroLive = null
        super.onPause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    /**
     * The band turning on its own, straight on the page: the file's background
     * is regraded to the page tone (grayscale and contrast baked in too).
     * Absent from public builds.
     */
    private fun hero(): View? {
        val video = runCatching { assets.openFd("meta-band.mp4") }.getOrNull() ?: return null
        val texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    prepared = false
                    player = MediaPlayer().apply {
                        setDataSource(video.fileDescriptor, video.startOffset, video.length)
                        setSurface(Surface(surface))
                        isLooping = true
                        setVolume(0f, 0f)
                        setOnPreparedListener {
                            prepared = true
                            start()
                            heroLive = null
                            render(BandService.state.value)
                        }
                        prepareAsync()
                    }
                }
                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    prepared = false
                    player?.release()
                    player = null
                    heroLive = null
                    return true
                }
                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
        heroView = texture
        return FrameLayout(this).apply {
            addView(texture)
            // 3:2, like the file.
            addOnLayoutChangeListener { view, left, _, right, _, _, _, _, _ ->
                val height = (right - left) * 2 / 3
                if (view.layoutParams.height != height) view.post {
                    view.layoutParams = view.layoutParams.apply { this.height = height }
                }
            }
        }
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
    private fun powerBlock() = row(gap = 16f).apply {
        power = NeuDrawable(context, 999f, soft = true)
        val button = FrameLayout(context).apply {
            background = power
            val size = dpi(76f) + 2 * power.margin
            layoutParams = LinearLayout.LayoutParams(size, size)
            addView(icon(R.drawable.ic_power, 24f), FrameLayout.LayoutParams(dpi(24f), dpi(24f), Gravity.CENTER))
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

    /** A setting: its label and a line of detail on the left, the control on the right. */
    private fun settingRow(label: String, detail: String, control: View) = row(gap = 12f).apply {
        addView(column(gap = 4f).apply {
            addView(Cb.label(context, label))
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply { text = detail })
        }, weighted())
        addView(control)
    }

    private fun togglesBlock() = column(gap = 16f).apply {
        controls = pill("Controls on", R.drawable.ic_hand) {
            BandService.instance?.setPaused(!BandService.state.value.paused)
                ?: Prefs(context).let { it.paused = !it.paused; render(BandService.state.value) }
        }
        addView(settingRow("Controls", "Or hold the middle finger", controls.first))
    }

    private lateinit var gestureRows: LinearLayout

    private fun gestureCard(): View {
        gestureRows = column(gap = 4f)
        renderGestures()
        return card("Gestures", gestureRows)
    }

    /** One row per gesture: tap to choose its action. The pinch and the middle hold are fixed. */
    private fun renderGestures() {
        gestureRows.removeAllViews()
        val prefs = Prefs(this)
        for (gesture in Gesture.entries) {
            val action = prefs.action(gesture)
            gestureRows.addView(gestureRow(action.icon, gesture.label, action.label, action == Action.NONE) {
                pick(gesture)
            })
        }
        val dial = prefs.dial
        gestureRows.addView(gestureRow(dial.icon, "Pinch and turn", dial.label, dial == DialChoice.NONE) { pickDial() })
        gestureRows.addView(gestureRow(R.drawable.ic_power, "Middle hold", "Controls on or off", false, null))
    }

    private fun gestureRow(iconRes: Int, gesture: String, action: String, unset: Boolean, onClick: (() -> Unit)?) =
        row(gap = 12f).apply {
            setPadding(0, dpi(8f), 0, dpi(8f))
            addView(icon(iconRes, 16f, if (unset) Cb.INK_300 else Cb.INK_900))
            addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = gesture }, weighted())
            addView(Cb.text(context, 13f, color = if (unset) Cb.INK_400 else Cb.INK_500).apply { text = action })
            addView(icon(if (onClick != null) R.drawable.ic_chevron_right else R.drawable.ic_lock, 14f, Cb.INK_400))
            if (onClick != null) {
                isClickable = true
                background = android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(0x14111214), null, GradientDrawable().apply { setColor(-1); cornerRadius = dp(10f) },
                )
                setOnClickListener { onClick() }
            }
        }

    private fun pick(gesture: Gesture) {
        val prefs = Prefs(this)
        choose(gesture.label, Action.entries.map { Triple(it.icon, it.label, it == prefs.action(gesture)) }) { index ->
            val action = Action.entries[index]
            BandService.instance?.setAction(gesture, action) ?: prefs.setAction(gesture, action)
        }
    }

    private fun pickDial() {
        val prefs = Prefs(this)
        choose("Pinch and turn", DialChoice.entries.map { Triple(it.icon, it.label, it == prefs.dial) }) { index ->
            val dial = DialChoice.entries[index]
            BandService.instance?.setDial(dial) ?: run { prefs.dial = dial }
        }
    }

    /** The picker: a modal on the scrim (CargoBolt: 55% ink scrim, overlay shadow). */
    private fun choose(title: String, options: List<Triple<Int, String, Boolean>>, onChoice: (Int) -> Unit) {
        val dialog = android.app.Dialog(this)
        val list = column(gap = 2f)
        val sheet = column(gap = 12f).apply {
            setPadding(dpi(20f), dpi(18f), dpi(20f), dpi(14f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(Cb.PAGE)
            }
            elevation = dp(24f)
            addView(Cb.label(context, "Gesture"))
            addView(Cb.text(context, 22f, R.font.geist_semibold, Cb.INK_900, -.02f).apply { text = title })
            addView(list)
        }
        options.forEachIndexed { index, (iconRes, label, selected) ->
            list.addView(row(gap = 12f).apply {
                setPadding(dpi(10f), dpi(11f), dpi(10f), dpi(11f))
                addView(icon(iconRes, 16f))
                addView(Cb.text(context, 15f, if (selected) R.font.geist_semibold else R.font.geist_regular, Cb.INK_900).apply {
                    text = label
                }, weighted())
                if (selected) addView(icon(R.drawable.ic_check, 16f))
                background = if (selected) GradientDrawable().apply {
                    cornerRadius = dp(10f)
                    setColor(0x14111214) // --action-quiet
                } else null
                setOnClickListener {
                    onChoice(index)
                    renderGestures()
                    renderSetup()
                    dialog.dismiss()
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
        controls.first.setCompoundDrawablesRelative(
            getDrawable(if (paused) R.drawable.ic_hand_off else R.drawable.ic_hand)!!.mutate().apply {
                setBounds(0, 0, dpi(16f), dpi(16f))
                setTint(Cb.INK_900)
            }, null, null, null,
        )
        activity.text = state.log.takeLast(8).joinToString("\n").ifEmpty { "Nothing yet." }
        // The band is full size while it's connected with controls on;
        // otherwise smaller and faded to 30%.
        val live = running && state.phase == Phase.CONNECTED && !paused
        val hero = heroView
        if (hero != null && heroLive != live) {
            val first = heroLive == null
            heroLive = live
            if (prepared) player?.let { if (!it.isPlaying) it.start() }
            val scale = if (live) 1f else .8f
            val alpha = if (live) 1f else .3f
            if (first) {
                hero.scaleX = scale; hero.scaleY = scale; hero.alpha = alpha
            } else {
                // The band springs back to full size when it comes on, and settles small when it goes off.
                hero.animate().scaleX(scale).scaleY(scale).setDuration(720).setInterpolator(Cb.SPRING).start()
                android.animation.ObjectAnimator.ofFloat(hero, View.ALPHA, alpha).apply {
                    duration = 320
                    interpolator = Cb.EASE
                    start()
                }
            }
        }
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
        // Only asked for when a screen gesture is mapped somewhere.
        if (Prefs(this).usesScreen()) {
            val on = TouchService.instance != null
            setupRow(
                "Screen gestures",
                if (on) "On" else "Settings, Accessibility, Air Gestures. If it's greyed out: App info, then Allow restricted settings",
                on,
                "Turn on",
            ) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
        // Only asked for when brightness is mapped somewhere.
        if (Prefs(this).usesBrightness()) {
            val brightness = Settings.System.canWrite(this)
            setupRow("Brightness access", if (brightness) "Allowed" else "Needed for brightness", brightness, "Allow") {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")))
            }
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
