package dev.kb896.airgestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only: run any action without the band, for tests on an emulator.
 *
 *     adb shell am broadcast -n dev.kb896.airgestures.dev/dev.kb896.airgestures.DebugActions \
 *         -a dev.kb896.airgestures.RUN --es action nav.next
 *
 * Guarded by the DUMP permission, which adb's shell has and apps can't get.
 */
class DebugActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra("action") ?: return
        val result = runner(context).run(action)
        Log.i("AirGestures", "debug run $action: ${result ?: "ok"}")
    }

    companion object {
        /** One for the process, so the flashlight and the speech engine carry over between runs. */
        private var actions: Actions? = null

        private fun runner(context: Context) = actions ?: Actions(context.applicationContext).also { actions = it }
    }
}
