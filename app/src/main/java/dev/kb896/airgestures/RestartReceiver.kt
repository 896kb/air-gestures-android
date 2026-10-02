package dev.kb896.airgestures

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Brings the band service back after a reboot or an app update, if it was left on. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED) &&
            Prefs(context).running
        ) {
            runCatching { BandService.start(context) }
        }
    }
}
