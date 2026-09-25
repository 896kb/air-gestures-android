package dev.airgestures

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File

/**
 * The band's owner key and record in the app's private storage: `owner.key` (the
 * private key, 32 bytes, or 97 with the band's key) and `band.json` (address, name,
 * scheme guess) — the same files the Linux app keeps in ~/.local/state/air-gestures.
 */
object Identity {
    fun keyFile(context: Context) = File(context.filesDir, "owner.key")
    fun bandFile(context: Context) = File(context.filesDir, "band.json")

    fun present(context: Context) = keyFile(context).exists()

    /** The band's name from band.json, if there is one. */
    fun bandName(context: Context): String? = runCatching {
        JSONObject(bandFile(context).readText()).optString("name").ifEmpty { null }
    }.getOrNull()

    /**
     * Import the picked files, told apart by content, not name: 32 or 97 bytes is the
     * key (checked by opening it in the bridge), JSON with an address is band.json.
     * Returns what happened, for the activity log.
     */
    fun import(context: Context, uris: List<Uri>): String {
        var key: ByteArray? = null
        var band: String? = null
        for (uri in uris) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            when {
                bytes.size == 32 || bytes.size == 97 -> key = bytes
                runCatching { JSONObject(String(bytes)).has("address") }.getOrDefault(false) -> band = String(bytes)
            }
        }
        if (key == null) return "no owner key among the picked files (it's owner.key, 32 or 97 bytes)"
        try {
            Bridge.close(Bridge.open(key, 0, false, "", ""))
        } catch (e: Exception) {
            return "that isn't a usable owner key: ${e.message}"
        }
        keyFile(context).writeBytes(key)
        if (band != null) bandFile(context).writeText(band)
        return if (band != null) "imported the owner key and band.json" else "imported the owner key"
    }

    /**
     * Forget the band on this phone: the service, the key and record, the Bluetooth
     * bond and the companion link. The band keeps its owner; a factory reset (hold its
     * button about 16 s) clears that. Returns true when the bond couldn't be removed by
     * the app (the caller then opens Bluetooth settings).
     */
    @SuppressLint("MissingPermission")
    fun forget(context: Context): Boolean {
        BandService.stop(context)
        keyFile(context).delete()
        bandFile(context).delete()
        val prefs = Prefs(context)
        if (prefs.associationId >= 0) {
            runCatching {
                context.getSystemService(CompanionDeviceManager::class.java).disassociate(prefs.associationId)
            }
            prefs.associationId = -1
        }
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        val band = adapter?.bondedDevices?.firstOrNull { it.name?.lowercase()?.startsWith("meta band") == true }
            ?: return false
        // Removing a bond is a hidden API for ordinary apps; many phones still allow it.
        val removed = runCatching { band.javaClass.getMethod("removeBond").invoke(band) as Boolean }.getOrDefault(false)
        return !removed
    }
}
