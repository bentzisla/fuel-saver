package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.TrafficResolution
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
    fun `normalized segment times always sum to the route duration`() {
        val cases = listOf(
            Route(
                id = "single",
                distanceMeters = 10_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 600.0,
                segments = listOf(RouteSegment(10_000.0, 600.0)),
            ),
            Route(
                id = "single-jam",
                distanceMeters = 10_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 900.0,
                segments = listOf(RouteSegment(10_000.0, 600.0, congestionFactor = 0.25)),
            ),
            Route(
                id = "mixed",
                distanceMeters = 10_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 900.0,
                segments = listOf(
                    RouteSegment(5_000.0, 300.0, congestionFactor = 1.0),
                    RouteSegment(5_000.0, 300.0, congestionFactor = 0.25),
                ),
            ),
            Route(
                id = "mixed-nocongestion",
                distanceMeters = 10_000.0,
                staticDurationSeconds = 600.0,
                durationSeconds = 600.0,
                segments = listOf(
                    RouteSegment(5_000.0, 300.0, congestionFactor = 1.0),
                    RouteSegment(5_000.0, 300.0, congestionFactor = 0.25),
                ),
            ),
        )

        for (route in cases) {
            val times = model.normalizedSegmentsSeconds(route)
            assertEquals(
                "route ${route.id}: Σ t_i must equal durationSeconds",
                route.durationSeconds,
                times.sum(),
                1e-6,
            )
        }
    }

    @Test
    fun `congested segment costs more fuel than normal for equal distance`() {
        val allNormal = model.cost(twoSegmentRoute(secondFactor = 1.0), 7.0)
        val secondJam = model.cost(twoSegmentRoute(secondFactor = 0.25), 7.0)

        assertTrue(secondJam.segments[1].liters > allNormal.segments[1].liters)
    }

    @Test
    fun `none resolution with factor one is pure uniform time scaling`() {
        val route = Route(
            id = "none",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 1_200.0,
            trafficResolution = TrafficResolution.NONE,
            segments = listOf(RouteSegment(10_000.0, 600.0, congestionFactor = 1.0)),
        )

        val cost = model.cost(route, 7.0)
        val uniformSpeed = 10.0 / (1_200.0 / 3600.0)
        val expected = 10.0 * curve.litersPer100Km(uniformSpeed) / 100.0

        assertEquals(uniformSpeed, cost.avgSpeedKmh, 1e-6)
        assertEquals(expected, cost.fuelLiters, 1e-6)
    }

    @Test
    fun `cold start liters are added exactly once`() {
        val route = Route(
            id = "cold",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(10_000.0, 600.0)),
        )

        val without = model.cost(route, 7.0)
        val with = model.cost(route, 7.0, coldStartLiters = 0.15)

        assertEquals(without.fuelLiters + 0.15, with.fuelLiters, 1e-9)
        assertEquals(without.totalCost + 0.15 * 7.0, with.totalCost, 1e-9)
    }

    @Test
    fun `zero static duration falls back to the route average speed`() {
        val route = Route(
            id = "missing-static",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(distanceMeters = 10_000.0, staticDurationSeconds = 0.0)),
        )

        val cost = model.cost(route, 7.0)

        // Not 0 (which would charge the curve's ~2.2x crawl rate): the route average, 60 km/h.
        assertEquals(60.0, cost.segments.single().effectiveSpeedKmh, 1e-6)
        assertEquals(10.0 * curve.litersPer100Km(60.0) / 100.0, cost.fuelLiters, 1e-6)
    }

    @Test
    fun `non-finite price yields a finite zero fuel cost`() {
        val route = Route(
            id = "nan-price",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(10_000.0, 600.0)),
        )

        val cost = model.cost(route, pricePerLiter = Double.NaN)

        assertTrue(cost.totalCost.isFinite())
        assertEquals(0.0, cost.fuelCost, 0.0)
        assertEquals(cost.fuelLiters, 10.0 * curve.litersPer100Km(60.0) / 100.0, 1e-6)
    }

    @Test
    fun `slow factor override changes the costed speed of a slow segment`() {
        val route = Route(
            id = "slow-override",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 900.0,
            trafficResolution = TrafficResolution.NONE,
            segments = listOf(
                RouteSegment(5_000.0, 300.0, congestionFactor = 1.0, congestion = CongestionLevel.NORMAL),
                RouteSegment(5_000.0, 300.0, congestionFactor = 0.55, congestion = CongestionLevel.SLOW),
            ),
        )
        val defaultCost = model.cost(route, 7.0)
        val fasterSlow = FuelModel(
            curve = curve,
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(slowFactor = 0.8),
        ).cost(route, 7.0)

        // Raising the slow factor makes the slow half faster, so it burns less fuel.
        assertTrue(fasterSlow.segments[1].effectiveSpeedKmh > defaultCost.segments[1].effectiveSpeedKmh)
        assertTrue(fasterSlow.segments[1].liters < defaultCost.segments[1].liters)
    }

    @Test
    fun `uphill segment costs more than an identical flat segment`() {
        val flat = Route(
            id = "flat",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(10_000.0, 600.0, elevationDeltaM = 0.0)),
        )
        val uphill = flat.copy(id = "uphill", segments = listOf(RouteSegment(10_000.0, 600.0, elevationDeltaM = 500.0)))

        val flatCost = model.cost(flat, 7.0)
        val uphillCost = model.cost(uphill, 7.0)

        assertTrue(uphillCost.fuelLiters > flatCost.fuelLiters)
        assertEquals(flatCost.fuelLiters + GradeModel.extraLiters(500.0), uphillCost.fuelLiters, 1e-9)
    }

    @Test
    fun `a steep descent never makes a segment cost negative fuel`() {
        val route = Route(
            id = "steep-descent",
            distanceMeters = 100.0,
            staticDurationSeconds = 60.0,
            durationSeconds = 60.0,
            // A tiny distance with a huge elevation drop: the grade credit alone would be a much
            // larger negative number than the segment's own base liters.
            segments = listOf(RouteSegment(distanceMeters = 100.0, staticDurationSeconds = 60.0, elevationDeltaM = -2000.0)),
        )

        val cost = model.cost(route, 7.0)

        assertTrue(cost.fuelLiters >= 0.0)
        assertTrue(cost.totalCost >= 0.0)
    }

    @Test
    fun `mass passed to FuelModel scales the grade term`() {
        val route = Route(
            id = "grade-mass",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(10_000.0, 600.0, elevationDeltaM = 500.0)),
        )
        val light = FuelModel(curve = curve, idleLitersPerHour = 0.8, massKg = 1000.0).cost(route, 7.0)
        val heavy = FuelModel(curve = curve, idleLitersPerHour = 0.8, massKg = 2000.0).cost(route, 7.0)

        assertTrue(heavy.fuelLiters > light.fuelLiters)
    }

    @Test
    fun `missing elevation data costs the route exactly as before (no grade term)`() {
        val withoutGrade = Route(
            id = "no-grade",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 600.0,
            segments = listOf(RouteSegment(10_000.0, 600.0, elevationDeltaM = null)),
        )
        val flat = withoutGrade.copy(segments = listOf(RouteSegment(10_000.0, 600.0, elevationDeltaM = 0.0)))

        assertEquals(model.cost(flat, 7.0).fuelLiters, model.cost(withoutGrade, 7.0).fuelLiters, 1e-9)
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
    fun `ranker orders by total cost when time is ignored`() {
        val cheapShort = slowCheapRoute(id = "cheap").let { model.cost(it, 7.0) }
        val expensiveLong = fastPricierRoute(id = "expensive").let { model.cost(it, 7.0) }

        val ranked = RouteRanker.rank(listOf(expensiveLong, cheapShort), valuePerMinute = 0.0)
        assertEquals("cheap", ranked.first().route.id)
    }

    @Test
    fun `value of time can flip the ranking using the default`() {
        val slowCheap = slowCheapRoute(id = "slow")
        val pricierFast = fastPricierRoute(id = "fast")

        val slowCost = model.cost(slowCheap, 7.0)
        val fastCost = model.cost(pricierFast, 7.0)

        assertEquals("slow", RouteRanker.rank(listOf(slowCost, fastCost), valuePerMinute = 0.0).first().route.id)
        assertEquals("fast", RouteRanker.rank(listOf(slowCost, fastCost)).first().route.id)
    }

    private fun twoSegmentRoute(secondFactor: Double) = Route(
        id = "two",
        distanceMeters = 10_000.0,
        staticDurationSeconds = 600.0,
        durationSeconds = 900.0,
        segments = listOf(
            RouteSegment(
                distanceMeters = 5_000.0,
                staticDurationSeconds = 300.0,
                congestionFactor = 1.0,
                congestion = CongestionLevel.NORMAL,
            ),
            RouteSegment(
                distanceMeters = 5_000.0,
                staticDurationSeconds = 300.0,
                congestionFactor = secondFactor,
                congestion = if (secondFactor < 1.0) CongestionLevel.TRAFFIC_JAM else CongestionLevel.NORMAL,
            ),
        ),
    )

    private fun slowCheapRoute(id: String) = Route(
        id = id,
        distanceMeters = 10_000.0,
        staticDurationSeconds = 2_400.0,
        durationSeconds = 2_400.0,
        segments = listOf(RouteSegment(10_000.0, 2_400.0)),
    )

    private fun fastPricierRoute(id: String) = Route(
        id = id,
        distanceMeters = 30_000.0,
        staticDurationSeconds = 1_200.0,
        durationSeconds = 1_200.0,
        segments = listOf(RouteSegment(30_000.0, 1_200.0)),
    )
}