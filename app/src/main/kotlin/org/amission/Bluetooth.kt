package org.amission

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import org.amission.msp.BleUart
import org.amission.msp.ByteChannel
import org.amission.msp.GattCharacteristicInfo
import org.amission.msp.GattServiceInfo
import org.amission.msp.MspException
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** The two kinds of Bluetooth a flight-controller module can use. They are separate protocols on the phone. */
enum class BtTransport { CLASSIC, LE }

/** A Bluetooth device the user picked as the flight controller. */
data class BtTarget(val address: String, val name: String?, val transport: BtTransport) {
    val title: String get() = name?.takeIf { it.isNotBlank() } ?: address
    val kind: String get() = if (transport == BtTransport.LE) "BLE" else "Classic"
}

object BtPermissions {
    /** Android 12+ asks for Nearby devices access; before that, scanning needs the location permission. */
    val needed: Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun has(context: Context): Boolean =
        needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

/** A device seen while scanning; [paired] devices are listed first-hand from the phone's own pairing list. */
data class BtFound(val target: BtTarget, val rssi: Int?, val paired: Boolean)

/** Lists paired devices and scans for nearby Classic and BLE devices at the same time. */
class BtScanner(private val context: Context) {
    var results by mutableStateOf<List<BtFound>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val found = LinkedHashMap<String, BtFound>()
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var leCallback: ScanCallback? = null
    private val stopRunnable = Runnable { stop() }

    @SuppressLint("MissingPermission")
    fun start() {
        if (scanning) return
        val adapter = bluetoothAdapter(context)
        when {
            adapter == null -> { message = "This phone has no Bluetooth."; return }
            !adapter.isEnabled -> { message = "Turn Bluetooth on first."; return }
            !BtPermissions.has(context) -> { message = "Allow Bluetooth (Nearby devices) access first."; return }
        }
        adapter!!
        found.clear()
        message = null
        try {
            adapter.bondedDevices.orEmpty().forEach { addDevice(it, null, paired = true) }
            registerClassicReceiver()
            adapter.startDiscovery()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    addTarget(result.device, result.scanRecord?.deviceName, BtTransport.LE, result.rssi, paired = false)
                }

                override fun onScanFailed(errorCode: Int) { message = "Bluetooth scan failed (code $errorCode)." }
            }
            leCallback = callback
            adapter.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            scanning = true
            handler.postDelayed(stopRunnable, SCAN_MS)
            publish()
        } catch (e: SecurityException) {
            message = "Bluetooth access was denied."
            stop()
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(stopRunnable)
        try {
            val adapter = bluetoothAdapter(context)
            adapter?.cancelDiscovery()
            leCallback?.let { adapter?.bluetoothLeScanner?.stopScan(it) }
        } catch (e: Exception) {
            // already stopped, or permission revoked while scanning
        }
        leCallback = null
        receiver?.let { try { context.unregisterReceiver(it) } catch (e: IllegalArgumentException) { } }
        receiver = null
        scanning = false
    }

    private fun registerClassicReceiver() {
        val r = object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.action != BluetoothDevice.ACTION_FOUND) return
                val device = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt().takeIf { it != Short.MIN_VALUE.toInt() }
                addDevice(device, rssi, paired = false, advertisedName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME))
            }
        }
        receiver = r
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(r, filter)
    }

    @SuppressLint("MissingPermission")
    private fun addDevice(device: BluetoothDevice, rssi: Int?, paired: Boolean, advertisedName: String? = null) {
        val name = advertisedName ?: try { device.name } catch (e: SecurityException) { null }
        // A device can speak Classic, BLE or both; list each way of reaching it, since they are different connections.
        val type = try { device.type } catch (e: SecurityException) { BluetoothDevice.DEVICE_TYPE_UNKNOWN }
        if (type != BluetoothDevice.DEVICE_TYPE_LE) addTarget(device, name, BtTransport.CLASSIC, rssi, paired)
        if (type == BluetoothDevice.DEVICE_TYPE_LE || type == BluetoothDevice.DEVICE_TYPE_DUAL) addTarget(device, name, BtTransport.LE, rssi, paired)
    }

    @SuppressLint("MissingPermission")
    private fun addTarget(device: BluetoothDevice, name: String?, transport: BtTransport, rssi: Int?, paired: Boolean) {
        val shownName = name ?: try { device.name } catch (e: SecurityException) { null }
        val key = "${device.address}/$transport"
        val previous = found[key]
        found[key] = BtFound(
            BtTarget(device.address, shownName ?: previous?.target?.name, transport),
            rssi ?: previous?.rssi,
            paired || previous?.paired == true,
        )
        publish()
    }

    private fun publish() {
        results = found.values.sortedWith(
            compareByDescending<BtFound> { it.target.name != null }.thenByDescending { it.rssi ?: -200 },
        )
    }

    private companion object {
        const val SCAN_MS = 12_000L
    }
}

/** Classic Bluetooth serial (RFCOMM / SPP), as used by HC-05 style modules. */
class BluetoothClassicChannel private constructor(private val socket: BluetoothSocket) : ByteChannel, AutoCloseable {
    private val input = socket.inputStream
    private val output = socket.outputStream

    override fun write(data: ByteArray) {
        output.write(data)
        output.flush()
    }

    /** The socket has no read timeout, so poll for available bytes until the deadline. */
    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val available = input.available()
            if (available > 0) return input.read(buffer, 0, minOf(available, buffer.size))
            if (System.currentTimeMillis() >= deadline) return 0
            Thread.sleep(5)
        }
    }

    override fun close() {
        try { socket.close() } catch (e: IOException) { }
    }

    companion object {
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        @SuppressLint("MissingPermission")
        fun open(context: Context, address: String, timeoutMs: Long = 8_000): BluetoothClassicChannel {
            val adapter = bluetoothAdapter(context) ?: throw MspException("This phone has no Bluetooth.")
            if (!adapter.isEnabled) throw MspException("Bluetooth is turned off.")
            val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
                throw MspException("Not a valid Bluetooth address: $address")
            }
            adapter.cancelDiscovery() // discovery slows a connection down badly
            var last: Exception? = null
            for (secure in listOf(true, false)) {
                val socket = try {
                    if (secure) device.createRfcommSocketToServiceRecord(SPP) else device.createInsecureRfcommSocketToServiceRecord(SPP)
                } catch (e: SecurityException) {
                    throw MspException("Bluetooth access was denied.")
                }
                // connect() cannot be interrupted, but closing the socket from another thread makes it fail.
                val watchdog = Thread {
                    try { Thread.sleep(timeoutMs); socket.close() } catch (e: InterruptedException) { } catch (e: IOException) { }
                }.apply { isDaemon = true; start() }
                try {
                    socket.connect()
                    watchdog.interrupt()
                    return BluetoothClassicChannel(socket)
                } catch (e: IOException) {
                    last = e
                    try { socket.close() } catch (_: IOException) { }
                } finally {
                    watchdog.interrupt()
                }
            }
            throw MspException("Could not connect over Bluetooth (${last?.message ?: "no answer"}). Is the flight controller powered and paired?")
        }
    }
}

/** Bluetooth Low Energy "serial over GATT", as used by many modern FC modules. */
class BleUartChannel(private val context: Context) : BluetoothGattCallback(), ByteChannel, AutoCloseable {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null

    private val rx = LinkedBlockingQueue<ByteArray>()
    private var leftover = ByteArray(0)
    private val writeDone = Semaphore(0)
    private val ready = CountDownLatch(1)

    @Volatile private var chunk = 20
    @Volatile private var failure: String? = null

    @SuppressLint("MissingPermission")
    fun connect(address: String, timeoutMs: Long = 12_000) {
        val adapter = bluetoothAdapter(context) ?: throw MspException("This phone has no Bluetooth.")
        if (!adapter.isEnabled) throw MspException("Bluetooth is turned off.")
        val device = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) {
            throw MspException("Not a valid Bluetooth address: $address")
        }
        try {
            gatt = device.connectGatt(context, false, this, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw MspException("Bluetooth access was denied.")
        }
        if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            close()
            throw MspException(failure ?: "Could not connect over Bluetooth LE (no answer). Is the flight controller powered?")
        }
        failure?.let { close(); throw MspException(it) }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
            // A larger MTU means fewer, bigger packets; discovery continues once the exchange finishes.
            if (!g.requestMtu(185)) g.discoverServices()
        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
            if (ready.count > 0) { failure = "The Bluetooth LE connection was refused (status $status)."; ready.countDown() }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) chunk = (mtu - 3).coerceIn(20, 180)
        g.discoverServices()
    }

    @SuppressLint("MissingPermission")
    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        val info = g.services.map { s ->
            GattServiceInfo(
                s.uuid.toString(),
                s.characteristics.map { c ->
                    GattCharacteristicInfo(
                        c.uuid.toString(),
                        canWrite = c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0,
                        canNotify = c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0,
                    )
                },
            )
        }
        val pair = BleUart.select(info)
        if (pair == null) {
            failure = "This Bluetooth device has no serial service (found ${info.size} services). It may not be a flight controller module."
            ready.countDown()
            return
        }
        val service = g.getService(UUID.fromString(pair.serviceUuid))
        writeChar = service?.getCharacteristic(UUID.fromString(pair.writeUuid))
        val notify = service?.getCharacteristic(UUID.fromString(pair.notifyUuid))
        if (writeChar == null || notify == null) { failure = "The serial characteristics could not be read."; ready.countDown(); return }

        g.setCharacteristicNotification(notify, true)
        val cccd = notify.getDescriptor(CCCD)
        if (cccd == null) { ready.countDown(); return } // some modules notify without a descriptor
        val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(cccd, value)
        } else {
            @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) }
        }
    }

    override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
        if (status != BluetoothGatt.GATT_SUCCESS) failure = "Could not enable Bluetooth notifications (status $status)."
        ready.countDown()
    }

    @Deprecated("Used below Android 13")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        characteristic.value?.let { rx.offer(it.copyOf()) }
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        rx.offer(value.copyOf())
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
        writeDone.release()
    }

    @SuppressLint("MissingPermission")
    override fun write(data: ByteArray) {
        val g = gatt ?: throw IOException("Bluetooth is not connected")
        val c = writeChar ?: throw IOException("Bluetooth is not ready")
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        var offset = 0
        while (offset < data.size) {
            val part = data.copyOfRange(offset, minOf(offset + chunk, data.size))
            writeDone.drainPermits()
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, part, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION") run { c.writeType = type; c.value = part; g.writeCharacteristic(c) }
            }
            if (!started) throw IOException("Bluetooth write was refused")
            // One write may be in flight at a time; wait for its completion before sending the next piece.
            if (!writeDone.tryAcquire(2, TimeUnit.SECONDS)) throw IOException("Bluetooth write timed out")
            offset += part.size
        }
    }

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        if (leftover.isEmpty()) leftover = rx.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        val n = minOf(leftover.size, buffer.size)
        leftover.copyInto(buffer, 0, 0, n)
        leftover = leftover.copyOfRange(n, leftover.size)
        return n
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null
    }

    private companion object {
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
