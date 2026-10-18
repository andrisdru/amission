package org.amission.msp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelemetryTest {
    private fun payload(fix: Int, sats: Int, latE7: Int, lonE7: Int, alt: Int = 50, speed: Int = 0, course: Int = 0, hdop: Int = 120): ByteArray =
        ByteBuffer.allocate(GpsFix.PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(fix.toByte()); put(sats.toByte()); putInt(latE7); putInt(lonE7)
            putShort(alt.toShort()); putShort(speed.toShort()); putShort(course.toShort()); putShort(hdop.toShort())
        }.array()

    @Test
    fun decodesFieldsInTheOrderTheFcWritesThem() {
        val gps = GpsFix.decode(payload(2, 9, 480_064_270, 110_326_254, alt = 61, speed = 350, course = 1800, hdop = 95))
        assertEquals(2, gps.fixType)
        assertEquals(9, gps.satellites)
        assertEquals(48.006427, gps.latitude, 1e-9)
        assertEquals(11.0326254, gps.longitude, 1e-9)
        assertEquals(61, gps.altitudeM)
        assertEquals(350, gps.groundSpeedCms)
        assertEquals(1800, gps.courseDeg10)
        assertEquals(95, gps.hdop)
    }

    @Test
    fun negativeCoordinatesDecode() {
        val gps = GpsFix.decode(payload(2, 8, -337_000_000, -1_510_000_000))
        assertEquals(-33.7, gps.latitude, 1e-9)
        assertEquals(-151.0, gps.longitude, 1e-9)
    }

    @Test
    fun onlyAFull3dFixWithSatellitesAndRealCoordinatesIsUsable() {
        assertTrue(GpsFix.decode(payload(2, 6, 480_000_000, 110_000_000)).isUsable)
        assertFalse(GpsFix.decode(payload(0, 0, 0, 0)).isUsable, "no fix")
        assertFalse(GpsFix.decode(payload(1, 7, 480_000_000, 110_000_000)).isUsable, "2D only")
        assertFalse(GpsFix.decode(payload(2, 3, 480_000_000, 110_000_000)).isUsable, "too few satellites")
        assertFalse(GpsFix.decode(payload(2, 8, 0, 0)).isUsable, "0,0 is not a position")
    }

    @Test
    fun shortPayloadIsRejected() {
        assertFailsWith<MspException> { GpsFix.decode(ByteArray(10)) }
    }

    @Test
    fun clientRequestsRawGps() {
        var asked = -1
        val link = object : MspLink {
            override fun request(command: Int, payload: ByteArray): ByteArray {
                asked = command
                return payload(2, 7, 480_000_000, 110_000_000)
            }
        }
        assertTrue(TelemetryClient(link).gps().isUsable)
        assertEquals(Msp.RAW_GPS, asked)
    }
}
