package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileMathTest {
    @Test
    fun wholeWorldIsOneTileAtZoomZeroAndFourAtZoomOne() {
        assertEquals(1, TileMath.tilesAtZoom(-85.0, -180.0, 85.0, 179.99, 0))
        assertEquals(4, TileMath.tilesAtZoom(-85.0, -180.0, 85.0, 179.99, 1))
    }

    @Test
    fun knownTileIndices() {
        // Hand-computed from the OSM slippy-map formula: x = (24.10+180)/360*1024 = 580.5, y = 314.x
        assertEquals(580, TileMath.tileX(24.10, 10))
        assertEquals(313, TileMath.tileY(56.95, 10))
    }

    @Test
    fun tileCountGrowsRoughlyFourfoldPerZoom() {
        val z15 = TileMath.tilesAtZoom(56.0, 24.0, 57.0, 25.0, 15)
        val z16 = TileMath.tilesAtZoom(56.0, 24.0, 57.0, 25.0, 16)
        assertTrue(z16 in (z15 * 3)..(z15 * 5), "z15=$z15 z16=$z16")
    }

    @Test
    fun rangeIsInclusiveAndSumsEachZoom() {
        val a = TileMath.tilesAtZoom(56.80, 24.62, 56.82, 24.65, 14)
        val b = TileMath.tilesAtZoom(56.80, 24.62, 56.82, 24.65, 15)
        assertEquals(a + b, TileMath.tileCount(56.80, 24.62, 56.82, 24.65, 14, 15))
    }

    @Test
    fun polarLatitudesAreClampedNotCrashing() {
        assertEquals(0, TileMath.tileY(90.0, 5))
        assertEquals(31, TileMath.tileY(-90.0, 5))
    }
}
