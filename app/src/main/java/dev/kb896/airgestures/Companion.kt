package dev.kb896.airgestures

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
import java.util.regex.Pattern

/**
 * The CompanionDeviceManager link: the system then lets this app run and start
 * its service in the background, and wakes it when the band comes into range.
 */
object Companion {
    fun linked(activity: Activity): Boolean = Prefs(activity).associationId >= 0 &&
        activity.getSystemService(CompanionDeviceManager::class.java).myAssociations
            .any { it.id == Prefs(activity).associationId }

    /**
     * Asks again to be woken when the linked band appears. Linking asks once, but a link
     * made another way (restored, or moved over from the old package with adb) doesn't,
     * so the service asks each time it starts; asking twice changes nothing.
     */
    fun observe(context: Context) {
        val id = Prefs(context).associationId
        if (id < 0) return
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager.myAssociations.none { it.id == id }) return
        runCatching {
            manager.startObservingDevicePresence(ObservingDevicePresenceRequest.Builder().setAssociationId(id).build())
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
                manager.startObservingDevicePresence(
                    ObservingDevicePresenceRequest.Builder().setAssociationId(association.id).build(),
                )
                log("linked ${association.displayName ?: "the band"} as a companion device")
                val device = if (android.os.Build.VERSION.SDK_INT >= 34) {
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
    override fun onDevicePresenceEvent(event: DevicePresenceEvent) {
        if (event.event == DevicePresenceEvent.EVENT_BLE_APPEARED && Prefs(this).running &&
            BandService.instance == null
        ) {
            BandService.start(this)
        }
    }
}
