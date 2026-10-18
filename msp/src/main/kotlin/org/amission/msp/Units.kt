package org.amission.msp

import java.util.Locale

/** Unit conversions for values INAV stores in odd units, so the screen can show familiar ones next to them. */
object Units {
    /** Waypoint speed is stored in cm/s. 1 cm/s is 0.036 km/h. */
    fun cmPerSecToKmh(cmPerSec: Int): Double = cmPerSec * 0.036

    /** "18 km/h" for a whole number, "5.4 km/h" otherwise. */
    fun formatKmh(cmPerSec: Int): String {
        val kmh = Math.round(cmPerSecToKmh(cmPerSec) * 10.0) / 10.0
        return if (kmh == Math.floor(kmh)) String.format(Locale.US, "%.0f km/h", kmh) else String.format(Locale.US, "%.1f km/h", kmh)
    }

    /**
     * A multicopter ignores a waypoint speed under this (0.5 m/s) and flies at its own default speed instead
     * (nav_auto_speed, 500 cm/s unless changed). Taken from INAV 9.1.0 getActiveSpeed().
     */
    const val MIN_WAYPOINT_SPEED_CM_S = 50
}
