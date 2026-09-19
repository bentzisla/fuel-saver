package com.fuelroute.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedBinStatsTest {

    @Test
    fun `idle speeds map to bin zero`() {
        assertEquals(0, speedToBinIndex(0.0))
        assertEquals(0, speedToBinIndex(0.9))
        assertEquals(0, speedToBinIndex(-3.0))
    }

    @Test
    fun `a 4 kmh crawl maps to bin one not the idle bin`() {
        assertEquals(1, speedToBinIndex(4.0))
    }

    @Test
    fun `50 kmh maps to bin eleven`() {
        assertEquals(11, speedToBinIndex(50.0))
    }

    @Test
    fun `bin index maps back to the representative speed`() {
        assertEquals(0.0, binIndexToSpeedKmh(0), 1e-9)
        assertEquals(2.5, binIndexToSpeedKmh(1), 1e-9)
        assertEquals(7.5, binIndexToSpeedKmh(2), 1e-9)
        assertEquals(52.5, binIndexToSpeedKmh(11), 1e-9)
    }
}
