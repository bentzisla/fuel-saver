package com.fuelroute.data.places

import kotlinx.serialization.Serializable

const val PLACES_AUTOCOMPLETE_FIELD_MASK = "suggestions.placePrediction.placeId," +
    "suggestions.placePrediction.text.text," +
    "suggestions.placePrediction.structuredFormat.mainText.text," +
    "suggestions.placePrediction.structuredFormat.secondaryText.text"

const val PLACES_DETAILS_FIELD_MASK = "location,formattedAddress,displayName"

/** Resolved coordinates + canonical address for a place, used for an exact nav hand-off. */
data class PlaceDetails(
    val latitude: Double?,
    val longitude: Double?,
    val formattedAddress: String?,
)

@Serializable
data class PlacesAutocompleteRequest(
    val input: String,
    val languageCode: String = "he",
    val includedRegionCodes: List<String> = listOf("IL"),
    /**
     * Billing session token shared by a burst of autocomplete calls and the single details
     * lookup that follows. Null means "no session" (each request billed separately).
     */
    val sessionToken: String? = null,
)

@Serializable
data class PlacesAutocompleteResponse(
    val suggestions: List<AutocompleteSuggestionDto> = emptyList(),
)

@Serializable
data class AutocompleteSuggestionDto(
    /**
     * Null for a generic query-only suggestion (`queryPrediction`), which carries no place to
     * select. Such suggestions are skipped rather than failing the whole response.
     */
    val placePrediction: PlacePredictionDto? = null,
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

@Serializable
data class PlaceDetailsResponse(
    val formattedAddress: String? = null,
    val displayName: TextDto? = null,
    val location: LatLngDto? = null,
)

@Serializable
data class LatLngDto(
    val latitude: Double? = null,
    val longitude: Double? = null,
)