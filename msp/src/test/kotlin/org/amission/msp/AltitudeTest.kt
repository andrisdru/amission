package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AltitudeTest {
    private val key = GroundHeight.homeKey(48.0 to 11.0, null)

    private fun wp(alt: Int = 8000, ground: Int? = null, basis: String? = null, sea: Boolean = false) =
        Waypoint(1, WaypointAction.WAYPOINT, 480_100_000, 110_200_000, alt, p3 = if (sea) 1 else 0, groundHeightM = ground, groundBasis = basis)

    @Test
    fun modeIsDerivedFromTheFieldsThatHoldIt() {
        assertEquals(AltitudeMode.HOME, wp().altitudeMode)
        assertEquals(AltitudeMode.SEA_LEVEL, wp(sea = true).altitudeMode)
        assertEquals(AltitudeMode.GROUND, wp(ground = 60).altitudeMode)
    }

    @Test
    fun groundHeightBecomesAHomeRelativeAltitude() {
        // Home ground 23 m, ground under the point 30 m, wanted 60 m above that ground: 60 + 7 = 67 m above take-off.
        assertEquals(6700, GroundHeight.toHomeRelativeCm(60, terrainAtPointM = 30.0, homeElevationM = 23.0))
        // Ground below take-off height lowers the number: 60 m above ground that is 10 m lower than home is 50 m above home.
        assertEquals(5000, GroundHeight.toHomeRelativeCm(60, terrainAtPointM = 13.0, homeElevationM = 23.0))
        assertEquals(6050, GroundHeight.toHomeRelativeCm(60, terrainAtPointM = 23.5, homeElevationM = 23.0))
    }

    @Test
    fun resolvingStoresTheNumberAndWhatItWasComputedFrom() {
        val r = GroundHeight.resolve(wp(ground = 60), terrainAtPointM = 30.0, homeElevationM = 23.0, homeKey = key)
        assertEquals(6700, r.altitudeCm)
        assertTrue(GroundHeight.isResolved(r, key))
        assertEquals(AltitudeMode.GROUND, r.altitudeMode, "it is still a ground-mode point")
    }

    @Test
    fun resolvedGroundPointsNeverUseTheSeaLevelFlag() {
        val r = GroundHeight.resolve(wp(ground = 60, sea = true), 30.0, 23.0, key)
        assertFalse(r.isSeaLevelRef)
    }

    @Test
    fun aStoredAltitudeGoesStaleWhenAnyInputChanges() {
        val r = GroundHeight.resolve(wp(ground = 60), 30.0, 23.0, key)
        assertTrue(GroundHeight.isResolved(r, key))
        assertFalse(GroundHeight.isResolved(r.copy(latE7 = r.latE7 + 1), key), "moved")
        assertFalse(GroundHeight.isResolved(r.copy(lonE7 = r.lonE7 - 1), key), "moved")
        assertFalse(GroundHeight.isResolved(r.copy(groundHeightM = 70), key), "different height wanted")
        assertFalse(GroundHeight.isResolved(r, GroundHeight.homeKey(48.1 to 11.0, null)), "home moved")
        assertFalse(GroundHeight.isResolved(r, GroundHeight.homeKey(null, wp())), "home removed")
    }

    @Test
    fun pointsNotInGroundModeAreAlwaysResolved() {
        assertTrue(GroundHeight.isResolved(wp(), key))
        assertTrue(GroundHeight.isResolved(wp(sea = true), key))
    }

    @Test
    fun unresolvedGroundPointIsNotResolved() {
        assertFalse(GroundHeight.isResolved(wp(ground = 60), key))
    }

    @Test
    fun withoutAHomeTheFirstPointStandsInForIt() {
        val first = wp()
        assertEquals("P480100000,110200000", GroundHeight.homeKey(null, first))
        assertEquals("none", GroundHeight.homeKey(null, null))
    }

    @Test
    fun switchingHomeToGroundKeepsTheRealHeightWhenTheGroundIsKnown() {
        // 80 m above take-off, ground at the point is 10 m higher than at home: that is 70 m above the ground there.
        val g = GroundHeight.switchMode(wp(alt = 8000), AltitudeMode.GROUND, homeElevationM = 23.0, terrainAtPointM = 33.0, homeKey = key)
        assertEquals(70, g.groundHeightM)
        assertNull(g.groundBasis, "converted again from current data before use")
    }

    @Test
    fun switchingToGroundWithUnknownTerrainKeepsTheNumber() {
        val g = GroundHeight.switchMode(wp(alt = 8000), AltitudeMode.GROUND, null, null, key)
        assertEquals(80, g.groundHeightM)
    }

    @Test
    fun switchingGroundToHomeKeepsTheConvertedHeightAndDropsTheIntent() {
        val r = GroundHeight.resolve(wp(ground = 60), 30.0, 23.0, key)
        val h = GroundHeight.switchMode(r, AltitudeMode.HOME, 23.0, 30.0, key)
        assertEquals(6700, h.altitudeCm)
        assertNull(h.groundHeightM)
        assertNull(h.groundBasis)
        assertEquals(AltitudeMode.HOME, h.altitudeMode)
    }

    @Test
    fun switchingGroundToHomeWithAStaleNumberDoesNotInventAHeight() {
        val stale = wp(alt = 1234, ground = 60) // never resolved
        val h = GroundHeight.switchMode(stale, AltitudeMode.HOME, 23.0, 30.0, key)
        assertEquals(1234, h.altitudeCm, "the number is kept as typed rather than trusted")
    }

    @Test
    fun switchingBetweenHomeAndSeaLevelKeepsTheRealHeightWhenHomeGroundIsKnown() {
        val toSea = GroundHeight.switchMode(wp(alt = 8000), AltitudeMode.SEA_LEVEL, 23.0, null, key)
        assertEquals(10_300, toSea.altitudeCm, "80 m above a 23 m take-off is 103 m above sea level")
        assertTrue(toSea.isSeaLevelRef)
        val back = GroundHeight.switchMode(toSea, AltitudeMode.HOME, 23.0, null, key)
        assertEquals(8000, back.altitudeCm)
        assertFalse(back.isSeaLevelRef)
    }

    @Test
    fun switchingToSeaLevelWithoutKnownHomeGroundJustFlipsTheFlag() {
        val toSea = GroundHeight.switchMode(wp(alt = 8000), AltitudeMode.SEA_LEVEL, null, null, key)
        assertEquals(8000, toSea.altitudeCm)
        assertTrue(toSea.isSeaLevelRef)
    }

    @Test
    fun groundHeightIsKeptInARealisticRange() {
        val g = GroundHeight.switchMode(wp(alt = 900_000), AltitudeMode.GROUND, null, null, key)
        assertEquals(GroundHeight.MAX_GROUND_M, g.groundHeightM)
    }

    @Test
    fun switchingToTheSameModeChangesNothing() {
        val w = wp(ground = 60)
        assertEquals(w, GroundHeight.switchMode(w, AltitudeMode.GROUND, 23.0, 30.0, key))
    }
}

class GroundIntentStaysOffTheWireTest {
    private class FakeFc : MspLink {
        val stored = sortedMapOf<Int, ByteArray>()
        override fun request(command: Int, payload: ByteArray): ByteArray = when (command) {
            Msp.WP_GETINFO -> byteArrayOf(0, 120, if (stored.isEmpty()) 0 else 1, stored.size.toByte())
            Msp.SET_WP -> { stored[payload[0].toInt() and 0xFF] = payload; ByteArray(0) }
            Msp.WP -> stored[payload[0].toInt() and 0xFF] ?: throw MspException("none")
            else -> throw MspException("unexpected $command")
        }
    }

    @Test
    fun groundModePointsEncodeLikeAnyOtherAndVerifyAfterUpload() {
        val key = GroundHeight.homeKey(48.0 to 11.0, null)
        val ground = GroundHeight.resolve(
            Waypoint(0, WaypointAction.WAYPOINT, 480_100_000, 110_200_000, 0, groundHeightM = 60),
            terrainAtPointM = 30.0, homeElevationM = 23.0, homeKey = key,
        )
        val plain = ground.wireForm()
        assertTrue(ground.encode().contentEquals(plain.encode()), "the intent is not part of the wire format")

        val fc = FakeFc()
        MissionClient(fc).uploadAndVerify(listOf(ground, Waypoint(0, WaypointAction.RTH, 0, 0, 0)))
        val back = MissionClient(fc).download()
        assertEquals(6700, back.first().altitudeCm)
        assertNull(back.first().groundHeightM)
    }
}
