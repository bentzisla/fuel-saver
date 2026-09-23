package com.fuelroute.ui.route

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteHighlightsTest {

    private fun cost(total: Double, minutes: Double) = RouteCost(
        route = Route(
            id = "r$total-$minutes",
            distanceMeters = 10_000.0,
            staticDurationSeconds = minutes * 60,
            durationSeconds = minutes * 60,
            segments = emptyList(),
        ),
        fuelLiters = total / 7.0,
        fuelCost = total,
        tollCost = 0.0,
        totalCost = total,
        durationMinutes = minutes,
        distanceKm = 10.0,
        avgSpeedKmh = 60.0,
    )

    @Test
    fun `cheapest route reports savings and extra time versus the fastest`() {
        val costs = listOf(cost(20.0, 40.0), cost(24.5, 32.0))

        val cheapest = RouteHighlights.compare(costs, 0)!!

        assertTrue(cheapest.isCheapest)
        assertFalse(cheapest.isFastest)
        assertEquals(4.5, cheapest.savingsVsFastest, 1e-9)
        assertEquals(8.0, cheapest.extraMinutesVsFastest, 1e-9)
        assertEquals(0.0, cheapest.premiumVsCheapest, 1e-9)
    }

    @Test
    fun `fastest route reports premium and minutes saved versus the cheapest`() {
        val costs = listOf(cost(20.0, 40.0), cost(24.5, 32.0))

        val fastest = RouteHighlights.compare(costs, 1)!!

        assertFalse(fastest.isCheapest)
        assertTrue(fastest.isFastest)
        assertEquals(4.5, fastest.premiumVsCheapest, 1e-9)
        assertEquals(8.0, fastest.minutesSavedVsCheapest, 1e-9)
        assertEquals(0.0, fastest.savingsVsFastest, 1e-9)
    }

    @Test
    fun `a route that is both cheapest and fastest has no trade-off`() {
        val costs = listOf(cost(18.0, 30.0), cost(22.0, 35.0))

        assertTrue(RouteHighlights.compare(costs, 0)!!.noTradeOff)
        assertFalse(RouteHighlights.compare(costs, 1)!!.noTradeOff)
    }

    @Test
    fun `ties within epsilon count as cheapest and fastest`() {
        val costs = listOf(cost(20.0, 30.0), cost(20.001, 30.2))

        val second = RouteHighlights.compare(costs, 1)!!

        assertTrue(second.isCheapest)
        assertTrue(second.isFastest)
    }

    @Test
    fun `out of range index returns null`() {
        assertNull(RouteHighlights.compare(listOf(cost(1.0, 1.0)), 3))
        assertNull(RouteHighlights.compare(emptyList(), 0))
    }
}
