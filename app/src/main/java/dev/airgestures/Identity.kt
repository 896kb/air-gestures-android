package dev.airgestures

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.companion.CompanionDeviceManager
import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * The band's owner key and record in the app's private storage: `owner.key` (the
 * private key, 32 bytes, or 97 with the band's key) and `band.json` (address, name,
 * scheme guess) — the same files the Linux app keeps in ~/.local/state/air-gestures.
 * `owner.pending.key` is a claim from this phone the band may have committed whose
 * confirmation never arrived ([BandClaim]); [BandLink] tries it first and confirms it.
 */
object Identity {
    private const val BUNDLE_FORMAT = "air-gestures-band"
    private const val BUNDLE_VERSION = 1

    fun keyFile(context: Context) = File(context.filesDir, "owner.key")
    fun pendingFile(context: Context) = File(context.filesDir, "owner.pending.key")
    fun bandFile(context: Context) = File(context.filesDir, "band.json")

    fun present(context: Context) = keyFile(context).exists() || pendingFile(context).exists()

    /** Only an unconfirmed claim's key is there. */
    fun unconfirmed(context: Context) = !keyFile(context).exists() && pendingFile(context).exists()

    /** The band's name from band.json, if there is one. */
    fun bandName(context: Context): String? = runCatching {
        JSONObject(bandFile(context).readText()).optString("name").ifEmpty { null }
    }.getOrNull()

    /**
     * `air-gestures-band.json`, the file the Linux app's `air-gestures export`/`import`
     * share: the confirmed owner key (base64) and band.json. Anyone holding it controls
     * the band. Throws when there's no confirmed key (an unconfirmed claim isn't exported).
     */
    fun exportBundle(context: Context): ByteArray {
        val key = keyFile(context).takeIf { it.exists() }?.readBytes()
            ?: throw IllegalStateException("no confirmed owner key on this phone")
        val bundle = JSONObject()
            .put("format", BUNDLE_FORMAT)
            .put("version", BUNDLE_VERSION)
            .put("owner_key", Base64.encodeToString(key, Base64.NO_WRAP))
        runCatching { JSONObject(bandFile(context).readText()) }.getOrNull()?.let { bundle.put("band", it) }
        return (bundle.toString(2) + "\n").toByteArray()
    }

    /**
     * Import the picked files, told apart by content, not name: JSON with
     * `"format": "air-gestures-band"` is an exported bundle (the key and maybe the band),
     * 32 or 97 bytes is the key (checked by opening it in the bridge), other JSON with an
     * address is band.json. Returns what happened, for the activity log.
     */
    fun import(context: Context, uris: List<Uri>): String {
        var key: ByteArray? = null
        var band: String? = null
        for (uri in uris) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: continue
            val json = runCatching { JSONObject(String(bytes)) }.getOrNull()
            when {
                json?.has("format") == true -> {
                    if (json.optString("format") != BUNDLE_FORMAT) return "that isn't an air-gestures band file"
                    val version = json.optInt("version", 0)
                    if (version > BUNDLE_VERSION) {
                        return "that band file is version $version, newer than this app understands; update the app"
                    }
                    if (version < 1) return "that band file has no usable version"
                    key = runCatching { Base64.decode(json.getString("owner_key"), Base64.DEFAULT) }.getOrNull()
                        ?: return "that band file's owner key isn't readable"
                    json.optJSONObject("band")?.let { band = it.toString(2) }
                }
                bytes.size == 32 || bytes.size == 97 -> key = bytes
                json?.has("address") == true -> band = String(bytes)
            }
        }
        val owner = key ?: return "no owner key among the picked files (air-gestures-band.json, or owner.key)"
        if (owner.size != 32 && owner.size != 97) return "that isn't a usable owner key (${owner.size} bytes)"
        try {
            Bridge.close(Bridge.open(owner, 0, false, "", ""))
        } catch (e: Exception) {
            return "that isn't a usable owner key: ${e.message}"
        }
        keyFile(context).writeBytes(owner)
        // The imported key is the newer one.
        pendingFile(context).delete()
        band?.let { bandFile(context).writeText(it) }
        return if (band != null) "imported the owner key and band" else "imported the owner key"
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
        pendingFile(context).delete()
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
