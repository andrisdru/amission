package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WaypointFlagsTest {
    private val base = Waypoint(1, WaypointAction.WAYPOINT, 0, 0, 0)

    @Test
    fun seaLevelRefUsesBit0() {
        val w = base.withSeaLevelRef(true)
        assertEquals(1, w.p3)
        assertTrue(w.isSeaLevelRef)
        assertFalse(w.withSeaLevelRef(false).isSeaLevelRef)
    }

    @Test
    fun userActionsUseBits1To4AndAreIndependent() {
        val w = base.withUserAction(1, true).withUserAction(3, true).withSeaLevelRef(true)
        assertEquals(0b0_1011, w.p3)
        assertTrue(w.userAction(1)); assertFalse(w.userAction(2)); assertTrue(w.userAction(3)); assertFalse(w.userAction(4))
        assertEquals(0b0_1001, w.withUserAction(1, false).p3.let { it or 0b1000 })
        assertEquals(0b0_0011, w.withUserAction(3, false).p3)
    }

    @Test
    fun positionalActions() {
        assertTrue(WaypointAction.WAYPOINT.hasPosition)
        assertTrue(WaypointAction.LAND.hasPosition)
        assertFalse(WaypointAction.JUMP.hasPosition)
        assertFalse(WaypointAction.SET_HEAD.hasPosition)
        assertFalse(WaypointAction.RTH.hasPosition)
    }

    @Test
    fun haversineMatchesKnownDistance() {
        // 0.001 degree of latitude is ~111.2 m
        val d = Geo.distanceMeters(56.0, 24.0, 56.001, 24.0)
        assertEquals(111.2, d, 0.5)
    }
}

class FlownToTest {
    @Test
    fun onlyPointsTheQuadReachesAreFlownTo() {
        assertTrue(WaypointAction.WAYPOINT.isFlownTo)
        assertTrue(WaypointAction.POSHOLD_TIME.isFlownTo)
        assertTrue(WaypointAction.LAND.isFlownTo)
        assertFalse(WaypointAction.SET_POI.isFlownTo, "a POI is looked at, not flown to")
        assertFalse(WaypointAction.RTH.isFlownTo)
        assertFalse(WaypointAction.JUMP.isFlownTo)
        assertFalse(WaypointAction.SET_HEAD.isFlownTo)
    }
}
