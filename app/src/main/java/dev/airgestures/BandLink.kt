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
import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/**
 * One connection to the band, as the desktop daemon makes it: find the band by
 * name, read its input-channel PSM over GATT, open an L2CAP channel on it and
 * run the band-core session over that. Everything is reported through [log].
 * Permissions (BLUETOOTH_SCAN/CONNECT) are checked by the caller.
 */
@SuppressLint("MissingPermission")
class BandLink(private val context: Context, private val log: (String) -> Unit) {
    private val adapter = context.getSystemService(BluetoothManager::class.java).adapter
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var socket: BluetoothSocket? = null
    private var handle = 0L
    @Volatile private var running = false

    fun start() {
        if (running) return
        // Null while Bluetooth is off (or still turning on).
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth is off: turn it on and tap Connect again")
            return
        }
        val dir = context.filesDir
        val key = File(dir, "owner.key")
        if (!key.exists()) {
            log("no owner.key in ${dir.path}: run scripts/push-identity.sh")
            return
        }
        val guess = File(dir, "band.json").takeIf { it.exists() }
            ?.let { org.json.JSONObject(it.readText()).optInt("scheme_guess", 0) } ?: 0
        handle = try {
            Bridge.open(key.readBytes(), guess)
        } catch (e: Exception) {
            log("owner.key: ${e.message}")
            return
        }
        running = true
        log("scanning for a Meta Band… (scheme guess $guess)")
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scan,
        )
    }

    fun stop() {
        running = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        runCatching { socket?.close() }
        runCatching { gatt?.close() }
        socket = null
        gatt = null
        synchronized(lock) {
            if (handle != 0L) Bridge.close(handle)
            handle = 0L
        }
        log("stopped")
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.lowercase().startsWith("meta band") || gatt != null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            log("found $name at ${result.device.address} (rssi ${result.rssi}); connecting GATT")
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) = log("scan failed: $errorCode")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            log("GATT state $newState (status $status), bond ${gatt.device.bondState}")
            if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
            if (newState == BluetoothProfile.STATE_DISCONNECTED && running) stop()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(BAND_SERVICE)?.getCharacteristic(PSM_CHARACTERISTIC)
            if (characteristic == null) {
                log("no Neural Band input service (status $status)")
                return
            }
            // May trigger Bluetooth pairing (a system dialog) the first time.
            log("reading the input channel PSM")
            gatt.readCharacteristic(characteristic)
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS || value.size != 2) {
                log("PSM read failed (status $status, ${value.size} bytes)")
                return
            }
            val psm = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
            log("PSM $psm; opening L2CAP")
            thread(name = "band-l2cap") { runChannel(gatt.device, psm) }
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

    private fun runChannel(device: BluetoothDevice, psm: Int) {
        val channel = try {
            device.createInsecureL2capChannel(psm).also { it.connect() }
        } catch (insecure: Exception) {
            log("insecure L2CAP failed (${insecure.message}); trying secure")
            try {
                device.createL2capChannel(psm).also { it.connect() }
            } catch (secure: Exception) {
                log("L2CAP failed: ${secure.message}")
                return
            }
        }
        socket = channel
        log("L2CAP open (max rx ${channel.maxReceivePacketSize}, tx ${channel.maxTransmitPacketSize})")
        val input = channel.inputStream
        val output = channel.outputStream
        val write = { bytes: ByteArray -> if (bytes.isNotEmpty()) synchronized(output) { output.write(bytes) } }
        try {
            write(synchronized(lock) { Bridge.request(handle) })
            thread(name = "band-tick") {
                while (running) {
                    SystemClock.sleep(250)
                    try {
                        val out = synchronized(lock) { if (handle == 0L) null else Bridge.tick(handle, now()) }
                        out?.let(write)
                        drainEvents()
                    } catch (e: Exception) {
                        log("tick: ${e.message}")
                        break
                    }
                }
            }
            val buffer = ByteArray(8192)
            while (running) {
                val count = input.read(buffer)
                if (count < 0) break
                val out = synchronized(lock) {
                    if (handle == 0L) null else Bridge.feed(handle, buffer.copyOf(count), now())
                }
                out?.let(write)
                drainEvents()
            }
            log("the band closed the channel")
        } catch (e: Exception) {
            if (running) log("link: ${e.message}")
        } finally {
            if (running) stop()
        }
    }

    private fun drainEvents() {
        val lines = synchronized(lock) { if (handle == 0L) "" else Bridge.events(handle) }
        if (lines.isNotEmpty()) lines.lines().forEach(log)
    }

    companion object {
        val BAND_SERVICE: UUID = UUID.fromString("0000feb8-0000-1000-8000-00805f9b34fb")
        val PSM_CHARACTERISTIC: UUID = UUID.fromString("2d41da7c-82b6-42aa-b34e-e2e01df8cc1a")
    }
}
