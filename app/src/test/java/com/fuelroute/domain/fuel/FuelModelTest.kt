package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.ranking.RouteRanker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelModelTest {

    private val curve = DefaultCurve.forVehicle(7.0)
    private val model = FuelModel(curve = curve, idleLitersPerHour = 0.8)

    @Test
    fun `computes fuel for a simple segment`() {
        val route = Route(
            id = "r1",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(distanceMeters = 10_000.0, staticDurationSeconds = 600.0)),
        )

        val cost = model.cost(route, pricePerLiter = 7.0)
        val expectedLiters = 10.0 * curve.litersPer100Km(60.0) / 100.0

        assertEquals(expectedLiters, cost.fuelLiters, 1e-6)
        assertEquals(expectedLiters * 7.0, cost.totalCost, 1e-6)
        assertEquals(10.0, cost.distanceKm, 1e-9)
        assertEquals(60.0, cost.avgSpeedKmh, 1e-6)
    }

    @Test
    fun `congestion increases fuel consumption`() {
        val normal = routeWithCongestion(CongestionLevel.NORMAL)
        val jam = routeWithCongestion(CongestionLevel.TRAFFIC_JAM)

        val normalCost = model.cost(normal, 7.0)
        val jamCost = model.cost(jam, 7.0)

        assertTrue(jamCost.fuelLiters > normalCost.fuelLiters)
    }

    @Test
    fun `toll is added to the total`() {
        val route = Route(
            id = "t",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(10_000.0, 600.0)),
            tollCost = 12.5,
        )

        val cost = model.cost(route, 7.0)
        assertEquals(cost.fuelCost + 12.5, cost.totalCost, 1e-9)
    }

    @Test
    fun `ranker orders by total cost`() {
        val cheapShort = model.cost(
            Route(
                id = "cheap",
                distanceMeters = 5_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 600.0,
                segments = listOf(RouteSegment(5_000.0, 600.0)),
            ),
            7.0,
        )
        val expensiveLong = model.cost(
            Route(
                id = "expensive",
                distanceMeters = 20_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 600.0,
                segments = listOf(RouteSegment(20_000.0, 600.0)),
            ),
            7.0,
        )

        val ranked = RouteRanker.rank(listOf(expensiveLong, cheapShort))
        assertEquals("cheap", ranked.first().route.id)
    }

    @Test
    fun `value of time can flip the ranking`() {
        val cheapSlow = model.cost(
            Route(
                id = "slow",
                distanceMeters = 10_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 2_400.0,
                segments = listOf(RouteSegment(10_000.0, 600.0, 2_400.0)),
            ),
            7.0,
        )
        val costlyFast = model.cost(
            Route(
                id = "fast",
                distanceMeters = 15_000.0,
                staticDurationSeconds = 900.0,
                durationSeconds = 900.0,
                segments = listOf(RouteSegment(15_000.0, 900.0)),
            ),
            7.0,
        )

        assertEquals("slow", RouteRanker.rank(listOf(cheapSlow, costlyFast)).first().route.id)
        assertEquals("fast", RouteRanker.rank(listOf(cheapSlow, costlyFast), valuePerMinute = 1.0).first().route.id)
    }

    private fun routeWithCongestion(level: CongestionLevel) = Route(
        id = level.name,
        distanceMeters = 10_000.0,
        staticDurationSeconds = 600.0,
        durationSeconds = 900.0,
        segments = listOf(RouteSegment(10_000.0, 600.0, 900.0, level)),
    )
}