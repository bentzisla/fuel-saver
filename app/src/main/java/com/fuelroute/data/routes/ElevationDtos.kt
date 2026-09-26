package com.fuelroute.data.routes

import kotlinx.serialization.Serializable

@Serializable
data class ElevationResponse(
    val results: List<ElevationResultDto> = emptyList(),
    /** "OK", or an error status such as "REQUEST_DENIED" (API not enabled on the key), "OVER_QUERY_LIMIT". */
    val status: String = "UNKNOWN_ERROR",
    val errorMessage: String? = null,
)

@Serializable
data class ElevationResultDto(
    val elevation: Double = 0.0,
    val location: LatLngDto? = null,
    val resolution: Double = 0.0,
)
