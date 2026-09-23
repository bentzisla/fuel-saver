package com.fuelroute.domain.learning

import com.fuelroute.domain.model.FuelType
import com.fuelroute.domain.model.ObdSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineDisplacementTest {

    @Test
    fun `cc values are converted to litres`() {
        assertEquals(1.8, EngineDisplacement.normalizeLiters(1800.0)!!, 1e-9)
        assertEquals(1.598, EngineDisplacement.normalizeLiters(1598.0)!!, 1e-9)
        assertEquals(1.8, EngineDisplacement.parseLiters("1800")!!, 1e-9)
        assertEquals(1.8, EngineDisplacement.parseLiters(" 1,8 ")!!, 1e-9)
    }

    @Test
    fun `litre values pass through`() {
        assertEquals(1.8, EngineDisplacement.normalizeLiters(1.8)!!, 1e-9)
        assertEquals(6.2, EngineDisplacement.normalizeLiters(6.2)!!, 1e-9)
    }

    @Test
    fun `out of range or garbage is unknown`() {
        assertNull(EngineDisplacement.normalizeLiters(0.0))
        assertNull(EngineDisplacement.normalizeLiters(-2.0))
        assertNull(EngineDisplacement.normalizeLiters(12.0))
        assertNull(EngineDisplacement.normalizeLiters(50.0)) // 0.05 L
        assertNull(EngineDisplacement.normalizeLiters(Double.NaN))
        assertNull(EngineDisplacement.parseLiters(""))
        assertNull(EngineDisplacement.parseLiters("abc"))
    }

    @Test
    fun `needs repair only when normalization changes the value`() {
        assertTrue(EngineDisplacement.needsRepair(1800.0))
        assertTrue(EngineDisplacement.needsRepair(12.0))
        assertFalse(EngineDisplacement.needsRepair(1.8))
        assertFalse(EngineDisplacement.needsRepair(null))
    }

    /**
     * Numbers from the user's phone DB (Honda Civic 2008, displacement stored as 1800):
     * rpm 1751, MAP 27 kPa, IAT 44 C, no MAF -> speed-density gave ~2177 L/h instead of ~2.18.
     */
    @Test
    fun `speed density with a displacement typed in cc is normalized not multiplied by 1000`() {
        val sample = ObdSample(timestampMs = 0, speedKmh = 45.0, rpm = 1751.0, mapKpa = 27.0, intakeTempC = 44.0)

        val wrong = FuelRateCalculator.speedDensityLph(27.0, 1751.0, 44.0, 1800.0, FuelType.GASOLINE)
        assertEquals(2177.0, wrong, 5.0) // the bug, reproduced

        val fixed = FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, 1800.0)!!
        assertEquals(2.18, fixed, 0.01)
        assertEquals(fixed, FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, 1.8)!!, 1e-12)
    }

    @Test
    fun `an implausible displacement disables speed density instead of guessing`() {
        val sample = ObdSample(timestampMs = 0, rpm = 1751.0, mapKpa = 27.0, intakeTempC = 44.0)
        assertNull(FuelRateCalculator.fuelRateLph(sample, FuelType.GASOLINE, 12.0))
    }
}
