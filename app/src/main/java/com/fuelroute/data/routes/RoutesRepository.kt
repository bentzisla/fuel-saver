package com.fuelroute.data.routes

import android.util.Log
import com.fuelroute.BuildConfig
import com.fuelroute.domain.model.Route
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
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
            departureTime = DEPARTURE_TIME_FORMATTER.format(Instant.now().truncatedTo(ChronoUnit.SECONDS)),
        )
        val response = service.computeRoutes(BuildConfig.MAPS_API_KEY, request)
        val routes = RoutesMapper.toDomain(response)
        Log.d("FuelRoute", "routes returned: ${routes.size} labels=${routes.map { it.routeLabels }}")
        return routes
    }

    private companion object {
        val DEPARTURE_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT
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