package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlightProfileTest {
    // Two points about 1.11 km apart (0.01 degree of latitude), 80 m above take-off.
    private fun path(a1: Double = 80.0, a2: Double = 80.0, ref1: Boolean = false, ref2: Boolean = false) = listOf(
        PathVertex(1, 48.00, 11.00, a1, ref1),
        PathVertex(2, 48.01, 11.00, a2, ref2),
    )

    private fun flatTerrain(samples: List<RouteSample>, h: Double) = samples.map { h }

    @Test
    fun routeSamplingIncludesEveryWaypointAndStaysInOrder() {
        val p = listOf(PathVertex(1, 48.00, 11.00, 80.0, false), PathVertex(5, 48.005, 11.00, 80.0, false), PathVertex(9, 48.01, 11.02, 80.0, false))
        val s = FlightProfileBuilder.sampleRoute(p)
        assertEquals(listOf(0, 1, 2), s.mapNotNull { it.vertex })
        assertEquals(s.map { it.distanceM }.sorted(), s.map { it.distanceM })
        assertEquals(FlightProfileBuilder.vertexDistances(p).last(), s.last().distanceM, 1e-6)
    }

    @Test
    fun sampleCountIsCappedForLongRoutes() {
        val long = listOf(PathVertex(1, 50.0, 10.0, 80.0, false), PathVertex(2, 60.0, 10.0, 80.0, false)) // ~1100 km
        assertTrue(FlightProfileBuilder.sampleRoute(long, maxSamples = 300).size <= 305)
    }

    @Test
    fun relativeAltitudeIsCountedFromHomeElevation() {
        val s = FlightProfileBuilder.sampleRoute(path())
        // Flat ground at 60 m, home also at 60 m, plan 80 m above take-off: 140 m above sea, 80 m above ground.
        val prof = FlightProfileBuilder.build(path(), s, flatTerrain(s, 60.0), homeElevationM = 60.0, minAglM = 40.0)
        assertEquals(140.0, prof.points.first().flightM, 1e-9)
        assertEquals(80.0, prof.points.first().aglM, 1e-9)
        assertFalse(prof.hasIssues)
    }

    @Test
    fun seaLevelReferenceIgnoresHomeElevation() {
        val p = path(a1 = 140.0, a2 = 140.0, ref1 = true, ref2 = true)
        val s = FlightProfileBuilder.sampleRoute(p)
        val prof = FlightProfileBuilder.build(p, s, flatTerrain(s, 60.0), homeElevationM = 999.0, minAglM = 40.0)
        assertEquals(140.0, prof.points.first().flightM, 1e-9)
        assertEquals(80.0, prof.points.first().aglM, 1e-9)
    }

    @Test
    fun hillUnderTheRouteBringsClearanceBelowTheLimitAndIsReported() {
        val s = FlightProfileBuilder.sampleRoute(path())
        // Home at 50 m. Ground rises to 110 m in the middle: 130 m flight altitude leaves only 20 m.
        val terrain = s.map { 50.0 + 60.0 * (1.0 - Math.abs(it.t - 0.5) * 2.0) }
        val prof = FlightProfileBuilder.build(path(), s, terrain, homeElevationM = 50.0, minAglM = 40.0)
        assertTrue(prof.hasIssues)
        val issue = prof.issues.single()
        assertEquals(20.0, issue.lowestAglM, 1.0)
        assertEquals(20.0, prof.lowest!!.aglM, 1.0)
        assertTrue(issue.fromDistanceM > 0 && issue.toDistanceM < prof.totalDistanceM, "the ends are still clear")
    }

    @Test
    fun groundAboveThePlanGivesNegativeClearance() {
        val s = FlightProfileBuilder.sampleRoute(path())
        val prof = FlightProfileBuilder.build(path(a1 = 10.0, a2 = 10.0), s, flatTerrain(s, 60.0).mapIndexed { i, v -> if (i == 1) 100.0 else v }, 60.0, 40.0)
        assertTrue(prof.lowest!!.aglM < 0)
        assertTrue(prof.hasIssues)
    }

    @Test
    fun altitudeChangesLinearlyBetweenWaypoints() {
        val p = path(a1 = 40.0, a2 = 120.0)
        val s = FlightProfileBuilder.sampleRoute(p)
        val prof = FlightProfileBuilder.build(p, s, flatTerrain(s, 0.0), 0.0, 40.0)
        val mid = prof.points.minBy { Math.abs(it.distanceM - prof.totalDistanceM / 2) }
        assertEquals(80.0, mid.flightM, 3.0)
    }

    @Test
    fun separateRunsAreSeparateIssues() {
        val s = FlightProfileBuilder.sampleRoute(path())
        val third = s.size / 3
        val terrain = s.mapIndexed { i, _ -> if (i in 2..third || i in (2 * third)..(s.size - 3)) 100.0 else 0.0 }
        val prof = FlightProfileBuilder.build(path(), s, terrain, 0.0, 40.0)
        assertEquals(2, prof.issues.size)
    }

    @Test
    fun singleWaypointAndEmptyRoutesDoNotCrash() {
        assertTrue(FlightProfileBuilder.sampleRoute(emptyList()).isEmpty())
        val one = listOf(PathVertex(1, 48.0, 11.0, 80.0, false))
        val s = FlightProfileBuilder.sampleRoute(one)
        assertEquals(1, s.size)
        assertEquals(80.0, FlightProfileBuilder.build(one, s, listOf(10.0), 10.0, 40.0).points.single().aglM, 1e-9)
    }
}
