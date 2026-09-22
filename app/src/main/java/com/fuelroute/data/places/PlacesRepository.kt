package com.fuelroute.data.places

import com.fuelroute.BuildConfig
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
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

    /**
     * Places billing session (see Google Places session-token docs). Lifecycle:
     * 1. The first [autocomplete] of a search flow lazily creates a UUID and reuses it for every
     *    subsequent autocomplete call in that flow.
     * 2. [details] continues the same session, then ends it, so the *next* search flow starts a
     *    fresh token.
     * 3. If the user abandons a flow without selecting a place the token stays until the next
     *    [details]; a stale token only means one more autocomplete burst shares a session, which
     *    is harmless for billing.
     *
     * Kept process-local (singleton) and not persisted: a session must not survive a process
     * restart, otherwise unrelated searches would be billed together.
     */
    private val sessionToken = AtomicReference<String?>(null)

    override suspend fun autocomplete(input: String): List<PlaceSuggestion> {
        if (input.isBlank()) return emptyList()
        val response = service.autocomplete(
            BuildConfig.MAPS_API_KEY,
            PlacesAutocompleteRequest(
                input = input,
                sessionToken = currentSessionToken(),
            ),
        )
        return response.suggestions.mapNotNull { dto ->
            // A query-only suggestion has no placePrediction; skip it instead of failing the list.
            val prediction = dto.placePrediction ?: return@mapNotNull null
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
        val token = sessionToken.get()
        try {
            val response = service.details(
                apiKey = BuildConfig.MAPS_API_KEY,
                placeId = placeId,
                sessionToken = token,
            )
            return PlaceDetails(
                latitude = response.location?.latitude,
                longitude = response.location?.longitude,
                formattedAddress = response.formattedAddress,
            )
        } finally {
            // Selecting a place ends the billing session regardless of the response.
            if (token != null) endSession(token)
        }
    }

    /** Returns the in-flight session token, creating one on first use. */
    private fun currentSessionToken(): String {
        sessionToken.get()?.let { return it }
        val created = UUID.randomUUID().toString()
        return if (sessionToken.compareAndSet(null, created)) created else sessionToken.get()!!
    }

    /** Ends [token]'s session only if it is still the active one (guards a racing new flow). */
    private fun endSession(token: String) {
        sessionToken.compareAndSet(token, null)
    }
}