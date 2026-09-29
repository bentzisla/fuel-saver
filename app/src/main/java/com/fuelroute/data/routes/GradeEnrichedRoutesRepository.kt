package com.fuelroute.data.routes

import android.util.Log
import com.fuelroute.domain.model.Route
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Decorates [RoutesRepository] with per-segment net elevation changes from [ElevationRepository],
 * so [com.fuelroute.domain.fuel.FuelModel] can charge a route's climb (and partially credit its
 * descent) instead of pricing an uphill drive as if it were flat (PLAN.md §4.4; bug report:
 * Beit Shemesh -> Jerusalem, a ~500m net climb, priced under 10 NIS).
 *
 * The profile is sampled about every [ElevationRepository.SAMPLE_SPACING_M] along each route
 * ([ElevationRepository.samplesFor]), so step boundaries are interpolated between nearby samples
 * rather than a fixed 32 points regardless of route length.
 *
 * All or nothing: the alternatives are compared against each other, so a grade term on some of
 * them but not others would bias the ranking (an uphill route missing its climb looks cheapest).
 * When elevation is unavailable for any alternative (no polyline, the Elevation API call failed
 * or was denied, no network), every route is returned exactly as [delegate] produced it -
 * [Route.gradeDataMissing] stays true, [ElevationRepository] logs the failure once, and the UI
 * shows a hint that the climb was not included.
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
        return enrichAll(routes, elevationRepository)
    }

    companion object {

        /**
         * [routes] with elevation deltas on every segment, or [routes] unchanged when any one of
         * them cannot be enriched (so the alternatives stay comparable).
         */
        internal suspend fun enrichAll(routes: List<Route>, elevation: ElevationRepository): List<Route> {
            if (routes.isEmpty()) return routes
            val enriched = coroutineScope {
                routes.map { route -> async { enrich(route, elevation) } }.awaitAll()
            }
            return if (enriched.any { it == null }) routes else enriched.filterNotNull()
        }

        /** [route] with per-segment elevation deltas, or null when its profile is unavailable. */
        private suspend fun enrich(route: Route, elevation: ElevationRepository): Route? {
            val polyline = route.encodedPolyline
            if (polyline.isNullOrBlank() || route.segments.isEmpty()) return null

            val totalDistanceMeters = route.segments.sumOf { it.distanceMeters }
            if (!(totalDistanceMeters > 0.0)) return null

            val samples = ElevationRepository.samplesFor(
                route.distanceMeters.takeIf { it.isFinite() && it > 0.0 } ?: totalDistanceMeters,
            )
            val profile = try {
                elevation.profile(polyline, samples)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ElevationRepository already degrades network/API errors to null; this only
                // guards against an unexpected exception so a route search never fails on it.
                Log.w("FuelRoute", "unexpected elevation enrichment failure: ${e.javaClass.simpleName}")
                null
            }
            if (profile == null || profile.size < 2 || profile.any { !it.isFinite() }) return null

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
}
