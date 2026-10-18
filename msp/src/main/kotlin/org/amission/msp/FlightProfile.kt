package org.amission.msp

import kotlin.math.max
import kotlin.math.min

/**
 * A waypoint that has a map position. [altitudeM] is relative to the take-off point unless
 * [seaLevelRef] is set (INAV p3 bit 0), in which case it is a height above sea level.
 */
data class PathVertex(
    val waypointNumber: Int,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double,
    val seaLevelRef: Boolean,
)

/** A place along the route at which terrain height is needed. [vertex] is set when it is exactly a waypoint. */
data class RouteSample(
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    /** Index of the leg's first vertex, and how far along that leg (0..1) this sample is. */
    val leg: Int,
    val t: Double,
    val vertex: Int?,
)

data class ProfilePoint(
    val distanceM: Double,
    val terrainM: Double,
    /** Planned altitude above sea level at this point. */
    val flightM: Double,
    /** Height above the ground at this point (negative means the plan is below the terrain). */
    val aglM: Double,
    val vertex: Int?,
)

/** A stretch of the route lower than the minimum height above ground. */
data class ClearanceIssue(
    val fromDistanceM: Double,
    val toDistanceM: Double,
    val lowestAglM: Double,
    val lowestAtM: Double,
    val nearWaypoint: Int,
)

class FlightProfile(
    val points: List<ProfilePoint>,
    val vertexDistancesM: List<Double>,
    /** Waypoint number of each path vertex, in order. */
    val vertexNumbers: List<Int>,
    val homeElevationM: Double,
    val minAglM: Double,
    val issues: List<ClearanceIssue>,
) {
    val totalDistanceM: Double get() = points.lastOrNull()?.distanceM ?: 0.0
    val lowest: ProfilePoint? get() = points.minByOrNull { it.aglM }
    val hasIssues: Boolean get() = issues.isNotEmpty()

    /** Ground height at [p] measured from the take-off ground (negative = below take-off). */
    fun terrainAboveHomeM(p: ProfilePoint): Double = p.terrainM - homeElevationM

    /** Planned altitude at [p] measured from the take-off ground, which is how altitudes are entered. */
    fun flightAboveHomeM(p: ProfilePoint): Double = p.flightM - homeElevationM
}

/**
 * Altitude-against-distance view of a mission over the terrain. Terrain heights come from outside
 * (they need a network lookup), so this is split in two: [sampleRoute] says where heights are
 * needed, [build] turns the returned heights into the profile.
 */
object FlightProfileBuilder {

    fun vertexDistances(path: List<PathVertex>): List<Double> {
        val out = ArrayList<Double>(path.size)
        var d = 0.0
        path.forEachIndexed { i, v ->
            if (i > 0) d += Geo.distanceMeters(path[i - 1].lat, path[i - 1].lon, v.lat, v.lon)
            out += d
        }
        return out
    }

    /** Points along the route, every waypoint included, roughly [minStepM] apart but never more than [maxSamples]. */
    fun sampleRoute(path: List<PathVertex>, maxSamples: Int = 300, minStepM: Double = 25.0): List<RouteSample> {
        if (path.isEmpty()) return emptyList()
        if (path.size == 1) return listOf(RouteSample(path[0].lat, path[0].lon, 0.0, 0, 0.0, 0))
        val dist = vertexDistances(path)
        val step = max(minStepM, dist.last() / maxSamples)
        val out = ArrayList<RouteSample>()
        for (i in 0 until path.size - 1) {
            val a = path[i]; val b = path[i + 1]
            val len = dist[i + 1] - dist[i]
            out += RouteSample(a.lat, a.lon, dist[i], i, 0.0, i)
            if (len > 0) {
                var s = step
                while (s < len - step / 2) { // skip an interior sample that would almost coincide with the next vertex
                    val t = s / len
                    out += RouteSample(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, dist[i] + s, i, t, null)
                    s += step
                }
            }
        }
        val last = path.last()
        out += RouteSample(last.lat, last.lon, dist.last(), path.size - 2, 1.0, path.size - 1)
        return out
    }

    /**
     * [terrainM] holds the ground height above sea level for each of [samples], in order.
     * [homeElevationM] is the ground height at the take-off point, which relative altitudes count from.
     */
    fun build(
        path: List<PathVertex>,
        samples: List<RouteSample>,
        terrainM: List<Double>,
        homeElevationM: Double,
        minAglM: Double,
    ): FlightProfile {
        require(samples.size == terrainM.size) { "need one terrain height per sample" }
        val dist = vertexDistances(path)
        fun amsl(v: PathVertex) = if (v.seaLevelRef) v.altitudeM else homeElevationM + v.altitudeM

        val points = samples.mapIndexed { i, s ->
            val flight = if (path.size == 1) amsl(path[0]) else {
                val a = amsl(path[s.leg]); val b = amsl(path[s.leg + 1])
                a + (b - a) * s.t
            }
            ProfilePoint(s.distanceM, terrainM[i], flight, flight - terrainM[i], s.vertex)
        }

        val issues = ArrayList<ClearanceIssue>()
        var runStart = -1
        fun closeRun(endExclusive: Int) {
            if (runStart < 0) return
            val run = points.subList(runStart, endExclusive)
            val low = run.minBy { it.aglM }
            val near = path.indices.minBy { Math.abs(dist[it] - low.distanceM) }
            issues += ClearanceIssue(run.first().distanceM, run.last().distanceM, low.aglM, low.distanceM, path[near].waypointNumber)
            runStart = -1
        }
        points.forEachIndexed { i, p ->
            if (p.aglM < minAglM) { if (runStart < 0) runStart = i } else closeRun(i)
        }
        closeRun(points.size)
        return FlightProfile(points, dist, path.map { it.waypointNumber }, homeElevationM, minAglM, issues)
    }

    /** Smallest and largest height (terrain or flight) above the take-off ground, for scaling a chart. */
    fun heightRange(profile: FlightProfile): Pair<Double, Double> {
        if (profile.points.isEmpty()) return 0.0 to 1.0
        val lo = profile.points.minOf { min(profile.terrainAboveHomeM(it), profile.flightAboveHomeM(it)) }
        val hi = profile.points.maxOf { max(profile.terrainAboveHomeM(it), profile.flightAboveHomeM(it)) }
        return lo to hi
    }
}
