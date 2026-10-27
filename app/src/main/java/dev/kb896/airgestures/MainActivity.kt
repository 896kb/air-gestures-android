package dev.kb896.airgestures

import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Same Ko-fi page the website links. */
private const val KOFI_URL = "https://ko-fi.com/88896kb"

/**
 * Home: the band, its state, the controls, and the way to the other screens. With two bands a
 * switch above the status picks which one the status, the switches and Setup are about (a
 * sideways swipe over them too).
 */
class MainActivity : Screen() {
    private val watch: (BandService.State) -> Unit = ::render

    private lateinit var chip: FrameLayout
    private lateinit var subtitle: TextView
    private lateinit var power: NeuDrawable
    private lateinit var powerState: TextView
    private lateinit var battery: TextView
    private lateinit var hand: TextView
    private lateinit var firmware: TextView
    private lateinit var gestures: TextView
    private lateinit var lastGesture: TextView
    private lateinit var profile: TextView
    private lateinit var controls: Pair<TextView, NeuDrawable>
    private lateinit var fields: Pair<TextView, NeuDrawable>
    private lateinit var fieldsDetail: TextView
    private lateinit var choicesPopup: TextView
    private lateinit var choicesInline: TextView
    private lateinit var wristLeft: TextView
    private lateinit var wristRight: TextView
    private var player: MediaPlayer? = null
    /** `start`/`pause` are only legal once the player is prepared (else it drops into its error state). */
    private var prepared = false
    private var heroView: View? = null
    /** Whether the hero shows the band as live (full speed and opacity); null = not set yet. */
    private var heroLive: Boolean? = null
    private lateinit var setupNav: FrameLayout
    /** With two bands: which one this page is about. */
    private lateinit var bandSwitch: FrameLayout
    /** With two bands: the profiles this band uses. */
    private lateinit var bandProfiles: FrameLayout
    /** The band names the switch was last drawn with. */
    private var drawnBands: List<String>? = null
    /** The claim state the Setup row was last counted for. */
    private var navClaiming = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The bottom room keeps the last card clear of the Ko-fi pill.
        val page = pageColumn(bottom = 88f).apply {
            addView(header())
            update = column(gap = 0f)
            addView(update)
            hero()?.let(::addView)
            addView(powerBlock())
            bandSwitch = FrameLayout(context)
            addView(bandSwitch)
            addView(statusCard().also(::swipeBetweenBands))
            addView(togglesBlock().also(::swipeBetweenBands))
            addView(navCard())
            // The installed version, quietly, bottom left.
            addView(Cb.text(context, 12f, color = Cb.INK_400).apply {
                text = "v" + UpdateCheck.version(context)
            })
        }
        show(page, floating = kofiPill())
        requestPermissions(Setup.permissions, PERMISSIONS)
    }

    /** Where the rest of the app lives. */
    private fun navCard() = card(null, column().apply {
        addView(navRow(R.drawable.ic_hand, "Gestures") { open<GesturesActivity>() })
        addView(navRow(R.drawable.ic_graduation_cap, "Practice") { open<PracticeActivity>() })
        setupNav = FrameLayout(context)
        addView(setupNav)
        addView(navRow(R.drawable.ic_activity, "Activity") { open<ActivityLogActivity>() })
        addView(navRow(R.drawable.ic_heart, "Credits") { open<CreditsActivity>() })
    })

    /** Setup's row, with how many steps are left. */
    private fun renderNav() {
        val pending = Setup.pending(this)
        setupNav.removeAllViews()
        setupNav.addView(navRow(R.drawable.ic_shield_check, "Setup", if (pending > 0) "$pending to do" else null) {
            open<SetupActivity>()
        })
    }

    override fun onResume() {
        super.onResume()
        // A band added or removed elsewhere (Setup): the page follows.
        BandService.selectionChanged(this)
        BandService.state.observe(watch)
        registerReceiver(bluetoothChanged, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
        renderNav()
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
            renderNav()
        }
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
        val (p, pv) = stat("Profile"); profile = pv.apply { typeface = Cb.font(context, R.font.geist_medium); textSize = 15f }
        // Tapping the profile picks this band's from its own.
        p.isClickable = true
        p.background = ripple()
        p.setOnClickListener { pickProfile() }
        val (f, fv) = stat("Firmware"); firmware = fv.apply { typeface = Cb.font(context, R.font.geist_medium); textSize = 15f }
        return card("Status", column(gap = 16f).apply {
            addView(row().apply { addView(b, weighted()); addView(h, weighted()) })
            addView(row().apply { addView(g, weighted()); addView(l, weighted()) })
            addView(row().apply { addView(p, weighted()); addView(f, weighted()) })
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

    /**
     * Handwriting in text fields on or off. It needs firmware with a handwriting model (build 64
     * and later) and its own accessibility service, which the system has to turn on.
     */
    private fun toggleFields() {
        val prefs = Prefs(this)
        if (prefs.writeInFields) {
            prefs.writeInFields = false
            render(BandService.state.value)
            return
        }
        if (BandService.instance?.bandWrites() == false) {
            confirm(
                "Update the band first",
                "The band's firmware has no handwriting model. Handwriting came with firmware build 64; " +
                    "update the band in Setup › Band firmware updates, then turn this on.",
                "Open firmware",
            ) { open<FirmwareActivity>() }
            return
        }
        prefs.writeInFields = true
        Words.load(this)
        render(BandService.state.value)
        if (!WriteService.enabled(this)) {
            confirm(
                "Turn on the text field service",
                "To type into text fields, Air Gestures needs its \"handwriting in text fields\" accessibility " +
                    "service. It notices which field you're in and changes only that field's text; password " +
                    "fields are left alone, and nothing leaves the phone.\n\nIn Accessibility, tap Installed apps, then " +
                    "turn on \"${applicationInfo.loadLabel(packageManager)} handwriting in text fields\".",
                "Open settings",
            ) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
    }

    /** Pick the profile in force on the band this page shows, from the ones it uses. */
    private fun pickProfile() {
        val slot = Bands.selected(this)
        val list = Bands.profiles(this, slot)
        if (list.profiles.size < 2) return
        val active = list.active(Profiles.foreground)
        choose(
            "Profile",
            list.profiles.map { Option(it.name, it.id == active.id, R.drawable.ic_layers) },
            heading = if (list.locked(Profiles.foreground)) "The app in front has its own" else "Switch to",
        ) { index ->
            BandService.pickProfile(this, list.profiles[index].id)
            render(BandService.state.value)
        }
    }

    /** A sideways fling over [view] shows the other band (with two). */
    @SuppressLint("ClickableViewAccessibility")
    private fun swipeBetweenBands(view: View) {
        val detector = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (!Bands.two(this@MainActivity) || Math.abs(vx) < 2 * Math.abs(vy) || Math.abs(vx) < dp(400f)) return false
                // As the switch reads: swiping left shows the band on the right.
                val order = Bands.inOrder(this@MainActivity)
                showBand(if (vx < 0) order.last() else order.first())
                return true
            }
        })
        // Taking the touch from its start: a sideways fling then reaches it (the page still scrolls).
        view.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
    }

    private fun showBand(slot: Int) {
        if (slot == Bands.selected(this)) return
        Bands.select(this, slot)
        renderNav()
    }

    /** The band switch, drawn again when the names change. */
    private fun renderBands() {
        val two = Bands.two(this)
        // The left wrist's band on the left; the switch centred.
        val order = if (two) Bands.inOrder(this) else emptyList()
        val names = order.map { Bands.name(this, it) }
        if (names != drawnBands) {
            drawnBands = names
            bandSwitch.removeAllViews()
            if (two) bandSwitch.addView(
                segmented(*order.map { slot -> segment(Bands.name(this, slot)) { showBand(slot) } }.toTypedArray()),
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL),
            )
        }
        bandSwitch.visibility = if (two) View.VISIBLE else View.GONE
        val selected = Bands.selected(this)
        (bandSwitch.getChildAt(0) as? LinearLayout)?.let { track ->
            for (i in 0 until track.childCount) {
                val view = track.getChildAt(i) as TextView
                val active = order.getOrNull(i) == selected
                view.setTextColor(if (active) android.graphics.Color.WHITE else Cb.INK_700)
                view.background = if (active) GradientDrawable().apply {
                    cornerRadius = dp(999f)
                    setColor(Cb.INK_900)
                } else null
            }
        }
        bandProfiles.removeAllViews()
        bandProfiles.visibility = if (two) View.VISIBLE else View.GONE
        if (two) {
            val mine = Bands.profiles(this, selected).profiles
            val detail = if (Bands.on(this, selected) == null) "All of them" else mine.joinToString(", ") { it.name }
            bandProfiles.addView(navRow(R.drawable.ic_layers, "Profiles on this band", detail = detail) {
                BandProfilesActivity.open(this, selected)
            })
        }
    }

    private fun setHand(hand: String) {
        BandService.instance?.setHand(hand) ?: run { Prefs(this).hand = hand }
        render(BandService.state.value)
        // Judgement 16: a wrist this band has no palm positions for asks to calibrate (once a band is known).
        val band = Prefs(this).bandAddress ?: return
        if ((hand == "left" || hand == "right") && Calibrations.get(this, band, hand).palm == null) {
            val until = if (hand == "left") "every gesture on the left wrist counts as palm down" else "the right wrist uses positions measured on another hand"
            confirm(
                "Calibrate your $hand wrist?",
                "Palm positions are measured per band and wrist. Until you do, $until.",
                "Calibrate",
            ) { open<PalmCalibrationActivity>() }
        }
    }

    /** What writing in a text field does, and the gestures meanwhile (the ? by its switch). */
    private fun fieldsHelp() {
        val body = column(gap = 14f)
        for ((heading, text) in Sections.read(this, "fields-help.txt")) {
            body.addView(column(gap = 4f).apply {
                addView(Cb.text(context, 15f, R.font.geist_semibold, Cb.INK_900).apply { this.text = heading })
                addView(Cb.text(context, 14f, color = Cb.INK_700).apply {
                    this.text = text
                    setLineSpacing(0f, 1.3f)
                })
            })
        }
        val scroll = ScrollView(this).apply {
            addView(body)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * .6f).toInt())
        }
        sheet("Handwriting in text fields", scroll, "Got it", cancel = false) { true }
    }

    private fun togglesBlock() = column(gap = 16f).apply {
        controls = pill("Controls on", R.drawable.ic_hand) {
            BandService.instance?.setPaused(!BandService.state.value.paused)
                ?: Prefs(context).let { it.paused = !it.paused; render(BandService.state.value) }
        }
        addView(settingRow("Controls", "Or hold the middle finger", controls.first))
        fields = pill("Off", R.drawable.ic_pencil) { toggleFields() }
        fieldsDetail = Cb.text(context, 13f, color = Cb.INK_500)
        addView(row(gap = 12f).apply {
            addView(column(gap = 4f).apply {
                addView(row(gap = 6f).apply {
                    addView(Cb.label(context, "Handwriting in text fields"))
                    addView(FrameLayout(context).apply {
                        addView(icon(R.drawable.ic_help, 18f, Cb.INK_500), FrameLayout.LayoutParams(dpi(18f), dpi(18f), Gravity.CENTER))
                        layoutParams = LinearLayout.LayoutParams(dpi(30f), dpi(30f))
                        contentDescription = "How handwriting in text fields works"
                        isClickable = true
                        background = ripple(999f)
                        setOnClickListener { fieldsHelp() }
                    })
                })
                addView(fieldsDetail)
            }, weighted())
            addView(fields.first)
        })
        choicesPopup = segment("Popup") { Prefs(context).choicesPopup = true; render(BandService.state.value) }
        choicesInline = segment("In the field") { Prefs(context).choicesPopup = false; render(BandService.state.value) }
        addView(settingRow("Word choices", "Swipe up while writing for a word's other readings", segmented(choicesPopup, choicesInline)))
        wristLeft = segment("Left") { setHand("left") }
        wristRight = segment("Right") { setHand("right") }
        addView(settingRow("Wrist", "Which arm the band is on", segmented(wristLeft, wristRight)))
        bandProfiles = FrameLayout(context)
        addView(bandProfiles)
    }

    // ---- state ----

    private fun toggleService() {
        if (BandClaim.current != null) {
            stopClaim()
            return
        }
        if (!Setup.granted(this)) {
            requestPermissions(Setup.permissions, PERMISSIONS)
            return
        }
        // This band's connection only (with two, the other keeps its own).
        val slot = Bands.selected(this)
        if (BandService.connected(this, slot)) {
            BandService.connect(this, slot, false)
        } else if (!Setup.bluetoothOn(this)) {
            askForBluetooth()
        } else {
            BandService.connect(this, slot, true)
        }
        render(BandService.state.value)
    }

    private fun render(state: BandService.State) {
        // This band's connection: the service runs and keeps it.
        val running = BandService.connected(this)
        val claiming = state.claiming
        if (claiming != navClaiming) {
            navClaiming = claiming
            renderNav()
        }
        val paused = if (running) state.paused else Prefs(this).paused
        val bluetooth = Setup.bluetoothOn(this)
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
        renderBands()
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
        profile.text = state.profile ?: Profiles.active(this).name
        firmware.text = state.firmware?.let { Firmware.name(it) } ?: "—"

        // Toggled-on states are pushed, toggled-off states raised.
        controls.first.text = if (paused) "Controls off" else "Controls on"
        if ((controls.second.pressed > .5f) == paused) controls.second.press(!paused)
        controls.first.setCompoundDrawablesRelative(
            getDrawable(if (paused) R.drawable.ic_hand_off else R.drawable.ic_hand)!!.mutate().apply {
                setBounds(0, 0, dpi(16f), dpi(16f))
                setTint(Cb.INK_900)
            }, null, null, null,
        )
        val writing = Prefs(this).writeInFields
        fields.first.text = if (writing) "On" else "Off"
        if ((fields.second.pressed > .5f) != writing) fields.second.press(writing)
        val elsewhere = Bands.writer(this)?.takeIf { it != Bands.selected(this) }
        fieldsDetail.text = when {
            BandService.instance?.bandWrites() == false -> "Needs band firmware build 64 or later: Setup › Band firmware updates"
            elsewhere != null -> "On for the ${Bands.name(this, elsewhere).lowercase()}; turning it on here moves it"
            writing && !WriteService.enabled(this) -> "Turn on its accessibility service to start"
            else -> "In a text field, write letters on a table with a finger"
        }
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
        for ((view, active) in listOf(choicesPopup to Prefs(this).choicesPopup, choicesInline to !Prefs(this).choicesPopup)) {
            view.setTextColor(if (active) android.graphics.Color.WHITE else Cb.INK_700)
            view.background = if (active) GradientDrawable().apply {
                cornerRadius = dp(999f)
                setColor(Cb.INK_900)
            } else null
        }
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderNav()
    }

}

