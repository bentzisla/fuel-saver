package com.fuelroute.data.location

import com.fuelroute.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reverse geocodes a coordinate into a human-readable address via the Geocoding
 * API. Returns null when the API is not enabled or the request fails, so callers
 * can fall back to a label.
 */
@Singleton
class ReverseGeocoder @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {

    suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = withContext(Dispatchers.IO) {
        val url = "https://maps.googleapis.com/maps/api/geocode/json" +
            "?latlng=$latitude,$longitude&language=he&key=${BuildConfig.MAPS_API_KEY}"
        val request = Request.Builder().url(url).build()
        val response = runCatching { client.newCall(request).execute() }.getOrNull() ?: return@withContext null
        response.use {
            if (!it.isSuccessful) return@withContext null
            val body = it.body?.string() ?: return@withContext null
            val parsed = runCatching { json.decodeFromString<GeocodeResponse>(body) }.getOrNull()
            parsed?.results?.firstOrNull()?.formattedAddress
        }
    }

    @Serializable
    private data class GeocodeResponse(val results: List<GeocodeResult> = emptyList())

    @Serializable
    private data class GeocodeResult(
        @SerialName("formatted_address") val formattedAddress: String? = null,
    )
}