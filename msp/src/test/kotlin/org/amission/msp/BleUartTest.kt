package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BleUartTest {
    private fun c(id: String, write: Boolean = false, notify: Boolean = false) = GattCharacteristicInfo(id, write, notify)
    private val base = "-0000-1000-8000-00805f9b34fb"

    private val genericAccess = GattServiceInfo("00001800$base", listOf(c("00002a00$base", write = true)))

    @Test
    fun nordicUartIsRecognisedWithSeparateWriteAndNotify() {
        val nordic = GattServiceInfo(
            "6E400001-B5A3-F393-E0A9-E50E24DCCA9E",
            listOf(
                c("6E400002-B5A3-F393-E0A9-E50E24DCCA9E", write = true),
                c("6E400003-B5A3-F393-E0A9-E50E24DCCA9E", notify = true),
            ),
        )
        val pair = BleUart.select(listOf(genericAccess, nordic))!!
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", pair.writeUuid)
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", pair.notifyUuid)
    }

    @Test
    fun hm10SingleCharacteristicServesBothDirections() {
        val hm10 = GattServiceInfo("0000ffe0$base", listOf(c("0000ffe1$base", write = true, notify = true)))
        val pair = BleUart.select(listOf(hm10))!!
        assertEquals(pair.writeUuid, pair.notifyUuid)
    }

    @Test
    fun speedyBeeLayoutIsRecognised() {
        val sb = GattServiceInfo("0000abf0$base", listOf(c("0000abf1$base", write = true), c("0000abf2$base", notify = true)))
        val pair = BleUart.select(listOf(sb))!!
        assertEquals("0000abf1$base", pair.writeUuid)
        assertEquals("0000abf2$base", pair.notifyUuid)
    }

    @Test
    fun unknownVendorServiceIsFoundByCapability() {
        val odd = GattServiceInfo(
            "12345678-1234-1234-1234-123456789abc",
            listOf(c("aaaa0001-1234-1234-1234-123456789abc", write = true), c("aaaa0002-1234-1234-1234-123456789abc", notify = true)),
        )
        val pair = BleUart.select(listOf(genericAccess, odd))!!
        assertEquals("aaaa0001-1234-1234-1234-123456789abc", pair.writeUuid)
        assertEquals("aaaa0002-1234-1234-1234-123456789abc", pair.notifyUuid)
    }

    @Test
    fun standardServicesAreNeverUsedAsASerialPipe() {
        val access = GattServiceInfo("00001800$base", listOf(c("00002a00$base", write = true, notify = true)))
        assertNull(BleUart.select(listOf(access)))
    }

    @Test
    fun aServiceThatCanOnlyWriteOrOnlyNotifyIsNotEnough() {
        val half = GattServiceInfo("12345678-1234-1234-1234-123456789abc", listOf(c("aaaa0001-1234-1234-1234-123456789abc", write = true)))
        assertNull(BleUart.select(listOf(half)))
    }

    @Test
    fun aKnownServiceIsPreferredOverAnEarlierUnknownOne() {
        val odd = GattServiceInfo("12345678-1234-1234-1234-123456789abc", listOf(c("aaaa0001-1234-1234-1234-123456789abc", write = true, notify = true)))
        val hm10 = GattServiceInfo("0000ffe0$base", listOf(c("0000ffe1$base", write = true, notify = true)))
        assertEquals("0000ffe0$base", BleUart.select(listOf(odd, hm10))!!.serviceUuid)
    }
}
