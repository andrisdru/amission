package org.amission.msp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderErrorsTest {
    @Test
    fun refusedKeyIsExplained() {
        for (raw in listOf("HTTP status code 401", "HTTP status code 403", "Request failed: 401 Unauthorized")) {
            val m = ProviderErrors.describe(raw)
            assertTrue("refused some requests" in m, "$raw -> $m")
            assertTrue("few minutes" in m, m)
        }
        assertTrue("HTTP 403" in ProviderErrors.describe("HTTP status code 403"))
        assertTrue("HTTP 401" in ProviderErrors.describe("HTTP status code 401"))
    }

    @Test
    fun tooManyRequestsIsExplained() {
        assertTrue("too many requests" in ProviderErrors.describe("HTTP status code 429"))
    }

    @Test
    fun numbersThatMerelyContainTheDigitsAreNotMistakenForStatusCodes() {
        assertEquals("Download problem: tile 14010 failed", ProviderErrors.describe("tile 14010 failed"))
        assertEquals("Download problem: HTTP status code 500", ProviderErrors.describe("HTTP status code 500"))
    }

    @Test
    fun emptyAndMissingTextHaveAFallback() {
        assertEquals("Download problem.", ProviderErrors.describe(null))
        assertEquals("Download problem.", ProviderErrors.describe("  "))
    }
}
