package com.fuelroute.data.routes

import android.util.Log
import com.fuelroute.BuildConfig
import com.fuelroute.domain.model.Route
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class RouteWaypoint(
    val address: String? = null,
    val placeId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/**
 * Optional request inputs. [departureTimeMs] null means "now" (field omitted);
 * [emissionType] comes from the active vehicle's [com.fuelroute.domain.model.FuelType].
 */
data class RouteRequestOptions(
    val departureTimeMs: Long? = null,
    val emissionType: String? = null,
    val requestFuelEfficient: Boolean = false,
    /** Coordinates the route must pass through, as (lat, lng). Used to verify a navigation plan. */
    val via: List<Pair<Double, Double>> = emptyList(),
)

interface RoutesRepository {
    /**
     * @param forceRefresh bypasses any cache (the UI "רענן" action).
     */
    suspend fun getAlternatives(
        origin: RouteWaypoint,
        destination: RouteWaypoint,
        options: RouteRequestOptions = RouteRequestOptions(),
        forceRefresh: Boolean = false,
    ): List<Route>
}

@Singleton
class GoogleRoutesRepository @Inject constructor(
    private val service: RoutesService,
) : RoutesRepository {

    override suspend fun getAlternatives(
        origin: RouteWaypoint,
        destination: RouteWaypoint,
        options: RouteRequestOptions,
        forceRefresh: Boolean,
    ): List<Route> {
        val request = RoutesRequestFactory.create(origin, destination, options, System.currentTimeMillis())
        val response = try {
            service.computeRoutes(BuildConfig.MAPS_API_KEY, request)
        } catch (e: HttpException) {
            val body = e.response()?.errorBody()?.string()
            Log.e("FuelRoute", "computeRoutes HTTP ${e.code()}: $body")
            throw e
        } catch (e: IOException) {
            Log.e("FuelRoute", "computeRoutes network failure: ${e.javaClass.simpleName}")
            throw e
        }
        val routes = RoutesMapper.toDomain(response)
        Log.d("FuelRoute", "routes returned: ${routes.size} labels=${routes.map { it.routeLabels }}")
        if (routes.isEmpty()) throw RoutesError.NoRoute
        return routes
    }
}
