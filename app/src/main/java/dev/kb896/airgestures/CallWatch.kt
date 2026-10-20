package dev.kb896.airgestures

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager

/**
 * Whether the phone is ringing or in a call, so the During calls layer goes on and off.
 * Needs the phone-state permission (Setup's Phone calls step); without it nothing is watched.
 */
class CallWatch(private val context: Context, private val changed: () -> Unit) :
    TelephonyCallback(), TelephonyCallback.CallStateListener {
    private var watching = false

    /** Start watching if allowed; again is harmless (e.g. right after the permission is granted). */
    fun start() {
        if (watching || context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(TelephonyManager::class.java).registerTelephonyCallback(context.mainExecutor, this)
        watching = true
    }

    fun stop() {
        if (watching) context.getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(this)
        watching = false
        inCall = false
    }

    override fun onCallStateChanged(state: Int) {
        val now = state != TelephonyManager.CALL_STATE_IDLE
        if (now == inCall) return
        inCall = now
        changed()
    }

    companion object {
        /** Ringing or off-hook; read by [Prefs.mapping]. */
        @Volatile
        var inCall = false
            private set
    }
}
