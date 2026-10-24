package dev.kb896.airgestures

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** The Meta sign-in's request code (then the check). */
private const val SIGN_IN = 1
/** Off its charger, the band must be at least this (%) to start an update: a margin of ours, not Meta's. */
private const val MIN_BATTERY = 50
/** Unplugged, the phone must be at least this (%) to start one: it does the sending. */
private const val MIN_PHONE_BATTERY = 25

/**
 * Setup › Firmware: what the band runs, the builds Meta offers it, and installing one: the key
 * first if the band lacks it, then the firmware. The band accepts Install only in the
 * connection that sent and verified the update, so installing sends, checks and installs in one go.
 */
class FirmwareActivity : Screen() {
    private lateinit var running: TextView
    private lateinit var status: TextView
    private lateinit var progress: TextView
    /** A meter per file of the running (or last) update. */
    private lateinit var files: LinearLayout
    private lateinit var downloadBar: android.widget.ProgressBar
    /** Cancel, under an update in progress (or one the band holds), greyed while not safe. */
    private lateinit var cancelRow: LinearLayout
    private lateinit var cancelButton: TextView
    private lateinit var cancelNote: TextView

    /** After the band accepts Install: restarting, checking, then the result. */
    private lateinit var installStage: LinearLayout
    private lateinit var installText: TextView
    private lateinit var installBar: android.widget.ProgressBar
    private lateinit var downloads: LinearLayout
    private lateinit var checking: TextView
    /** A check with Meta is running. */
    private var asking = false

    /**
     * An update's progress, meters, install stage and Cancel: in the card of the build being
     * installed, else (a key or a cancel alone) under the band's status.
     */
    private lateinit var updateBox: LinearLayout
    private lateinit var bandSlot: LinearLayout
    /** The build last started from this screen. */
    private var started: String? = null

    /** What the downloads were last drawn for: the installed marker, the key notes and the progress's place follow these. */
    private var drawnFor: List<Any?>? = null

    private fun drawKey(state: BandService.State) =
        listOf(state.firmware, state.otaKek, activeVersion(), state.otaBusy || state.install?.stage.let { it == "restarting" || it == "checking" })

    /** The build an update is (or was last) about. */
    private fun activeVersion(): String? = started
        ?: BandService.state.value.install?.version?.toString()
        ?: Prefs(this).otaExpect.takeIf { it != 0L }?.toString()

    private val watch: (BandService.State) -> Unit = { state ->
        renderBand(state)
        if (drawnFor != drawKey(state)) renderDownloads()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        running = Cb.data(this)
        status = Cb.text(this, 13f, color = Cb.INK_500)
        progress = Cb.text(this, 13f, R.font.geist_medium, Cb.INK_900)
        checking = Cb.text(this, 13f, color = Cb.INK_500)
        files = column(gap = 10f)
        downloadBar = meter().apply { visibility = android.view.View.GONE }
        installText = Cb.text(this, 13f, R.font.geist_medium, Cb.INK_900)
        installBar = meter()
        cancelButton = pill("Cancel update") { cancel() }.first
        cancelNote = Cb.text(this, 12f, color = Cb.INK_500)
        cancelRow = row(gap = 10f).apply {
            addView(cancelButton)
            addView(cancelNote, weighted())
            visibility = android.view.View.GONE
        }
        installStage = column(gap = 6f).apply {
            addView(Cb.label(context, "Install"))
            addView(installText)
            addView(installBar)
            visibility = android.view.View.GONE
        }
        updateBox = column(gap = 10f).apply {
            addView(progress)
            addView(files)
            addView(installStage)
            addView(cancelRow)
        }
        bandSlot = column()
        downloads = column(gap = 14f)
        val band = column(gap = 10f).apply {
            addView(Cb.label(context, "Running"))
            addView(running)
            addView(status)
            addView(bandSlot)
            addView(row(gap = 10f).apply {
                addView(pill("Refresh status") { ask() }.first)
            })
        }
        val offered = column(gap = 12f).apply {
            addView(Cb.text(context, 13f, color = Cb.INK_500).apply {
                text = "Builds Meta offers this band, downloaded to the phone. Install firmware sends one to the band, " +
                    "has the band check it, and installs it: the band then restarts (keep it on its charger, " +
                    "about 15 minutes). An install can't be undone: the band refuses older firmware."
                setLineSpacing(0f, 1.3f)
            })
            addView(row(gap = 10f).apply { addView(pill("Check with Meta") { checkMeta() }.first) })
            addView(checking)
            addView(downloadBar)
            addView(downloads)
        }
        show(pageColumn().apply {
            addView(card("Band", band))
            addView(warning())
            addView(card("Firmware", offered))
        }, title = "Firmware")
    }

    /** Above the install buttons: what an update can do to the band, in plain terms. */
    private fun warning() = Cb.text(this, 14f, R.font.geist_bold, Cb.Signal.STOP.label).apply {
        text = "WARNING: updating the firmware can brick your band. This is not Meta's updater and " +
            "nothing about it is guaranteed to work. It has been tested, on one band, but a failed or " +
            "interrupted update could leave yours unusable, and Meta may not repair or replace it. An " +
            "installed update can't be undone: to the best of our knowledge you cannot downgrade the " +
            "firmware, because the band refuses older versions. Go on only if you accept that risk."
        setLineSpacing(0f, 1.3f)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(Cb.Signal.STOP.fill)
        }
        setPadding(dpi(16f), dpi(14f), dpi(16f), dpi(14f))
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
        val state = BandService.state.value
        renderBand(state)
        renderDownloads()
        // The key notes need the band's update status: ask once on opening.
        if (state.otaKek == null && state.phase == Phase.CONNECTED && !state.otaBusy) BandService.instance?.otaStatus()
    }

    override fun onPause() {
        BandService.state.remove(watch)
        super.onPause()
    }

    private fun renderBand(state: BandService.State) {
        val connected = BandService.instance != null && state.phase == Phase.CONNECTED
        running.text = state.firmware?.let { "${Firmware.name(it)} · $it" } ?: "—"
        status.text = when {
            !connected -> "Connect the band to update it."
            state.otaStatus != null -> "Update status: ${state.otaStatus}"
            else -> "Tap Refresh status for the band's update status."
        }
        progress.text = state.otaProgress?.let { if (state.otaBusy) "Working: $it" else "Last: $it" } ?: ""
        progress.visibility = if (state.otaProgress == null) android.view.View.GONE else android.view.View.VISIBLE
        renderFiles(state.otaItems)
        renderInstall(state.install)
        renderCancel(state)
    }

    /**
     * Cancel shows while an update runs or the band holds one (in progress or ready), and works
     * until the band is told to install: then it greys out, and goes once the install is done.
     */
    private fun renderCancel(state: BandService.State) {
        val held = state.otaStatus?.let { it.startsWith("UPDATE_IN_PROGRESS") || it.startsWith("UPDATE_READY") } == true
        val installing = state.install?.stage.let { it == "restarting" || it == "checking" }
        cancelRow.visibility = if (state.otaBusy || held || installing) android.view.View.VISIBLE else android.view.View.GONE
        val safe = state.otaCancellable && !installing
        cancelButton.isEnabled = safe
        cancelButton.alpha = if (safe) 1f else .4f
        cancelNote.text = if (safe) "Stops the update; the band keeps the firmware it runs." else "Not safe to cancel right now: the band is installing."
    }

    private fun renderInstall(install: BandService.Install?) {
        installStage.visibility = if (install == null) android.view.View.GONE else android.view.View.VISIBLE
        install ?: return
        val seconds = (android.os.SystemClock.uptimeMillis() - install.since) / 1000
        val name = Firmware.name(install.version.toString())
        installText.text = when (install.stage) {
            "restarting" -> "The band is installing $name and restarting… ${seconds}s (about a minute)"
            "checking" -> "The band is back; checking what it runs…"
            "installed" -> "Installed: the band runs $name"
            else -> "Not confirmed: the band runs ${install.running?.let { Firmware.name(it) } ?: "unknown firmware"}, not $name"
        }
        val finished = install.stage == "installed" || install.stage == "failed"
        installBar.isIndeterminate = !finished
        installBar.progress = if (finished) 1000 else 0
        installBar.progressTintList = android.content.res.ColorStateList.valueOf(
            when (install.stage) {
                "installed" -> Cb.Signal.LIVE.fill
                "failed" -> Cb.Signal.STOP.fill
                else -> Cb.INK_900
            },
        )
        installText.setTextColor(if (install.stage == "failed") Cb.Signal.STOP.fill else Cb.INK_900)
        // The seconds count while it restarts.
        installStage.removeCallbacks(installTick)
        if (!finished) installStage.postDelayed(installTick, 1000)
    }

    private val installTick = Runnable { renderInstall(BandService.state.value.install) }

    /** "2 files to send", then a labelled meter for each. */
    private fun renderFiles(items: List<BandService.OtaItem>) {
        if (files.childCount != items.size * 2 + 1) {
            files.removeAllViews()
            if (items.isEmpty()) return
            files.addView(Cb.label(this, if (items.size == 1) "1 file to send" else "${items.size} files to send"))
            for (item in items) {
                files.addView(Cb.text(this, 13f, R.font.geist_medium, Cb.INK_900))
                files.addView(meter())
            }
        }
        items.forEachIndexed { i, item ->
            val percent = if (item.size == 0L) 0 else (item.sent * 100 / item.size).toInt()
            val stage = when (item.stage) {
                "sending" -> "sending, $percent %"
                "checking" -> "the band is checking it"
                "done" -> "sent and checked"
                else -> "waiting"
            }
            val name = item.name.replaceFirstChar { it.uppercase() }
            (files.getChildAt(1 + i * 2) as TextView).text = "$name · ${"%.1f".format(item.size / 1e6)} MB · $stage"
            (files.getChildAt(2 + i * 2) as android.widget.ProgressBar).progress = if (item.size == 0L) 0 else (item.sent * 1000 / item.size).toInt()
        }
    }

    private fun meter() = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progressTintList = android.content.res.ColorStateList.valueOf(Cb.INK_900)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(6f))
    }

    private fun renderDownloads() {
        downloads.removeAllViews()
        drawnFor = drawKey(BandService.state.value)
        (updateBox.parent as? LinearLayout)?.removeView(updateBox)
        bandSlot.addView(updateBox)
        val packages = Firmware.packages(this)
        if (packages.isEmpty()) {
            downloads.addView(Cb.text(this, 13f, color = Cb.INK_500).apply { text = "Nothing downloaded yet." })
            return
        }
        val current = BandService.state.value.firmware
        for (pkg in packages) downloads.addView(packageView(pkg, pkg.version == current))
    }

    private fun packageView(pkg: Firmware.Package, installed: Boolean) = column(gap = 8f).apply {
        background = Cb.hairlineCard(context, 10f)
        setPadding(dpi(12f), dpi(10f), dpi(12f), dpi(12f))
        addView(row(gap = 8f).apply {
            addView(Cb.text(context, 15f, R.font.geist_semibold, Cb.INK_900).apply { text = pkg.title }, weighted())
            if (installed) addView(Cb.chip(context, Cb.Signal.LIVE, "Installed"))
        })
        addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = "${pkg.version} · ${"%.1f".format(pkg.size / 1e6)} MB" })
        pkg.notes?.let { notes -> addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = notes; setLineSpacing(0f, 1.25f) }) }
        // While this build is being sent or installed, its card shows the progress only.
        val updating = pkg.version == activeVersion() && drawKey(BandService.state.value).last() == true
        if (!installed && !updating) {
            val bandKey = BandService.state.value.otaKek
            val need = pkg.kek
            fun note(text: String) = Cb.text(context, 13f, color = Cb.INK_700).apply { this.text = text; setLineSpacing(0f, 1.3f) }
            when {
                need == null -> {}
                bandKey == null -> {
                    addView(note("This firmware needs key $need on the band. Tap Refresh status to see which key it has."))
                    addView(row().apply { addView(pill("Refresh status") { ask() }.first) })
                }
                bandKey != need -> {
                    addView(note("Your band has key $bandKey, but this firmware needs key $need installed on the band."))
                    addView(row().apply { addView(pill("Install key $need") { installKey(pkg, need) }.first) })
                }
                else -> addView(note("Your band has key $need, which this firmware needs."))
            }
            addView(note(if (need != null && bandKey != need) "Once the key is installed you can install the firmware." else "You can install the firmware."))
            addView(row().apply { addView(pill("Install firmware") { update(pkg) }.first) })
        }
        if (pkg.version == activeVersion()) {
            (updateBox.parent as? LinearLayout)?.removeView(updateBox)
            addView(updateBox)
        }
    }

    /** The service, when the band is connected and no update runs; else say why not. */
    private fun ready(): BandService? {
        val service = BandService.instance
        val state = BandService.state.value
        return when {
            service == null || state.phase != Phase.CONNECTED -> null.also { log("connect the band first") }
            state.otaBusy -> null.also { log("an update is already running") }
            else -> service
        }
    }

    /**
     * Before changing the band: on its charger, or charged enough to get through a send and
     * the restart. The band refuses updates when low too, but only when one starts.
     */
    private fun powered(): Boolean {
        val phone = registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val phoneLevel = phone?.let { it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1) }
        val phoneCharging = phone?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (!phoneCharging && phoneLevel != null && phoneLevel in 0 until MIN_PHONE_BATTERY) {
            sheet(
                "Charge the phone first",
                Cb.text(this, 14f, color = Cb.INK_500).apply {
                    text = "The phone is at $phoneLevel %. Plug it in, or charge it to at least $MIN_PHONE_BATTERY %, " +
                        "before updating: the phone sends the firmware for about 15 minutes, and if it dies or " +
                        "sleeps partway the update has to start again."
                    setLineSpacing(0f, 1.3f)
                },
                "OK",
            ) { true }
            return false
        }
        val state = BandService.state.value
        val level = state.battery
        if (state.charging || (level != null && level >= MIN_BATTERY)) return true
        sheet(
            "Charge the band first",
            Cb.text(this, 14f, color = Cb.INK_500).apply {
                text = (if (level == null) "The band hasn't said how charged it is yet. " else "The band is at $level %. ") +
                    "Put it on its charger, or charge it to at least $MIN_BATTERY %, before updating: a send takes about " +
                    "15 minutes, and running out partway through the install could leave it unusable."
                setLineSpacing(0f, 1.3f)
            },
            "OK",
        ) { true }
        return false
    }

    private fun ask() = ready()?.otaStatus()

    private fun cancel() = confirm(
        "Cancel the update?",
        "The update stops, and the band drops what it received so far. What it runs now stays as it is.",
        "Cancel update",
    ) {
        // A running update is stopped by the cancel itself, so don't wait for it to finish.
        val service = BandService.instance
        if (service == null || BandService.state.value.phase != Phase.CONNECTED) log("connect the band first")
        else service.otaStart("cancel", "")
    }

    private fun update(pkg: Firmware.Package) = if (powered()) confirmUpdate(pkg) else Unit

    private fun confirmUpdate(pkg: Firmware.Package) = confirm(
        "Install ${pkg.title}?",
        "The band gets this firmware's key if it lacks it, then the firmware is sent and checked, and installed " +
            "in the same connection, about 15 minutes; the band restarts at the end. Keep the band on its charger, " +
            "keep your phone and band nearby, and try to stay on this app until it says Installed. " +
            "This can't be undone: the band refuses older firmware.",
        "Install",
    ) { run(pkg, "update") }

    private fun installKey(pkg: Firmware.Package, key: Long) = if (powered()) confirmKey(pkg, key) else Unit

    private fun confirmKey(pkg: Firmware.Package, key: Long) = confirm(
        "Install key $key?",
        "The band needs this key to read ${pkg.title}. It stays on the band, replacing the key it has; " +
            "it doesn't change the firmware the band runs.",
        "Install key",
    ) { run(pkg, "key") }

    private fun run(pkg: Firmware.Package, mode: String) {
        // Checked again: the band may have come off its charger while the question was open.
        if (!powered()) return
        val service = ready() ?: return
        thread(name = "firmware-unpack") {
            val dir = runCatching { Firmware.unpack(this, pkg) }
            runOnUiThread {
                dir.onSuccess {
                    service.otaStart(mode, it.path)
                    started = pkg.version
                    renderDownloads()
                    // The progress is in this build's card: keep it in view.
                    updateBox.post { updateBox.requestRectangleOnScreen(android.graphics.Rect(0, 0, updateBox.width, updateBox.height)) }
                }.onFailure { log("firmware: ${it.message}") }
            }
        }
    }

    private fun checkMeta() {
        if (asking) return
        val session = SessionStore.load(this)
        if (session == null) {
            confirm(
                "Sign in to Meta?",
                "Meta offers firmware to signed-in owners of the band. Sign in on Meta's own page; the app keeps only the session.",
                "Sign in",
            ) { startActivityForResult(Intent(this, MetaSignIn::class.java), SIGN_IN) }
            return
        }
        val serial = BandService.instance?.deviceInfo()?.optString("serial")?.takeIf { it.isNotEmpty() }
        if (serial == null) {
            checking.text = "Connect the band first: Meta asks which band it is."
            return
        }
        asking = true
        checking.text = "Asking Meta…"
        thread(name = "firmware-check") {
            val result = runCatching { Firmware.check(this, session, serial) }
            runOnUiThread {
                asking = false
                downloadBar.visibility = android.view.View.GONE
                checking.text = result.getOrElse { "Couldn't check: ${it.message}" }
                renderDownloads()
            }
        }
        checking.post(downloadTick)
    }

    /** While Meta is asked: the download's meter. */
    private val downloadTick: Runnable = object : Runnable {
        override fun run() {
            if (!asking) return
            val progress = runCatching { org.json.JSONObject(Bridge.firmwareProgress()) }.getOrNull()
            if (progress != null && progress.has("done")) {
                val done = progress.getLong("done")
                val total = progress.getLong("total")
                val name = Firmware.name(progress.getString("version"))
                downloadBar.visibility = android.view.View.VISIBLE
                downloadBar.isIndeterminate = total <= 0
                if (total > 0) downloadBar.progress = (done * 1000 / total).toInt()
                checking.text = "Downloading $name: ${"%.1f".format(done / 1e6)}" +
                    (if (total > 0) " of ${"%.1f".format(total / 1e6)} MB" else " MB")
            }
            checking.postDelayed(this, 250)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN && resultCode == RESULT_OK) checkMeta()
    }
}
