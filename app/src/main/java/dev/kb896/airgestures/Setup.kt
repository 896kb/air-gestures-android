package dev.kb896.airgestures

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** One line in Setup: what it is, how it stands, and the button that sorts it out. */
class Step(
    val title: String,
    val detail: String,
    /** Drawn with a check; otherwise with the alert icon and its button. */
    val done: Boolean,
    val button: String?,
    /** The button shows even when done (Replace, Export, Forget, Sign out). */
    val always: Boolean = false,
    val icon: Int? = null,
    /** Counted in Home's "N to do" while not done; actions (Claim, Forget, …) aren't. */
    val required: Boolean = true,
    val action: ((SetupActivity) -> Unit)? = null,
)

/** Setup worked out in one place, so Home's badge and the Setup screen agree. */
object Setup {
    val permissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    fun granted(context: Context) = permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun bluetoothOn(context: Context) =
        context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true

    /** How many required steps aren't done. */
    fun pending(context: Context) = steps(context).count { it.required && !it.done }

    fun steps(context: Context): List<Step> = buildList {
        val prefs = Prefs(context)
        val ownerKey = Identity.present(context)
        val claiming = BandClaim.current != null
        // Claiming is offered until a confirmed key is here (an unconfirmed one may be retried).
        if (!Identity.keyFile(context).exists()) add(Step(
            "Claim a band",
            if (claiming) "Claiming; progress is under Activity" else "Sign in to Meta, then claim a band in pairing mode",
            false,
            if (claiming) "Stop" else "Claim",
            always = true,
            icon = R.drawable.ic_link,
            required = false,
        ) { if (claiming) it.stopClaim() else it.claim() })
        val band = Identity.bandName(context)?.let { " for $it" } ?: ""
        add(Step(
            "Owner key",
            when {
                Identity.unconfirmed(context) -> "Claimed$band, not confirmed yet; connecting confirms it"
                ownerKey -> "Loaded$band"
                else -> "Or import air-gestures-band.json from a computer"
            },
            ownerKey,
            if (ownerKey) "Replace" else "Import",
            always = true,
        ) { it.importKey(replacing = ownerKey) })
        // Only a confirmed key is exported: the band may never have committed a pending one.
        if (Identity.keyFile(context).exists()) add(Step(
            "Export the key", "Save it for a computer (air-gestures import) or another phone", true, "Export",
            always = true, icon = R.drawable.ic_arrow_down, required = false,
        ) { it.exportKey() })
        if (ownerKey) add(Step(
            "Forget the band", "Removes the key, the pairing and the link from this phone", false, "Forget",
            always = true, icon = R.drawable.ic_link, required = false,
        ) { it.forgetBand() })
        if (SessionStore.present(context)) add(Step(
            "Meta sign-in", "Signed in; used only to claim a band", true, "Sign out", always = true, required = false,
        ) { it.signOut() })
        val bluetooth = bluetoothOn(context)
        add(Step("Bluetooth", if (bluetooth) "On" else "Off: the band needs it", bluetooth, "Turn on") { it.askForBluetooth() })
        val granted = granted(context)
        add(Step("Bluetooth and notifications", if (granted) "Allowed" else "Needed to connect", granted, "Allow") {
            it.requestPermissions(permissions, PERMISSIONS)
        })
        val linked = Companion.linked(context)
        add(Step("Companion link", if (linked) "Linked; the system wakes the app" else "Hold the band's button 3 s, then link", linked, "Link") {
            it.link()
        })
        // Only asked for when a screen gesture is mapped somewhere.
        if (prefs.usesScreen()) {
            val on = TouchService.instance != null
            add(Step(
                "Screen gestures",
                if (on) "On" else "Settings, Accessibility, Air Gestures. If it's greyed out: App info, then Allow restricted settings",
                on,
                "Turn on",
            ) { it.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        }
        // HyperOS drops app launches from the background without its own permission.
        if (Xiaomi.isXiaomi && prefs.usesApps()) {
            val allowed = Xiaomi.canOpenFromBackground(context)
            add(Step(
                "Open apps from gestures",
                when (allowed) {
                    true -> "Allowed"
                    false -> "Other permissions: allow Open new windows while running in the background"
                    null -> "Check Other permissions: Open new windows while running in the background"
                },
                allowed == true,
                "Open",
            ) { Xiaomi.openPermissions(it) })
        }
        // Only asked for when brightness is mapped somewhere.
        if (prefs.usesBrightness()) {
            val brightness = Settings.System.canWrite(context)
            add(Step("Brightness access", if (brightness) "Allowed" else "Needed for brightness", brightness, "Allow") {
                it.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${it.packageName}")))
            })
        }
        // Only asked for when the dial seeks.
        if (prefs.usesMedia()) {
            val media = MediaAccess.granted(context)
            add(Step(
                "Notification access",
                if (media) "Allowed" else "Needed for playback position. If it's greyed out: App info, then Allow restricted settings",
                media,
                "Allow",
            ) { screen ->
                val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, MediaAccess.component(screen).flattenToString())
                runCatching { screen.startActivity(detail) }
                    .onFailure { screen.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            })
        }
        val unrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
        add(Step("Battery", if (unrestricted) "Unrestricted" else "Set to no restrictions", unrestricted, "Open") {
            @SuppressLint("BatteryLife")
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}"))
            it.startActivity(intent)
        })
    }
}
