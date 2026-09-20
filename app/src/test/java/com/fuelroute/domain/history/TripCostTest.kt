package com.fuelroute.domain.history

import org.junit.Assert.assertEquals
import org.junit.Test

class TripCostTest {

    private val delta = 1e-9

    @Test
    fun `actualCost multiplies the trip fuel by its own price`() {
        val cost = TripCost(fuelL = 8.0, pricePerLiterAtTrip = 7.5)

        assertEquals(60.0, cost.actualCost, delta)
    }

    @Test
    fun `actualCost is a snapshot and does not move when the price later changes`() {
        val snapshot = TripCost(fuelL = 10.0, pricePerLiterAtTrip = 7.0)
        val storedCost = snapshot.actualCost

        // A later refuel/price update must not retroactively change already-stored history.
        val laterPrice = 9.0
        val newCost = TripCost(fuelL = 10.0, pricePerLiterAtTrip = laterPrice).actualCost

        assertEquals(70.0, storedCost, delta)
        assertEquals(90.0, newCost, delta)
        assertEquals(70.0, snapshot.actualCost, delta)
    }
}