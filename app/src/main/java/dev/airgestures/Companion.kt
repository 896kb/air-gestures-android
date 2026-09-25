package dev.airgestures

import android.app.Activity
import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.CompanionDeviceService
import android.companion.DevicePresenceEvent
import android.companion.ObservingDevicePresenceRequest
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

    /** Shows the system's device picker; the band must be advertising (pairing mode). */
    fun link(activity: Activity, requestCode: Int, log: (String) -> Unit) {
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
            }

            override fun onFailure(error: CharSequence?) {
                log("companion link failed: ${error ?: "no band found (hold its button 3 s)"}")
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
