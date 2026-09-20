package com.fuelroute.domain.obd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoConnectDebounceTest {

    @Test
    fun `a stop immediately followed by a start is ignored`() {
        val stopAt = 1_000L
        assertTrue(AutoConnectDebounce.shouldIgnoreStart(stopAt, stopAt))
        assertTrue(AutoConnectDebounce.shouldIgnoreStart(stopAt, stopAt + 4_999L))
    }

    @Test
    fun `a start after the window is allowed`() {
        val stopAt = 1_000L
        assertFalse(AutoConnectDebounce.shouldIgnoreStart(stopAt, stopAt + 5_000L))
        assertFalse(AutoConnectDebounce.shouldIgnoreStart(stopAt, stopAt + 60_000L))
    }

    @Test
    fun `no stop recorded means starts are allowed`() {
        assertFalse(AutoConnectDebounce.shouldIgnoreStart(null, 1_000L))
    }

    @Test
    fun `a backwards clock does not block a start`() {
        assertFalse(AutoConnectDebounce.shouldIgnoreStart(2_000L, 1_000L))
    }
}
