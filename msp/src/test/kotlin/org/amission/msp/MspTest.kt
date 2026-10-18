package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory stand-in for an FC's mission storage. */
private class FakeFc(var max: Int = 120, val corruptReadBack: Boolean = false) : MspLink {
    val stored = sortedMapOf<Int, ByteArray>()
    var saved = false

    override fun request(command: Int, payload: ByteArray): ByteArray = when (command) {
        Msp.WP_GETINFO -> byteArrayOf(0, max.toByte(), if (stored.isEmpty()) 0 else 1, stored.size.toByte())
        Msp.SET_WP -> { stored[payload[0].toInt() and 0xFF] = payload; ByteArray(0) }
        Msp.WP -> {
            val data = stored[payload[0].toInt() and 0xFF] ?: throw MspException("no such waypoint")
            if (corruptReadBack) data.copyOf().also { it[2] = (it[2] + 1).toByte() } else data
        }
        Msp.WP_MISSION_SAVE -> { saved = true; ByteArray(0) }
        else -> throw MspException("unexpected command $command")
    }
}

class MspFrameTest {
    @Test
    fun encodesRequestWithXorChecksum() {
        // len=1 cmd=118 payload=[3]  checksum = 1 xor 118 xor 3 = 116
        val frame = Msp.encodeRequest(Msp.WP, byteArrayOf(3))
        assertContentEquals(
            byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), '<'.code.toByte(), 1, 118, 3, 116),
            frame,
        )
    }

    @Test
    fun parsesResponseSplitAcrossReadsAndSkipsGarbage() {
        val parser = MspParser()
        val reply = byteArrayOf(
            0x55, 0x00, // garbage
            '$'.code.toByte(), 'M'.code.toByte(), '>'.code.toByte(), 2, 20, 7, 9,
            (2 xor 20 xor 7 xor 9).toByte(),
        )
        var result: MspMessage? = null
        for (b in reply) parser.feed(b)?.let { result = it }
        val msg = assertNotNull(result)
        assertEquals(20, msg.command)
        assertContentEquals(byteArrayOf(7, 9), msg.payload)
        assertTrue(!msg.isError)
    }

    @Test
    fun rejectsBadChecksum() {
        val parser = MspParser()
        val bad = byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), '>'.code.toByte(), 0, 20, 0x7F)
        assertNull(bad.map { parser.feed(it) }.lastOrNull())
    }

    @Test
    fun flagsErrorFrames() {
        val parser = MspParser()
        val frame = byteArrayOf('$'.code.toByte(), 'M'.code.toByte(), '!'.code.toByte(), 0, 209.toByte(), 209.toByte())
        val msg = frame.toList().mapNotNull { parser.feed(it) }.single()
        assertTrue(msg.isError)
    }
}

class WaypointTest {
    @Test
    fun roundTripsAndUsesLittleEndianLayout() {
        val wp = Waypoint(2, WaypointAction.WAYPOINT, 593_000_000, -1_234_567, 5000, p1 = 500, p3 = 1, isLast = true)
        val bytes = wp.encode()
        assertEquals(Waypoint.PAYLOAD_SIZE, bytes.size)
        assertEquals(2, bytes[0].toInt())
        assertEquals(1, bytes[1].toInt())
        assertEquals(0xA5, bytes[20].toInt() and 0xFF)
        assertEquals(wp, Waypoint.decode(bytes))
    }

    @Test
    fun negativeCoordinatesSurviveRoundTrip() {
        val wp = Waypoint(1, WaypointAction.RTH, -337_000_000, -1_510_000_000, 0)
        assertEquals(wp, Waypoint.decode(wp.encode()))
    }

    @Test
    fun rejectsShortPayload() {
        assertFailsWith<MspException> { Waypoint.decode(ByteArray(5)) }
    }
}

class MissionClientTest {
    private fun mission() = listOf(
        Waypoint(0, WaypointAction.WAYPOINT, 590_000_000, 180_000_000, 3000, p1 = 500),
        Waypoint(0, WaypointAction.POSHOLD_TIME, 590_001_000, 180_001_000, 3000, p1 = 10),
        Waypoint(0, WaypointAction.RTH, 0, 0, 0),
    )

    @Test
    fun uploadRenumbersMarksLastAndVerifies() {
        val fc = FakeFc()
        val client = MissionClient(fc)
        client.uploadAndVerify(mission())
        val back = client.download()
        assertEquals(listOf(1, 2, 3), back.map { it.number })
        assertEquals(listOf(false, false, true), back.map { it.isLast })
    }

    @Test
    fun jumpIsSentWithZeroedRuntimeCounter() {
        val wps = mission().dropLast(1) + Waypoint(0, WaypointAction.JUMP, 0, 0, 0, p1 = 1, p2 = 2, p3 = 7)
        val normalized = MissionClient.normalize(wps)
        assertEquals(0, normalized.last().p3)
        assertEquals(2, normalized.last().p2)
    }

    @Test
    fun failsWhenReadBackDiffers() {
        val client = MissionClient(FakeFc(corruptReadBack = true))
        assertFailsWith<MspException> { client.uploadAndVerify(mission()) }
    }

    @Test
    fun refusesMissionLargerThanFcLimit() {
        val client = MissionClient(FakeFc(max = 2))
        assertFailsWith<MspException> { client.uploadAndVerify(mission()) }
    }

    @Test
    fun refusesEmptyMission() {
        assertFailsWith<MspException> { MissionClient(FakeFc()).uploadAndVerify(emptyList()) }
    }

    @Test
    fun saveSendsEepromCommand() {
        val fc = FakeFc()
        MissionClient(fc).saveToEeprom()
        assertTrue(fc.saved)
    }
}
