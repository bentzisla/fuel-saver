package com.fuelroute.domain.ranking

import com.fuelroute.domain.fuel.CurveBlender
import com.fuelroute.domain.fuel.DefaultCurve
import com.fuelroute.domain.learning.LearnedCurve
import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.domain.model.SpeedBinStats
import com.fuelroute.domain.model.speedToBinIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class RouteConfidenceTest {

    private val fallback = DefaultCurve.forVehicle(7.0)

    /** 80 km measured around 60 km/h only, at a plausible 6.5 L/100km. */
    private val learned = LearnedCurve(
        listOf(55.0, 60.0, 65.0).map { speed ->
            SpeedBinStats(
                vehicleId = "car",
                binIndex = speedToBinIndex(speed),
                distanceKm = 80.0,
                fuelL = 80.0 * 6.5 / 100.0,
                seconds = 80.0 / speed * 3600.0,
                samples = 5_000,
            )
        },
    )

    @Test
    fun `no learned data means zero share and low confidence`() {
        val cost = cost(10.0 to 60.0)

        assertEquals(0.0, RouteConfidence.learnedShare(cost, null, fallback), 0.0)
        assertEquals(RouteConfidenceLevel.LOW, RouteConfidence.level(0.0))
    }

    @Test
    fun `share is distance weighted over the route's speed mix`() {
        // 30 km in the measured band, 20 km at 120 km/h where nothing was measured.
        val cost = cost(30.0 to 60.0, 20.0 to 120.0)
        val w60 = CurveBlender.weightAt(learned, fallback, 60.0)

        val share = RouteConfidence.learnedShare(cost, learned, fallback)

        assertEquals(0.0, CurveBlender.weightAt(learned, fallback, 120.0), 0.0)
        assertEquals(30.0 * w60 / 50.0, share, 1e-9)
        assertEquals(RouteConfidenceLevel.MEDIUM, RouteConfidence.level(share))
    }

    @Test
    fun `a route entirely in the measured band has high confidence`() {
        val share = RouteConfidence.learnedShare(cost(20.0 to 60.0), learned, fallback)

        assertEquals(RouteConfidenceLevel.HIGH, RouteConfidence.level(share))
    }

    private fun cost(vararg segments: Pair<Double, Double>) = RouteCost(
        route = Route(id = "r", distanceMeters = 1.0, staticDurationSeconds = 1.0, durationSeconds = 1.0, segments = emptyList()),
        fuelLiters = 1.0,
        fuelCost = 7.0,
        tollCost = 0.0,
        totalCost = 7.0,
        durationMinutes = 1.0,
        distanceKm = segments.sumOf { it.first },
        avgSpeedKmh = 60.0,
        segments = segments.map { (km, speed) ->
            SegmentCost(
                distanceKm = km,
                effectiveSpeedKmh = speed,
                congestion = CongestionLevel.NORMAL,
                litersPer100Km = 6.5,
                liters = km * 0.065,
            )
        },
    )
}
