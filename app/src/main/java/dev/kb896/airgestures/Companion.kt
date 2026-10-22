package dev.kb896.airgestures

import android.annotation.TargetApi
import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
import android.content.Context
import android.content.IntentSender
import android.os.Build
import java.util.regex.Pattern

/**
 * The CompanionDeviceManager link: the system then lets this app run and start
 * its service in the background, and wakes it when the band comes into range.
 */
object Companion {
    fun linked(context: Context): Boolean = Prefs(context).associationId >= 0 &&
        context.getSystemService(CompanionDeviceManager::class.java).myAssociations
            .any { it.id == Prefs(context).associationId }

    /**
     * Asks again to be woken when the linked band appears. Linking asks once, but a link
     * made another way (restored, or moved over from the old package with adb) doesn't,
     * so the service asks each time it starts; asking twice changes nothing.
     */
    fun observe(context: Context) {
        val id = Prefs(context).associationId
        if (id < 0) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        val association = manager.myAssociations.firstOrNull { it.id == id } ?: return
        watch(manager, association)
    }

    /**
     * Asks the system to wake the app when [association]'s band appears: by the
     * association on Android 16, by the band's address before. Fails quietly (the
     * link may have gone); the service asks again each time it starts.
     */
    private fun watch(manager: CompanionDeviceManager, association: AssociationInfo) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 36) {
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
                )
            } else {
                val address = association.deviceMacAddress ?: return
                @Suppress("DEPRECATION")
                manager.startObservingDevicePresence(address.toString())
            }
        }
    }

    /**
     * Shows the system's device picker; the band must be advertising (pairing mode).
     * [onLinked] gets the picked band (Android 14+ reports it; null before), so a claim
     * can use the same choice; [onFailed] runs when the picker is cancelled or finds nothing.
     */
    fun link(
        activity: Activity,
        requestCode: Int,
        log: (String) -> Unit,
        onLinked: (android.bluetooth.BluetoothDevice?) -> Unit = {},
        onFailed: () -> Unit = {},
    ) {
        val request = AssociationRequest.Builder()
            .addDeviceFilter(
                BluetoothLeDeviceFilter.Builder()
                    .setNamePattern(Pattern.compile("Meta Band.*"))
                    .setScanFilter(ScanFilter.Builder().build())
                    .build(),
            )
            .setSingleDevice(true)
            .build()
        val manager = activity.getSystemService(CompanionDeviceManager::class.java)
        manager.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                activity.startIntentSenderForResult(intentSender, requestCode, null, 0, 0, 0)
            }

            override fun onAssociationCreated(association: AssociationInfo) {
                Prefs(activity).associationId = association.id
                watch(manager, association)
                log("linked ${association.displayName ?: "the band"} as a companion device")
                val device = if (Build.VERSION.SDK_INT >= 34) {
                    association.associatedDevice?.bleDevice?.device
                } else {
                    null
                }
                onLinked(device)
            }

            override fun onFailure(error: CharSequence?) {
                log("companion link failed: ${error ?: "no band found (hold its button 3 s)"}")
                onFailed()
            }
        })
    }
}

/** Woken by the system when the linked band appears: bring the service back if it should run. */
class BandPresenceService : CompanionDeviceService() {
    /** Android 16 and newer. */
    @TargetApi(36)
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED) appeared()
    }

    /** Android 13 to 15 (16 calls [onDevicePresenceEvent] instead). */
    @Deprecated("Deprecated in Java")
    override fun onDeviceAppeared(associationInfo: AssociationInfo) = appeared()

    /** Start the service if the band should run; if it runs, have it connect directly now. */
    private fun appeared() {
        if (!Prefs(this).running) return
        BandService.instance?.nudge() ?: BandService.start(this)
    }
}
