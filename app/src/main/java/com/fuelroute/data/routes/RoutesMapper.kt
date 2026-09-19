package com.fuelroute.data.routes

import com.fuelroute.domain.model.CongestionLevel
import com.fuelroute.domain.model.Route
import com.fuelroute.domain.model.RouteSegment

object RoutesMapper {

    fun toDomain(response: ComputeRoutesResponse): List<Route> =
        response.routes.mapIndexed { index, dto -> toRoute(dto, index) }

    private fun toRoute(dto: RouteDto, index: Int): Route {
        val durationSec = dto.duration.parseDurationSeconds()
        val staticSec = dto.staticDuration?.parseDurationSeconds() ?: durationSec
        val segments = dto.legs.flatMap { leg -> legToSegments(leg, durationSec, staticSec) }
        val toll = dto.travelAdvisory
            ?.tollInfo
            ?.estimatedPrice
            ?.firstOrNull()
            ?.let { it.units + it.nanos / 1e9 }

        return Route(
            id = "route-$index",
            routeLabels = dto.routeLabels,
            distanceMeters = dto.distanceMeters,
            staticDurationSeconds = staticSec,
            durationSeconds = durationSec,
            segments = segments,
            tollCost = toll,
            encodedPolyline = dto.polyline?.encodedPolyline,
        )
    }

    private fun legToSegments(leg: LegDto, routeDurationSec: Double, routeStaticSec: Double): List<RouteSegment> {
        val intervals = congestionIntervals(leg)
        val trafficScale = if (routeStaticSec > 0.0) routeDurationSec / routeStaticSec else 1.0

        val segments = mutableListOf<RouteSegment>()
        var cursor = 0.0
        for (step in leg.steps) {
            val staticSec = step.staticDuration?.parseDurationSeconds() ?: 0.0
            val end = cursor + step.distanceMeters
            segments += RouteSegment(
                distanceMeters = step.distanceMeters,
                staticDurationSeconds = staticSec,
                trafficDurationSeconds = staticSec * trafficScale,
                congestion = dominantCongestion(intervals, cursor, end, trafficScale),
            )
            cursor = end
        }
        return segments
    }

    private data class Interval(val startM: Double, val endM: Double, val level: CongestionLevel)

    private fun congestionIntervals(leg: LegDto): List<Interval> {
        val raw = leg.travelAdvisory?.speedReadingIntervals.orEmpty()
        if (raw.isEmpty()) return emptyList()

        val points = PolylineDecoder.decode(leg.polyline?.encodedPolyline)
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
            Interval(start, end, level)
        }
    }

    private fun dominantCongestion(
        intervals: List<Interval>,
        start: Double,
        end: Double,
        trafficScale: Double,
    ): CongestionLevel {
        if (intervals.isEmpty() || end <= start) {
            return fallbackForTraffic(trafficScale)
        }

        val overlap = HashMap<CongestionLevel, Double>()
        for (interval in intervals) {
            val s = maxOf(start, interval.startM)
            val e = minOf(end, interval.endM)
            if (e > s) {
                overlap[interval.level] = (overlap[interval.level] ?: 0.0) + (e - s)
            }
        }
        return overlap.maxByOrNull { it.value }?.key ?: fallbackForTraffic(trafficScale)
    }

    private fun fallbackForTraffic(trafficScale: Double): CongestionLevel = when {
        trafficScale > 1.3 -> CongestionLevel.TRAFFIC_JAM
        trafficScale > 1.1 -> CongestionLevel.SLOW
        else -> CongestionLevel.NORMAL
    }

    private fun String.toCongestionLevel(): CongestionLevel? = when (this) {
        "NORMAL" -> CongestionLevel.NORMAL
        "SLOW" -> CongestionLevel.SLOW
        "TRAFFIC_JAM" -> CongestionLevel.TRAFFIC_JAM
        else -> null
    }

    private fun String.parseDurationSeconds(): Double =
        trimEnd('s').toDoubleOrNull() ?: 0.0
}