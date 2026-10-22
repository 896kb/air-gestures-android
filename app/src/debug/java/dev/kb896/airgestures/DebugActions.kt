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
