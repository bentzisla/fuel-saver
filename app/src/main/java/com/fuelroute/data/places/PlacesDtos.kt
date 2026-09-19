package com.fuelroute.data.places

import kotlinx.serialization.Serializable

const val PLACES_AUTOCOMPLETE_FIELD_MASK = "suggestions.placePrediction.placeId," +
    "suggestions.placePrediction.text.text," +
    "suggestions.placePrediction.structuredFormat.mainText.text," +
    "suggestions.placePrediction.structuredFormat.secondaryText.text"

@Serializable
data class PlacesAutocompleteRequest(
    val input: String,
    val languageCode: String = "he",
    val includedRegionCodes: List<String> = listOf("IL"),
)

@Serializable
data class PlacesAutocompleteResponse(
    val suggestions: List<AutocompleteSuggestionDto> = emptyList(),
)

@Serializable
data class AutocompleteSuggestionDto(
    val placePrediction: PlacePredictionDto,
)

@Serializable
data class PlacePredictionDto(
    val placeId: String? = null,
    val text: TextDto? = null,
    val structuredFormat: StructuredFormatDto? = null,
)

@Serializable
data class TextDto(
    val text: String? = null,
)

@Serializable
data class StructuredFormatDto(
    val mainText: TextDto? = null,
    val secondaryText: TextDto? = null,
)