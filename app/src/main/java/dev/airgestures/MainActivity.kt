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

/** The file picker's request code for importing the owner key. */
private const val IMPORT = 3
/** The Meta sign-in's request code (then the claim). */
private const val SIGN_IN = 4
/** The file picker's request code for exporting the owner key. */
private const val EXPORT = 5

/** Same Ko-fi page the website links. */
private const val KOFI_URL = "https://ko-fi.com/88896kb"

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
    private lateinit var wristLeft: TextView
    private lateinit var wristRight: TextView
    private lateinit var activity: TextView
    private var player: MediaPlayer? = null
    /** `start`/`pause` are only legal once the player is prepared (else it drops into its error state). */
    private var prepared = false
    private var heroView: View? = null
    /** Whether the hero shows the band as live (full speed and opacity); null = not set yet. */
    private var heroLive: Boolean? = null
    /** The claim state Setup was last drawn for. */
    private var setupClaiming = false

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
            // The bottom room keeps the last card clear of the Ko-fi pill.
            setPadding(dpi(20f), dpi(16f), dpi(20f), dpi(88f))
            addView(header())
            update = column(gap = 0f)
            addView(update)
            hero()?.let(::addView)
            addView(powerBlock())
            addView(statusCard())
            addView(togglesBlock())
            addView(gestureCard())
            addView(setupCard())
            addView(activityCard())
            // The installed version, quietly, bottom left.
            addView(Cb.text(context, 12f, color = Cb.INK_400).apply {
                text = "v" + UpdateCheck.version(context)
            })
        }
        val scroll = ScrollView(this).apply {
            addView(page)
            clipToPadding = true
        }
        val kofi = kofiPill()
        setContentView(FrameLayout(this).apply {
            addView(scroll)
            addView(kofi, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END,
            ))
            // Edge-to-edge is enforced from API 35: scroll inside the system bars, not under them.
            setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                (kofi.layoutParams as FrameLayout.LayoutParams).setMargins(0, 0, bars.right + dpi(16f), bars.bottom + dpi(16f))
                kofi.requestLayout()
                insets
            }
        })
        requestPermissions(permissions(), 1)
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
        registerReceiver(bluetoothChanged, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        renderSetup()
        renderUpdate()
        UpdateCheck.maybe(this) { runOnUiThread(::renderUpdate) }
    }

    private lateinit var update: LinearLayout

    /** A newer release than this build: say so at the top, with its first note line and the download. */
    private fun renderUpdate() {
        update.removeAllViews()
        val release = UpdateCheck.available(this)
        // Gone while there's nothing to say, so the page keeps its spacing.
        update.visibility = if (release == null) View.GONE else View.VISIBLE
        if (release == null) return
        val version = release.tag.removePrefix("v")
        val notes = UpdateCheck.plain(release.notes).lineSequence().map { it.removePrefix("• ") }.firstOrNull { it.isNotBlank() }
        update.addView(card("Air Gestures $version is out", column(gap = 12f).apply {
            addView(Cb.text(context, 14f, color = Cb.INK_500).apply {
                text = listOfNotNull("You have ${UpdateCheck.version(context)}.", notes).joinToString(" ")
            })
            addView(row(gap = 10f).apply {
                addView(pill("Download APK", R.drawable.ic_arrow_down) { openUrl(UpdateCheck.APK) }.first)
                addView(pill("All releases") { openUrl(UpdateCheck.RELEASES) }.first)
            })
        }))
    }

    private fun openUrl(url: String) = startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))

    /** Bluetooth switched on or off: the chip, the power button and Setup follow. */
    private val bluetoothChanged = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            render(BandService.state.value)
            renderSetup()
        }
    }

    private fun bluetoothOn() =
        getSystemService(android.bluetooth.BluetoothManager::class.java).adapter?.isEnabled == true

    /** The band needs Bluetooth: say so and offer Android's own prompt to switch it on. */
    private fun askForBluetooth() = confirm(
        "Bluetooth is off",
        "The band connects over Bluetooth. Turn it on to use the band.",
        "Turn on",
    ) {
        @SuppressLint("MissingPermission")
        val request = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
        runCatching { startActivity(request) }.onFailure { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    }

    override fun onPause() {
        BandService.state.remove(watch)
        runCatching { unregisterReceiver(bluetoothChanged) }
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
     * Shipped in assets/; the page still works if the file is missing.
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
        addView(row(gap = 10f).apply {
            addView(ImageView(context).apply {
                setImageResource(R.drawable.app_mark)
                contentDescription = null
                layoutParams = LinearLayout.LayoutParams(dpi(36f), dpi(36f))
            })
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

    /** Support on Ko-fi: the site's coral pill, floating bottom-right over the page. */
    @SuppressLint("ClickableViewAccessibility")
    private fun kofiPill() = Cb.text(this, 14f, R.font.geist_semibold, Cb.Signal.STOP.label, -.005f).apply {
        text = "Support on Ko-fi"
        isSingleLine = true
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dpi(999f).toFloat()
            setColor(Cb.Signal.STOP.fill)
        }
        setPadding(dpi(18f), dpi(12f), dpi(18f), dpi(12f))
        val coffee = context.getDrawable(R.drawable.ic_coffee)!!.mutate().apply {
            setBounds(0, 0, dpi(16f), dpi(16f))
            setTint(Cb.Signal.STOP.label)
        }
        setCompoundDrawables(coffee, null, null, null)
        compoundDrawablePadding = dpi(8f)
        elevation = dpi(6f).toFloat()
        outlineAmbientShadowColor = Cb.Signal.STOP.fill
        outlineSpotShadowColor = Cb.Signal.STOP.fill
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(.985f).scaleY(.985f).translationZ(-dpi(4f).toFloat()).setDuration(Cb.DURATION).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(Cb.DURATION).start()
            }
            false
        }
        setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(KOFI_URL))) }
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

    /** Segmented control: an ink-filled active pill on a pushed track. */
    private fun segment(text: String, onClick: () -> Unit) = Cb.text(this, 13f, R.font.geist_medium, Cb.INK_700).apply {
        this.text = text
        gravity = Gravity.CENTER
        setPadding(dpi(18f), dpi(8f), dpi(18f), dpi(8f))
        setOnClickListener { onClick() }
    }

    private fun segmented(vararg segments: TextView) = row().apply {
        val track = NeuDrawable(context, 999f, soft = false).apply { pressed = 1f }
        background = track
        val m = track.margin
        setPadding(m + dpi(4f), m + dpi(4f), m + dpi(4f), m + dpi(4f))
        segments.forEach(::addView)
    }

    private fun setHand(hand: String) {
        BandService.instance?.setHand(hand) ?: run { Prefs(this).hand = hand }
        render(BandService.state.value)
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
        wristLeft = segment("Left") { setHand("left") }
        wristRight = segment("Right") { setHand("right") }
        addView(settingRow("Wrist", "Which arm the band is on", segmented(wristLeft, wristRight)))
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
            val label = if (action == Action.OPEN_APP) "Open ${appName(prefs.actionId(gesture).removePrefix(APP_PREFIX))}" else action.label
            gestureRows.addView(gestureRow(action.icon, gesture.label, label, action == Action.NONE) {
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
        choose(gesture.label, Action.entries.map { Option(it.label, it == prefs.action(gesture), it.icon) }) { index ->
            val action = Action.entries[index]
            if (action == Action.OPEN_APP) pickApp(gesture) else setAction(gesture, action.id)
        }
    }

    private fun setAction(gesture: Gesture, id: String) =
        BandService.instance?.setAction(gesture, id) ?: Prefs(this).setAction(gesture, id)

    /** The apps with a launcher icon, by name, each with its own icon. */
    private fun pickApp(gesture: Gesture) {
        val current = Prefs(this).actionId(gesture).removePrefix(APP_PREFIX)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .map { it.activityInfo.packageName to it }
            .sortedBy { (_, info) -> info.loadLabel(packageManager).toString().lowercase() }
        choose("Open an app", apps.map { (pkg, info) ->
            Option(info.loadLabel(packageManager).toString(), pkg == current, image = info.loadIcon(packageManager))
        }) { index -> setAction(gesture, APP_PREFIX + apps[index].first) }
    }

    /** An app's name, or its package if it's gone. */
    private fun appName(pkg: String) = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** One line in the picker: a Lucide icon (tinted) or an app's own icon (as is). */
    private class Option(val label: String, val selected: Boolean, val iconRes: Int = 0, val image: android.graphics.drawable.Drawable? = null)

    private fun pickDial() {
        val prefs = Prefs(this)
        choose("Pinch and turn", DialChoice.entries.map { Option(it.label, it == prefs.dial, it.icon) }) { index ->
            val dial = DialChoice.entries[index]
            BandService.instance?.setDial(dial) ?: run { prefs.dial = dial }
        }
    }

    /** The picker: a modal on the scrim (CargoBolt: 55% ink scrim, overlay shadow). */
    private fun choose(title: String, options: List<Option>, onChoice: (Int) -> Unit) {
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
        if (BandClaim.current != null) {
            confirm("Stop claiming?", "The claim stops here. If the band already accepted this phone, its key is kept.", "Stop") {
                BandClaim.cancel()
            }
            return
        }
        if (!granted()) {
            requestPermissions(permissions(), 1)
            return
        }
        if (BandService.instance != null) {
            BandService.stop(this)
        } else if (!bluetoothOn()) {
            askForBluetooth()
        } else {
            BandService.start(this)
        }
    }

    /**
     * Claim a band from this phone: explain, sign in to Meta if there's no session yet,
     * then (the band gives up pairing mode while the sign-in page is open) ask for pairing
     * mode and run [BandClaim].
     */
    private fun claim() {
        if (!granted()) {
            requestPermissions(permissions(), 1)
            return
        }
        if (!bluetoothOn()) {
            askForBluetooth()
            return
        }
        val signedIn = SessionStore.present(this)
        confirm(
            "Claim a band?",
            "Sign in to Meta on Meta's own page; the app keeps only the resulting session. " +
                "Hold the band's button for 3 seconds until it flashes (pairing mode)" +
                (if (signedIn) ", then tap Claim. " else " once you're signed in. ") +
                "Claiming makes this phone the band's owner: it unlinks the band from Meta's glasses and app, " +
                "and a key from a computer that claimed it before stops working.",
            if (signedIn) "Claim" else "Sign in",
        ) {
            if (signedIn) runClaim() else startActivityForResult(Intent(this, MetaSignIn::class.java), SIGN_IN)
        }
    }

    private fun runClaim() {
        val session = SessionStore.load(this)
        if (session == null) {
            SessionStore.delete(this)
            log("the Meta sign-in couldn't be read; sign in again")
            startActivityForResult(Intent(this, MetaSignIn::class.java), SIGN_IN)
            return
        }
        // The companion picker doubles as the claim's device picker: the band is in
        // pairing mode for the claim anyway, so one choice links it and says which band
        // to claim. Without a picked device (cancelled, or before Android 14) it scans.
        if (Companion.linked(this)) {
            BandClaim.start(this, session)
            return
        }
        log("choose the band in the system's list to link and claim it")
        Companion.link(
            this,
            2,
            ::log,
            onLinked = { device -> BandClaim.start(this, session, device); renderSetup() },
            onFailed = { BandClaim.start(this, session) },
        )
    }

    private fun render(state: BandService.State) {
        val running = BandService.instance != null
        val claiming = state.claiming
        if (claiming != setupClaiming) {
            setupClaiming = claiming
            renderSetup()
        }
        val paused = if (running) state.paused else Prefs(this).paused
        val bluetooth = bluetoothOn()
        val (signal, label) = when {
            claiming -> Cb.Signal.INFO to "Claiming"
            !bluetooth -> Cb.Signal.STOP to "Bluetooth off"
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

        if ((power.pressed > .5f) != (running || claiming)) power.press(running || claiming)
        powerState.text = when {
            claiming -> "Claiming a band"
            !bluetooth -> "Bluetooth is off"
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
        // The wrist chosen here, else what the band reports.
        val wrist = Prefs(this).hand.takeIf { it != "band" } ?: state.hand
        for ((view, name) in listOf(wristLeft to "left", wristRight to "right")) {
            val active = wrist == name
            view.setTextColor(if (active) android.graphics.Color.WHITE else Cb.INK_700)
            view.background = if (active) GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(Cb.INK_900)
            } else null
        }
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
        val ownerKey = Identity.present(this)
        val claiming = BandClaim.current != null
        // Claiming is offered until a confirmed key is here (an unconfirmed one may be retried).
        if (!Identity.keyFile(this).exists()) {
            setupRow(
                "Claim a band",
                if (claiming) "Claiming; progress is under Activity" else "Sign in to Meta, then claim a band in pairing mode",
                false,
                if (claiming) "Stop" else "Claim",
                always = true,
                icon = R.drawable.ic_link,
            ) {
                if (claiming) toggleService() else claim()
            }
        }
        val band = Identity.bandName(this)?.let { " for $it" } ?: ""
        setupRow(
            "Owner key",
            when {
                Identity.unconfirmed(this) -> "Claimed$band, not confirmed yet; connecting confirms it"
                ownerKey -> "Loaded$band"
                else -> "Or import air-gestures-band.json from a computer"
            },
            ownerKey,
            if (ownerKey) "Replace" else "Import",
            always = true,
        ) {
            val pick = {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                    IMPORT,
                )
            }
            // Replacing drops the key this phone owns the band with; importing into an empty slot doesn't.
            if (!ownerKey) {
                pick()
            } else {
                confirm(
                    "Replace the owner key?",
                    "The key on this phone is overwritten by the one you pick. If this phone claimed the band, " +
                        "its key is the only one the band trusts: export it first, or the band has to be claimed again.",
                    "Choose file",
                    pick,
                )
            }
        }
        // Only a confirmed key is exported: the band may never have committed a pending one.
        if (Identity.keyFile(this).exists()) {
            setupRow("Export the key", "Save it for a computer (air-gestures import) or another phone", true, "Export", always = true, icon = R.drawable.ic_arrow_down) {
                confirm(
                    "Export the owner key?",
                    "The file lets anyone who has it control the band. Keep it private, and delete it once " +
                        "it's imported on the other device. The band talks to one device at a time.",
                    "Choose where",
                ) {
                    startActivityForResult(
                        Intent(Intent.ACTION_CREATE_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json")
                            .putExtra(Intent.EXTRA_TITLE, "air-gestures-band.json"),
                        EXPORT,
                    )
                }
            }
        }
        if (ownerKey) {
            setupRow("Forget the band", "Removes the key, the pairing and the link from this phone", false, "Forget", always = true, icon = R.drawable.ic_link) {
                confirm(
                    "Forget the band?",
                    "The key, the Bluetooth pairing and the companion link are removed from this phone. " +
                        "The band keeps its owner: to use it with Meta's apps again, hold its button for about 16 seconds to factory reset it.",
                    "Forget",
                ) {
                    val openSettings = Identity.forget(this)
                    log("forgot the band on this phone")
                    if (openSettings) {
                        log("tap Forget on the band in Bluetooth settings to remove the pairing")
                        startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    }
                    renderSetup()
                    render(BandService.state.value)
                }
            }
        }
        if (SessionStore.present(this)) {
            setupRow("Meta sign-in", "Signed in; used only to claim a band", true, "Sign out", always = true) {
                confirm(
                    "Sign out of Meta?",
                    "The Meta sign-in is deleted from this phone. The band keeps working; you'd sign in again " +
                        "only to claim a band.",
                    "Sign out",
                ) {
                    SessionStore.delete(this)
                    log("signed out of Meta on this phone")
                    renderSetup()
                }
            }
        }
        val bluetooth = bluetoothOn()
        setupRow("Bluetooth", if (bluetooth) "On" else "Off: the band needs it", bluetooth, "Turn on") { askForBluetooth() }
        setupRow("Bluetooth and notifications", if (granted()) "Allowed" else "Needed to connect", granted(), "Allow") {
            requestPermissions(permissions(), 1)
        }
        val linked = Companion.linked(this)
        setupRow("Companion link", if (linked) "Linked; the system wakes the app" else "Hold the band's button 3 s, then link", linked, "Link") {
            Companion.link(this, 2, log = { line -> log(line); renderSetup() })
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
        // Only asked for when the dial seeks.
        if (Prefs(this).usesMedia()) {
            val media = MediaAccess.granted(this)
            setupRow(
                "Notification access",
                if (media) "Allowed" else "Needed for playback position. If it's greyed out: App info, then Allow restricted settings",
                media,
                "Allow",
            ) {
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaAccess.component(this).flattenToString())
                runCatching { startActivity(detail) }.onFailure { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        val unrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        setupRow("Battery", if (unrestricted) "Unrestricted" else "Set to no restrictions", unrestricted, "Open") {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            startActivity(intent)
        }
    }

    private fun setupRow(
        title: String,
        detail: String,
        ok: Boolean,
        button: String?,
        always: Boolean = false,
        icon: Int? = null,
        onClick: (() -> Unit)? = null,
    ) {
        setup.addView(row(gap = 12f).apply {
            addView(icon(icon ?: if (ok) R.drawable.ic_shield_check else R.drawable.ic_circle_alert, 16f, if (ok) Cb.INK_900 else Cb.INK_500))
            addView(column(gap = 3f).apply {
                addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = title })
                addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = detail })
            }, weighted())
            if ((always || !ok) && button != null && onClick != null) addView(pill(button, null, onClick).first)
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
        if (requestCode == SIGN_IN) {
            if (resultCode == RESULT_OK) {
                log("signed in to Meta")
                renderSetup()
                confirm(
                    "Put the band in pairing mode",
                    "Hold the band's button for 3 seconds until it flashes, then tap Claim.",
                    "Claim",
                ) { runClaim() }
            } else {
                data?.getStringExtra(MetaSignIn.EXTRA_ERROR)?.let(::log)
            }
            return
        }
        if (requestCode == IMPORT && resultCode == RESULT_OK && data != null) {
            val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }
                ?: listOfNotNull(data.data)
            log(Identity.import(this, uris))
        }
        if (requestCode == EXPORT && resultCode == RESULT_OK) {
            val uri = data?.data
            log(
                try {
                    val bytes = Identity.exportBundle(this)
                    contentResolver.openOutputStream(uri ?: throw IllegalStateException("no file chosen"), "wt")
                        ?.use { it.write(bytes) } ?: throw IllegalStateException("couldn't open the file")
                    "exported the owner key; keep the file private"
                } catch (e: Exception) {
                    "export failed: ${e.message}"
                },
            )
        }
        renderSetup()
    }

    private fun log(line: String) = BandService.state.update { copy(log = (log + line).takeLast(60)) }

    /** A confirmation in the picker's style: title, body, Cancel and the action. */
    private fun confirm(title: String, body: String, action: String, onConfirm: () -> Unit) {
        val dialog = android.app.Dialog(this)
        val sheet = column(gap = 14f).apply {
            setPadding(dpi(22f), dpi(20f), dpi(22f), dpi(18f))
            background = GradientDrawable().apply {
                cornerRadius = dp(20f)
                setColor(Cb.PAGE)
            }
            elevation = dp(24f)
            addView(Cb.text(context, 22f, R.font.geist_semibold, Cb.INK_900, -.02f).apply { text = title })
            addView(Cb.text(context, 14f, color = Cb.INK_500).apply { text = body; setLineSpacing(0f, 1.3f) })
            addView(row(gap = 12f).apply {
                gravity = Gravity.END
                addView(pill("Cancel") { dialog.dismiss() }.first)
                addView(pill(action) { dialog.dismiss(); onConfirm() }.first)
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
    }

}
