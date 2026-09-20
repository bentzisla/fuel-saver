package com.fuelroute.domain.ranking

import com.fuelroute.domain.fuel.ModelConstants
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.RouteSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteRankerTest {

    @Test
    fun `ranks by total cost with the default value of time`() {
        // a: 15 + 10*0.5 = 20; b: 20 + 2*0.5 = 21.
        val a = cost(id = "a", totalCost = 15.0, minutes = 10.0)
        val b = cost(id = "b", totalCost = 20.0, minutes = 2.0)

        val ranked = RouteRanker.rank(listOf(a, b))

        assertEquals(listOf("a", "b"), ranked.map { it.route.id })
    }

    @Test
    fun `a higher value of time can flip the ranking toward the faster route`() {
        val a = cost(id = "a", totalCost = 15.0, minutes = 10.0)
        val b = cost(id = "b", totalCost = 20.0, minutes = 2.0)

        // a: 15 + 10*5 = 65; b: 20 + 2*5 = 30.
        val ranked = RouteRanker.rank(listOf(a, b), valuePerMinute = 5.0)

        assertEquals(listOf("b", "a"), ranked.map { it.route.id })
    }

    @Test
    fun `cheapest returns the head of the ranking`() {
        val a = cost(id = "a", totalCost = 15.0, minutes = 10.0)
        val b = cost(id = "b", totalCost = 20.0, minutes = 2.0)

        assertEquals("b", RouteRanker.cheapest(listOf(a, b), valuePerMinute = 5.0)?.route?.id)
    }

    @Test
    fun `cheapest is null for an empty list`() {
        assertNull(RouteRanker.cheapest(emptyList()))
    }

    @Test
    fun `default value of time matches the model constant`() {
        assertEquals(0.5, ModelConstants.DEFAULT_VALUE_PER_MINUTE, 1e-12)
        assertEquals(
            RouteRanker.rank(listOf(cost("a", 10.0, 20.0))),
            RouteRanker.rank(listOf(cost("a", 10.0, 20.0)), ModelConstants.DEFAULT_VALUE_PER_MINUTE),
        )
    }

    private fun cost(id: String, totalCost: Double, minutes: Double) = RouteCost(
        route = Route(
            id = id,
            distanceMeters = 10_000.0,
            staticDurationSeconds = minutes * 60.0,
            durationSeconds = minutes * 60.0,
            segments = listOf(RouteSegment(10_000.0, minutes * 60.0)),
        ),
        fuelLiters = 1.0,
        fuelCost = totalCost,
        tollCost = 0.0,
        totalCost = totalCost,
        durationMinutes = minutes,
        distanceKm = 10.0,
        avgSpeedKmh = 40.0,
    )
}