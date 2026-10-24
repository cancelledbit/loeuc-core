package example.hud

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * The BLE central that talks to the HUD. Everything here is ordinary Android Bluetooth code -
 * LoEUC is not involved in it, and the wheel's own link is LoEUC's, not this app's.
 *
 * All state lives on the main thread: GATT callbacks arrive on a binder thread and are posted
 * over first, which is also the thread the extension callbacks arrive on.
 */
@SuppressLint("MissingPermission") // Every entry point checks [HudPermissions.granted] first.
class HudLink(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onLinkChanged(connected: Boolean)
        fun onEvent(event: HudEvent)
    }

    private val main = Handler(Looper.getMainLooper())
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter

    private var running = false
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var telemetry: BluetoothGattCharacteristic? = null
    private var config: BluetoothGattCharacteristic? = null
    private var ready = false
    private var writeInFlight = false
    private var brightness = 70

    val isConnected: Boolean get() = ready

    fun start() {
        if (running) return
        running = true
        scan()
    }

    fun stop() {
        running = false
        main.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.close()
        gatt = null
        setReady(false)
    }

    /**
     * Sends one telemetry packet. A packet that finds the previous one still in flight is
     * dropped rather than queued: the next snapshot is 200 ms away and fresher.
     */
    fun send(packet: ByteArray) {
        val target = telemetry ?: return
        if (!ready || writeInFlight) return
        writeInFlight = write(target, packet, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
    }

    fun setBrightness(percent: Int) {
        brightness = percent.coerceIn(0, 100)
        val target = config ?: return
        if (ready && !writeInFlight) {
            writeInFlight = write(target, byteArrayOf(brightness.toByte()), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        }
    }

    private fun scan() {
        if (!running || scanning || gatt != null) return
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null || !HudPermissions.granted(context)) {
            Log.w(LogTag, "cannot scan: Bluetooth off or permissions missing - open the app once to grant them")
            retryLater()
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(ServiceUuid)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        scanning = true
        // A HUD that is switched off should not keep the radio scanning for the whole ride.
        main.postDelayed({ if (scanning) { stopScan(); retryLater() } }, ScanWindowMillis)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    private fun retryLater() {
        if (running) main.postDelayed(::scan, RetryMillis)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            main.post { connect(result.device) }
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (!running || gatt != null) return
        stopScan()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    g.discoverServices()
                } else {
                    g.close()
                    gatt = null
                    setReady(false)
                    retryLater()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                if (g !== gatt) return@post
                val service = g.getService(ServiceUuid)
                telemetry = service?.getCharacteristic(TelemetryUuid)
                config = service?.getCharacteristic(ConfigUuid)
                val events = service?.getCharacteristic(EventsUuid)
                if (telemetry == null || events == null) {
                    Log.w(LogTag, "not a HUD: characteristics missing")
                    g.disconnect()
                    return@post
                }
                // Notifications need both halves: the local flag and the remote descriptor.
                g.setCharacteristicNotification(events, true)
                val cccd = events.getDescriptor(ClientConfigUuid)
                if (cccd == null) {
                    setReady(true)
                } else {
                    writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            main.post {
                if (g !== gatt) return@post
                setReady(true)
                setBrightness(brightness)
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post { writeInFlight = false }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid != EventsUuid) return
            val event = HudPacket.decodeEvent(value) ?: return
            main.post { listener.onEvent(event) }
        }

        // Before API 33 the value arrives through the characteristic instead of a parameter.
        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            onCharacteristicChanged(g, characteristic, characteristic.value ?: return)
        }
    }

    private fun setReady(value: Boolean) {
        writeInFlight = false
        if (ready == value) return
        ready = value
        listener.onLinkChanged(value)
    }

    private fun write(characteristic: BluetoothGattCharacteristic, bytes: ByteArray, type: Int): Boolean {
        val target = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            target.writeCharacteristic(characteristic, bytes, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = type
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            target.writeCharacteristic(characteristic)
        }
    }

    private fun writeDescriptor(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, value)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(descriptor)
        }
    }

    companion object {
        private const val LogTag = "HudLink"
        private const val ScanWindowMillis = 15_000L
        private const val RetryMillis = 5_000L

        val ServiceUuid: UUID = UUID.fromString("9b3c0001-4c6f-4555-4300-00000000a001")
        val TelemetryUuid: UUID = UUID.fromString("9b3c0001-4c6f-4555-4300-00000000a002")
        val EventsUuid: UUID = UUID.fromString("9b3c0001-4c6f-4555-4300-00000000a003")
        val ConfigUuid: UUID = UUID.fromString("9b3c0001-4c6f-4555-4300-00000000a004")
        private val ClientConfigUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}

/** The Bluetooth permissions this app needs, which differ before and after Android 12. */
object HudPermissions {
    val required: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun granted(context: Context): Boolean = required.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
