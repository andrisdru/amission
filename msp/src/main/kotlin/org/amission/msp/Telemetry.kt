package org.amission.msp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reply to MSP_RAW_GPS. Latitude/longitude are degrees * 1e7, altitude is metres. */
data class GpsFix(
    val fixType: Int,
    val satellites: Int,
    val latE7: Int,
    val lonE7: Int,
    val altitudeM: Int,
    val groundSpeedCms: Int,
    val courseDeg10: Int,
    val hdop: Int,
) {
    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7

    /**
     * True when the position is trustworthy enough to centre a map on. INAV fix types are
     * 0 = none, 1 = 2D, 2 = 3D. A quad powered only over USB often reports no fix, or a stale
     * 0,0, so require a 3D fix, a few satellites and real coordinates.
     */
    val isUsable: Boolean
        get() = fixType >= FIX_3D && satellites >= MIN_SATELLITES && !(latE7 == 0 && lonE7 == 0)

    companion object {
        const val FIX_3D = 2
        const val MIN_SATELLITES = 4
        const val PAYLOAD_SIZE = 18

        fun decode(payload: ByteArray): GpsFix {
            if (payload.size < PAYLOAD_SIZE) throw MspException("RAW_GPS payload too short: ${payload.size}")
            val b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            return GpsFix(
                fixType = b.get().toInt() and 0xFF,
                satellites = b.get().toInt() and 0xFF,
                latE7 = b.getInt(),
                lonE7 = b.getInt(),
                altitudeM = b.getShort().toInt(),
                groundSpeedCms = b.getShort().toInt() and 0xFFFF,
                courseDeg10 = b.getShort().toInt() and 0xFFFF,
                hdop = b.getShort().toInt() and 0xFFFF,
            )
        }
    }
}

/** Read-only live data from the FC. */
class TelemetryClient(private val link: MspLink) {
    fun gps(): GpsFix = GpsFix.decode(link.request(Msp.RAW_GPS))
}
