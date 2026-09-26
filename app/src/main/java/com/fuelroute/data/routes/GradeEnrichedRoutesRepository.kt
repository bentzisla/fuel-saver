package com.fuelroute.data.routes

import android.util.Log
import com.fuelroute.domain.model.Route
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decorates [RoutesRepository] with per-segment net elevation changes from [ElevationRepository],
 * so [com.fuelroute.domain.fuel.FuelModel] can charge a route's climb (and partially credit its
 * descent) instead of pricing an uphill drive as if it were flat (PLAN.md §4.4; bug report:
 * Beit Shemesh -> Jerusalem, a ~500m net climb, priced under 10 NIS).
 *
 * Failure is always graceful: when a route has no polyline, the Elevation API call fails or is
 * denied (e.g. `REQUEST_DENIED` - the API is not enabled on the key), or there is no network,
 * that route is returned exactly as [delegate] produced it - [Route.gradeDataMissing] stays
 * true, [ElevationRepository] logs the failure once, and the UI shows a hint that the climb was
 * not included rather than silently under-costing the route.
 */
@Singleton
class GradeEnrichedRoutesRepository @Inject constructor(
    private val delegate: CachingRoutesRepository,
    private val elevationRepository: ElevationRepository,
) : RoutesRepository {

    override suspend fun getAlternatives(
        origin: RouteWaypoint,
        destination: RouteWaypoint,
        options: RouteRequestOptions,
        forceRefresh: Boolean,
    ): List<Route> {
        val routes = delegate.getAlternatives(origin, destination, options, forceRefresh)
        return routes.map { enrich(it) }
    }

    private suspend fun enrich(route: Route): Route {
        val polyline = route.encodedPolyline
        if (polyline.isNullOrBlank() || route.segments.isEmpty()) return route

        val profile = try {
            elevationRepository.profile(polyline)
        } catch (e: Throwable) {
            // ElevationRepository already degrades network/API errors to null; this only guards
            // against an unexpected exception so a route search can never fail because of it.
            Log.w("FuelRoute", "unexpected elevation enrichment failure: ${e.javaClass.simpleName}")
            null
        }
        if (profile == null || profile.size < 2) return route

        val totalDistanceMeters = route.segments.sumOf { it.distanceMeters }
        if (totalDistanceMeters <= 0.0) return route

        var cursor = 0.0
        val segments = route.segments.map { segment ->
            val start = cursor
            val end = cursor + segment.distanceMeters
            cursor = end
            val delta = elevationAt(profile, totalDistanceMeters, end) -
                elevationAt(profile, totalDistanceMeters, start)
            segment.copy(elevationDeltaM = delta)
        }
        return route.copy(segments = segments, gradeDataMissing = false)
    }

    /**
     * Linear interpolation of [profile] (evenly spaced by distance along the whole route) at
     * [distanceMeters] into the [totalDistanceMeters]-long route.
     */
    private fun elevationAt(profile: List<Double>, totalDistanceMeters: Double, distanceMeters: Double): Double {
        val t = (distanceMeters / totalDistanceMeters).coerceIn(0.0, 1.0)
        val pos = t * (profile.size - 1)
        val lo = pos.toInt().coerceIn(0, profile.size - 2)
        val frac = pos - lo
        return profile[lo] + frac * (profile[lo + 1] - profile[lo])
    }
}
