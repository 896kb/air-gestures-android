package dev.kb896.airgestures

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import kotlin.concurrent.thread

/** Where the link is. */
enum class Phase { STOPPED, SEARCHING, CONNECTING, CONNECTED }

/**
 * Keeps one band connected, as the desktop daemon does: read the input-channel
 * PSM over GATT, open an L2CAP channel on it and run the band-core session.
 * Once the phone is bonded to the band it reconnects with `autoConnect`, so the
 * Bluetooth stack waits for the band (no pairing mode); before that it scans for
 * a band in pairing mode. Permissions are checked by the caller.
 */
@SuppressLint("MissingPermission")
class BandLink(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onPhase(phase: Phase, band: String?)
        fun onStatus(status: JSONObject)
        fun onActions(actions: List<String>)
        fun onLog(line: String)
    }

    // Looked up on every use: after Bluetooth is switched off and on, an adapter held
    // from before can keep reporting "off" (seen on HyperOS).
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
    private val prefs = Prefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var socket: BluetoothSocket? = null
    private var handle = 0L
    private var lastStatus = ""
    private val hapticCommands = ArrayDeque<PendingHaptic>()
    /** This connection uses owner.pending.key: confirm it once the band accepts it. */
    @Volatile private var pending = false
    @Volatile private var wanted = false
    @Volatile private var generation = 0

    val bondedBand: BluetoothDevice?
        get() = adapter?.bondedDevices?.firstOrNull { it.name?.lowercase()?.startsWith("meta band") == true }

    fun start() {
        if (wanted) return
        wanted = true
        connect()
    }

    fun stop() {
        wanted = false
        teardown()
        listener.onPhase(Phase.STOPPED, null)
    }

    fun setPaused(paused: Boolean) = synchronized(lock) {
        if (handle != 0L) Bridge.setPaused(handle, paused, now())
        publishStatus()
    }

    fun setBandVibration(enabled: Boolean) = synchronized(lock) {
        enqueueHaptics(HapticCommand.BandVibration(enabled))
    }

    fun controlsHaptics(paused: Boolean) = synchronized(lock) {
        enqueueHaptics(HapticCommand.Controls(paused))
    }

    fun syncControlsHaptics(paused: Boolean) = synchronized(lock) {
        enqueueHaptics(HapticCommand.SyncControls(paused))
    }

    fun profileHaptics(previous: Boolean) = synchronized(lock) {
        enqueueHaptics(HapticCommand.Profile(previous))
    }

    fun volumeDetent() = synchronized(lock) {
        enqueueHaptics(HapticCommand.VolumeDetent)
    }

    private fun enqueueHaptics(command: HapticCommand) {
        if (handle != 0L) hapticCommands.addLast(PendingHaptic(generation, command))
    }

    fun setMapping(mapping: String) = synchronized(lock) {
        if (handle != 0L) Bridge.setMapping(handle, mapping)
    }

    private fun connect() {
        if (!wanted) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth is off. Retrying in 5 s.")
            listener.onPhase(Phase.SEARCHING, null)
            retry(5000)
            return
        }
        val bonded = bondedBand
        if (bonded != null) {
            listener.onPhase(Phase.SEARCHING, bonded.name)
            log("waiting for ${bonded.name}")
            gatt = bonded.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            listener.onPhase(Phase.SEARCHING, null)
            log("scanning for a band in pairing mode (hold its button 3 s)")
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scan,
            )
        }
    }

    /** Drop everything from the current attempt; `connect` starts a fresh one. */
    private fun teardown() {
        synchronized(lock) {
            generation++
            hapticCommands.clear()
            runCatching { socket?.close() }
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            socket = null
            gatt = null
            if (handle != 0L) Bridge.close(handle)
            handle = 0L
            lastStatus = ""
        }
        main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
    }

    private fun retry(delayMs: Long) {
        teardown()
        if (wanted) main.postDelayed({ connect() }, delayMs)
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.lowercase().startsWith("meta band") || gatt != null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            log("found $name; connecting")
            listener.onPhase(Phase.CONNECTING, name)
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed ($errorCode)")
            retry(5000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val attempt = synchronized(lock) {
                if (gatt != this@BandLink.gatt) return
                generation
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    synchronized(lock) {
                        if (attempt != generation || gatt != this@BandLink.gatt) return
                        listener.onPhase(Phase.CONNECTING, gatt.device.name)
                        gatt.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("the band disconnected (status $status)")
                    main.post { if (attempt == generation) retry(2000) }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (!synchronized(lock) { gatt == this@BandLink.gatt }) return
            val characteristic = gatt.getService(BAND_SERVICE)?.getCharacteristic(PSM_CHARACTERISTIC)
            if (characteristic == null) {
                log("this device has no Neural Band input service")
                retry(5000)
                return
            }
            // The first read may trigger Bluetooth pairing (a system prompt).
            gatt.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            val attempt = synchronized(lock) {
                if (gatt != this@BandLink.gatt) return
                generation
            }
            if (status != BluetoothGatt.GATT_SUCCESS || value.size != 2) {
                log("reading the input channel failed (status $status)")
                retry(5000)
                return
            }
            val psm = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
            thread(name = "band-l2cap") { runChannel(gatt.device, psm, attempt) }
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

    private fun openSession(): Boolean {
        val dir = context.filesDir
        // A pending key is always newer: the band may have committed a claim whose
        // confirmation was lost (the desktop's rule).
        val key = Identity.pendingFile(context).takeIf { it.exists() } ?: Identity.keyFile(context)
        if (!key.exists()) {
            log("no owner key yet: claim the band or import its key")
            return false
        }
        pending = key == Identity.pendingFile(context)
        val guess = File(dir, "band.json").takeIf { it.exists() }
            ?.let { JSONObject(it.readText()).optInt("scheme_guess", 0) } ?: 0
        return try {
            synchronized(lock) {
                handle = Bridge.open(key.readBytes(), guess, prefs.paused, "", prefs.mapping())
                // This queues Perform1/2 inside the new session before its first status snapshot.
                Bridge.setBandVibration(handle, prefs.bandVibration)
            }
            true
        } catch (e: Exception) {
            log("owner key: ${e.message}")
            false
        }
    }

    private fun runChannel(device: BluetoothDevice, psm: Int, attempt: Int) {
        val opened = synchronized(lock) { if (attempt == generation) openSession() else null }
        if (opened == null) return
        if (!opened) {
            main.post { if (attempt == generation) stop() }
            return
        }
        val channel = try {
            device.createInsecureL2capChannel(psm).also { it.connect() }
        } catch (e: Exception) {
            log("opening the input channel failed: ${e.message}")
            main.post { if (attempt == generation) retry(3000) }
            return
        }
        synchronized(lock) {
            if (attempt != generation || handle == 0L) {
                runCatching { channel.close() }
                return
            }
            socket = channel
        }
        val input = channel.inputStream
        val output = channel.outputStream
        val write = { bytes: ByteArray -> if (bytes.isNotEmpty()) synchronized(output) { output.write(bytes) } }
        try {
            synchronized(lock) {
                if (attempt != generation || handle == 0L) return
                write(Bridge.request(handle))
            }
            thread(name = "band-tick") {
                while (attempt == generation) {
                    SystemClock.sleep(50)
                    try {
                        synchronized(lock) {
                            if (attempt == generation && handle != 0L) {
                                Bridge.tick(handle, now()).takeIf { it.isNotEmpty() }?.let(write)
                                drain()
                                flushHapticsLocked(write, attempt)
                            }
                        }
                    } catch (e: Exception) {
                        if (attempt == generation) {
                            log("link: ${e.message}")
                            main.post { if (attempt == generation) retry(2000) }
                        }
                        break
                    }
                }
            }
            val buffer = ByteArray(8192)
            while (attempt == generation) {
                val count = input.read(buffer)
                if (count < 0) break
                synchronized(lock) {
                    if (attempt == generation && handle != 0L) {
                        Bridge.feed(handle, buffer.copyOf(count), now()).takeIf { it.isNotEmpty() }?.let(write)
                        drain()
                        flushHapticsLocked(write, attempt)
                    }
                }
            }
            if (attempt == generation) log("the band closed the input channel")
        } catch (e: Exception) {
            if (attempt == generation) log("link: ${e.message}")
        }
        main.post { if (attempt == generation) retry(2000) }
    }

    /** Hand actions, log lines and a changed status to the listener. */
    private fun drain() {
        val (actions, lines) = synchronized(lock) {
            if (handle == 0L) return
            Bridge.actions(handle) to Bridge.log(handle)
        }
        if (lines.isNotEmpty()) lines.lines().forEach(::log)
        if (pending && "connected" in lines.lines()) {
            pending = false
            if (Identity.pendingFile(context).renameTo(Identity.keyFile(context))) log("the band accepted the claimed key")
        }
        if (actions.isNotEmpty()) listener.onActions(actions.lines())
        synchronized(lock) { publishStatus() }
    }

    /** Call with `lock` held. */
    private fun publishStatus() {
        if (handle == 0L) return
        val json = Bridge.status(handle)
        if (json == lastStatus) return
        lastStatus = json
        val status = JSONObject(json)
        if (status.optBoolean("connected")) {
            listener.onPhase(Phase.CONNECTED, gatt?.device?.name)
        }
        listener.onStatus(status)
    }

    /** Invoke JNI and write on the active generation's existing single writer. Caller holds [lock]. */
    private fun flushHapticsLocked(write: (ByteArray) -> Unit, attempt: Int) {
        while (attempt == generation && handle != 0L && hapticCommands.isNotEmpty()) {
            val pending = hapticCommands.removeFirst()
            if (pending.generation != attempt) continue
            val bytes = when (val command = pending.command) {
                is HapticCommand.BandVibration -> Bridge.setBandVibration(handle, command.enabled)
                is HapticCommand.Controls -> Bridge.controlsHaptics(handle, command.paused)
                is HapticCommand.SyncControls -> Bridge.syncControlsHaptics(handle, command.paused)
                is HapticCommand.Profile -> Bridge.profileHaptics(handle, command.previous)
                HapticCommand.VolumeDetent -> Bridge.volumeDetent(handle)
            }
            if (bytes.isNotEmpty()) write(bytes)
        }
    }

    private data class PendingHaptic(val generation: Int, val command: HapticCommand)

    private sealed interface HapticCommand {
        data class BandVibration(val enabled: Boolean) : HapticCommand
        data class Controls(val paused: Boolean) : HapticCommand
        data class SyncControls(val paused: Boolean) : HapticCommand
        data class Profile(val previous: Boolean) : HapticCommand
        data object VolumeDetent : HapticCommand
    }

    private fun log(line: String) = listener.onLog(line)

    companion object {
        val BAND_SERVICE: UUID = UUID.fromString("0000feb8-0000-1000-8000-00805f9b34fb")
        val PSM_CHARACTERISTIC: UUID = UUID.fromString("2d41da7c-82b6-42aa-b34e-e2e01df8cc1a")
    }
}
