package com.fuelroute.data.routes

import com.fuelroute.domain.model.Route
import com.fuelroute.domain.nav.GeoPoint
import com.fuelroute.domain.nav.NavPlan
import com.fuelroute.domain.nav.WaypointPlanner
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out how to hand a chosen route to Google Maps with as few stops as possible: none when
 * the chosen route is the one Maps would pick anyway, otherwise the minimal waypoints verified
 * against the Routes API (see [WaypointPlanner]).
 */
@Singleton
class NavigationPlanner @Inject constructor(
    // Plain cached routes, not the grade-enriched binding: verifying waypoints only compares
    // geometry, so an Elevation API call per verification would be wasted quota.
    private val routes: CachingRoutesRepository,
) {

    private val planner = WaypointPlanner()

    suspend fun plan(
        origin: RouteWaypoint,
        destination: RouteWaypoint,
        options: RouteRequestOptions,
        chosen: Route,
        default: Route?,
    ): NavPlan {
        // The API's own first route is what Maps navigates by default: no stops needed.
        if (default == null || chosen.id == default.id) return NavPlan(emptyList(), exact = true)
        val chosenLine = chosen.points()
        val defaultLine = default.points()
        if (chosenLine.size < 2 || defaultLine.size < 2) return NavPlan(emptyList(), exact = false)

        return planner.plan(chosenLine, defaultLine) { waypoints ->
            try {
                routes.getAlternatives(
                    origin = origin,
                    destination = destination,
                    options = options.copy(via = waypoints.map { it.lat to it.lng }),
                ).firstOrNull()?.points()?.takeIf { it.size >= 2 }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun Route.points(): List<GeoPoint> =
        PolylineDecoder.decode(encodedPolyline).map { GeoPoint(it.lat, it.lng) }
}
