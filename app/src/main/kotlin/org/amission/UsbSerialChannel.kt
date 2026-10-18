package org.amission

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import org.amission.msp.ByteChannel
import org.amission.msp.MspException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** [ByteChannel] over a USB-OTG connected flight controller (CDC/VCP). */
class UsbSerialChannel private constructor(private val port: UsbSerialPort) : ByteChannel, AutoCloseable {

    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)

    override fun close() = port.close()

    companion object {
        private const val WRITE_TIMEOUT_MS = 1000
        private const val ACTION_USB_PERMISSION = "org.amission.USB_PERMISSION"

        /** Finds the first attached serial device, asks for permission if needed, and opens it at [baud]. */
        suspend fun open(context: Context, baud: Int = 115200): UsbSerialChannel {
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()
                ?: throw MspException("No USB serial device found. Check the OTG cable.")
            if (!manager.hasPermission(driver.device) && !requestPermission(context, manager, driver.device)) {
                throw MspException("USB permission denied")
            }
            val connection = manager.openDevice(driver.device)
                ?: throw MspException("Could not open USB device")
            val port = driver.ports.first()
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            return UsbSerialChannel(port)
        }

        private suspend fun requestPermission(
            context: Context,
            manager: UsbManager,
            device: android.hardware.usb.UsbDevice,
        ): Boolean = suspendCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    context.unregisterReceiver(this)
                    cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, filter)
            }
            val pending = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE,
            )
            manager.requestPermission(device, pending)
        }
    }
}
