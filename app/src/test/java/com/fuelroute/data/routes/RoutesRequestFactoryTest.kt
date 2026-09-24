package com.fuelroute.data.routes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesRequestFactoryTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val now = 1_760_000_000_000L // fixed clock for deterministic assertions

    private fun encode(options: RouteRequestOptions): String =
        json.encodeToString(
            ComputeRoutesRequest.serializer(),
            RoutesRequestFactory.create(
                origin = RouteWaypoint(placeId = "origin"),
                destination = RouteWaypoint(placeId = "destination"),
                options = options,
                nowMs = now,
            ),
        )

    @Test
    fun `future departure time is sent as ISO-8601 UTC`() {
        val departure = now + 3_600_000
        val encoded = encode(RouteRequestOptions(departureTimeMs = departure))

        assertTrue(encoded.contains("\"departureTime\":\"${RoutesRequestFactory.isoUtc(departure)}\""))
    }

    @Test
    fun `isoUtc formats as ISO-8601 with a Z suffix`() {
        assertEquals("1970-01-01T00:00:00Z", RoutesRequestFactory.isoUtc(0L))
    }

    @Test
    fun `now and past departure times are omitted`() {
        assertFalse(encode(RouteRequestOptions(departureTimeMs = null)).contains("departureTime"))
        assertFalse(encode(RouteRequestOptions(departureTimeMs = now - 60_000)).contains("departureTime"))
        assertFalse(encode(RouteRequestOptions(departureTimeMs = now)).contains("departureTime"))
    }

    @Test
    fun `emission type is sent via routeModifiers`() {
        val encoded = encode(RouteRequestOptions(emissionType = "HYBRID"))

        assertTrue(encoded.contains("\"routeModifiers\""))
        assertTrue(encoded.contains("\"vehicleInfo\":{\"emissionType\":\"HYBRID\"}"))
    }

    @Test
    fun `missing emission type omits routeModifiers`() {
        assertFalse(encode(RouteRequestOptions(emissionType = null)).contains("routeModifiers"))
    }

    @Test
    fun `fuel efficient reference route is omitted unless explicitly enabled`() {
        assertFalse(encode(RouteRequestOptions()).contains("requestedReferenceRoutes"))
        assertTrue(
            encode(RouteRequestOptions(requestFuelEfficient = true))
                .contains("\"requestedReferenceRoutes\":[\"FUEL_EFFICIENT\"]"),
        )
    }

    @Test
    fun `origin and destination waypoints are encoded`() {
        val encoded = encode(RouteRequestOptions())

        assertTrue(encoded.contains("\"placeId\":\"origin\""))
        assertTrue(encoded.contains("\"placeId\":\"destination\""))
    }

    @Test
    fun `via points become intermediates and disable alternatives`() {
        val encoded = encode(RouteRequestOptions(via = listOf(32.01 to 34.85, 32.02 to 34.87)))
        assertTrue(encoded.contains("\"intermediates\""))
        assertTrue(encoded.contains("\"latitude\":32.01"))
        assertTrue(encoded.contains("\"computeAlternativeRoutes\":false"))
    }

    @Test
    fun `without via points alternatives stay on and no intermediates are sent`() {
        val encoded = encode(RouteRequestOptions())
        assertFalse(encoded.contains("intermediates"))
        assertTrue(encoded.contains("\"computeAlternativeRoutes\":true"))
    }
}
