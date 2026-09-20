package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.domain.model.TrafficResolution
import kotlin.math.max

class FuelModel(
    private val curve: ConsumptionCurve,
    private val idleLitersPerHour: Double,
    private val overrides: FuelModelOverrides = FuelModelOverrides.DEFAULT,
) {

    private val stopGoWeight: Double get() = overrides.effectiveStopGoWeight
    private val jamFactor: Double get() = overrides.effectiveJamFactor

    fun cost(
        route: Route,
        pricePerLiter: Double,
        coldStartLiters: Double = 0.0,
    ): RouteCost {
        val scale = timeScale(route)
        // No congestion data = pure uniform time scaling; the extra delay is already
        // reflected in a lower effective speed, so do not pile a stop-go idle term on top.
        val applyStopGo = route.trafficResolution != TrafficResolution.NONE
        val correction = overrides.effectiveFuelCorrection
        var fuelLiters = coldStartLiters * correction
        val segmentCosts = mutableListOf<SegmentCost>()

        for (segment in route.segments) {
            val distanceKm = segment.distanceMeters / 1000.0
            val t = rawSeconds(segment) * scale
            val effectiveSpeed = if (t > 0.0) distanceKm / (t / 3600.0) else 0.0
            val litersPer100 = curve.litersPer100Km(effectiveSpeed)
            val baseLiters = distanceKm * litersPer100 / 100.0
            val extraHours = max(0.0, (t - segment.staticDurationSeconds) / 3600.0)
            val stopGoLiters = if (applyStopGo) idleLitersPerHour * extraHours * stopGoWeight else 0.0
            val segmentLiters = (baseLiters + stopGoLiters) * correction
            fuelLiters += segmentLiters
            segmentCosts += SegmentCost(
                distanceKm = distanceKm,
                effectiveSpeedKmh = effectiveSpeed,
                congestion = segment.congestion,
                litersPer100Km = litersPer100,
                liters = segmentLiters,
            )
        }

        val toll = route.tollCost ?: 0.0
        val fuelCost = fuelLiters * pricePerLiter
        val durationMinutes = route.durationSeconds / 60.0
        val distanceKm = route.distanceMeters / 1000.0
        val avgSpeed = if (route.durationSeconds > 0.0) distanceKm / (route.durationSeconds / 3600.0) else 0.0

        return RouteCost(
            route = route,
            fuelLiters = fuelLiters,
            fuelCost = fuelCost,
            tollCost = toll,
            totalCost = fuelCost + toll,
            durationMinutes = durationMinutes,
            distanceKm = distanceKm,
            avgSpeedKmh = avgSpeed,
            segments = segmentCosts,
        )
    }

    internal fun normalizedSegmentsSeconds(route: Route): List<Double> {
        val scale = timeScale(route)
        return route.segments.map { rawSeconds(it) * scale }
    }

    private fun timeScale(route: Route): Double {
        val sumRaw = route.segments.sumOf { rawSeconds(it) }
        if (sumRaw <= 0.0) return 1.0
        return route.durationSeconds / sumRaw
    }

    private fun rawSeconds(segment: RouteSegment): Double {
        val factor = segment.congestionFactor.coerceIn(jamFactor, ModelConstants.NORMAL_FACTOR)
        return segment.staticDurationSeconds / factor
    }
}