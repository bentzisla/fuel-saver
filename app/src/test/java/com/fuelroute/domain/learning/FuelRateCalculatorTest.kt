package com.fuelroute.domain.learning

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelRateCalculatorTest {

    @Test
    fun `prefers the direct fuel rate pid`() {
        val sample = ObdSample(timestampMs = 0, fuelRateLph = 7.5, mafGps = 20.0)
        assertEquals(7.5, FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, 2.0)!!, 1e-9)
    }

    @Test
    fun `falls back to maf`() {
        val sample = ObdSample(timestampMs = 0, mafGps = 14.7)
        assertEquals(4.8322, FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, 2.0)!!, 1e-3)
    }

    @Test
    fun `maf formula uses gasoline constants`() {
        assertEquals(14.7 / 14.7 * 3600.0 / 745.0, FuelRateCalculator.mafToLph(14.7, FuelType.GASOLINE), 1e-6)
    }

    @Test
    fun `maf formula uses diesel constants`() {
        assertEquals(14.7 / 14.5 * 3600.0 / 832.0, FuelRateCalculator.mafToLph(14.7, FuelType.DIESEL), 1e-6)
    }

    @Test
    fun `falls back to speed density when there is no maf`() {
        val sample = ObdSample(timestampMs = 0, rpm = 2000.0, mapKpa = 50.0, intakeTempC = 25.0)
        val rate = FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, 2.0)
        assertNotNull(rate)
        assertTrue("rate=$rate", rate!! > 0.5 && rate < 8.0)
    }

    @Test
    fun `speed density needs engine displacement`() {
        val sample = ObdSample(timestampMs = 0, rpm = 2000.0, mapKpa = 50.0, intakeTempC = 25.0)
        assertNull(FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, null))
    }

    @Test
    fun `returns null when there is no usable data`() {
        assertNull(FuelRateCalculator.fuelRateLph(ObdSample(timestampMs = 0), FuelType.GASOLINE, 2.0))
    }

    @Test
    fun `estimates above the engine maximum are rejected not clamped`() {
        // MAF sentinel 655.35 g/s -> ~215 L/h; 5E sentinel 3276.75 L/h.
        assertNull(FuelRateCalculator.fuelRateLph(ObdSample(timestampMs = 0, mafGps = 655.35), FuelType.GASOLINE, 1.8))
        assertNull(FuelRateCalculator.fuelRateLph(ObdSample(timestampMs = 0, fuelRateLph = 3276.75), FuelType.GASOLINE, null))
    }

    @Test
    fun `estimates report their source`() {
        val direct = FuelRateCalculator.estimate(ObdSample(timestampMs = 0, fuelRateLph = 5.0, mafGps = 9.0), FuelType.GASOLINE, 1.8)!!
        assertEquals(FuelRateSource.DIRECT, direct.source)
        val maf = FuelRateCalculator.estimate(ObdSample(timestampMs = 0, mafGps = 9.0), FuelType.GASOLINE, 1.8)!!
        assertEquals(FuelRateSource.MAF, maf.source)
        assertTrue(maf.learnable)
    }

    @Test
    fun `diesel air-mass estimates are shown but never learned`() {
        val maf = FuelRateCalculator.estimate(ObdSample(timestampMs = 0, mafGps = 20.0), FuelType.DIESEL, 2.0)!!
        assertEquals(FuelRateSource.MAF, maf.source)
        assertFalse(maf.learnable)
        val direct = FuelRateCalculator.estimate(ObdSample(timestampMs = 0, fuelRateLph = 4.0), FuelType.DIESEL, 2.0)!!
        assertTrue(direct.learnable)
    }
}