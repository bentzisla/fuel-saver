package com.fuelroute.data.price

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PricePinningTest {

    @Test
    fun `full refuel updates an unpinned price`() {
        val current = FuelPrice(pricePerLiter = 7.0, grade = FuelGrades.GASOLINE_95, manuallyPinned = false)
        val updated = PricePinning.applyFullRefuel(current, observedPricePerLiter = 7.9)
        assertEquals(7.9, updated.pricePerLiter, 1e-9)
        assertFalse(updated.manuallyPinned)
    }

    @Test
    fun `full refuel never clobbers a pinned price`() {
        val pinned = FuelPrice(pricePerLiter = 6.5, grade = FuelGrades.GASOLINE_95, manuallyPinned = true)
        val updated = PricePinning.applyFullRefuel(pinned, observedPricePerLiter = 8.2)
        assertEquals(6.5, updated.pricePerLiter, 1e-9)
        assertTrue(updated.manuallyPinned)
    }
}