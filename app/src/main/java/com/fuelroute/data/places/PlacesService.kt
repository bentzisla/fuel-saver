package com.fuelroute.data.places

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path

interface PlacesService {

    @Headers("X-Goog-FieldMask: $PLACES_AUTOCOMPLETE_FIELD_MASK")
    @POST("v1/places:autocomplete")
    suspend fun autocomplete(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Body request: PlacesAutocompleteRequest,
    ): PlacesAutocompleteResponse

    @Headers("X-Goog-FieldMask: $PLACES_DETAILS_FIELD_MASK")
    @GET("v1/places/{placeId}")
    suspend fun details(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Path("placeId") placeId: String,
    ): PlaceDetailsResponse
}