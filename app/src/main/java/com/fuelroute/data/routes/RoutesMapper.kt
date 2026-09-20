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
        val routeIntervals = congestionIntervals(
            dto.travelAdvisory?.speedReadingIntervals.orEmpty(),
            dto.polyline?.encodedPolyline,
        )

        var usedPerSegment = false
        var usedRouteAverage = false
        var routeCursor = 0.0
        val segments = mutableListOf<RouteSegment>()

        for (leg in dto.legs) {
            val legIntervals = congestionIntervals(
                leg.travelAdvisory?.speedReadingIntervals.orEmpty(),
                leg.polyline?.encodedPolyline,
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

            // Per-leg intervals index into the leg polyline (cursor from 0); route-level
            // intervals index into the route polyline, so the cursor keeps advancing across legs.
            var cursor = if (useRouteLevel) routeCursor else 0.0
            for (step in leg.steps) {
                val end = cursor + step.distanceMeters
                val factor = if (intervals.isEmpty()) {
                    1.0
                } else {
                    CongestionModel.weightedSpeedFactor(intervals, cursor, end)
                }
                val level = if (intervals.isEmpty()) {
                    CongestionLevel.NORMAL
                } else {
                    CongestionModel.dominantLevel(intervals, cursor, end)
                }
                segments += RouteSegment(
                    distanceMeters = step.distanceMeters.toDouble(),
                    staticDurationSeconds = step.staticDuration?.parseDurationSeconds() ?: 0.0,
                    congestionFactor = factor,
                    congestion = level,
                )
                cursor = end
            }
            routeCursor += leg.steps.sumOf { it.distanceMeters }
        }

        val prices = dto.travelAdvisory?.tollInfo?.estimatedPrice
        val toll = prices?.firstOrNull()?.let { it.units + it.nanos / 1e9 }

        return Route(
            id = "route-$index",
            routeLabels = dto.routeLabels,
            distanceMeters = dto.distanceMeters.toDouble(),
            staticDurationSeconds = staticSec,
            durationSeconds = durationSec,
            segments = segments,
            tollCost = toll,
            tollUnknown = prices.isNullOrEmpty(),
            trafficResolution = when {
                usedPerSegment -> TrafficResolution.PER_SEGMENT
                usedRouteAverage -> TrafficResolution.ROUTE_AVERAGE
                else -> TrafficResolution.NONE
            },
            encodedPolyline = dto.polyline?.encodedPolyline,
        )
    }

    private fun congestionIntervals(
        raw: List<SpeedReadingIntervalDto>,
        encodedPolyline: String?,
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

        return raw.mapNotNull { interval ->
            val level = interval.speed.toCongestionLevel() ?: return@mapNotNull null
            val start = cumulative[interval.startPolylinePointIndex.coerceIn(0, points.size - 1)]
            val end = cumulative[interval.endPolylinePointIndex.coerceIn(0, points.size - 1)]
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
