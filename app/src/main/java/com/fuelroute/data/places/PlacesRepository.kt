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
}