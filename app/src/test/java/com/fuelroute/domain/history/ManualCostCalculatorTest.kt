package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualCostCalculatorTest {

    private val delta = 1e-9

    @Test
    fun `estimate derives liters and cost from distance and consumption`() {
        val estimate = ManualCostCalculator.estimate(
            distanceKm = 50.0,
            litersPer100Km = 8.0,
            pricePerLiter = 7.5,
        )!!

        assertEquals(4.0, estimate.liters, delta)
        assertEquals(30.0, estimate.cost, delta)
    }

    @Test
    fun `fromConsumption keeps the inputs and derives the cost`() {
        val input = ManualCostCalculator.fromConsumption(
            distanceKm = 20.0,
            litersPer100Km = 6.0,
            pricePerLiter = 7.0,
        )!!

        assertEquals(8.4, input.cost, delta)
        assertEquals(20.0, input.distanceKm!!, delta)
        assertEquals(6.0, input.litersPer100Km!!, delta)
    }

    @Test
    fun `fromCost builds a direct input with no distance or consumption`() {
        val input = ManualCostCalculator.fromCost(42.5)!!

        assertEquals(42.5, input.cost, delta)
        assertNull(input.distanceKm)
        assertNull(input.litersPer100Km)
    }

    @Test
    fun `litersFromCost divides the direct cost by the price`() {
        assertEquals(5.0, ManualCostCalculator.litersFromCost(35.0, 7.0)!!, delta)
    }

    @Test
    fun `non-positive and non-finite inputs are rejected`() {
        assertNull(ManualCostCalculator.estimate(0.0, 8.0, 7.0))
        assertNull(ManualCostCalculator.estimate(10.0, 0.0, 7.0))
        assertNull(ManualCostCalculator.estimate(10.0, 8.0, 0.0))
        assertNull(ManualCostCalculator.estimate(Double.NaN, 8.0, 7.0))
        assertNull(ManualCostCalculator.estimate(10.0, Double.POSITIVE_INFINITY, 7.0))
        assertNull(ManualCostCalculator.fromCost(-1.0))
        assertNull(ManualCostCalculator.litersFromCost(10.0, 0.0))
    }

    @Test
    fun `validators accept positive finite values only`() {
        assertTrue(ManualCostCalculator.isValidCost(1.0))
        assertTrue(ManualCostCalculator.isValidDistance(0.5))
        assertTrue(ManualCostCalculator.isValidConsumption(5.0))
        assertTrue(ManualCostCalculator.isValidPrice(7.0))
        assertFalse(ManualCostCalculator.isValidCost(0.0))
        assertFalse(ManualCostCalculator.isValidDistance(Double.NaN))
    }
}
