package dev.kb896.airgestures

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicOutputTest {
    @Test fun `Bluetooth only profiles require an active audio output`() {
        assertTrue(MusicOutput.allowed(false, intArrayOf()))
        assertFalse(MusicOutput.allowed(true, intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        assertTrue(MusicOutput.allowed(true, intArrayOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
        assertTrue(MusicOutput.allowed(true, intArrayOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
    }
}
