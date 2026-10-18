package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MissionCheckTest {
    private fun wp(a: WaypointAction = WaypointAction.WAYPOINT, p1: Int = 0, p2: Int = 0) =
        Waypoint(0, a, 480_000_000, 110_000_000, 8000, p1 = p1, p2 = p2)

    private fun check(vararg w: Waypoint) = MissionCheck.check(w.toList())

    @Test
    fun aPlainMissionEndingInRthIsClean() {
        assertTrue(check(wp(), wp(), wp(WaypointAction.RTH)).isEmpty())
    }

    @Test
    fun emptyMissionHasNothingToCheck() {
        assertTrue(MissionCheck.check(emptyList()).isEmpty())
    }

    @Test
    fun pointsAfterAnRthAreReportedAsNeverFlown() {
        // The exact mistake seen on a real phone: RTH, then another waypoint, then a second RTH.
        val issues = check(wp(), wp(WaypointAction.RTH), wp(), wp(WaypointAction.RTH))
        val i = issues.single()
        assertEquals(3, i.pointNumber)
        assertTrue("Points 3, 4 are never flown" in i.message, i.message)
        assertTrue("Point 2 (RTH) ends the mission" in i.message, i.message)
    }

    @Test
    fun pointsAfterALandAreReported() {
        val issues = check(wp(), wp(WaypointAction.LAND), wp())
        assertTrue("Point 3 is never flown" in issues.single().message)
        assertTrue("Land" in issues.single().message)
    }

    @Test
    fun aJumpMakesLaterPointsReachableAgain() {
        // 1 -> 2 -> 3(jump to 2, repeats) -> falls through to 4 -> RTH
        assertTrue(check(wp(), wp(), wp(WaypointAction.JUMP, p1 = 2, p2 = 3), wp(), wp(WaypointAction.RTH)).isEmpty())
    }

    @Test
    fun aPointOnlyReachableByJumpingBackwardIsStillReachable() {
        // 1 -> RTH(2) ... 3 can only be reached if something jumps there; nothing does
        val issues = check(wp(), wp(WaypointAction.RTH), wp())
        assertEquals(3, issues.single().pointNumber)
        // with a jump to it from before the RTH it becomes reachable
        val ok = check(wp(), wp(WaypointAction.JUMP, p1 = 4, p2 = 1), wp(WaypointAction.RTH), wp(), wp(WaypointAction.RTH))
        assertTrue(ok.isEmpty(), ok.toString())
    }

    @Test
    fun jumpToAMissingPointIsAnError() {
        val issues = check(wp(), wp(WaypointAction.JUMP, p1 = 9, p2 = 1), wp(WaypointAction.RTH))
        assertTrue(issues.any { it.pointNumber == 2 && "does not exist" in it.message })
    }

    @Test
    fun jumpToPointZeroIsAnError() {
        assertTrue(check(wp(), wp(WaypointAction.JUMP, p1 = 0, p2 = 1)).any { "does not exist" in it.message })
    }

    @Test
    fun endingWithSetHeadIsFlagged() {
        val issues = check(wp(), wp(WaypointAction.SET_HEAD, p1 = 90))
        assertTrue(issues.single().message.contains("Set Head"))
    }

    @Test
    fun aMissionWithoutAnRthAtTheEndIsNotAnErrorByItself() {
        assertTrue(check(wp(), wp()).isEmpty())
    }
}
