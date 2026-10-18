package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    @Test
    fun wholeKilometresPerHourHaveNoDecimal() {
        assertEquals("18 km/h", Units.formatKmh(500))
        assertEquals("36 km/h", Units.formatKmh(1000))
        assertEquals("0 km/h", Units.formatKmh(0))
    }

    @Test
    fun otherValuesShowOneDecimal() {
        assertEquals("5.4 km/h", Units.formatKmh(150))
        assertEquals("1.8 km/h", Units.formatKmh(50))
        assertEquals("10.8 km/h", Units.formatKmh(300))
    }

    @Test
    fun conversionIsExact() {
        assertEquals(18.0, Units.cmPerSecToKmh(500), 1e-9)
        assertEquals(0.036, Units.cmPerSecToKmh(1), 1e-9)
    }
}
