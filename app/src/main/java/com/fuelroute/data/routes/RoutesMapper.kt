package com.fuelroute.data.routes

import com.fuelroute.domain.fuel.CongestionInterval
import com.fuelroute.domain.fuel.CongestionModel
import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment
import com.fuelroute.domain.model.TrafficResolution

object RoutesMapper {

    fun toDomain(response: ComputeRoutesResponse): List<Route> =
        response.routes.mapIndexed { index, dto -> toRoute(dto, index) }

    private fun toRoute(dto: RouteDto, index: Int): Route {
        val durationSec = dto.duration.parseDurationSeconds()
        val staticSec = dto.staticDuration?.parseDurationSeconds() ?: durationSec

        // Route-level traffic is the fallback for legs that carry no intervals of their own.
        // Its polyline spans every leg, so it is scaled to the summed step distance of the route.
        val routeStepDistance = dto.legs.sumOf { leg -> leg.steps.sumOf { it.distanceMeters }.toDouble() }
        val routeIntervals = congestionIntervals(
            dto.travelAdvisory?.speedReadingIntervals.orEmpty(),
            dto.polyline?.encodedPolyline,
            routeStepDistance,
        )

        var usedPerSegment = false
        var usedRouteAverage = false
        var routeCursor = 0.0
        val segments = mutableListOf<RouteSegment>()

        for (leg in dto.legs) {
            val legDistance = leg.steps.sumOf { it.distanceMeters }.toDouble()
            val legIntervals = congestionIntervals(
                leg.travelAdvisory?.speedReadingIntervals.orEmpty(),
                leg.polyline?.encodedPolyline,
                legDistance,
            )
            val useRouteLevel = legIntervals.isEmpty() && routeIntervals.isNotEmpty()
            val intervals = when {
                legIntervals.isNotEmpty() -> {
                    usedPerSegment = true
                    legIntervals
                }
                useRouteLevel -> {
                    usedRouteAverage = true
                    routeIntervals
                }
                else -> emptyList()
            }

            // Fallback static duration for steps that omit it: the leg's own staticDuration,
            // else its traffic-inclusive duration, else the route-level static fallback. It is
            // shared across the leg's steps in proportion to their distance.
            val legStaticSec = leg.staticDuration?.parseDurationSeconds()?.takeIf { it > 0.0 }
                ?: leg.duration.parseDurationSeconds().takeIf { it > 0.0 }
                ?: staticSec

            // Per-leg intervals index into the leg polyline (cursor from 0); route-level
            // intervals index into the route polyline, so the cursor keeps advancing across legs.
            var cursor = if (useRouteLevel) routeCursor else 0.0
            for (step in leg.steps) {
                val end = cursor + step.distanceMeters
                // Per-level metres let FuelModel cost SLOW/JAM stretches inside the step as their
                // own sub-segments (and apply the debug overrides to them); the harmonic factor is
                // the step's time-equivalent summary for consumers that want a single number.
                val lengths = if (intervals.isEmpty()) {
                    null
                } else {
                    CongestionModel.levelLengths(intervals, cursor, end)
                }
                val factor = lengths?.let { CongestionModel.harmonicFactor(it) } ?: 1.0
                val level = if (intervals.isEmpty()) {
                    CongestionLevel.NORMAL
                } else {
                    CongestionModel.dominantLevel(intervals, cursor, end)
                }
                // `staticDuration` absent/0 with real distance: distribute the leg's static time
                // by distance. Charging 0 here would make FuelModel fall through to speed 0 and
                // bill the whole step at the curve's crawl rate.
                val stepStaticSec = step.staticDuration?.parseDurationSeconds()?.takeIf { it > 0.0 }
                    ?: if (step.distanceMeters > 0 && legDistance > 0.0) {
                        legStaticSec * (step.distanceMeters / legDistance)
                    } else {
                        0.0
                    }
                segments += RouteSegment(
                    distanceMeters = step.distanceMeters.toDouble(),
                    staticDurationSeconds = stepStaticSec,
                    congestionFactor = factor,
                    congestion = level,
                    slowMeters = lengths?.slowM ?: 0.0,
                    jamMeters = lengths?.jamM ?: 0.0,
                )
                cursor = end
            }
            routeCursor += leg.steps.sumOf { it.distanceMeters }
        }

        // `TOLLS` is always requested and the field mask asks for the parent
        // `travelAdvisory.tollInfo` at route and leg level. Route-level tollInfo is the route
        // total; when Google only fills the per-leg advisories, sum the leg estimates.
        // Absence everywhere means "no toll", while present-but-unpriced means tolls exist but
        // the amount is unknown.
        //
        // A route-level tollInfo with an empty price must NOT shadow priced leg tolls: Google can
        // return the route-level advisory unpriced while still pricing each leg. Only call the
        // toll unknown when neither the route nor any leg carries a price.
        val routeTollInfo = dto.travelAdvisory?.tollInfo
        val legTollInfos = dto.legs.mapNotNull { it.travelAdvisory?.tollInfo }
        val routeTollPrice = routeTollInfo?.firstPrice()
        val legTollPrice = legTollInfos
            .mapNotNull { it.firstPrice() }
            .takeIf { it.isNotEmpty() }
            ?.sum()
        val toll: Double? = when {
            routeTollPrice != null -> routeTollPrice
            legTollPrice != null -> legTollPrice
            routeTollInfo != null || legTollInfos.isNotEmpty() -> null
            else -> 0.0
        }

        return Route(
            id = "route-$index",
            routeLabels = dto.routeLabels,
            distanceMeters = dto.distanceMeters.toDouble(),
            staticDurationSeconds = staticSec,
            durationSeconds = durationSec,
            segments = segments,
            tollCost = toll,
            tollUnknown = toll == null,
            trafficResolution = when {
                usedPerSegment -> TrafficResolution.PER_SEGMENT
                usedRouteAverage -> TrafficResolution.ROUTE_AVERAGE
                else -> TrafficResolution.NONE
            },
            encodedPolyline = dto.polyline?.encodedPolyline,
        )
    }

    /** First `estimatedPrice` as a decimal amount, or null when the toll exists but is unpriced. */
    private fun TollInfoDto.firstPrice(): Double? =
        estimatedPrice.firstOrNull()?.let { it.units + it.nanos / 1e9 }

    /**
     * Converts polyline-index intervals into metre ranges along the steps.
     *
     * Interval positions come from the haversine length of the decoded polyline, but steps are
     * laid out by Google's `distanceMeters`; the two differ by 1-2%, which drifts intervals off
     * their steps towards the end of a long leg. Cumulative polyline distances are therefore
     * scaled so the polyline's total equals [targetLengthM] (the summed step distance).
     */
    private fun congestionIntervals(
        raw: List<SpeedReadingIntervalDto>,
        encodedPolyline: String?,
        targetLengthM: Double,
    ): List<CongestionInterval> {
        if (raw.isEmpty()) return emptyList()

        val points = PolylineDecoder.decode(encodedPolyline)
        if (points.size < 2) return emptyList()

        val cumulative = DoubleArray(points.size)
        for (i in 1 until points.size) {
            cumulative[i] = cumulative[i - 1] + PolylineDecoder.distanceMeters(points[i - 1], points[i])
        }
        val total = cumulative.last()
        if (total <= 0.0) return emptyList()
        val scale = if (targetLengthM.isFinite() && targetLengthM > 0.0) targetLengthM / total else 1.0

        return raw.mapNotNull { interval ->
            val level = interval.speed.toCongestionLevel() ?: return@mapNotNull null
            val start = cumulative[interval.startPolylinePointIndex.coerceIn(0, points.size - 1)] * scale
            val end = cumulative[interval.endPolylinePointIndex.coerceIn(0, points.size - 1)] * scale
            if (end <= start) return@mapNotNull null
            CongestionInterval(start, end, level)
        }
    }

    private fun String.toCongestionLevel(): CongestionLevel? = when (this) {
        "NORMAL" -> CongestionLevel.NORMAL
        "SLOW" -> CongestionLevel.SLOW
        "TRAFFIC_JAM" -> CongestionLevel.TRAFFIC_JAM
        else -> null
    }
}

/** Google durations are seconds with an `s` suffix, e.g. `1500s`. Malformed input throws. */
internal fun String.parseDurationSeconds(): Double {
    val trimmed = trim()
    if (!trimmed.endsWith("s") || trimmed.length <= 1) {
        throw RoutesParseException("Malformed duration: \"$this\"")
    }
    val value = trimmed.dropLast(1).toDoubleOrNull()
        ?: throw RoutesParseException("Malformed duration: \"$this\"")
    if (!value.isFinite() || value < 0.0) {
        throw RoutesParseException("Malformed duration: \"$this\"")
    }
    return value
}
