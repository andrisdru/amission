package org.amission.msp

/** What a BLE characteristic can do, in the terms this app cares about. */
data class GattCharacteristicInfo(val uuid: String, val canWrite: Boolean, val canNotify: Boolean)

data class GattServiceInfo(val uuid: String, val characteristics: List<GattCharacteristicInfo>)

/** The pair of characteristics used as a serial pipe: write to [writeUuid], receive on [notifyUuid]. They may be the same one. */
data class UartPair(val serviceUuid: String, val writeUuid: String, val notifyUuid: String)

/**
 * Finds the "serial over BLE" characteristics on a flight-controller Bluetooth module. Modules do
 * not share one standard, so well-known layouts are tried first and then any non-standard service
 * that can both be written to and send notifications.
 */
object BleUart {
    private const val BASE = "-0000-1000-8000-00805f9b34fb"

    private fun short(id: String) = "0000$id$BASE"

    private data class Known(val service: String, val write: String, val notify: String)

    private val known = listOf(
        // Nordic UART service
        Known("6e400001-b5a3-f393-e0a9-e50e24dcca9e", "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
        // HM-10 / CC2541 style modules: one characteristic for both directions
        Known(short("ffe0"), short("ffe1"), short("ffe1")),
        // SpeedyBee modules
        Known(short("abf0"), short("abf1"), short("abf2")),
        // Common ESP-style layout
        Known(short("fff0"), short("fff2"), short("fff1")),
    )

    /** 16-bit services every device has and that never carry user data: Generic Access, Generic Attribute, Device Information. */
    private val standardServices = setOf("1800", "1801", "180a").map { short(it) }.toSet()

    fun select(services: List<GattServiceInfo>): UartPair? {
        val byService = services.associateBy { it.uuid.lowercase() }
        for (k in known) {
            val s = byService[k.service] ?: continue
            val chars = s.characteristics.associateBy { it.uuid.lowercase() }
            val w = chars[k.write]
            val n = chars[k.notify]
            if (w != null && w.canWrite && n != null && n.canNotify) return UartPair(k.service, k.write, k.notify)
        }
        for (s in services) {
            if (s.uuid.lowercase() in standardServices) continue
            // A single characteristic that does both is the simplest and most common arrangement.
            val both = s.characteristics.firstOrNull { it.canWrite && it.canNotify }
            if (both != null) return UartPair(s.uuid.lowercase(), both.uuid.lowercase(), both.uuid.lowercase())
            val w = s.characteristics.firstOrNull { it.canWrite }
            val n = s.characteristics.firstOrNull { it.canNotify }
            if (w != null && n != null) return UartPair(s.uuid.lowercase(), w.uuid.lowercase(), n.uuid.lowercase())
        }
        return null
    }
}
