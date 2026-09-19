package com.fuelroute.data.routes

import com.fuelroute.BuildConfig
import com.fuelroute.domain.model.Route
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
        val response = service.computeRoutes(BuildConfig.MAPS_API_KEY, request)
        return RoutesMapper.toDomain(response)
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