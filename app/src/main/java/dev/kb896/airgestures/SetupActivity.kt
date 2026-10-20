package dev.kb896.airgestures

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout

/** The companion picker's request code. */
private const val COMPANION = 2
/** The file picker's request code for importing the owner key. */
private const val IMPORT = 3
/** The Meta sign-in's request code (then the claim). */
private const val SIGN_IN = 4
/** The file picker's request code for exporting the owner key. */
private const val EXPORT = 5

/** Setup: the owner key, the permissions and the system settings the band needs. */
class SetupActivity : Screen() {
    private lateinit var steps: LinearLayout
    /** The claim state the steps were last drawn for. */
    private var drawnClaiming = false
    private val watch: (BandService.State) -> Unit = { state -> if (state.claiming != drawnClaiming) render() }

    /** Bluetooth switched on or off: its step follows. */
    private val bluetoothChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        steps = column(gap = 14f)
        show(pageColumn().apply { addView(card(null, steps)) }, title = "Setup")
    }

    override fun onResume() {
        super.onResume()
        BandService.state.observe(watch)
        registerReceiver(bluetoothChanged, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        render()
    }

    override fun onPause() {
        BandService.state.remove(watch)
        runCatching { unregisterReceiver(bluetoothChanged) }
        super.onPause()
    }

    private fun render() {
        drawnClaiming = BandService.state.value.claiming
        steps.removeAllViews()
        Setup.steps(this).forEach { steps.addView(stepRow(it)) }
    }

    private fun stepRow(step: Step) = row(gap = 12f).apply {
        addView(icon(step.icon ?: if (step.done) R.drawable.ic_shield_check else R.drawable.ic_circle_alert, 16f, if (step.done) Cb.INK_900 else Cb.INK_500))
        addView(column(gap = 3f).apply {
            addView(Cb.text(context, 14f, R.font.geist_medium, Cb.INK_900).apply { text = step.title })
            addView(Cb.text(context, 12f, color = Cb.INK_500).apply { text = step.detail })
        }, weighted())
        val action = step.action
        if ((step.always || !step.done) && step.button != null && action != null) {
            addView(pill(step.button) { action(this@SetupActivity) }.first)
        }
    }

    fun link() = Companion.link(this, COMPANION, log = { line -> log(line); render() })

    /**
     * Claim a band from this phone: explain, sign in to Meta if there's no session yet,
     * then (the band gives up pairing mode while the sign-in page is open) ask for pairing
     * mode and run [BandClaim].
     */
    fun claim() {
        if (!Setup.granted(this)) {
            requestPermissions(Setup.permissions, PERMISSIONS)
            return
        }
        if (!Setup.bluetoothOn(this)) {
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
            COMPANION,
            ::log,
            onLinked = { device -> BandClaim.start(this, session, device); render() },
            onFailed = { BandClaim.start(this, session) },
        )
    }

    /** Import an owner key; replacing one asks first (it may be the only key the band trusts). */
    fun importKey(replacing: Boolean) {
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
        if (!replacing) {
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

    fun exportKey() = confirm(
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

    fun forgetBand() = confirm(
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
        render()
    }

    fun signOut() = confirm(
        "Sign out of Meta?",
        "The Meta sign-in is deleted from this phone. The band keeps working; you'd sign in again " +
            "only to claim a band.",
        "Sign out",
    ) {
        SessionStore.delete(this)
        log("signed out of Meta on this phone")
        render()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SIGN_IN) {
            if (resultCode == RESULT_OK) {
                log("signed in to Meta")
                render()
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
        render()
    }
}
