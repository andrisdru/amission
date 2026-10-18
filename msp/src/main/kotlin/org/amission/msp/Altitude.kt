package org.amission.msp

/** What a waypoint's altitude is measured from, as the user chose it. The flight controller only knows two of these. */
enum class AltitudeMode {
    /** Metres above the take-off point (INAV's default "relative" altitude). */
    HOME,

    /** Metres above sea level (INAV's "absolute" altitude, p3 bit 0). */
    SEA_LEVEL,

    /**
     * Metres above the ground under the waypoint. INAV has no such mode, so the app converts it to a [HOME] altitude
     * from terrain heights; the uploaded mission contains only the converted number.
     */
    GROUND,
}

/**
 * Conversion between "height above the ground under the waypoint" and the home-relative altitude INAV stores.
 *
 * The converted altitude depends on three things: where the waypoint is, how high above ground it should be, and where
 * the ground height is measured from (home). [basis] records exactly those, so a stored converted altitude can be
 * recognised as stale the moment any of them changes.
 */
object GroundHeight {

    /** Identifies the take-off reference: the planned home if there is one, else the first point with a position. */
    fun homeKey(home: Pair<Double, Double>?, firstPoint: Waypoint?): String = when {
        home != null -> "H${Math.round(home.first * 1e7)},${Math.round(home.second * 1e7)}"
        firstPoint != null -> "P${firstPoint.latE7},${firstPoint.lonE7}"
        else -> "none"
    }

    fun basis(w: Waypoint, homeKey: String): String = "${w.latE7},${w.lonE7},${w.groundHeightM},$homeKey"

    /** True for any point that is not in ground mode, and for ground-mode points whose stored altitude is current. */
    fun isResolved(w: Waypoint, homeKey: String): Boolean =
        w.groundHeightM == null || w.groundBasis == basis(w, homeKey)

    /** Home-relative altitude in cm for [groundHeightM] above terrain at the point, when home's ground is [homeElevationM]. */
    fun toHomeRelativeCm(groundHeightM: Int, terrainAtPointM: Double, homeElevationM: Double): Int =
        Math.round((terrainAtPointM - homeElevationM + groundHeightM) * 100.0).toInt()

    /** Stores the converted altitude on [w] and records what it was computed from. */
    fun resolve(w: Waypoint, terrainAtPointM: Double, homeElevationM: Double, homeKey: String): Waypoint {
        val g = w.groundHeightM ?: return w
        return w.copy(
            altitudeCm = toHomeRelativeCm(g, terrainAtPointM, homeElevationM),
            p3 = w.p3 and 1.inv(), // ground mode always converts to home-relative, never sea level
            groundBasis = basis(w, homeKey),
        )
    }

    /**
     * Changes how [w]'s altitude is measured while keeping the real height of the point where that can be known, so
     * switching the mode does not quietly move the waypoint. [homeElevationM] and [terrainAtPointM] are ground heights
     * above sea level at home and under the point; either may be unknown (null), for instance offline.
     * When the real height cannot be worked out the number is kept as typed, which is what the old switch did.
     */
    fun switchMode(
        w: Waypoint,
        to: AltitudeMode,
        homeElevationM: Double?,
        terrainAtPointM: Double?,
        homeKey: String,
    ): Waypoint {
        val from = w.altitudeMode
        if (from == to) return w

        // Real height above the take-off point, in metres, if it is known.
        val aboveHomeM: Double? = when (from) {
            AltitudeMode.HOME -> w.altitudeCm / 100.0
            AltitudeMode.GROUND -> if (isResolved(w, homeKey)) w.altitudeCm / 100.0 else null
            AltitudeMode.SEA_LEVEL -> homeElevationM?.let { w.altitudeCm / 100.0 - it }
        }
        val cleared = w.copy(groundHeightM = null, groundBasis = null)

        return when (to) {
            AltitudeMode.HOME -> {
                val base = cleared.withSeaLevelRef(false)
                if (aboveHomeM != null) base.copy(altitudeCm = Math.round(aboveHomeM * 100.0).toInt()) else base
            }
            AltitudeMode.SEA_LEVEL -> {
                val base = cleared.withSeaLevelRef(true)
                if (aboveHomeM != null && homeElevationM != null) {
                    base.copy(altitudeCm = Math.round((aboveHomeM + homeElevationM) * 100.0).toInt())
                } else {
                    base
                }
            }
            AltitudeMode.GROUND -> {
                val aboveGround = when {
                    aboveHomeM != null && terrainAtPointM != null && homeElevationM != null ->
                        aboveHomeM - (terrainAtPointM - homeElevationM)
                    else -> aboveHomeM ?: (w.altitudeCm / 100.0)
                }
                w.withSeaLevelRef(false).copy(
                    groundHeightM = Math.round(aboveGround).toInt().coerceIn(MIN_GROUND_M, MAX_GROUND_M),
                    groundBasis = null, // converted again, from current terrain data, before it is used
                )
            }
        }
    }

    const val MIN_GROUND_M = -500
    const val MAX_GROUND_M = 500
}
