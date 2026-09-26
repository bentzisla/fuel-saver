package com.fuelroute.data.routes

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Google Elevation API (`maps.googleapis.com`, not `routes.googleapis.com`/`places.googleapis.com`
 * - a plain REST/JSON endpoint that shares the same [com.fuelroute.BuildConfig.MAPS_API_KEY]).
 * See [ElevationRepository] for sampling-along-a-polyline and caching.
 */
interface ElevationService {

    @GET("maps/api/elevation/json")
    suspend fun elevation(
        @Query("path") path: String,
        @Query("samples") samples: Int,
        @Query("key") apiKey: String,
    ): ElevationResponse
}
