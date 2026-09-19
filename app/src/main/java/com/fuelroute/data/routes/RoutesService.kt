package com.fuelroute.data.routes

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface RoutesService {

    @Headers("X-Goog-FieldMask: $ROUTES_FIELD_MASK")
    @POST("directions/v2:computeRoutes")
    suspend fun computeRoutes(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Body request: ComputeRoutesRequest,
    ): ComputeRoutesResponse
}