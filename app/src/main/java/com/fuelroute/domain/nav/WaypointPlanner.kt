package com.fuelroute.domain.nav

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lng: Double)

/** Pure polyline geometry (no Android types). Distances are metres on a local flat projection. */
object RouteGeometry {

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val (x, y) = project(b, a)
        return hypot(x, y)
    }

    /** Distance from [p] to the closest point on the polyline [line]; +infinity when empty. */
    fun distanceToPolylineM(p: GeoPoint, line: List<GeoPoint>): Double {
        if (line.isEmpty()) return Double.POSITIVE_INFINITY
        if (line.size == 1) return distanceM(p, line[0])
        var best = Double.POSITIVE_INFINITY
        for (i in 0 until line.size - 1) {
            best = min(best, pointToSegmentM(p, line[i], line[i + 1]))
            if (best == 0.0) break
        }
        return best
    }

    /** Points spaced roughly [stepM] apart along [line], always including both ends. */
    fun resample(line: List<GeoPoint>, stepM: Double): List<GeoPoint> = resampleWithPosition(line, stepM).map { it.first }

    /** Like [resample], with each point's distance from the start of the line. */
    fun resampleWithPosition(line: List<GeoPoint>, stepM: Double): List<Pair<GeoPoint, Double>> {
        if (line.isEmpty()) return emptyList()
        val out = mutableListOf(line[0] to 0.0)
        var travelled = 0.0
        var nextAt = stepM
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val b = line[i]
            val seg = distanceM(a, b)
            while (seg > 0.0 && nextAt <= travelled + seg) {
                val t = (nextAt - travelled) / seg
                out += GeoPoint(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t) to nextAt
                nextAt += stepM
            }
            travelled += seg
        }
        if (travelled > out.last().second) out += line.last() to travelled
        return out
    }

    /** Fraction of [a] (sampled every [stepM]) lying within [toleranceM] of [b]. */
    fun coverage(a: List<GeoPoint>, b: List<GeoPoint>, toleranceM: Double, stepM: Double = 100.0): Double {
        val samples = resample(a, stepM)
        if (samples.isEmpty()) return 0.0
        return samples.count { distanceToPolylineM(it, b) <= toleranceM }.toDouble() / samples.size
    }

    private fun pointToSegmentM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val (ax, ay) = project(a, p)
        val (bx, by) = project(b, p)
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(ax, ay)
        val t = max(0.0, min(1.0, -(ax * dx + ay * dy) / len2))
        return sqrt((ax + t * dx) * (ax + t * dx) + (ay + t * dy) * (ay + t * dy))
    }

    /** [point] in metres east/north of [origin]. */
    private fun project(point: GeoPoint, origin: GeoPoint): Pair<Double, Double> {
        val latRad = origin.lat * PI / 180.0
        val x = (point.lng - origin.lng) * PI / 180.0 * cos(latRad) * EARTH_RADIUS_M
        val y = (point.lat - origin.lat) * PI / 180.0 * EARTH_RADIUS_M
        return x to y
    }
}

/**
 * How to hand a chosen route to Google Maps with the fewest stops.
 *
 * @property waypoints intermediate points (usually 0-2) that make Maps follow the chosen route.
 * @property exact true when the route Google computes through [waypoints] was verified to match
 *   the chosen one; false when this is only the best approximation found.
 */
data class NavPlan(val waypoints: List<GeoPoint>, val exact: Boolean)

/**
 * Finds the smallest set of waypoints that reproduces a chosen route.
 *
 * Google Maps' URL scheme cannot take a route, only stops; every stop is something the driver
 * sees. So instead of sprinkling evenly spaced points, this starts from the route Maps would
 * pick anyway ([default]) and adds only the point where the chosen route departs furthest from
 * it, then asks [routeVia] what Google would compute through that point. It repeats (up to
 * [maxWaypoints]) until the computed route matches the chosen one.
 */
class WaypointPlanner(
    private val maxWaypoints: Int = 3,
    private val toleranceM: Double = 60.0,
    private val matchThreshold: Double = 0.92,
    private val minDeviationM: Double = 250.0,
    private val endMarginM: Double = 500.0,
    private val stepM: Double = 100.0,
) {

    /**
     * @param routeVia returns the polyline Google computes from the same origin to the same
     *   destination through the given waypoints, or null when it could not be fetched.
     */
    suspend fun plan(
        chosen: List<GeoPoint>,
        default: List<GeoPoint>,
        routeVia: suspend (List<GeoPoint>) -> List<GeoPoint>?,
    ): NavPlan {
        if (chosen.size < 2) return NavPlan(emptyList(), exact = true)
        if (matches(chosen, default)) return NavPlan(emptyList(), exact = true)

        val samples = RouteGeometry.resampleWithPosition(chosen, stepM)
        val totalM = samples.last().second
        val picked = mutableListOf<Pair<GeoPoint, Double>>()
        var reference = default

        repeat(maxWaypoints) {
            val next = farthest(samples, totalM, reference, picked)
                ?: return NavPlan(picked.map { it.first }, exact = false)
            picked += next
            picked.sortBy { it.second }
            val waypoints = picked.map { it.first }
            val via = routeVia(waypoints) ?: return NavPlan(waypoints, exact = false)
            if (matches(chosen, via)) return NavPlan(waypoints, exact = true)
            reference = via
        }
        return NavPlan(picked.map { it.first }, exact = false)
    }

    private fun matches(a: List<GeoPoint>, b: List<GeoPoint>): Boolean =
        RouteGeometry.coverage(a, b, toleranceM, stepM) >= matchThreshold &&
            RouteGeometry.coverage(b, a, toleranceM, stepM) >= matchThreshold

    private fun farthest(
        samples: List<Pair<GeoPoint, Double>>,
        totalM: Double,
        reference: List<GeoPoint>,
        picked: List<Pair<GeoPoint, Double>>,
    ): Pair<GeoPoint, Double>? {
        var best: Pair<GeoPoint, Double>? = null
        var bestDeviation = minDeviationM
        for (candidate in samples) {
            val (point, pos) = candidate
            if (pos < endMarginM || pos > totalM - endMarginM) continue
            if (picked.any { kotlin.math.abs(it.second - pos) < endMarginM }) continue
            val deviation = RouteGeometry.distanceToPolylineM(point, reference)
            if (deviation > bestDeviation) {
                bestDeviation = deviation
                best = candidate
            }
        }
        return best
    }
}
