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
        // Hand-computed from the OSM slippy-map formula at zoom 10 for lon 10, lat 50:
        // x = (10+180)/360*1024 = 540.4, y = 347.3 (tan 50 = 1.1918, sec 50 = 1.5557, ln(2.7475)/pi = 0.3217)
        assertEquals(540, TileMath.tileX(10.0, 10))
        assertEquals(347, TileMath.tileY(50.0, 10))
    }

    @Test
    fun tileCountGrowsRoughlyFourfoldPerZoom() {
        val z15 = TileMath.tilesAtZoom(48.0, 11.0, 49.0, 12.0, 15)
        val z16 = TileMath.tilesAtZoom(48.0, 11.0, 49.0, 12.0, 16)
        assertTrue(z16 in (z15 * 3)..(z15 * 5), "z15=$z15 z16=$z16")
    }

    @Test
    fun rangeIsInclusiveAndSumsEachZoom() {
        val a = TileMath.tilesAtZoom(48.00, 11.02, 48.02, 11.05, 14)
        val b = TileMath.tilesAtZoom(48.00, 11.02, 48.02, 11.05, 15)
        assertEquals(a + b, TileMath.tileCount(48.00, 11.02, 48.02, 11.05, 14, 15))
    }

    @Test
    fun polarLatitudesAreClampedNotCrashing() {
        assertEquals(0, TileMath.tileY(90.0, 5))
        assertEquals(31, TileMath.tileY(-90.0, 5))
    }
}
