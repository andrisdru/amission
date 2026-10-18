package org.amission.msp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** INAV waypoint actions (navigation.h, navWaypointActions_e). */
enum class WaypointAction(val code: Int, val label: String, val hasPosition: Boolean) {
    WAYPOINT(1, "Waypoint", true),
    POSHOLD_TIME(3, "PosHold Time", true),
    RTH(4, "RTH", false),
    SET_POI(5, "Set POI", true),
    JUMP(6, "Jump", false),
    SET_HEAD(7, "Set Head", false),
    LAND(8, "Land", true);

    /** True when the quad actually flies to this point. A Set POI has a map position but is only looked at. */
    val isFlownTo: Boolean get() = this == WAYPOINT || this == POSHOLD_TIME || this == LAND

    companion object {
        fun fromCode(code: Int): WaypointAction =
            entries.firstOrNull { it.code == code } ?: throw MspException("Unknown waypoint action $code")
    }
}

/**
 * One mission waypoint as stored on the FC. Coordinates are degrees * 1e7, altitude is cm.
 * [number] is 1-based. Meaning of p1..p3 depends on [action] (e.g. WAYPOINT: p1 = speed cm/s,
 * POSHOLD_TIME: p1 = seconds; p3 bit 0 selects absolute altitude in recent INAV versions).
 */
data class Waypoint(
    val number: Int,
    val action: WaypointAction,
    val latE7: Int,
    val lonE7: Int,
    val altitudeCm: Int,
    val p1: Int = 0,
    val p2: Int = 0,
    val p3: Int = 0,
    val isLast: Boolean = false,
    /**
     * App-only, never sent to the flight controller or written to a mission file: the user asked for this many metres above
     * the ground under the point. [altitudeCm] then holds the home-relative number converted from it, and [groundBasis]
     * records what that conversion was computed from (see [GroundHeight]).
     */
    val groundHeightM: Int? = null,
    val groundBasis: String? = null,
) {
    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7

    val altitudeMode: AltitudeMode
        get() = when {
            groundHeightM != null -> AltitudeMode.GROUND
            isSeaLevelRef -> AltitudeMode.SEA_LEVEL
            else -> AltitudeMode.HOME
        }

    /** The waypoint as the flight controller stores it, without the app-only ground-height intent. */
    fun wireForm(): Waypoint = copy(groundHeightM = null, groundBasis = null)

    /** p3 bit 0: altitude is relative to sea level instead of the home point. */
    val isSeaLevelRef: Boolean get() = (p3 and 1) != 0

    fun withSeaLevelRef(on: Boolean): Waypoint = copy(p3 = setBit(p3, 0, on))

    /** p3 bits 1..4: user actions 1..4 that INAV exposes to logic conditions. */
    fun userAction(n: Int): Boolean {
        require(n in 1..4) { "user action must be 1..4" }
        return (p3 shr n and 1) != 0
    }

    fun withUserAction(n: Int, on: Boolean): Waypoint {
        require(n in 1..4) { "user action must be 1..4" }
        return copy(p3 = setBit(p3, n, on))
    }

    private fun setBit(value: Int, bit: Int, on: Boolean) =
        if (on) value or (1 shl bit) else value and (1 shl bit).inv()

    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(number.toByte())
        buf.put(action.code.toByte())
        buf.putInt(latE7)
        buf.putInt(lonE7)
        buf.putInt(altitudeCm)
        buf.putShort(p1.toShort())
        buf.putShort(p2.toShort())
        buf.putShort(p3.toShort())
        buf.put((if (isLast) FLAG_LAST else 0).toByte())
        return buf.array()
    }

    companion object {
        const val PAYLOAD_SIZE = 21
        const val FLAG_LAST = 0xA5

        fun decode(payload: ByteArray): Waypoint {
            if (payload.size < PAYLOAD_SIZE) throw MspException("Waypoint payload too short: ${payload.size}")
            val buf = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
            val number = buf.get().toInt() and 0xFF
            val action = WaypointAction.fromCode(buf.get().toInt() and 0xFF)
            val lat = buf.getInt()
            val lon = buf.getInt()
            val alt = buf.getInt()
            val p1 = buf.getShort().toInt()
            val p2 = buf.getShort().toInt()
            val p3 = buf.getShort().toInt()
            val flag = buf.get().toInt() and 0xFF
            return Waypoint(number, action, lat, lon, alt, p1, p2, p3, flag == FLAG_LAST)
        }
    }
}

/** Reply to MSP_WP_GETINFO. */
data class MissionInfo(val maxWaypoints: Int, val isValid: Boolean, val waypointCount: Int) {
    companion object {
        fun decode(payload: ByteArray): MissionInfo {
            if (payload.size < 4) throw MspException("WP_GETINFO payload too short: ${payload.size}")
            // payload[0] is reserved
            return MissionInfo(
                maxWaypoints = payload[1].toInt() and 0xFF,
                isValid = payload[2].toInt() != 0,
                waypointCount = payload[3].toInt() and 0xFF,
            )
        }
    }
}
