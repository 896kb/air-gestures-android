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
    /** This connection uses owner.pending.key: confirm it once the band accepts it. */
    @Volatile private var pending = false
    @Volatile private var wanted = false
    @Volatile private var generation = 0
    /** The next attempt connects directly (fast, gives up after ~30 s) instead of waiting in the background. */
    @Volatile private var direct = true
    /** The Bluetooth link of the current attempt is up. */
    @Volatile private var linkUp = false
    /** The session of the current attempt reached "connected". */
    @Volatile private var live = false

    val bondedBand: BluetoothDevice?
        get() = adapter?.bondedDevices?.firstOrNull { it.name?.lowercase()?.startsWith("meta band") == true }

    fun start() {
        if (wanted) return
        wanted = true
        direct = true
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

    fun setBandVibration(enabled: Boolean) = synchronized(lock) { enqueueHaptics(HapticCommand.BandVibration(enabled)) }
    fun controlsHaptics(paused: Boolean) = synchronized(lock) { enqueueHaptics(HapticCommand.Controls(paused)) }
    fun syncControlsHaptics(paused: Boolean) = synchronized(lock) { enqueueHaptics(HapticCommand.SyncControls(paused)) }
    fun profileHaptics(previous: Boolean = false) = synchronized(lock) { enqueueHaptics(HapticCommand.Profile(previous)) }
    fun volumeDetent() = synchronized(lock) { enqueueHaptics(HapticCommand.VolumeDetent) }

    private val hapticCommands = java.util.ArrayDeque<HapticCommand>()

    private fun enqueueHaptics(command: HapticCommand) {
        if (handle != 0L) hapticCommands.addLast(command)
    }

    /** New profiles, hand or call layer (Prefs.profilesJson); the next connection reads them anyway. */
    fun setProfiles(json: String) = synchronized(lock) {
        if (handle != 0L) Bridge.setProfiles(handle, json)
    }

    /** The app in front ("": none known). */
    fun setForeground(app: String) = synchronized(lock) {
        if (handle != 0L) Bridge.setForeground(handle, app)
    }

    fun setInCall(inCall: Boolean) = synchronized(lock) {
        if (handle != 0L) Bridge.setInCall(handle, inCall)
    }

    /** A lesson is listening: gestures are reported, nothing runs. Kept for the next connection. */
    fun setPractice(on: Boolean) = synchronized(lock) {
        practice = on
        if (handle != 0L) Bridge.setPractice(handle, on)
    }

    /** What [setPractice] last said. */
    private var practice = false

    /** Teaching drawings: draw sessions report their strokes and match nothing. Kept for the next connection. */
    fun setTeaching(on: Boolean) = synchronized(lock) {
        teaching = on
        if (handle != 0L) Bridge.setTeaching(handle, on)
    }

    /** What [setTeaching] last said. */
    private var teaching = false

    /** Teaching drags: pinches report `@drag-sample`, nothing acts. Kept for the next connection. */
    fun setDragTeaching(on: Boolean) = synchronized(lock) {
        dragTeaching = on
        if (handle != 0L) Bridge.setDragTeaching(handle, on)
    }

    /** What [setDragTeaching] last said. */
    private var dragTeaching = false

    /** The drawings draw sessions match against (DrawingLibrary JSON); the next connection reads them anyway. */
    fun setLibrary(json: String) = synchronized(lock) {
        if (handle != 0L) Bridge.setLibrary(handle, json)
    }

    /** Open a draw session from the app (a teach screen's try). */
    fun startDrawing() = synchronized(lock) {
        if (handle != 0L) Bridge.startDrawing(handle, now())
    }

    /** Close the open draw session, if any (`@draw-cancelled`). */
    fun cancelDrawing() = synchronized(lock) {
        if (handle != 0L) Bridge.cancelDrawing(handle)
    }

    /** `{"palm", "roll"}` right now (nulls: no fresh reading); null when not connected. */
    fun palmNow(): JSONObject? = synchronized(lock) {
        if (handle == 0L) return null
        JSONObject(Bridge.palmNow(handle, now()))
    }

    /**
     * The band is probably near (the system saw it, the phone was unlocked): while waiting for
     * it in the background, connect directly now instead. Does nothing once the link is up.
     */
    fun nudge() {
        main.post {
            if (!wanted || linkUp || bondedBand == null) return@post
            log("the band may be near; connecting directly")
            direct = true
            retry(0)
        }
    }

    /** Debug: write a ConfigReq given as hex to the band (see Bridge.probeConfig). */
    fun probeConfig(hex: String) = debugSend { Bridge.probeConfig(it, hex, now()) }

    /** Debug: ask the band's update status, read only (see Bridge.otaStatus). */
    fun otaStatus() = debugSend { Bridge.otaStatus(it) }

    /** Debug: one step of a firmware update from the package unzipped in [dir] (see Bridge.otaStart). */
    fun otaStart(mode: String, dir: String) {
        // Megabytes over the link: the shortest connection interval, as for raw sEMG.
        runCatching { gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) }
        debugSend { Bridge.otaStart(it, mode, dir, now()) }
    }

    /**
     * The band's handwriting into a text field, on or off (see Bridge.setFieldTyping): one after
     * another on [fieldSends], so an on and the off after it land in that order.
     */
    fun setFieldTyping(on: Boolean, before: String = "", popup: Boolean = true) =
        fieldSends.execute { send("handwriting") { Bridge.setFieldTyping(it, on, before, popup, now()) } }

    /** Field typing's own thread (see [setFieldTyping]). */
    private val fieldSends = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "band-field") }

    /** The field's edits since the last call and the pen; null when not connected. */
    fun fieldEdits(): org.json.JSONObject? = synchronized(lock) {
        if (handle == 0L) return null
        org.json.JSONObject(Bridge.fieldEdits(handle, now()))
    }

    /** Choosing a word's suggestions in a text field (see Bridge.fieldChoose). */
    fun fieldChoose(): Boolean = synchronized(lock) { handle != 0L && Bridge.fieldChoose(handle) }
    fun fieldChooseStep(forward: Boolean) = synchronized(lock) { if (handle != 0L) Bridge.fieldChooseStep(handle, forward) }
    fun fieldChoosePick() = synchronized(lock) { if (handle != 0L) Bridge.fieldChoosePick(handle, now()) }
    fun fieldChooseCancel() = synchronized(lock) { if (handle != 0L) Bridge.fieldChooseCancel(handle, now()) }

    /** A double tap in a text field: the pen goes down. */
    fun fieldPenDown() = synchronized(lock) { if (handle != 0L) Bridge.fieldPenDown(handle, now()) }

    /** Debug: the band's own handwriting on or off (see Bridge.setBandHandwriting). */
    fun setBandHandwriting(on: Boolean) = debugSend { Bridge.setBandHandwriting(it, on) }

    /** Debug: what the band's handwriting has sent (see Bridge.bandHandwriting); null when not connected. */
    fun bandHandwriting(clear: Boolean): org.json.JSONObject? = synchronized(lock) {
        if (handle == 0L) return null
        org.json.JSONObject(Bridge.bandHandwriting(handle, clear))
    }

    /** What the band said about itself (see Bridge.deviceInfo); null before it has. */
    fun deviceInfo(): org.json.JSONObject? = synchronized(lock) {
        if (handle == 0L) return null
        org.json.JSONObject(Bridge.deviceInfo(handle)).takeIf { it.length() > 0 }
    }

    /** Send what [request] returns for the open session, off the main thread; problems go to the log. */
    private fun debugSend(request: (Long) -> ByteArray) = thread(name = "band-debug") { send("debug", request) }

    /** Ask the bridge for bytes and write them to the band, here; failures go to the log after [what]. */
    private fun send(what: String, request: (Long) -> ByteArray) {
        try {
            val out = synchronized(lock) { if (handle == 0L) null else request(handle) }
            val stream = socket?.outputStream
            if (out == null || stream == null) log("$what: not connected") else synchronized(stream) { stream.write(out) }
            drain()
        } catch (e: Exception) {
            log("$what: ${e.message}")
        }
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
            // Calibrations are kept per band.
            if (prefs.bandAddress != bonded.address) prefs.bandAddress = bonded.address
            listener.onPhase(Phase.SEARCHING, bonded.name)
            // Directly when the band is probably near (fast); else Android's background
            // reconnect, which waits as long as it takes but can be slow to notice the band.
            log(if (direct) "connecting to ${bonded.name}" else "waiting for ${bonded.name}")
            gatt = bonded.connectGatt(context, !direct, gattCallback, BluetoothDevice.TRANSPORT_LE)
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
        generation++
        main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        if (socket != null) closedAt = SystemClock.elapsedRealtime()
        runCatching { socket?.close() }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        socket = null
        gatt = null
        linkUp = false
        live = false
        synchronized(lock) {
            if (handle != 0L) Bridge.close(handle)
            handle = 0L
            lastStatus = ""
            hapticCommands.clear()
        }
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
            if (gatt != this@BandLink.gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    linkUp = true
                    listener.onPhase(Phase.CONNECTING, gatt.device.name)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("the band disconnected (status $status)")
                    // A link that was up: the band is likely still near, so try directly again.
                    // A direct attempt that never got a link: the band is away, wait in the background.
                    direct = linkUp
                    retry(if (linkUp) 500 else 2000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
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
            if (status != BluetoothGatt.GATT_SUCCESS || value.size != 2) {
                log("reading the input channel failed (status $status)")
                retry(5000)
                return
            }
            val psm = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
            val attempt = generation
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
                handle = Bridge.open(key.readBytes(), guess, prefs.paused, prefs.profilesJson())
                Bridge.setBandVibration(handle, prefs.bandVibration)
                Bridge.setForeground(handle, Profiles.foreground ?: "")
                Bridge.setInCall(handle, CallWatch.inCall)
                Bridge.setPractice(handle, practice)
                Bridge.setLibrary(handle, Drawings.load(context).toJson())
                Bridge.setTeaching(handle, teaching)
                Bridge.setDragTeaching(handle, dragTeaching)
                prefs.probe.takeIf { it.isNotEmpty() }?.let {
                    Bridge.setProbe(handle, it)
                    log("probing stream flags $it")
                }
            }
            true
        } catch (e: Exception) {
            log("owner key: ${e.message}")
            false
        }
    }

    private fun runChannel(device: BluetoothDevice, psm: Int, attempt: Int) {
        if (!openSession()) {
            main.post { stop() }
            return
        }
        // Right after a channel closed, the band refuses a new one (it answers after ~3 s, then
        // closes it): give it a moment first.
        val settle = closedAt + CHANNEL_SETTLE_MS - SystemClock.elapsedRealtime()
        if (settle > 0) SystemClock.sleep(settle)
        val channel = try {
            device.createInsecureL2capChannel(psm).also { it.connect() }
        } catch (e: Exception) {
            log("opening the input channel failed: ${e.message}")
            main.post { if (attempt == generation) retry(3000) }
            return
        }
        socket = channel
        val input = channel.inputStream
        val output = channel.outputStream
        val write = { bytes: ByteArray -> if (bytes.isNotEmpty()) synchronized(output) { output.write(bytes) } }
        try {
            write(synchronized(lock) { Bridge.request(handle) })
            thread(name = "band-tick") {
                while (attempt == generation) {
                    SystemClock.sleep(50)
                    try {
                        synchronized(lock) {
                            if (attempt == generation && handle != 0L) {
                                Bridge.tick(handle, now()).let(write)
                                flushHapticsLocked(write, attempt)
                            }
                        }
                        drain()
                    } catch (e: Exception) {
                        if (attempt == generation) log("link: ${e.message}")
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
                        Bridge.feed(handle, buffer.copyOf(count), now()).let(write)
                        flushHapticsLocked(write, attempt)
                    }
                }
                drain()
            }
            if (attempt == generation) log("the band closed the input channel")
        } catch (e: Exception) {
            if (attempt == generation) log("link: ${e.message}")
        }
        closedAt = SystemClock.elapsedRealtime()
        // Refused before it ever connected: try again soon (the settle wait still applies).
        val again = if (live) 2000L else 500L
        main.post { if (attempt == generation) retry(again) }
    }

    /** Hand actions, log lines and a changed status to the listener. */
    private fun drain() {
        val (actions, lines) = synchronized(lock) {
            if (handle == 0L) return
            Bridge.actions(handle) to Bridge.log(handle)
        }
        if (lines.isNotEmpty()) lines.lines().forEach(::log)
        if ("connected" in lines.lines()) live = true
        if (pending && "connected" in lines.lines()) {
            pending = false
            if (Identity.pendingFile(context).renameTo(Identity.keyFile(context))) log("the band accepted the claimed key")
        }
        if (actions.isNotEmpty()) listener.onActions(actions.lines())
        synchronized(lock) { publishStatus() }
    }

    /** Execute queued haptic requests in the same order as band writes. Caller holds [lock]. */
    private fun flushHapticsLocked(write: (ByteArray) -> Unit, attempt: Int) {
        while (attempt == generation && handle != 0L && hapticCommands.isNotEmpty()) {
            val bytes = when (val command = hapticCommands.removeFirst()) {
                is HapticCommand.BandVibration -> Bridge.setBandVibration(handle, command.enabled)
                is HapticCommand.Controls -> Bridge.controlsHaptics(handle, command.paused)
                is HapticCommand.SyncControls -> Bridge.syncControlsHaptics(handle, command.paused)
                is HapticCommand.Profile -> Bridge.profileHaptics(handle, command.previous)
                HapticCommand.VolumeDetent -> Bridge.volumeDetent(handle)
            }
            write(bytes)
        }
    }

    private sealed interface HapticCommand {
        data class BandVibration(val enabled: Boolean) : HapticCommand
        data class Controls(val paused: Boolean) : HapticCommand
        data class SyncControls(val paused: Boolean) : HapticCommand
        data class Profile(val previous: Boolean) : HapticCommand
        data object VolumeDetent : HapticCommand
    }

    /** Call with `lock` held. */
    private fun publishStatus() {
        if (handle == 0L) return
        val json = Bridge.status(handle)
        if (json == lastStatus) return
        lastStatus = json
        val status = JSONObject(json)
        if (status.optBoolean("connected")) listener.onPhase(Phase.CONNECTED, gatt?.device?.name)
        listener.onStatus(status)
    }

    private fun log(line: String) = listener.onLog(line)

    companion object {
        val BAND_SERVICE: UUID = UUID.fromString("0000feb8-0000-1000-8000-00805f9b34fb")
        val PSM_CHARACTERISTIC: UUID = UUID.fromString("2d41da7c-82b6-42aa-b34e-e2e01df8cc1a")
        /** How long after a channel closed the band takes a new one (measured; see runChannel). */
        const val CHANNEL_SETTLE_MS = 2000L

        /**
         * When the last input channel closed ([SystemClock.elapsedRealtime]): for the whole
         * process, since a restarted service (a new BandLink) meets the same band.
         */
        @Volatile private var closedAt = 0L
    }
}
