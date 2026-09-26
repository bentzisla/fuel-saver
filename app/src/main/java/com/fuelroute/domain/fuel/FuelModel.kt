package com.fuelroute.domain.fuel

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteCost
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.SegmentCost
import com.fuelroute.domain.model.TrafficResolution
import kotlin.math.max
import kotlin.math.min

class FuelModel(
    private val curve: ConsumptionCurve,
    private val idleLitersPerHour: Double,
    private val overrides: FuelModelOverrides = FuelModelOverrides.DEFAULT,
    /** Vehicle curb weight for the grade term ([GradeModel]); see [VehicleProfile.massKg]. */
    private val massKg: Double = GradeModel.DEFAULT_VEHICLE_MASS_KG,
    /** Fuel energy density for the grade term; pass [GradeModel.DIESEL_MJ_PER_L] for diesel. */
    private val energyDensityMjPerL: Double = GradeModel.GASOLINE_MJ_PER_L,
) {

    private val stopGoWeight: Double get() = overrides.effectiveStopGoWeight

    /**
     * Tightest lower clamp for a segment's speed factor. `jamFactor` is no longer overloaded as
     * the sole clamp: the smaller of the configured slow/jam factors wins, so lowering either
     * slows every congested segment.
     */
    private val congestionFloor: Double get() = min(overrides.effectiveSlowFactor, overrides.effectiveJamFactor)

    fun cost(
        route: Route,
        pricePerLiter: Double,
        coldStartLiters: Double = 0.0,
    ): RouteCost {
        val rate = pricePerLiter.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val correction = overrides.effectiveFuelCorrection.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        val coldStart = coldStartLiters.takeIf { it.isFinite() && it > 0.0 } ?: 0.0

        val routeDistanceKm = (route.distanceMeters / 1000.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val routeDurationSeconds = route.durationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val routeAvgSpeedKmh = if (routeDurationSeconds > 0.0) {
            routeDistanceKm / (routeDurationSeconds / 3600.0)
        } else {
            0.0
        }
        val scale = timeScale(route)
        // No congestion data = pure uniform time scaling; the extra delay is already
        // reflected in a lower effective speed, so do not pile a stop-go idle term on top.
        val applyStopGo = route.trafficResolution != TrafficResolution.NONE
        var fuelLiters = coldStart * correction
        val segmentCosts = mutableListOf<SegmentCost>()

        for (segment in route.segments) {
            val distanceKm = (segment.distanceMeters / 1000.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val staticSeconds = segment.staticDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            val t = rawSeconds(segment) * scale
            val effectiveSpeed = when {
                distanceKm <= 0.0 -> 0.0
                t > 0.0 -> distanceKm / (t / 3600.0)
                // Missing/zero staticDuration: use the route's average speed instead of 0, which
                // would charge the whole step at the curve's crawl rate.
                routeAvgSpeedKmh > 0.0 -> routeAvgSpeedKmh
                else -> 0.0
            }
            val litersPer100 = curve.litersPer100Km(effectiveSpeed)
            val baseLiters = distanceKm * litersPer100 / 100.0
            val stopGoLiters = if (applyStopGo && staticSeconds > 0.0) {
                val extraHours = max(0.0, (t - staticSeconds) / 3600.0)
                idleLitersPerHour * extraHours * stopGoWeight
            } else {
                0.0
            }
            val gradeLiters = segment.elevationDeltaM?.let {
                GradeModel.extraLiters(it, massKg, energyDensityMjPerL)
            } ?: 0.0
            // A descent's grade credit may exceed this segment's own base+stop-go liters, but it
            // must never make the *segment* cheaper than free (PLAN.md §4.4 / GradeModel).
            val segmentLiters = max(0.0, baseLiters + stopGoLiters + gradeLiters) * correction
            fuelLiters += segmentLiters
            segmentCosts += SegmentCost(
                distanceKm = distanceKm,
                effectiveSpeedKmh = effectiveSpeed,
                congestion = segment.congestion,
                litersPer100Km = litersPer100,
                liters = segmentLiters,
            )
        }

        val toll = (route.tollCost ?: 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val fuelCost = fuelLiters * rate
        val durationMinutes = routeDurationSeconds / 60.0
        val avgSpeed = routeAvgSpeedKmh

        return RouteCost(
            route = route,
            fuelLiters = fuelLiters,
            fuelCost = fuelCost,
            tollCost = toll,
            totalCost = fuelCost + toll,
            durationMinutes = durationMinutes,
            distanceKm = routeDistanceKm,
            avgSpeedKmh = avgSpeed,
            segments = segmentCosts,
        )
    }

    internal fun normalizedSegmentsSeconds(route: Route): List<Double> {
        val scale = timeScale(route)
        return route.segments.map { rawSeconds(it) * scale }
    }

    private fun timeScale(route: Route): Double {
        if (!route.durationSeconds.isFinite() || route.durationSeconds <= 0.0) return 1.0
        val sumRaw = route.segments.sumOf { rawSeconds(it) }
        if (!sumRaw.isFinite() || sumRaw <= 0.0) return 1.0
        return route.durationSeconds / sumRaw
    }

    private fun rawSeconds(segment: RouteSegment): Double {
        val staticSeconds = segment.staticDurationSeconds.takeIf { it.isFinite() && it > 0.0 } ?: return 0.0
        val factor = effectiveCongestionFactor(segment)
        return staticSeconds / factor
    }

    /**
     * Maps the mapper's length-weighted [RouteSegment.congestionFactor] onto the configured
     * slow/jam severities while preserving how mixed the segment is.
     *
     * The mapper builds its factor from [ModelConstants] level factors, so an override would
     * otherwise be ignored. Scaling the factor's deficit from `NORMAL_FACTOR` by the ratio of the
     * override deficit to the default deficit for the segment's dominant level keeps a
     * partially-congested segment partially congested, and turns a purely-SLOW segment into
     * exactly [FuelModelOverrides.effectiveSlowFactor] (and likewise for jam). The result is
     * clamped to `[min(slow, jam), NORMAL_FACTOR]`.
     */
    private fun effectiveCongestionFactor(segment: RouteSegment): Double {
        val factor = segment.congestionFactor
            .takeIf { it.isFinite() }
            ?.coerceIn(0.0, ModelConstants.NORMAL_FACTOR)
            ?: ModelConstants.NORMAL_FACTOR
        val (defaultLevel, overrideLevel) = when (segment.congestion) {
            CongestionLevel.SLOW ->
                ModelConstants.SLOW_FACTOR to overrides.effectiveSlowFactor
            CongestionLevel.TRAFFIC_JAM ->
                ModelConstants.JAM_FACTOR to overrides.effectiveJamFactor
            CongestionLevel.NORMAL ->
                ModelConstants.NORMAL_FACTOR to ModelConstants.NORMAL_FACTOR
        }
        val defaultDeficit = ModelConstants.NORMAL_FACTOR - defaultLevel
        if (defaultDeficit <= 0.0) return factor.coerceIn(congestionFloor, ModelConstants.NORMAL_FACTOR)
        val scale = (ModelConstants.NORMAL_FACTOR - overrideLevel) / defaultDeficit
        if (!scale.isFinite()) return factor.coerceIn(congestionFloor, ModelConstants.NORMAL_FACTOR)
        val scaled = ModelConstants.NORMAL_FACTOR - (ModelConstants.NORMAL_FACTOR - factor) * scale
        return scaled.coerceIn(congestionFloor, ModelConstants.NORMAL_FACTOR)
    }
}