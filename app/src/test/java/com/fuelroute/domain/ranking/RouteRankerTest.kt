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

    @Test
    fun `routes within the tie margin put the faster one first`() {
        // 20.00 vs 20.40 NIS is well inside the model's error: prefer the faster route.
        val cheapSlow = cost(id = "cheap-slow", totalCost = 20.0, minutes = 30.0)
        val fastSimilar = cost(id = "fast", totalCost = 20.4, minutes = 26.0)
        val pricey = cost(id = "pricey", totalCost = 30.0, minutes = 20.0)

        val ranked = RouteRanker.rank(listOf(cheapSlow, pricey, fastSimilar), valuePerMinute = 0.0)

        assertEquals(listOf("fast", "cheap-slow", "pricey"), ranked.map { it.route.id })
    }

    @Test
    fun `a lead larger than the tie margin still wins`() {
        // 5% of 40 = 2 NIS margin; 3 NIS apart is a real difference.
        val cheap = cost(id = "cheap", totalCost = 40.0, minutes = 50.0)
        val fast = cost(id = "fast", totalCost = 43.0, minutes = 40.0)

        assertEquals("cheap", RouteRanker.rank(listOf(fast, cheap), valuePerMinute = 0.0).first().route.id)
    }

    @Test
    fun `an unpriced toll does not win on fuel alone over a toll-free route`() {
        val tollUnknown = cost(id = "toll-road", totalCost = 18.0, minutes = 30.0, tollUnknown = true)
        val tollFree = cost(id = "free", totalCost = 22.0, minutes = 31.0)

        val ranked = RouteRanker.rank(listOf(tollUnknown, tollFree), valuePerMinute = 0.0)

        assertEquals("free", ranked.first().route.id)
    }

    @Test
    fun `value of time is clamped at use`() {
        val slowCheap = cost(id = "slow", totalCost = 10.0, minutes = 60.0)
        val fastPricey = cost(id = "fast", totalCost = 30.0, minutes = 10.0)

        // Negative would rank the slowest route first; it behaves like 0 instead.
        assertEquals("slow", RouteRanker.rank(listOf(fastPricey, slowCheap), valuePerMinute = -5.0).first().route.id)
        // Infinity would make every key infinite; it behaves like the maximum instead.
        assertEquals("fast", RouteRanker.rank(listOf(slowCheap, fastPricey), valuePerMinute = Double.POSITIVE_INFINITY).first().route.id)
        assertEquals(RouteRanker.MAX_VALUE_PER_MINUTE, RouteRanker.sanitizeValuePerMinute(1e9), 0.0)
        assertEquals(0.0, RouteRanker.sanitizeValuePerMinute(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(
            ModelConstants.DEFAULT_VALUE_PER_MINUTE,
            RouteRanker.sanitizeValuePerMinute(Double.NaN),
            0.0,
        )
    }

    private fun cost(id: String, totalCost: Double, minutes: Double, tollUnknown: Boolean = false) = RouteCost(
        route = Route(
            id = id,
            distanceMeters = 10_000.0,
            staticDurationSeconds = minutes * 60.0,
            durationSeconds = minutes * 60.0,
            segments = listOf(RouteSegment(10_000.0, minutes * 60.0)),
            tollCost = if (tollUnknown) null else 0.0,
            tollUnknown = tollUnknown,
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