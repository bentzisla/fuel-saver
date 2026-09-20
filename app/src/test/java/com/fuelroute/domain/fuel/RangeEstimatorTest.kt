package com.fuelroute.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeEstimatorTest {

    @Test
    fun `remaining range is liters left divided by consumption`() {
        // 50 L tank at 25% = 12.5 L; at 10 L/100km -> 125 km.
        assertEquals(125.0, RangeEstimator.remainingRangeKm(50.0, 25.0, 10.0)!!, 1e-9)
    }

    @Test
    fun `remaining range clamps level to 0-100`() {
        assertEquals(0.0, RangeEstimator.remainingRangeKm(50.0, -5.0, 8.0)!!, 1e-9)
        assertEquals(625.0, RangeEstimator.remainingRangeKm(50.0, 150.0, 8.0)!!, 1e-9)
    }

    @Test
    fun `remaining range is null when inputs are missing or invalid`() {
        assertNull(RangeEstimator.remainingRangeKm(null, 50.0, 8.0))
        assertNull(RangeEstimator.remainingRangeKm(50.0, null, 8.0))
        assertNull(RangeEstimator.remainingRangeKm(50.0, 50.0, null))
        assertNull(RangeEstimator.remainingRangeKm(0.0, 50.0, 8.0))
        assertNull(RangeEstimator.remainingRangeKm(50.0, 50.0, 0.0))
    }

    @Test
    fun `exceeds tank capacity only when both are known and liters are larger`() {
        assertTrue(RangeEstimator.exceedsTankCapacity(60.0, 50.0))
        assertFalse(RangeEstimator.exceedsTankCapacity(50.0, 50.0))
        assertFalse(RangeEstimator.exceedsTankCapacity(40.0, 50.0))
        assertFalse(RangeEstimator.exceedsTankCapacity(null, 50.0))
        assertFalse(RangeEstimator.exceedsTankCapacity(60.0, null))
        assertFalse(RangeEstimator.exceedsTankCapacity(60.0, 0.0))
    }
}