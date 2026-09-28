package com.fuelroute.domain.ranking

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `routes a few agorot apart are a cost tie`() {
        val badges = RouteInsights.badges(listOf(cost(20.0, 30.0), cost(20.3, 25.0)))

        assertEquals(0, badges.cheapestIndex)
        assertTrue(badges.cheapestIsTie)
    }

    @Test
    fun `tie margin is the larger of 1 NIS and 5 percent`() {
        assertEquals(1.0, RouteInsights.tieThreshold(10.0), 1e-12)
        assertEquals(3.0, RouteInsights.tieThreshold(60.0), 1e-12)
        assertFalse(RouteInsights.badges(listOf(cost(60.0, 30.0), cost(63.5, 25.0))).cheapestIsTie)
        assertTrue(RouteInsights.badges(listOf(cost(60.0, 30.0), cost(62.5, 25.0))).cheapestIsTie)
    }

    @Test
    fun `an unpriced toll route is not the cheapest over a toll-free one on fuel alone`() {
        val unknownToll = cost(totalCost = 17.0, minutes = 20.0, tollUnknown = true)
        val tollFree = cost(totalCost = 21.0, minutes = 25.0)

        val badges = RouteInsights.badges(listOf(unknownToll, tollFree))

        assertEquals(1, badges.cheapestIndex)
        assertEquals(17.0 + RouteInsights.UNKNOWN_TOLL_ESTIMATE_NIS, RouteInsights.rankingCost(unknownToll), 1e-9)
    }

    private fun cost(totalCost: Double, minutes: Double, tollUnknown: Boolean = false) = RouteCost(
        route = Route(
            id = "r-$totalCost-$minutes",
            distanceMeters = 1_000.0,
            staticDurationSeconds = minutes * 60.0,
            durationSeconds = minutes * 60.0,
            segments = listOf(RouteSegment(1_000.0, minutes * 60.0)),
            tollCost = if (tollUnknown) null else 0.0,
            tollUnknown = tollUnknown,
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