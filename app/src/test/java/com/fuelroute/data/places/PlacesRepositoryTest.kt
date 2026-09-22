package com.fuelroute.data.places

import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlacesRepositoryTest {

    private val service = mockk<PlacesService>()
    private val repository = GooglePlacesRepository(service)

    @Test
    fun `query-only suggestions are skipped instead of failing the response`() = runTest {
        coEvery { service.autocomplete(any(), any()) } returns PlacesAutocompleteResponse(
            suggestions = listOf(
                // Generic query suggestion: no placePrediction at all.
                AutocompleteSuggestionDto(placePrediction = null),
                AutocompleteSuggestionDto(
                    placePrediction = PlacePredictionDto(
                        placeId = "place-1",
                        structuredFormat = StructuredFormatDto(mainText = TextDto("Main")),
                    ),
                ),
                // Prediction present but with no place id: not selectable, skipped.
                AutocompleteSuggestionDto(
                    placePrediction = PlacePredictionDto(placeId = null, text = TextDto("No id")),
                ),
            ),
        )

        val result = repository.autocomplete("דלק")

        assertEquals(1, result.size)
        assertEquals("place-1", result.single().placeId)
        assertEquals("Main", result.single().mainText)
    }

    @Test
    fun `autocomplete shares one session token and details ends the session`() = runTest {
        val requestSlot = slot<PlacesAutocompleteRequest>()
        coEvery { service.autocomplete(any(), capture(requestSlot)) } returns PlacesAutocompleteResponse()
        coEvery { service.details(any(), any(), any()) } returns PlaceDetailsResponse()

        repository.autocomplete("א")
        val first = requestSlot.captured.sessionToken
        repository.autocomplete("אב")
        val second = requestSlot.captured.sessionToken

        assertNotNull(first)
        assertEquals(first, second)

        repository.details("place-1")

        repository.autocomplete("אבג")
        val afterDetails = requestSlot.captured.sessionToken

        assertNotNull(afterDetails)
        assertNotEquals(first, afterDetails)
    }
}