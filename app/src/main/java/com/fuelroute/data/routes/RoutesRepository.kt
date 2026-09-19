package com.fuelroute.data.routes

import android.util.Log
import com.fuelroute.BuildConfig
import com.fuelroute.domain.model.Route
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

data class RouteWaypoint(
    val address: String? = null,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

interface RoutesRepository {
    suspend fun getAlternatives(origin: RouteWaypoint, destination: RouteWaypoint): List<Route>
}

@Singleton
class GoogleRoutesRepository @Inject constructor(
    private val service: RoutesService,
) : RoutesRepository {

    override suspend fun getAlternatives(origin: RouteWaypoint, destination: RouteWaypoint): List<Route> {
        val request = ComputeRoutesRequest(
            origin = origin.toDto(),
            destination = destination.toDto(),
        )
        // No departureTime yet: sending "now" truncated to the second lands slightly in the
        // past, and the Routes API rejects a past departureTime for DRIVE with HTTP 400
        // (past times are TRANSIT-only). The user-facing departure time picker is card 06.
        val response = try {
            service.computeRoutes(BuildConfig.MAPS_API_KEY, request)
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e("FuelRoute", "computeRoutes HTTP ${e.code()}: $body")
            throw e
        }
        val routes = RoutesMapper.toDomain(response)
        Log.d("FuelRoute", "routes returned: ${routes.size} labels=${routes.map { it.routeLabels }}")
        return routes
    }

    private fun RouteWaypoint.toDto() = WaypointDto(
        address = address,
        placeId = placeId,
        location = if (latitude != null && longitude != null) {
            LocationDto(LatLngDto(latitude, longitude))
        } else {
            null
        },
    )
}