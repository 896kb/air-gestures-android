package dev.kb896.airgestures

import android.media.AudioDeviceInfo

/** Bluetooth audio route types used by per-profile music gating. */
object MusicOutput {
    fun allowed(onlyBluetooth: Boolean, outputTypes: IntArray): Boolean =
        !onlyBluetooth || outputTypes.any(::isBluetoothAudioType)

    fun isBluetoothAudioType(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
    )
}
