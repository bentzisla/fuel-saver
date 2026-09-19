package com.fuelroute.data.routes

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputeRoutesRequestTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `serializes requested reference routes and departure time`() {
        val request = ComputeRoutesRequest(
            origin = WaypointDto(placeId = "origin"),
            destination = WaypointDto(placeId = "destination"),
            departureTime = "2026-09-19T12:00:00Z",
        )

        val encoded = json.encodeToString(ComputeRoutesRequest.serializer(), request)

        assertTrue(encoded.contains("\"requestedReferenceRoutes\":[\"FUEL_EFFICIENT\"]"))
        assertTrue(encoded.contains("\"departureTime\":\"2026-09-19T12:00:00Z\""))
    }

    @Test
    fun `encodes alternatives, traffic and reference route but omits null departure time`() {
        val request = ComputeRoutesRequest(
            origin = WaypointDto(placeId = "origin"),
            destination = WaypointDto(placeId = "destination"),
        )

        val encoded = json.encodeToString(ComputeRoutesRequest.serializer(), request)

        assertTrue(encoded.contains("\"computeAlternativeRoutes\":true"))
        assertTrue(encoded.contains("\"extraComputations\":[\"TRAFFIC_ON_POLYLINE\",\"TOLLS\"]"))
        assertTrue(encoded.contains("\"requestedReferenceRoutes\":[\"FUEL_EFFICIENT\"]"))
        assertTrue(!encoded.contains("departureTime"))
    }

    @Test
    fun `round trips requested reference routes and departure time`() {
        val request = ComputeRoutesRequest(
            origin = WaypointDto(placeId = "origin"),
            destination = WaypointDto(placeId = "destination"),
            requestedReferenceRoutes = listOf("FUEL_EFFICIENT", "DEFAULT_ROUTE"),
            departureTime = "2026-09-19T12:00:00Z",
        )

        val decoded = json.decodeFromString(
            ComputeRoutesRequest.serializer(),
            json.encodeToString(ComputeRoutesRequest.serializer(), request),
        )

        assertEquals(listOf("FUEL_EFFICIENT", "DEFAULT_ROUTE"), decoded.requestedReferenceRoutes)
        assertEquals("2026-09-19T12:00:00Z", decoded.departureTime)
    }
}