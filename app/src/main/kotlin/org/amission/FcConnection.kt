package org.amission

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.amission.msp.ByteChannel
import org.amission.msp.MissionClient
import org.amission.msp.MspException
import org.amission.msp.MspTimeoutException
import org.amission.msp.SerialMspLink

/** An open connection to the flight controller; closing it releases the USB or Bluetooth link. */
class FcLink(val channel: ByteChannel, private val closer: AutoCloseable, val description: String) : AutoCloseable {
    override fun close() = closer.close()
}

/**
 * Decides how to reach the flight controller. With a USB cable plugged in that is used; otherwise the
 * saved Bluetooth device. The user can prefer Bluetooth even when a cable is plugged in.
 */
class FcConnections(private val app: Application) {
    private val prefs = app.getSharedPreferences("amission_prefs", Context.MODE_PRIVATE)

    var bluetooth by mutableStateOf(loadTarget())
        private set
    var preferBluetooth by mutableStateOf(prefs.getBoolean("preferBluetooth", false))
        private set

    private fun loadTarget(): BtTarget? {
        val address = prefs.getString("btAddress", null) ?: return null
        val transport = runCatching { BtTransport.valueOf(prefs.getString("btTransport", "")!!) }.getOrNull() ?: return null
        return BtTarget(address, prefs.getString("btName", null), transport)
    }

    fun select(target: BtTarget) {
        bluetooth = target
        prefs.edit().putString("btAddress", target.address).putString("btName", target.name)
            .putString("btTransport", target.transport.name).apply()
    }

    fun forget() {
        bluetooth = null
        prefs.edit().remove("btAddress").remove("btName").remove("btTransport").apply()
    }

    fun updatePreferBluetooth(value: Boolean) {
        preferBluetooth = value
        prefs.edit().putBoolean("preferBluetooth", value).apply()
    }

    /** True if there is any way to try: a cable is plugged in, or a Bluetooth device is saved. */
    fun canReachFc(): Boolean = bluetooth != null || UsbSerialChannel.isDeviceAttached(app)

    suspend fun open(): FcLink {
        val bt = bluetooth
        val usbAttached = UsbSerialChannel.isDeviceAttached(app)
        return when {
            bt != null && (preferBluetooth || !usbAttached) -> openBluetooth(bt)
            usbAttached -> UsbSerialChannel.open(app).let { FcLink(it, it, "USB") }
            else -> throw MspException("No flight controller found. Plug it in with a USB OTG cable, or choose a Bluetooth device in More ▸ Bluetooth.")
        }
    }

    suspend fun openBluetooth(target: BtTarget): FcLink = withContext(Dispatchers.IO) {
        when (target.transport) {
            BtTransport.CLASSIC -> BluetoothClassicChannel.open(app, target.address).let { FcLink(it, it, "Bluetooth ${target.title}") }
            BtTransport.LE -> connectLe(target)
        }
    }

    /**
     * A BLE module often still holds the previous connection for a moment after it was closed, which shows up as a
     * timeout (GATT status 147 or no answer). One more attempt after a short pause usually succeeds. Other failures
     * (no serial service, permission, Bluetooth off) will not change, so they are reported straight away.
     */
    private suspend fun connectLe(target: BtTarget): FcLink {
        var lastError: MspException? = null
        repeat(2) { attempt ->
            if (attempt > 0) delay(1500)
            val channel = BleUartChannel(app)
            try {
                channel.connect(target.address)
                return FcLink(channel, channel, "Bluetooth ${target.title}")
            } catch (e: MspException) {
                channel.close()
                lastError = e
                if (!isRetryable(e)) throw e
            }
        }
        throw lastError ?: MspException("Could not connect over Bluetooth LE.")
    }

    private fun isRetryable(e: MspException): Boolean {
        val m = e.message.orEmpty()
        return "status 147" in m || "status 133" in m || "no answer" in m
    }

    /** Opens the device, asks the FC a simple question, and says in plain words what happened. */
    suspend fun test(target: BtTarget): String {
        return try {
            openBluetooth(target).use { link ->
                withContext(Dispatchers.IO) {
                    val info = MissionClient(SerialMspLink(link.channel, timeoutMs = 1500, retries = 1)).info()
                    "Connected. The flight controller answered (mission memory: ${info.waypointCount} of ${info.maxWaypoints} waypoints)."
                }
            }
        } catch (e: MspTimeoutException) {
            "Connected over Bluetooth, but the flight controller did not answer. Check that MSP is enabled on the UART the " +
                "Bluetooth module is wired to (UART3 on the GEPRC TAKER H743 BT) in the Configurator's Ports tab."
        } catch (e: Exception) {
            e.message ?: "Could not connect."
        }
    }
}
