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
            val timing = model.timingSummary(route)
            assertEquals(
                "route ${route.id}: Σ t_i + stationary delay must equal durationSeconds",
                route.durationSeconds,
                timing.segmentSeconds.sum() + timing.stationarySeconds,
                1e-6,
            )
        }
    }

    @Test
    fun `a step is costed as its congestion sub-segments, each at its own speed`() {
        // 10 km at a free-flow 60 km/h; the second half is jammed (5 km at 15 km/h).
        val route = Route(
            id = "split",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 1_500.0,
            segments = listOf(
                RouteSegment(
                    distanceMeters = 10_000.0,
                    staticDurationSeconds = 600.0,
                    congestionFactor = 0.4,
                    congestion = CongestionLevel.TRAFFIC_JAM,
                    jamMeters = 5_000.0,
                ),
            ),
        )

        val cost = model.cost(route, 7.0)

        val expected = 5.0 * curve.litersPer100Km(60.0) / 100.0 +
            5.0 * curve.litersPer100Km(15.0) / 100.0 +
            0.8 * (900.0 / 3600.0) * ModelConstants.STOP_GO_WEIGHT
        assertEquals(expected, cost.fuelLiters, 1e-9)
        // The step's display speed is the time-equivalent (harmonic) one: 10 km in 25 min.
        assertEquals(24.0, cost.segments.single().effectiveSpeedKmh, 1e-9)
    }

    @Test
    fun `unexplained delay on a highway is idle time, not a slower cheaper highway`() {
        val route = Route(
            id = "highway-delay",
            distanceMeters = 20_000.0,
            staticDurationSeconds = 720.0,
            durationSeconds = 1_020.0,
            trafficResolution = TrafficResolution.PER_SEGMENT,
            segments = listOf(RouteSegment(20_000.0, 720.0)),
        )

        val cost = model.cost(route, 7.0)

        assertEquals(100.0, cost.segments.single().effectiveSpeedKmh, 1e-9)
        val expected = 20.0 * curve.litersPer100Km(100.0) / 100.0 + 0.8 * 300.0 / 3600.0
        assertEquals(expected, cost.fuelLiters, 1e-9)
        assertEquals(300.0, model.timingSummary(route).stationarySeconds, 1e-9)
    }

    @Test
    fun `residual delay goes to the congested step, leaving the highway at free flow`() {
        val route = Route(
            id = "delay-to-jam",
            distanceMeters = 22_000.0,
            staticDurationSeconds = 840.0,
            // Modelled: 720 s highway + 480 s jam = 1200 s; Google says 1320 s.
            durationSeconds = 1_320.0,
            segments = listOf(
                RouteSegment(20_000.0, 720.0),
                RouteSegment(
                    2_000.0,
                    120.0,
                    congestionFactor = 0.25,
                    congestion = CongestionLevel.TRAFFIC_JAM,
                    jamMeters = 2_000.0,
                ),
            ),
        )

        val timing = model.timingSummary(route)
        val cost = model.cost(route, 7.0)

        assertEquals(720.0, timing.segmentSeconds[0], 1e-6)
        assertEquals(600.0, timing.segmentSeconds[1], 1e-6)
        assertEquals(0.0, timing.stationarySeconds, 1e-6)
        assertEquals(100.0, cost.segments[0].effectiveSpeedKmh, 1e-6)
        assertEquals(12.0, cost.segments[1].effectiveSpeedKmh, 1e-6)
    }

    @Test
    fun `a faster Google duration never pushes normal steps above free flow`() {
        val slowStep = RouteSegment(
            10_000.0,
            600.0,
            congestionFactor = ModelConstants.SLOW_FACTOR,
            congestion = CongestionLevel.SLOW,
            slowMeters = 10_000.0,
        )
        val route = Route(
            id = "speed-up",
            distanceMeters = 20_000.0,
            staticDurationSeconds = 1_200.0,
            // Modelled 600 + 1090.9 s; Google says 1500 s: only the slow step speeds up.
            durationSeconds = 1_500.0,
            segments = listOf(RouteSegment(10_000.0, 600.0), slowStep),
        )

        val cost = model.cost(route, 7.0)
        val timing = model.timingSummary(route)

        assertEquals(60.0, cost.segments[0].effectiveSpeedKmh, 1e-9)
        assertEquals(900.0, timing.segmentSeconds[1], 1e-6)

        // Google even faster than free flow everywhere: everything stays at free flow.
        val tooFast = route.copy(durationSeconds = 1_000.0)
        val fastCost = model.cost(tooFast, 7.0)
        assertTrue(fastCost.segments.all { it.effectiveSpeedKmh <= 60.0 + 1e-9 })
        assertEquals(20.0 * curve.litersPer100Km(60.0) / 100.0, fastCost.fuelLiters, 1e-9)
    }

    @Test
    fun `slow and jam overrides apply to the partial lengths inside a step`() {
        // No Google duration to normalize to, so the raw per-level times are used as-is.
        val route = Route(
            id = "partial-override",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 0.0,
            segments = listOf(
                RouteSegment(
                    distanceMeters = 10_000.0,
                    staticDurationSeconds = 600.0,
                    congestion = CongestionLevel.NORMAL,
                    slowMeters = 3_000.0,
                    jamMeters = 1_000.0,
                ),
            ),
        )
        val overridden = FuelModel(
            curve = curve,
            idleLitersPerHour = 0.8,
            overrides = FuelModelOverrides(slowFactor = 0.8, jamFactor = 0.5),
        )

        val defaultSeconds = model.timingSummary(route).segmentSeconds.single()
        val overriddenSeconds = overridden.timingSummary(route).segmentSeconds.single()

        assertEquals(360.0 + 180.0 / ModelConstants.SLOW_FACTOR + 60.0 / ModelConstants.JAM_FACTOR, defaultSeconds, 1e-6)
        assertEquals(360.0 + 180.0 / 0.8 + 60.0 / 0.5, overriddenSeconds, 1e-6)
    }

    @Test
    fun `congested segment costs more fuel than normal for equal distance`() {
        val allNormal = model.cost(twoSegmentRoute(secondFactor = 1.0), 7.0)
        val secondJam = model.cost(twoSegmentRoute(secondFactor = 0.25), 7.0)

        assertTrue(secondJam.segments[1].liters > allNormal.segments[1].liters)
    }

    @Test
    fun `none resolution charges the unexplained delay as idle time at free flow`() {
        val route = Route(
            id = "none",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 1_200.0,
            trafficResolution = TrafficResolution.NONE,
            segments = listOf(RouteSegment(10_000.0, 600.0, congestionFactor = 1.0)),
        )

        val cost = model.cost(route, 7.0)
        // No evidence of where the traffic is: drive at free flow, idle for the extra 600 s.
        val expected = 10.0 * curve.litersPer100Km(60.0) / 100.0 + 0.8 * 600.0 / 3600.0

        assertEquals(30.0, cost.avgSpeedKmh, 1e-6)
        assertEquals(60.0, cost.segments.single().effectiveSpeedKmh, 1e-6)
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
        // Without a Google duration to normalize to, the override alone sets the slow speed
        // (with one, normalization pins the total time and the override only redistributes it).
        val route = Route(
            id = "slow-override",
            distanceMeters = 10_000.0,
            staticDurationSeconds = 600.0,
            durationSeconds = 0.0,
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