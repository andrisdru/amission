package org.amission.msp

/** A byte link to the FC (USB serial, Bluetooth, TCP). Implementations are platform specific. */
interface MspLink {
    /** Sends one MSP request and blocks until the matching reply arrives. Must throw [MspException] on error frames or timeout. */
    fun request(command: Int, payload: ByteArray = ByteArray(0)): ByteArray
}

/** Mission operations on top of an [MspLink]. Call from a background thread. */
class MissionClient(private val link: MspLink) {

    fun info(): MissionInfo = MissionInfo.decode(link.request(Msp.WP_GETINFO))

    fun download(): List<Waypoint> {
        val info = info()
        return (1..info.waypointCount).map { downloadOne(it) }
    }

    private fun downloadOne(number: Int): Waypoint =
        Waypoint.decode(link.request(Msp.WP, byteArrayOf(number.toByte())))

    /**
     * Uploads [waypoints], then reads the mission back and compares it to what was sent.
     * Throws [MspException] if validation fails, an upload is rejected, or read-back differs.
     * Does not write to EEPROM; call [saveToEeprom] after a successful upload.
     */
    fun uploadAndVerify(waypoints: List<Waypoint>) {
        val normalized = normalize(waypoints)
        val max = info().maxWaypoints
        if (normalized.size > max) throw MspException("Mission has ${normalized.size} waypoints, FC allows $max")
        normalized.forEach { link.request(Msp.SET_WP, it.encode()) }

        val after = info()
        if (after.waypointCount != normalized.size) {
            throw MspException("FC reports ${after.waypointCount} waypoints after upload, expected ${normalized.size}")
        }
        val readBack = download()
        if (readBack != normalized) throw MspException("Read-back does not match the uploaded mission")
    }

    fun saveToEeprom() {
        link.request(Msp.WP_MISSION_SAVE, byteArrayOf(0))
    }

    fun loadFromEeprom() {
        link.request(Msp.WP_MISSION_LOAD, byteArrayOf(0))
    }

    companion object {
        /** Renumbers 1..n and sets the last-waypoint flag on the final entry only. */
        fun normalize(waypoints: List<Waypoint>): List<Waypoint> {
            if (waypoints.isEmpty()) throw MspException("Mission is empty")
            return waypoints.mapIndexed { i, w ->
                w.copy(number = i + 1, isLast = i == waypoints.lastIndex)
            }
        }
    }
}
