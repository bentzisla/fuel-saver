package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.SegmentCost
import kotlin.math.max

/**
 * Turns a route (already split into segments with free-flow and traffic times)
 * into a fuel cost using the effective consumption curve.
 */
class FuelModel(
    private val curve: ConsumptionCurve,
    private val idleLitersPerHour: Double,
    private val stopGoWeight: Double = 0.5,
) {

    fun cost(route: Route, pricePerLiter: Double): RouteCost {
        var fuelLiters = 0.0
        val segmentCosts = mutableListOf<SegmentCost>()

        for (segment in route.segments) {
            val distanceKm = segment.distanceMeters / 1000.0
            if (distanceKm <= 0.0) continue

            val freeFlowHours = segment.staticDurationSeconds / 3600.0
            val effectiveSpeed = if (freeFlowHours > 0.0) {
                (distanceKm / freeFlowHours) * segment.congestion.speedFactor
            } else {
                0.0
            }

            val litersPer100 = curve.litersPer100Km(effectiveSpeed)
            val baseLiters = distanceKm * litersPer100 / 100.0

            val trafficHours = (segment.trafficDurationSeconds ?: segment.staticDurationSeconds) / 3600.0
            val extraHours = max(0.0, trafficHours - freeFlowHours)
            val stopGoLiters = idleLitersPerHour * extraHours * stopGoWeight

            val segmentLiters = baseLiters + stopGoLiters
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
}