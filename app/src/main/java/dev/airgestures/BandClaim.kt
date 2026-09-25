package dev.airgestures

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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Claims a band in pairing mode for this phone (air-gesturesd `pair_band`): find it by
 * name, pair at the Bluetooth level, read the input-channel PSM over GATT, open an L2CAP
 * channel and run the bridge's ownership ceremony, which calls Meta's two HTTP steps
 * itself and writes the key files. A newer-firmware band that drops a key guess before a
 * single packet authenticates gets the next guess on a fresh channel, as on the desktop.
 *
 * Key rule (as the desktop's): the bridge writes `owner.pending.key` before the bytes that
 * let the band commit leave, and turns it into `owner.key` once the band confirms. After
 * a failure the pending key stays only if the band may have committed (an explicit
 * rejection drops it); band.json is written whenever a key was kept, so the next
 * connection tries it ([BandLink] prefers the pending key and confirms it on connect).
 *
 * One claim at a time ([current]); progress goes to [BandService.state]'s log.
 * Permissions are checked by the caller.
 */
@SuppressLint("MissingPermission")
class BandClaim private constructor(context: Context, private val session: String) {
    private val context = context.applicationContext
    // Looked up on every use: after Bluetooth is switched off and on, an adapter held
    // from before can keep reporting "off" (seen on HyperOS).
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var device: BluetoothDevice? = null
    private var name = "the band"
    private var bonding: BroadcastReceiver? = null
    private var bondTimeout: Runnable? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var channelThread: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var ended = false
    /** Guarded by [lock]; 0 when no bridge claim is open. */
    private var handle = 0L
    private var guess = 0

    /** Claim [picked] directly (chosen in the companion picker), or scan for a band in pairing mode. */
    private fun start(picked: BluetoothDevice?) {
        if (picked != null) {
            device = picked
            name = runCatching { picked.name }.getOrNull() ?: "the band"
            line("claiming $name")
            return bond(picked)
        }
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
            ?: return finish("failed: Bluetooth is off")
        line("looking for a band in pairing mode")
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
        main.postDelayed(notFound, SCAN_MS)
    }

    private fun cancel() {
        cancelled = true
        if (channelThread == null) finish("failed: the claim was cancelled")
        else runCatching { socket?.close() }
    }

    private val notFound = Runnable {
        finish("failed: no band in pairing mode found. hold the band's button for 3 seconds until it flashes, then try again.")
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val found = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!found.lowercase().startsWith("meta band") || device != null || ended) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            main.removeCallbacks(notFound)
            device = result.device
            name = found
            bond(result.device)
        }

        override fun onScanFailed(errorCode: Int) = finish("failed: the Bluetooth scan failed ($errorCode)")
    }

    /** Pair at the Bluetooth level first, as the desktop does; the channel works without it too. */
    private fun bond(device: BluetoothDevice) {
        if (device.bondState == BluetoothDevice.BOND_BONDED) return connect(device)
        line("pairing with $name over Bluetooth")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                @Suppress("DEPRECATION")
                val changed = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (changed?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded(device, true)
                    BluetoothDevice.BOND_NONE -> bonded(device, false)
                }
            }
        }
        bonding = receiver
        val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        if (!device.createBond()) return bonded(device, false)
        bondTimeout = Runnable { bonded(device, false) }.also { main.postDelayed(it, BOND_MS) }
    }

    private fun bonded(device: BluetoothDevice, ok: Boolean) {
        val receiver = bonding ?: return
        bonding = null
        bondTimeout?.let(main::removeCallbacks)
        runCatching { context.unregisterReceiver(receiver) }
        if (!ok) line("Bluetooth pairing didn't finish; going on without it")
        connect(device)
    }

    private fun connect(device: BluetoothDevice) {
        if (ended) return
        line("connecting to $name")
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                // Once the channel runs, its own read loop sees the end.
                BluetoothProfile.STATE_DISCONNECTED -> if (channelThread == null) {
                    finish("failed: the band disconnected (status $status). put it back in pairing mode and try again.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(BandLink.BAND_SERVICE)?.getCharacteristic(BandLink.PSM_CHARACTERISTIC)
                ?: return finish("failed: this device has no Neural Band input service")
            gatt.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (channelThread != null) return
            if (status != BluetoothGatt.GATT_SUCCESS || value.size != 2) {
                return finish("failed: reading the band's input channel failed (status $status)")
            }
            val psm = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
            channelThread = thread(name = "band-claim") { runClaim(gatt.device, psm) }
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

    /** Every guess worth trying, one fresh channel each; ends the claim. */
    private fun runClaim(device: BluetoothDevice, psm: Int) {
        while (true) {
            try {
                synchronized(lock) { handle = Bridge.claimOpen(session, context.filesDir.path, guess) }
            } catch (e: Exception) {
                return finish("failed: ${e.message}")
            }
            runChannel(device, psm)
            val (state, next) = synchronized(lock) {
                val result = Bridge.claimState(handle) to Bridge.claimNextGuess(handle)
                Bridge.claimClose(handle)
                handle = 0L
                result
            }
            if (state.startsWith("failed") && next >= 0 && !cancelled) {
                guess = next
                line("trying encryption guess ${next + 1}")
                SystemClock.sleep(1000)
                continue
            }
            return finish(if (cancelled && state != "done") "failed: the claim was cancelled" else state)
        }
    }

    /** One L2CAP channel: the read loop feeds the bridge, a tick thread keeps time (as [BandLink]). */
    private fun runChannel(device: BluetoothDevice, psm: Int) {
        val channel = try {
            device.createInsecureL2capChannel(psm).also { it.connect() }
        } catch (e: Exception) {
            synchronized(lock) { Bridge.claimClosed(handle, "opening the band's channel failed: ${e.message}") }
            return
        }
        socket = channel
        if (cancelled) runCatching { channel.close() }
        val output = channel.outputStream
        val write = { bytes: ByteArray -> if (bytes.isNotEmpty()) synchronized(output) { output.write(bytes) } }
        val open = AtomicBoolean(true)
        try {
            write(synchronized(lock) { Bridge.claimRequest(handle) })
            drain()
            thread(name = "band-claim-tick") {
                while (open.get()) {
                    SystemClock.sleep(50)
                    try {
                        val out = synchronized(lock) { if (handle == 0L) null else Bridge.claimTick(handle, now()) }
                        out?.let(write)
                        // Ended (done, or failed on a timeout): closing the channel ends the read loop.
                        if (drain() != "running") {
                            channel.close()
                            break
                        }
                    } catch (e: Exception) {
                        break
                    }
                }
            }
            val buffer = ByteArray(8192)
            val input = channel.inputStream
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                // May block on Meta's HTTP steps; the band waits.
                write(synchronized(lock) { Bridge.claimFeed(handle, buffer.copyOf(count), now()) })
                if (drain() != "running") break
            }
            synchronized(lock) { Bridge.claimClosed(handle, "the band closed the connection") }
        } catch (e: Exception) {
            val why = if (cancelled) "the claim was cancelled" else "the band's channel failed: ${e.message}"
            synchronized(lock) { Bridge.claimClosed(handle, why) }
        } finally {
            open.set(false)
            runCatching { channel.close() }
            socket = null
            drain()
        }
    }

    /** Hand progress lines on; returns the claim's state. */
    private fun drain(): String {
        val (lines, state) = synchronized(lock) {
            if (handle == 0L) return ""
            Bridge.claimLog(handle) to Bridge.claimState(handle)
        }
        if (lines.isNotEmpty()) lines.lines().forEach(::line)
        return state
    }

    /** The one way a claim ends: release Bluetooth, keep band.json with any kept key, report. */
    private fun finish(state: String) {
        synchronized(this) {
            if (ended) return
            ended = true
        }
        // Only the timers: progress lines already posted still arrive, before the end.
        main.removeCallbacks(notFound)
        bondTimeout?.let(main::removeCallbacks)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        bonding?.let { runCatching { context.unregisterReceiver(it) } }
        bonding = null
        runCatching { socket?.close() }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        val pending = File(context.filesDir, "owner.pending.key").exists()
        device?.takeIf { state == "done" || pending }?.let(::saveBand)
        main.post { ended(state, pending) }
    }

    /** band.json as the Linux app writes it: the bonded address when there is one. */
    private fun saveBand(device: BluetoothDevice) {
        val bonded = adapter?.bondedDevices?.firstOrNull { it.address == device.address }
            ?: adapter?.bondedDevices?.firstOrNull { it.name == name }
        val json = JSONObject()
            .put("address", (bonded ?: device).address)
            .put("name", name)
            .put("scheme_guess", guess)
        val temp = File(context.filesDir, ".band.json.tmp")
        temp.writeText(json.toString(2))
        temp.renameTo(Identity.bandFile(context))
    }

    private fun line(text: String) = main.post { log(text) }.let {}

    /** On the main thread. */
    private fun ended(state: String, pending: Boolean) {
        current = null
        when {
            state == "done" -> {
                log("claimed $name; connecting as its owner")
                // Android refuses a foreground service started from the background.
                runCatching { BandService.start(context) }
                    .onFailure { log("tap the power button to connect") }
            }
            state.startsWith("expired: ") -> {
                SessionStore.delete(context)
                log("sign in to Meta again, then claim once more")
            }
            else -> {
                val message = state.removePrefix("failed: ")
                if (BandService.state.value.log.lastOrNull() != message) log(message)
                if (pending) log("the band may have accepted this phone: its key is kept and the next connection tries it")
            }
        }
        BandService.state.update { copy(claiming = false) }
    }

    companion object {
        private const val SCAN_MS = 20_000L
        private const val BOND_MS = 30_000L

        /** The claim running now, if any. */
        var current: BandClaim? = null
            private set

        /** Start a claim with the stored Meta session (the caller checked there is one). */
        fun start(context: Context, sessionJson: String, picked: BluetoothDevice? = null) {
            if (current != null) return
            // One Bluetooth link at a time: the service must not hold the band.
            BandService.stop(context)
            BandService.state.update { copy(claiming = true) }
            current = BandClaim(context, sessionJson).also { it.start(picked) }
        }

        fun cancel() = current?.cancel()

        private fun log(line: String) = BandService.state.update { copy(log = (log + line).takeLast(60)) }
    }
}
