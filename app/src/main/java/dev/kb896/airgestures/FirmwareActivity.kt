package dev.kb896.airgestures

import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.concurrent.thread

/** The Meta sign-in's request code (then the check). */
private const val SIGN_IN = 1

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
    private lateinit var downloads: LinearLayout
    private lateinit var checking: TextView
    /** A check with Meta is running. */
    private var asking = false

    /** What the downloads were last drawn for: the installed marker and the key notes follow these. */
    private var drawnFor: Pair<String?, Long?>? = null

    private val watch: (BandService.State) -> Unit = { state ->
        renderBand(state)
        if (drawnFor != state.firmware to state.otaKek) renderDownloads()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        running = Cb.data(this)
        status = Cb.text(this, 13f, color = Cb.INK_500)
        progress = Cb.text(this, 13f, R.font.geist_medium, Cb.INK_900)
        checking = Cb.text(this, 13f, color = Cb.INK_500)
        files = column(gap = 10f)
        downloadBar = meter().apply { visibility = android.view.View.GONE }
        downloads = column(gap = 14f)
        val band = column(gap = 10f).apply {
            addView(Cb.label(context, "Running"))
            addView(running)
            addView(status)
            addView(progress)
            addView(files)
            addView(row(gap = 10f).apply {
                addView(pill("Ask the band") { ask() }.first)
                addView(pill("Cancel update") { cancel() }.first)
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
            else -> "Tap Ask the band for its update status."
        }
        progress.text = state.otaProgress?.let { if (state.otaBusy) "Working: $it" else "Last: $it" } ?: ""
        progress.visibility = if (state.otaProgress == null) android.view.View.GONE else android.view.View.VISIBLE
        renderFiles(state.otaItems)
    }

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
        drawnFor = BandService.state.value.let { it.firmware to it.otaKek }
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
        if (!installed) {
            val bandKey = BandService.state.value.otaKek
            val need = pkg.kek
            fun note(text: String) = Cb.text(context, 13f, color = Cb.INK_700).apply { this.text = text; setLineSpacing(0f, 1.3f) }
            when {
                need == null -> {}
                bandKey == null -> {
                    addView(note("This firmware needs key $need on the band. Ask the band which key it has."))
                    addView(row().apply { addView(pill("Ask the band") { ask() }.first) })
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

    private fun ask() = ready()?.otaStatus()

    private fun cancel() = confirm(
        "Cancel the update?",
        "The band drops any update it's receiving or holding ready. What it runs now stays as it is.",
        "Cancel update",
    ) { ready()?.otaStart("cancel", "") }

    private fun update(pkg: Firmware.Package) = confirm(
        "Install ${pkg.title}?",
        "The band gets this firmware's key if it lacks it, then the firmware is sent and checked, and installed " +
            "in the same connection. Keep the band on its charger and near the phone for about 15 minutes; it " +
            "restarts at the end. This can't be undone: the band refuses older firmware.",
        "Install",
    ) { run(pkg, "update") }

    private fun installKey(pkg: Firmware.Package, key: Long) = confirm(
        "Install key $key?",
        "The band needs this key to read ${pkg.title}. It stays on the band, replacing the key it has; " +
            "it doesn't change the firmware the band runs.",
        "Install key",
    ) { run(pkg, "key") }

    private fun run(pkg: Firmware.Package, mode: String) {
        val service = ready() ?: return
        thread(name = "firmware-unpack") {
            val dir = runCatching { Firmware.unpack(this, pkg) }
            runOnUiThread {
                dir.onSuccess { service.otaStart(mode, it.path) }.onFailure { log("firmware: ${it.message}") }
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
