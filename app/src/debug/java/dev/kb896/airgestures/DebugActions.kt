package dev.kb896.airgestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Debug builds only: run any action without the band, for tests on an emulator.
 *
 *     adb shell am broadcast -n dev.kb896.airgestures.dev/dev.kb896.airgestures.DebugActions \
 *         -a dev.kb896.airgestures.RUN --es action nav.next
 *
 * Or probe the band's streams from the next connection on (reconnects now; "" turns it off),
 * then read `adb logcat -s AirGestures`:
 *
 *     adb shell am broadcast -n dev.kb896.airgestures.dev/dev.kb896.airgestures.DebugActions \
 *         -a dev.kb896.airgestures.RUN --es probe 1,4,5,7,9,10,11,12
 *
 * Or write a ConfigReq (hex, the config read's fields) to the connected band, e.g. field 46:
 *
 *     ... --es config f202080800102018022809   # 46 = {1=0 2=32 3=2 5=9}
 *
 * Or stream raw sEMG alongside gestures (its rate goes to the log): `--ez emg true` / `false`.
 *
 * Or keep a copy of the firmware Meta offers the connected band (with the Meta sign-in; the
 * value is the version to report: "1" for a full build, "band" for what the band runs; "rest"
 * asks the glasses' REST check instead), into files/firmware:
 *
 *     ... --es firmware 1
 *
 * which needs the Meta sign-in; this opens it alone (no claim afterwards):
 *
 *     ... --ez signin true
 *
 * The band's update status (read only; the answer goes to the log):
 *
 *     ... --ez ota_status true
 *
 * A firmware update, one step at a time, from a package unzipped (with its kek.json) into the
 * app's files, e.g. files/ota/1003366292. The package's key, if the band lacks it (persistent);
 * sending each item for the band to verify, leaving the update staged (not installed); or
 * cancelling whatever update the band holds. A trial does key, send, then cancel. Install
 * commits the staged update (only the package's version): the band reboots into it, and on
 * reconnecting the app checks the firmware it reports.
 *
 *     ... --es ota_key ota/1003366292
 *     ... --es ota_send ota/1003366292
 *     ... --ez ota_cancel true
 *     ... --es ota_trial ota/1003366292
 *     ... --es ota_install ota/1003366292
 *
 * The band only accepts Install in the connection that verified the update, so this does all
 * of it in one go (cancel anything earlier, key, send, verify, install):
 *
 *     ... --es ota_update ota/1003366292
 *
 * Handwriting in text fields on or off (as Home's switch):
 *
 *     ... --ez write_in_fields true
 *
 * (The sign-in, again:)
 *
 *     ... --ez signin true
 *
 * Guarded by the DUMP permission, which adb's shell has and apps can't get.
 */
class DebugActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("probe")?.let { probe ->
            val prefs = Prefs(context)
            prefs.probe = probe.trim()
            Log.i("AirGestures", "debug probe ${prefs.probe.ifEmpty { "off" }}")
            if (prefs.running) {
                BandService.stop(context)
                BandService.start(context)
            }
            return
        }
        if (intent.hasExtra("emg")) {
            val on = intent.getBooleanExtra("emg", false)
            BandService.instance?.rawEmg(on) ?: Log.i("AirGestures", "debug emg: the band service isn't running")
            return
        }
        intent.getStringExtra("config")?.let { hex ->
            BandService.instance?.probeConfig(hex) ?: Log.i("AirGestures", "debug config: the band service isn't running")
            return
        }
        if (intent.getBooleanExtra("ota_status", false)) {
            BandService.instance?.otaStatus() ?: Log.i("AirGestures", "debug ota: the band service isn't running")
            return
        }
        val step = listOf("key", "send", "trial", "cancel", "install", "update").firstOrNull { intent.hasExtra("ota_$it") }
        if (step != null) {
            val dir = intent.getStringExtra("ota_$step") ?: ""
            val path = java.io.File(context.getExternalFilesDir(null), dir).path
            BandService.instance?.otaStart(step, path) ?: Log.i("AirGestures", "debug ota: the band service isn't running")
            return
        }
        if (intent.hasExtra("write_in_fields")) {
            Prefs(context).writeInFields = intent.getBooleanExtra("write_in_fields", false)
            Log.i("AirGestures", "debug write_in_fields ${Prefs(context).writeInFields}")
            return
        }
        if (intent.getBooleanExtra("signin", false)) {
            context.startActivity(Intent(context, MetaSignIn::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        intent.getStringExtra("firmware")?.let { version ->
            val session = SessionStore.load(context) ?: return run { Log.i("AirGestures", "debug firmware: not signed in to Meta") }
            val info = BandService.instance?.recording?.deviceInfo()
            val serial = info?.optString("serial")?.takeIf { it.isNotEmpty() }
                ?: return run { Log.i("AirGestures", "debug firmware: the band hasn't said its serial yet (connect it)") }
            // What the band runs, without its serial.
            Log.i("AirGestures", "debug firmware: the band runs ${JSONObject(info.toString()).apply { remove("serial") }}")
            val dir = context.getExternalFilesDir("firmware")?.path ?: return
            val result = goAsync()
            thread(name = "firmware") {
                val summary = runCatching { Bridge.firmwareArchive(session, serial, when (version) {
                    "band" -> info.optString("software")
                    "rest" -> "rest:" + info.optString("software")
                    else -> version.ifBlank { "1" }
                }, dir) }
                Log.i("AirGestures", "debug firmware: ${summary.getOrElse { "failed: ${it.message}" }}")
                result.finish()
            }
            return
        }
        val action = intent.getStringExtra("action") ?: return
        val result = runner(context).run(action)
        Log.i("AirGestures", "debug run $action: ${result ?: "ok"}")
    }

    companion object {
        /** One for the process, so the flashlight and the speech engine carry over between runs. */
        private var actions: Actions? = null

        private fun runner(context: Context) = actions ?: Actions(context.applicationContext) { Log.i("AirGestures", it) }.also { actions = it }
    }
}
