package com.fuelroute.data.places

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface PlacesService {

    @Headers("X-Goog-FieldMask: $PLACES_AUTOCOMPLETE_FIELD_MASK")
    @POST("v1/places:autocomplete")
    suspend fun autocomplete(
        @Header("X-Goog-Api-Key") apiKey: String,
        @Body request: PlacesAutocompleteRequest,
    ): PlacesAutocompleteResponse
}