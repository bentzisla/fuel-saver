package com.fuelroute.data.places

import com.fuelroute.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

data class PlaceSuggestion(
    val placeId: String,
    val mainText: String,
    val secondaryText: String?,
)

interface PlacesRepository {
    suspend fun autocomplete(input: String): List<PlaceSuggestion>

    /** Place Details lookup for coordinates / canonical address. Returns null when unavailable. */
    suspend fun details(placeId: String): PlaceDetails?
}

@Singleton
class GooglePlacesRepository @Inject constructor(
    private val service: PlacesService,
) : PlacesRepository {

    override suspend fun autocomplete(input: String): List<PlaceSuggestion> {
        if (input.isBlank()) return emptyList()
        val response = service.autocomplete(
            BuildConfig.MAPS_API_KEY,
            PlacesAutocompleteRequest(input = input),
        )
        return response.suggestions.mapNotNull { dto ->
            val prediction = dto.placePrediction
            val placeId = prediction.placeId ?: return@mapNotNull null
            PlaceSuggestion(
                placeId = placeId,
                mainText = prediction.structuredFormat?.mainText?.text
                    ?: prediction.text?.text
                    ?: "",
                secondaryText = prediction.structuredFormat?.secondaryText?.text,
            )
        }
    }

    override suspend fun details(placeId: String): PlaceDetails? {
        if (placeId.isBlank()) return null
        val response = service.details(BuildConfig.MAPS_API_KEY, placeId)
        return PlaceDetails(
            latitude = response.location?.latitude,
            longitude = response.location?.longitude,
            formattedAddress = response.formattedAddress,
        )
    }
}