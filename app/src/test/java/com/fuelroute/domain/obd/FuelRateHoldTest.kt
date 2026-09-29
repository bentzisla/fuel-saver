package com.fuelroute.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Test

class FuelRateHoldTest {

    @Test
    fun `a missing reply holds the last rate for up to 5 s, then counts as 0`() {
        val hold = FuelRateHold()
        assertEquals(2.4, hold.resolve(0L, 2.4), 1e-9)
        assertEquals(2.4, hold.resolve(250L, null), 1e-9)
        assertEquals(2.4, hold.resolve(5_000L, null), 1e-9)
        assertEquals(0.0, hold.resolve(5_001L, null), 1e-9)
    }

    @Test
    fun `nothing known yet counts as 0 and a new value replaces the held one`() {
        val hold = FuelRateHold()
        assertEquals(0.0, hold.resolve(0L, null), 1e-9)
        hold.resolve(100L, 1.0)
        assertEquals(3.0, hold.resolve(200L, 3.0), 1e-9)
        assertEquals(3.0, hold.resolve(300L, null), 1e-9)
    }

    @Test
    fun `reset forgets the held value`() {
        val hold = FuelRateHold()
        hold.resolve(0L, 2.0)
        hold.reset()
        assertEquals(0.0, hold.resolve(100L, null), 1e-9)
    }
}
