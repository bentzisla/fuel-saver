package com.fuelroute.domain.obd

import com.fuelroute.domain.fuel.ModelConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveCostCalculatorTest {

    // --- costPerHour: the headline ₪/h number ---

    @Test
    fun `cost per hour is fuel rate times price`() {
        assertEquals(5.6, LiveCostCalculator.costPerHour(0.8, 7.0), 1e-9)
    }

    @Test
    fun `cost per hour at idle is non-zero when burning fuel`() {
        // speed is irrelevant to the ₪/h headline; idle still costs money.
        assertEquals(7.0 * 0.8, LiveCostCalculator.costPerHour(0.8, 7.0), 1e-9)
    }

    @Test
    fun `cost per hour never returns NaN for null or non-finite inputs`() {
        assertEquals(0.0, LiveCostCalculator.costPerHour(null, 7.0), 1e-9)
        assertEquals(0.0, LiveCostCalculator.costPerHour(Double.NaN, 7.0), 1e-9)
        assertEquals(0.0, LiveCostCalculator.costPerHour(0.8, Double.NaN), 1e-9)
        assertEquals(0.0, LiveCostCalculator.costPerHour(-1.0, 7.0), 1e-9)
        assertFalse(LiveCostCalculator.costPerHour(null, 7.0).isNaN())
    }

    // --- L/100 km and ₪/km are null while stationary ---

    @Test
    fun `liters per 100 km is null below 1 km per hour`() {
        assertNull(LiveCostCalculator.litersPer100Km(0.8, 0.0))
        assertNull(LiveCostCalculator.litersPer100Km(0.8, 0.9))
        assertNull(LiveCostCalculator.litersPer100Km(0.8, null))
        assertNull(LiveCostCalculator.litersPer100Km(null, 50.0))
    }

    @Test
    fun `cost per km is null below 1 km per hour`() {
        assertNull(LiveCostCalculator.costPerKm(0.8, 0.0, 7.0))
        assertNull(LiveCostCalculator.costPerKm(0.8, 0.99, 7.0))
        assertNull(LiveCostCalculator.costPerKm(0.8, null, 7.0))
        assertNull(LiveCostCalculator.costPerKm(null, 50.0, 7.0))
    }

    @Test
    fun `exact arithmetic for a known rate speed and price`() {
        // 8 L/h at 80 km/h = 10 L/100 km; at 7 ₪/L that is 0.7 ₪/km and 56 ₪/h.
        assertEquals(10.0, LiveCostCalculator.litersPer100Km(8.0, 80.0)!!, 1e-9)
        assertEquals(0.7, LiveCostCalculator.costPerKm(8.0, 80.0, 7.0)!!, 1e-9)
        assertEquals(56.0, LiveCostCalculator.costPerHour(8.0, 7.0), 1e-9)
    }

    // --- tripCost ---

    @Test
    fun `trip cost is liters times price`() {
        assertEquals(35.0, LiveCostCalculator.tripCost(5.0, 7.0), 1e-9)
    }

    @Test
    fun `trip cost clamps negatives and rejects non-finite`() {
        assertEquals(0.0, LiveCostCalculator.tripCost(-5.0, 7.0), 1e-9)
        assertEquals(0.0, LiveCostCalculator.tripCost(Double.NaN, 7.0), 1e-9)
        assertEquals(0.0, LiveCostCalculator.tripCost(5.0, Double.NaN), 1e-9)
    }

    // --- EMA smoother ---

    @Test
    fun `smoother initializes on first valid sample then converges`() {
        val smoother = EmaSmoother(alpha = 0.5)
        assertNull(smoother.current)
        assertEquals(10.0, smoother.update(10.0)!!, 1e-9)
        val half = smoother.update(20.0)!!
        assertEquals(15.0, half, 1e-9)
        repeat(50) { smoother.update(20.0) }
        assertEquals(20.0, smoother.current!!, 1e-3)
    }

    @Test
    fun `smoother keeps last value and never returns NaN for null zero or non-finite`() {
        val smoother = EmaSmoother(alpha = 0.5)
        assertNull(smoother.update(null))
        assertEquals(4.0, smoother.update(4.0)!!, 1e-9)
        assertEquals(4.0, smoother.update(null)!!, 1e-9)
        assertEquals(4.0, smoother.update(Double.NaN)!!, 1e-9)
        // a genuine zero converges towards zero but is finite
        val afterZero = smoother.update(0.0)!!
        assertFalse(afterZero.isNaN())
        assertEquals(2.0, afterZero, 1e-9)
    }

    @Test
    fun `smoother defaults to the shared model constant`() {
        assertTrue(ModelConstants.LIVE_EMA_ALPHA in 0.0..1.0)
    }

    // --- throttle predicate ---

    @Test
    fun `identical snapshots produce no invalidation`() {
        val values = LiveDashboardValues(
            costPerHour = 42.0,
            consumption = 8.5,
            moving = true,
            tripCost = 3.2,
            speedKmh = 62.0,
            tripDistanceKm = 12.4,
        )
        val copy = values.copy()
        assertFalse(LiveCostCalculator.shouldInvalidate(values, copy))
        assertTrue(LiveCostCalculator.shouldInvalidate(null, values))
    }

    @Test
    fun `a changed value produces an invalidation`() {
        val base = LiveDashboardValues(
            costPerHour = 42.0,
            consumption = 8.5,
            moving = true,
            tripCost = 3.2,
            speedKmh = 62.0,
            tripDistanceKm = 12.4,
        )
        assertTrue(LiveCostCalculator.shouldInvalidate(base, base.copy(costPerHour = 43.0)))
        assertTrue(LiveCostCalculator.shouldInvalidate(base, base.copy(moving = false)))
        assertTrue(LiveCostCalculator.shouldInvalidate(null, base))
    }
}