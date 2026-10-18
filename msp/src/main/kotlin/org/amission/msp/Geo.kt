package org.amission.msp

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Web-Mercator (XYZ) tile arithmetic, used to size an offline map download before starting it. */
object TileMath {
    private const val MAX_LAT = 85.0511287798

    fun tileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val rad = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        val y = (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / Math.PI) / 2.0 * n
        return floor(y).toInt().coerceIn(0, n - 1)
    }

    /** Number of tiles covering the box at one zoom level (west must be <= east). */
    fun tilesAtZoom(south: Double, west: Double, north: Double, east: Double, zoom: Int): Long {
        val x0 = tileX(west, zoom); val x1 = tileX(east, zoom)
        val y0 = tileY(north, zoom); val y1 = tileY(south, zoom)
        return (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
    }

    /** Total tiles over an inclusive zoom range. */
    fun tileCount(south: Double, west: Double, north: Double, east: Double, minZoom: Int, maxZoom: Int): Long =
        (minZoom..maxZoom).sumOf { tilesAtZoom(south, west, north, east, it) }
}

object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle (haversine) distance in metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = p2 - p1
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(p1) * cos(p2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }
}
