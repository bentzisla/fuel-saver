package com.fuelroute.domain.ranking

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteInsightsTest {

    @Test
    fun `identifies the cheapest and the fastest routes independently`() {
        val costs = listOf(
            cost(totalCost = 30.0, minutes = 20.0),
            cost(totalCost = 18.0, minutes = 35.0),
            cost(totalCost = 25.0, minutes = 12.0),
        )

        val badges = RouteInsights.badges(costs)

        assertEquals(1, badges.cheapestIndex)
        assertEquals(2, badges.fastestIndex)
    }

    @Test
    fun `badges are null for an empty list`() {
        val badges = RouteInsights.badges(emptyList())
        assertNull(badges.cheapestIndex)
        assertNull(badges.fastestIndex)
    }

    @Test
    fun `a single route is both cheapest and fastest`() {
        val badges = RouteInsights.badges(listOf(cost(totalCost = 5.0, minutes = 7.0)))
        assertEquals(0, badges.cheapestIndex)
        assertEquals(0, badges.fastestIndex)
    }

    private fun cost(totalCost: Double, minutes: Double) = RouteCost(
        route = Route(
            id = "r-$totalCost-$minutes",
            distanceMeters = 1_000.0,
            staticDurationSeconds = minutes * 60.0,
            durationSeconds = minutes * 60.0,
            segments = listOf(RouteSegment(1_000.0, minutes * 60.0)),
        ),
        fuelLiters = 1.0,
        fuelCost = totalCost,
        tollCost = 0.0,
        totalCost = totalCost,
        durationMinutes = minutes,
        distanceKm = 1.0,
        avgSpeedKmh = 30.0,
    )
}